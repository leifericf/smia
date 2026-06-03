(ns clj-book.cli
  "Human-facing command-line front-end, invoked via `clojure -M:run`.
   Parses argv into the same request map consumed by `clj-book.build.request`
   and delegates to `clj-book.api`. The `-X` map API (`clj-book.api`)
   remains for programmatic callers; both front-ends share the single
   `clj-book.build.request/normalize` seam, which owns all defaults and
   validation. This namespace only translates strings to that map and
   renders results, exit codes, and errors for a human."
  (:require
   [clj-book.api :as api]
   [clj-book.build.preview :as preview]
   [clj-book.error :as error]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]))

;; --- option specs ------------------------------------------------------

(def ^:private common-options
  [["-c" "--config-path PATH" "book.edn location, relative to book-root."]
   [nil  "--validate-code"    "Evaluate code blocks marked {:test true}."]
   ["-h" "--help"             "Show this help."]])

(def ^:private build-options
  (into [["-p" "--profile PROFILE" "Edition to build (screen|print); repeatable."
          :multi true :default [] :default-desc "" :update-fn conj :parse-fn keyword]
         [nil "--output-root PATH" "Directory for build output."]
         [nil "--dry-run" "Print the build plan; render nothing."]]
        common-options))

(def ^:private validate-options common-options)

(def ^:private preview-options
  (into [["-p" "--profile PROFILE" "Edition to preview (screen|print); repeatable."
          :multi true :default [] :default-desc "" :update-fn conj :parse-fn keyword]
         [nil "--output-root PATH" "Directory for build output."]]
        common-options))

(def ^:private top-level-help
  (str/join
   \newline
   ["clj-book — build technical books as PDF on the JVM."
    ""
    "Usage: clojure -M:run <command> [book-root] [options]"
    ""
    "Commands:"
    "  build      Render the requested profiles to PDF."
    "  validate   Check a manuscript without rendering anything."
    "  preview    Rebuild the book on every save while you write."
    ""
    "Run \"clojure -M:run <command> --help\" for command-specific options."
    "The book-root positional defaults to \".\" (the current directory)."]))

;; --- argv -> request map ----------------------------------------------

(defn- args->request
  "Translate the positional book-root and parsed options into the request
   map `clj-book.build.request/normalize` expects. Only keys the user actually
   supplied are set, so normalize applies its own defaults — this is the
   contract that keeps the `-M` and `-X` front-ends in sync."
  [book-root {:keys [profile config-path output-root dry-run validate-code]}]
  (cond-> {}
    book-root      (assoc :book-root book-root)
    (seq profile)  (assoc :profiles profile)
    config-path    (assoc :config-path config-path)
    output-root    (assoc :output-root output-root)
    dry-run        (assoc :dry-run true)
    validate-code  (assoc :validate-code true)))

;; --- reporting ---------------------------------------------------------

(defn- err-println [& xs]
  (binding [*out* *err*] (apply println xs)))

(defn- report-exception
  "Render a structured clj-book error as a clean diagnostic; fall back to a
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

(def ^:private build-usage "Usage: clojure -M:run build [book-root] [options]")
(def ^:private validate-usage "Usage: clojure -M:run validate [book-root] [options]")
(def ^:private preview-usage "Usage: clojure -M:run preview [book-root] [options]")

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

(defn- run-build [args]
  (run-subcommand args build-options build-usage api/build))

(defn- run-validate [args]
  (run-subcommand args validate-options validate-usage
                  (fn [request]
                    (let [{:keys [warnings]} (api/validate request)]
                      (if (seq warnings)
                        (println "ok —" (count warnings) "warning(s)")
                        (println "ok"))))))

(defn- run-preview
  "Start a preview session and block until the watcher thread ends (in
   practice: until Ctrl-C kills the process). An initial-build failure
   propagates through `run-subcommand`'s catch and exits 1."
  [args]
  (run-subcommand args preview-options preview-usage
                  (fn [request]
                    (let [handle (preview/preview! request)]
                      (.join ^Thread (:thread handle))))))

;; --- dispatch ----------------------------------------------------------

(defn run
  "Parse argv, dispatch a subcommand, and return an integer exit code.
   Pure with respect to process state (no `System/exit`) so it is testable."
  [argv]
  (let [[command & rest] argv]
    (case command
      "build"             (run-build rest)
      "validate"          (run-validate rest)
      "preview"           (run-preview rest)
      (nil "-h" "--help") (do (println top-level-help) 0)
      (do (err-println "unknown command:" command)
          (println top-level-help)
          2))))

(defn -main [& argv]
  (let [code (run (vec argv))]
    (flush)
    (System/exit code)))
