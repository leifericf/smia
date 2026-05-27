(ns clj-book.pipeline
  "Orchestrate validation and target builds. Shared prerequisite steps
   run once for multi-target requests."
  (:require
   [clj-book.artifacts :as artifacts]
   [clj-book.compose :as compose]
   [clj-book.config :as config]
   [clj-book.docbook :as docbook]
   [clj-book.targets.pdf :as pdf-target]
   [clj-book.targets.site :as site-target]
   [clj-book.tokens :as tokens]
   [clj-book.tokens.css :as tokens-css]
   [clj-book.tokens.pdf :as tokens-pdf]
   [clojure.java.io :as io])
  (:import
   (java.time Instant)))

(defn- build-paths [{:keys [output-root]} config]
  (let [slug (:book/slug config)
        root (io/file output-root slug)]
    {:book-output-dir     (.getPath root)
     :intermediate-dir    (.getPath (io/file root "intermediate"))
     :site-output-dir     (.getPath (io/file root "site"))
     :pdf-output-dir      (.getPath (io/file root "pdf"))
     :tokens-dir          (.getPath (io/file root "intermediate" "tokens"))}))

(defn prepare
  "Run validation steps shared by every command and return the assembled
   build context."
  [request]
  (let [{:keys [config path warnings]} (config/load-config request)
        {:keys [tokens]}               (tokens/load-tokens request)
        paths                          (build-paths request config)]
    (merge request
           paths
           {:config       config
            :config-file  path
            :tokens       tokens
            :warnings     (vec warnings)
            :book-root    (:book-root request)})))

(defn- emit-shared-prereqs!
  "Compose the master adoc, write tier-1+tier-3 CSS, and the PDF theme
   YAML. Returns paths the target adapters need."
  [{:keys [intermediate-dir tokens-dir book-root tokens] :as ctx}]
  (let [master-path (compose/write-master!
                      (assoc ctx :intermediate-dir intermediate-dir))
        extras      (tokens-css/load-site-extras book-root)
        css         (tokens-css/compile-css {:tokens tokens :extras extras})
        css-out     (io/file tokens-dir "site.css")
        theme-yaml  (pdf-target/write-theme-yaml!
                      {:book-root book-root :tokens tokens
                       :intermediate-dir intermediate-dir})]
    (io/make-parents css-out)
    (spit css-out css)
    {:master-path master-path
     :site-css    (.getPath css-out)
     :theme-yaml  theme-yaml}))

(defn- run-target! [target ctx prereqs]
  (case target
    :site
    (let [_ (docbook/generate-docbook! ctx)
          out (site-target/build! ctx)]
      [{:target :site :path (:html out) :paths out}])

    :pdf
    (let [out (pdf-target/build!
                (merge ctx prereqs {:output-dir (:pdf-output-dir ctx)}))]
      [{:target :pdf :path (:pdf out) :paths out}])))

(defn validate
  "Run validation only. Returns `{:status :ok :warnings [...]}` or
   throws."
  [request]
  (let [ctx (prepare request)]
    {:status   :ok
     :config   (:config-file ctx)
     :tokens   (:tokens ctx)
     :warnings (:warnings ctx)}))

(defn build
  "Execute the requested target builds. Returns the manifest map."
  [{:keys [targets] :as request}]
  (let [started (Instant/now)
        ctx     (prepare request)
        prereqs (emit-shared-prereqs! ctx)
        ctx*    (merge ctx prereqs
                       {:intermediate-dir (:intermediate-dir ctx)})
        artifacts-out
        (reduce (fn [acc t]
                  (into acc
                        (run-target! t
                                     (merge ctx*
                                            (case t
                                              :site {:output-dir (:site-output-dir ctx*)}
                                              :pdf  {:output-dir (:pdf-output-dir ctx*)}))
                                     prereqs)))
                []
                targets)
        finished (Instant/now)]
    (artifacts/write!
      {:output-dir   (:book-output-dir ctx*)
       :config       (:config ctx*)
       :targets      targets
       :artifacts    artifacts-out
       :started-at   started
       :finished-at  finished
       :metadata     {:warnings (:warnings ctx*)}})))
