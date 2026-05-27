(ns clj-book.pipeline
  "Orchestrate validation and target builds. Shared prerequisite steps
   run once for multi-target requests.

   The pipeline threads three explicit values rather than one god-map:
   the normalized `request`, the loaded `manuscript` (config + tokens +
   warnings), and the resolved `paths`. Each step receives only the
   slices it needs; the path and prereq values are malli-checked at the
   seams (see clj-book.schema)."
  (:require
   [clj-book.artifacts :as artifacts]
   [clj-book.compose :as compose]
   [clj-book.config :as config]
   [clj-book.docbook :as docbook]
   [clj-book.schema :as schema]
   [clj-book.targets.pdf :as pdf-target]
   [clj-book.targets.site :as site-target]
   [clj-book.tokens :as tokens]
   [clj-book.tokens.css :as tokens-css]
   [clojure.java.io :as io])
  (:import
   (java.time Instant)))

(defn- build-paths [{:keys [output-root]} config]
  (let [slug (:book/slug config)
        root (io/file output-root slug)]
    {:book-output-dir  (.getPath root)
     :intermediate-dir (.getPath (io/file root "intermediate"))
     :site-output-dir  (.getPath (io/file root "site"))
     :pdf-output-dir   (.getPath (io/file root "pdf"))
     :tokens-dir       (.getPath (io/file root "intermediate" "tokens"))}))

(defn prepare
  "Run validation steps shared by every command and return a structured
   build context `{:request <normalized> :manuscript {...} :paths {...}}`."
  [request]
  (let [{:keys [config path warnings]} (config/load-config request)
        {:keys [tokens]}               (tokens/load-tokens request)
        paths                          (build-paths request config)]
    {:request    request
     :manuscript {:config      config
                  :config-file path
                  :tokens      tokens
                  :warnings    (vec warnings)}
     :paths      (schema/check schema/Paths paths
                               :clj-book.pipeline/invalid-paths)}))

(defn- emit-shared-prereqs!
  "Compose the master adoc, write tier-1+tier-3 CSS, and the PDF theme
   YAML. Returns the prereq paths the target adapters need."
  [{:keys [book-root]} {:keys [config tokens]} {:keys [intermediate-dir tokens-dir]}]
  (let [master-path (compose/write-master! {:book-root        book-root
                                            :config           config
                                            :intermediate-dir intermediate-dir})
        extras      (tokens-css/load-site-extras book-root)
        css         (tokens-css/compile-css {:tokens tokens :extras extras})
        css-out     (io/file tokens-dir "site.css")
        theme-yaml  (pdf-target/write-theme-yaml!
                      {:book-root        book-root
                       :tokens           tokens
                       :intermediate-dir intermediate-dir})]
    (io/make-parents css-out)
    (spit css-out css)
    (schema/check schema/Prereqs
                  {:master-path master-path
                   :site-css    (.getPath css-out)
                   :theme-yaml  theme-yaml}
                  :clj-book.pipeline/invalid-prereqs)))

(defn- build-site!
  [{:keys [book-root]} {:keys [config tokens]}
   {:keys [intermediate-dir site-output-dir]} {:keys [master-path]}]
  (docbook/generate-docbook! {:intermediate-dir intermediate-dir
                              :master-path      master-path})
  (let [out (site-target/build! {:intermediate-dir intermediate-dir
                                 :output-dir       site-output-dir
                                 :book-root        book-root
                                 :config           config
                                 :tokens           tokens})]
    {:target :site :path (:html out) :paths out}))

(defn- build-pdf!
  [{:keys [config]} {:keys [pdf-output-dir]} {:keys [master-path theme-yaml]}]
  (let [out (pdf-target/build! {:master-path master-path
                                :output-dir  pdf-output-dir
                                :config      config
                                :theme-yaml  theme-yaml})]
    {:target :pdf :path (:pdf out) :paths out}))

(defn- run-target! [target request manuscript paths prereqs]
  (case target
    :site (build-site! request manuscript paths prereqs)
    :pdf  (build-pdf! manuscript paths prereqs)))

(defn validate
  "Run validation only. Returns `{:status :ok :warnings [...]}` or
   throws."
  [request]
  (let [{:keys [manuscript]} (prepare request)]
    {:status   :ok
     :config   (:config-file manuscript)
     :tokens   (:tokens manuscript)
     :warnings (:warnings manuscript)}))

(defn build
  "Execute the requested target builds. Returns the manifest map."
  [{:keys [targets] :as request}]
  (let [started (Instant/now)
        {:keys [manuscript paths]} (prepare request)
        prereqs   (emit-shared-prereqs! request manuscript paths)
        artifacts-out (mapv #(run-target! % request manuscript paths prereqs)
                            targets)
        finished  (Instant/now)]
    (artifacts/write!
      {:output-dir  (:book-output-dir paths)
       :config      (:config manuscript)
       :targets     targets
       :artifacts   artifacts-out
       :started-at  started
       :finished-at finished
       :metadata    {:warnings (:warnings manuscript)}})))
