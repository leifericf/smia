(ns smia.build.artifacts
  "Artifact manifest emitter."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]))

(defn manifest
  "Build the deterministic-shape artifact manifest map for a completed
   build. `started-at`/`finished-at` are clock readings supplied by the
   caller (an `Instant` or an ISO-8601 string); they are stringified so
   the manifest is readable EDN. Pure: takes no clock of its own."
  [{:keys [config editions artifacts started-at finished-at metadata]}]
  {:book/slug         (:book/slug config)
   :build/started-at  (str started-at)
   :build/finished-at (str finished-at)
   :build/editions    (vec editions)
   :artifacts         (vec artifacts)
   :build/metadata    (merge {:tool "smia"} metadata)})

(defn write!
  "Write the manifest as EDN to `<output-dir>/artifacts.edn`. Returns
   the manifest map (with `:manifest/path` added)."
  [{:keys [output-dir] :as m}]
  (let [out (io/file output-dir "artifacts.edn")
        man (assoc (manifest m) :manifest/path (.getPath out))]
    (io/make-parents out)
    (spit out (with-out-str (pp/pprint (dissoc man :manifest/path))))
    man))
