(ns clj-book.build.execute
  "Build context (shell): load the manuscript, plan the build, and
   perform it.

   The AsciiDoc/site rendering pipeline has been removed; the new
   Hiccup -> XSL-FO -> FOP pipeline is wired in here in a later phase.
   For now `prepare`, `validate`, and the `--dry-run` plan are intact,
   so the public contract and the kept spine stay exercised."
  (:require
   [clj-book.build.plan :as plan]
   [clj-book.config :as config]
   [clj-book.error :as error]
   [clj-book.schema :as schema]
   [clj-book.theme.load :as theme]
   [clojure.java.io :as io]))

(defn- build-paths [{:keys [output-root]} config]
  (let [slug (:book/slug config)
        root (io/file output-root slug)]
    {:book-output-dir  (.getPath root)
     :intermediate-dir (.getPath (io/file root "intermediate"))
     :pdf-output-dir   (.getPath (io/file root "pdf"))}))

(defn prepare
  "Shell: load and validate the manuscript and resolve output paths.
   Returns `{:request <normalized> :manuscript {...} :paths {...}}`."
  [request]
  (let [{:keys [config path warnings]} (config/load-config request)
        {:keys [tokens]}               (theme/load-tokens request)
        paths                          (build-paths request config)]
    {:request    request
     :manuscript {:config      config
                  :config-file path
                  :tokens      tokens
                  :warnings    (vec warnings)}
     :paths      (schema/check schema/Paths paths
                               :clj-book.build.execute/invalid-paths)}))

(defn build
  "Execute the requested target builds and return the manifest map.

   With `:dry-run` truthy in the request, return the inspectable plan
   value instead of performing the build: the manuscript is still loaded
   and validated, but no targets are rendered and no artifacts written.

   Rendering is temporarily unavailable: the AsciiDoc/site pipeline has
   been removed and the Hiccup -> FO -> FOP pipeline is not yet wired in."
  [request]
  (let [the-plan (-> request prepare plan/plan)]
    (if (:dry-run request)
      the-plan
      (throw (error/ex :clj-book.build.execute/not-implemented
                       "Rendering is not yet wired in: the Hiccup -> FO -> FOP pipeline is under construction."
                       {:profiles (:profiles request)})))))

(defn validate
  "Run validation only. Returns `{:status :ok :warnings [...]}` or
   throws."
  [request]
  (let [{:keys [manuscript]} (prepare request)]
    {:status   :ok
     :config   (:config-file manuscript)
     :tokens   (:tokens manuscript)
     :warnings (:warnings manuscript)}))
