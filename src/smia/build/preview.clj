(ns smia.build.preview
  "Live preview: watch a book's source tree and rebuild on save in the
   same warm JVM.

   Change detection is mtime polling — snapshot the tree, diff, rebuild on
   a difference — because the JDK WatchService is itself a poller on macOS
   (with worse latency) and a snapshot diff is a pure function. FOP has no
   incremental layout, so every save re-renders the whole edition
   (~150 ms warm for one); preview therefore defaults to the `:screen`
   edition only.

   The pure decisions (what to ignore, how to diff, what is relevant) sit
   at the top of the namespace; the polling loop and the rebuild side
   effects follow."
  (:require
   [smia.api :as api]
   [smia.build.request :as request]
   [smia.error :as error]
   [smia.serve :as serve]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.time LocalTime)
   (java.time.format DateTimeFormatter)))

(declare poll-loop! snapshot! watch-ctx rebuild!)

(declare site-dir serve-site!)

(defn preview!
  "Start a live preview for `request-map` (the public request shape; the
   editions default to `[:screen]` for a fast loop). Builds once
   synchronously — a failure propagates to the caller — then polls the
   book tree every 250 ms in a daemon thread, rebuilding on any change and
   reporting a failed rebuild without stopping.

   When the previewed editions include `:site`, a static file server is
   started over the build's site directory (the clean directory URLs need
   one to browse), so the loop is edit → save → refresh. `opts` may carry
   `:port` (default 8000); PDF previews start no server. Returns
   `{:stop! <idempotent fn> :thread <Thread> :server <handle|nil>}`; call
   `(:stop! handle)` to end the session (REPL workflow:
   `(def h (preview! {:book-root \"manual\"}))` … `((:stop! h))`)."
  ([request-map] (preview! request-map {}))
  ([request-map {:keys [port]}]
   (let [request  (cond-> request-map
                    (nil? (:editions request-map)) (assoc :editions [:screen]))
         ctx      (watch-ctx (request/normalize request :build))
         stop?    (atom false)
         manifest (rebuild! request)
         server   (when-let [dir (site-dir manifest)]
                    (serve-site! dir port))]
     (let [thread (doto (Thread.
                         #(poll-loop! {:snapshot! (fn [] (snapshot! ctx))
                                       :rebuild!  (fn [] (rebuild! request))
                                       :sleep!    (fn [] (Thread/sleep 250))
                                       :stop?     stop?})
                         "smia-preview")
                    (.setDaemon true)
                    (.start))]
       {:stop!  (fn []
                  (reset! stop? true)
                  (.join thread 2000)
                  (when server (serve/stop! server))
                  :stopped)
        :thread thread
        :server server}))))

;; --- pure decisions ---------------------------------------------------------

(def ^:private ignore-patterns
  "Filename regexes that never trigger a rebuild: anything hidden (covers
   `.git`, `.DS_Store`, Emacs `.#` locks), editor backup and swap files."
  [#"^\." #"~$" #"\.sw[a-z]$" #"^#.*#$"])

(defn ignored-name?
  "True for a bare filename an editor or the OS writes incidentally."
  [name]
  (boolean (some #(re-find % name) ignore-patterns)))

(defn under-root?
  "True when `path` lies at or below `root` (both canonical path strings)."
  [root path]
  (or (= root path)
      (str/starts-with? path (str root File/separator))))

(defn relevant?
  "Should a changed `path` trigger a rebuild? False for ignored filenames
   and for anything under an `:excluded-roots` entry (the build output —
   the guard against rebuilding on our own writes)."
  [{:keys [excluded-roots]} path]
  (and (not (ignored-name? (.getName (File. ^String path))))
       (not-any? #(under-root? % path) excluded-roots)))

(defn changes
  "Diff two snapshots (`{path mtime}` maps): the set of created, modified,
   and deleted paths."
  [old new]
  (-> #{}
      (into (keep (fn [[path mtime]]
                    (when (not= mtime (get old path)) path)))
            new)
      (into (remove #(contains? new %)) (keys old))))

;; --- the polling loop -------------------------------------------------------

(defn poll-loop!
  "Drive rebuilds until `@stop?` is true. Each tick sleeps via `sleep!`,
   takes a fresh snapshot via `snapshot!` (a 0-arg fn returning
   `{path mtime}`), and calls `rebuild!` when anything changed since the
   previous one; after a rebuild the loop continues from a *post-rebuild*
   snapshot so its own writes never echo. A throwing `rebuild!` is
   reported and the loop continues — a broken save must not end the
   session. All collaborators are injected so tests drive the loop with
   scripted snapshots. Returns `:stopped`."
  [{:keys [snapshot! rebuild! sleep! stop?]}]
  (loop [prev (snapshot!)]
    (if @stop?
      :stopped
      (do (sleep!)
          (let [now (snapshot!)]
            (if (seq (changes prev now))
              (do (try
                    (rebuild!)
                    (catch Throwable t
                      (binding [*out* *err*]
                        (run! println (error/report-lines t)))))
                  (recur (snapshot!)))
              (recur now)))))))

(defn snapshot!
  "Walk the book tree and return `{path mtime}` for every relevant file,
   pruning ignored names and excluded roots (the build output) as it
   walks."
  [{:keys [book-root excluded-roots]}]
  (letfn [(walk [acc ^File f]
            (cond
              (ignored-name? (.getName f)) acc
              (some #(under-root? % (.getPath f)) excluded-roots) acc
              (.isDirectory f) (reduce walk acc (.listFiles f))
              (.isFile f) (assoc acc (.getPath f) (.lastModified f))
              :else acc))]
    (walk {} (io/file book-root))))

;; --- the rebuild ------------------------------------------------------------

(defn- watch-ctx
  "The watch context for a normalized request: the canonical book root and
   the canonical output root to exclude (harmless when it lies outside)."
  [normalized]
  {:book-root      (.getCanonicalPath (io/file (:book-root normalized)))
   :excluded-roots [(.getCanonicalPath (io/file (:output-root normalized)))]})

(defn- rebuild!
  "Build `request` and print one line per artifact:
   `HH:mm:ss  built screen in 142ms  build/<slug>/pdf/<slug>-screen.pdf`.
   Failures propagate to the caller (the loop reports and continues; the
   initial build lets them reach the front door)."
  [request]
  (let [t0       (System/nanoTime)
        manifest (api/build request)
        ms       (Math/round (/ (- (System/nanoTime) t0) 1000000.0))
        stamp    (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss"))]
    (doseq [artifact (:artifacts manifest)]
      (println (format "%s  built %s in %dms  %s"
                       stamp (name (:edition artifact)) ms (:path artifact))))
    manifest))

(defn- site-dir
  "The site edition's output directory in a build `manifest`, or nil when
   no site edition was built."
  [manifest]
  (some #(when (= :site (:edition %)) (:path %)) (:artifacts manifest)))

(defn- serve-site!
  "Start a static file server over the built site `dir` and announce the
   URL. Returns the `smia.serve` handle."
  [dir port]
  (let [handle (serve/serve! {:dir dir :port (or port 8000)})]
    (println (format "Serving the site at http://localhost:%d/  (Ctrl-C to stop)"
                     (:port handle)))
    handle))
