(ns clj-book.build.preview
  "Live preview: watch a book's source tree and rebuild on save in the
   same warm JVM.

   Change detection is mtime polling — snapshot the tree, diff, rebuild on
   a difference — because the JDK WatchService is itself a poller on macOS
   (with worse latency) and a snapshot diff is a pure function. FOP has no
   incremental layout, so every save re-renders the whole edition
   (~150 ms warm for one profile); preview therefore defaults to the
   `:screen` edition only.

   The pure decisions (what to ignore, how to diff, what is relevant) sit
   at the top; the polling loop and the rebuild side effects follow."
  (:require
   [clojure.string :as str])
  (:import
   (java.io File)))

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
