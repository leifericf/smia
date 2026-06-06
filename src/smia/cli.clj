(ns smia.cli
  "Human-facing command-line front-end: `smia <command>` from the packaged
   jar, `clojure -M:run <command>` from a source checkout.
   Parses argv (the repeatable `--edition` selects deliverables) into the
   same request map consumed by `smia.build.request`
   and delegates to `smia.api`. The `-X` map API (`smia.api`)
   remains for programmatic callers; both front-ends share the single
   `smia.build.request/normalize` seam, which owns all defaults and
   validation. This namespace only translates strings to that map and
   renders results, exit codes, and errors for a human."
  (:require
   [smia.api :as api]
   [smia.build.preview :as preview]
   [smia.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :as cli])
  (:gen-class))

;; --- option specs ------------------------------------------------------

(def ^:private common-options
  [["-c" "--config-path PATH" "book.edn location, relative to book-root."]
   [nil  "--validate-code"    "Evaluate code blocks marked {:test true}."]
   ["-h" "--help"             "Show this help."]])

(def ^:private build-options
  (into [["-e" "--edition EDITION" "Edition to build (screen|print|print-x|site|epub); repeatable."
          :multi true :default [] :default-desc "" :update-fn conj :parse-fn keyword]
         [nil "--output-root PATH" "Directory for build output."]
         [nil "--clean" "Remove the book's output directory before building."]
         [nil "--licensee TEXT" "Stamp 'Licensed to TEXT' in every PDF page footer."]
         [nil "--dry-run" "Print the build plan; render nothing."]]
        common-options))

(def ^:private validate-options common-options)

(def ^:private init-options
  [["-h" "--help" "Show this help."]])

(def ^:private preview-options
  (into [["-e" "--edition EDITION" "Edition to preview (screen|print|print-x|site|epub); repeatable."
          :multi true :default [] :default-desc "" :update-fn conj :parse-fn keyword]
         [nil "--output-root PATH" "Directory for build output."]
         [nil "--port PORT" "Port to serve the site on when previewing it (default 8000)."
          :parse-fn #(Integer/parseInt %) :default 8000
          :validate [#(<= 1 % 65535) "Must be a port number between 1 and 65535."]]]
        common-options))

(def ^:private top-level-help
  (str/join
   \newline
   ["Smia builds technical books as PDF, EPUB, and a static site on the JVM."
    ""
    "Usage: smia <command> [book-root] [options]"
    ""
    "Commands:"
    "  init       Scaffold a new book into a directory."
    "  build      Build the requested editions."
    "  validate   Check a manuscript without rendering anything."
    "  preview    Rebuild on every save; serve the site when previewing it."
    "  version    Print the Smia version."
    ""
    "Run \"smia <command> --help\" for command-specific options."
    "The book-root positional defaults to \".\" (the current directory)."
    "From a source checkout, \"clojure -M:run\" stands in for \"smia\"."]))

;; --- version -------------------------------------------------------------

(defn- version-line
  "Render the version stamped into the jar at build time
   (`smia/version.edn` on the classpath), or a dev fallback when running
   from source. Read defensively: a malformed stamp must never take the
   CLI down. Only the version surface reads this — the render path never
   does, so a stamped jar builds byte-identical output to a checkout."
  []
  (let [{:keys [version sha]}
        (try
          (some-> (io/resource "smia/version.edn") slurp edn/read-string)
          (catch Exception _ nil))]
    (if version
      (str "Smia " version (when sha (str " (" sha ")")))
      "Smia (dev)")))

;; --- argv -> request map ----------------------------------------------

(defn- args->request
  "Translate the positional book-root and parsed options into the request
   map `smia.build.request/normalize` expects. Only keys the user actually
   supplied are set, so normalize applies its own defaults — this is the
   contract that keeps the `-M` and `-X` front-ends in sync."
  [book-root {:keys [edition config-path output-root dry-run validate-code clean
                     licensee]}]
  (cond-> {}
    book-root      (assoc :book-root book-root)
    (seq edition)  (assoc :editions edition)
    config-path    (assoc :config-path config-path)
    output-root    (assoc :output-root output-root)
    dry-run        (assoc :dry-run true)
    validate-code  (assoc :validate-code true)
    clean          (assoc :clean true)
    licensee       (assoc :licensee licensee)))

;; --- reporting ---------------------------------------------------------

(defn- err-println [& xs]
  (binding [*out* *err*] (apply println xs)))

(defn- warning-line
  "Render one pipeline warning as a single human-readable line. Warnings
   come in several shapes (`:warning/note`, FOP `:message`, bare
   `:warning/type` with context), so unknown shapes fall back to EDN."
  [w]
  (cond
    (string? w)        w
    (:warning/note w)  (str (:warning/note w)
                            (when-let [ks (:warning/keys w)]
                              (str " " (pr-str ks))))
    (:message w)       (:message w)
    (:warning/type w)  (pr-str (dissoc w :warning/type))
    :else              (pr-str w)))

(defn- report-warnings
  "Print each warning to stderr, prefixed, so a quiet success stays quiet
   on stdout but no warning is ever silently dropped."
  [warnings]
  (run! #(err-println "warning:" (warning-line %)) warnings))

(defn- result-warnings
  "Collect every warning a build or plan result carries: manuscript-level
   warnings (manifest metadata; plan skeleton on a dry run) and the
   per-edition warnings on each artifact entry."
  [result]
  (concat (get-in result [:build/metadata :warnings])
          (get-in result [:manifest-skeleton :metadata :warnings])
          (mapcat :warnings (:artifacts result))))

(defn- report-exception
  "Render a structured smia error as a clean diagnostic; fall back to a
   stack trace only for unexpected (non-structured) throwables."
  [^Throwable t]
  (run! err-println (error/report-lines t))
  (when-not (error/data t)
    (.printStackTrace t)))

(defn- print-usage [header summary]
  (println header)
  (println)
  (println summary))

;; --- subcommands -------------------------------------------------------

(def ^:private init-usage "Usage: smia init [target-dir]")
(def ^:private build-usage "Usage: smia build [book-root] [options]")
(def ^:private validate-usage "Usage: smia validate [book-root] [options]")
(def ^:private preview-usage "Usage: smia preview [book-root] [options]")

(defn- run-subcommand
  "Parse `args` against `options`, then dispatch: print `usage` on `--help`
   (exit 0), report option `errors` (exit 2), or call `on-request` with the
   built request map and return 0 — or 1 if it throws."
  [args option-specs usage on-request]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args option-specs)]
    (cond
      (:help options) (do (print-usage usage summary) 0)
      errors          (do (run! err-println errors)
                          (err-println summary)
                          2)
      :else
      (try
        (on-request (args->request (first arguments) options))
        0
        (catch Throwable t (report-exception t) 1)))))

(defn- run-init
  "Scaffold a new book into the positional target directory (default the
   current one). Parsed directly (not via `run-subcommand`) because init
   takes a target, not a request map."
  [args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args init-options)]
    (cond
      (:help options) (do (print-usage init-usage summary) 0)
      errors          (do (run! err-println errors)
                          (err-println summary)
                          2)
      :else
      (try
        (let [{:keys [target files]} (api/init {:target (or (first arguments) ".")})]
          (println "Initialized a new book in" target)
          (run! #(println " " %) files)
          (println "Next: cd into it and run \"smia build\".")
          0)
        (catch Throwable t (report-exception t) 1)))))

(defn- report-artifacts
  "Name each artifact a completed build wrote. A dry-run result is the
   plan, which carries no `:artifacts` and so prints nothing here."
  [result]
  (doseq [{:keys [edition path]} (:artifacts result)]
    (println "Built" (str (name edition) ":") path)))

(defn- run-build [args]
  (run-subcommand args build-options build-usage
                  (fn [request]
                    (let [result (api/build request)]
                      (report-warnings (result-warnings result))
                      (report-artifacts result)))))

(defn- run-validate [args]
  (run-subcommand args validate-options validate-usage
                  (fn [request]
                    (let [{:keys [warnings]} (api/validate request)]
                      (report-warnings warnings)
                      (if (seq warnings)
                        (println "ok —" (count warnings) "warning(s)")
                        (println "ok"))))))

(defn- run-preview
  "Start a preview session and block until the watcher thread ends (in
   practice: until Ctrl-C kills the process). When the site edition is
   previewed it is also served at `http://localhost:<port>/`. An
   initial-build failure is reported and exits 1. Parsed directly (not via
   `run-subcommand`) so the `--port` option reaches `preview!` alongside
   the request map."
  [args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args preview-options)]
    (cond
      (:help options) (do (print-usage preview-usage summary) 0)
      errors          (do (run! err-println errors)
                          (err-println summary)
                          2)
      :else
      (try
        (let [handle (preview/preview! (args->request (first arguments) options)
                                       {:port (:port options)})]
          (.join ^Thread (:thread handle)))
        0
        (catch Throwable t (report-exception t) 1)))))

;; --- dispatch ----------------------------------------------------------

(defn run
  "Parse argv, dispatch a subcommand, and return an integer exit code.
   Pure with respect to process state (no `System/exit`) so it is testable."
  [argv]
  (let [[command & rest] argv]
    (case command
      "init"                (run-init rest)
      "build"               (run-build rest)
      "validate"            (run-validate rest)
      "preview"             (run-preview rest)
      ("version"
       "--version")         (do (println (version-line)) 0)
      (nil "-h" "--help")   (do (println top-level-help) 0)
      (do (err-println "unknown command:" command)
          (println top-level-help)
          2))))

(defn -main [& argv]
  (let [code (run (vec argv))]
    (flush)
    (System/exit code)))
