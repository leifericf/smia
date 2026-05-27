(ns clj-book.artifacts
  "Artifact manifest emitter."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp])
  (:import
   (java.time Instant)))

(defn- now-instant-str []
  (str (Instant/now)))

(defn manifest
  "Build the deterministic-shape artifact manifest map for a completed
   build."
  [{:keys [config targets artifacts started-at finished-at metadata]}]
  {:book/slug       (:book/slug config)
   :build/started-at  started-at
   :build/finished-at finished-at
   :build/targets   (vec targets)
   :artifacts       (vec artifacts)
   :build/metadata  (merge {:tool "clj-book" :version "1.0.0-alpha"}
                           metadata)})

(defn write!
  "Write the manifest as EDN to `<output-dir>/artifacts.edn`. Returns
   the manifest map (with `:manifest/path` added)."
  [{:keys [output-dir] :as m}]
  (let [out (io/file output-dir "artifacts.edn")
        man (assoc (manifest m) :manifest/path (.getPath out))]
    (io/make-parents out)
    (spit out (with-out-str (pp/pprint (dissoc man :manifest/path))))
    man))

(defn started-marker
  "Capture an ISO-8601 instant string for the build start time."
  []
  (now-instant-str))

(defn finished-marker
  "Capture an ISO-8601 instant string for the build end time."
  []
  (now-instant-str))
