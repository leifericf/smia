(ns clj-book.build.execute
  "Build context (shell): load the manuscript, plan the build, and
   perform it.

   `build` = execute! ∘ plan ∘ prepare. The pure decision lives in
   clj-book.build.plan; all IO and shelling out happen here. Shared
   prerequisites run once so multi-target requests reuse them."
  (:require
   [clj-book.artifacts :as artifacts]
   [clj-book.build.plan :as plan]
   [clj-book.compose :as compose]
   [clj-book.config :as config]
   [clj-book.docbook :as docbook]
   [clj-book.schema :as schema]
   [clj-book.targets.pdf :as pdf-target]
   [clj-book.targets.site :as site-target]
   [clj-book.theme.css :as theme-css]
   [clj-book.theme.load :as theme]
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

(defn- emit-shared-prereqs!
  "Compose the master adoc, write tier-1+tier-3 CSS, and the PDF theme
   YAML. Returns the prereq paths the target adapters need."
  [book-root {:keys [config tokens]} {:keys [intermediate-dir tokens-dir]}]
  (let [master-path (compose/write-master! {:book-root        book-root
                                            :config           config
                                            :intermediate-dir intermediate-dir})
        extras      (theme/load-site-extras book-root)
        css         (theme-css/compile-css {:tokens tokens :extras extras})
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
                  :clj-book.build.execute/invalid-prereqs)))

(defn- build-site! [{:keys [book-root manuscript paths prereqs output-dir]}]
  (let [{:keys [config tokens]} manuscript
        intermediate-dir        (:intermediate-dir paths)]
    (docbook/generate-docbook! {:intermediate-dir intermediate-dir
                                :master-path      (:master-path prereqs)})
    (let [out (site-target/build! {:intermediate-dir intermediate-dir
                                   :output-dir       output-dir
                                   :book-root        book-root
                                   :config           config
                                   :tokens           tokens})]
      {:target :site :path (:html out) :paths out})))

(defn- build-pdf! [{:keys [manuscript prereqs output-dir]}]
  (let [out (pdf-target/build! {:master-path (:master-path prereqs)
                                :output-dir  output-dir
                                :config      (:config manuscript)
                                :theme-yaml  (:theme-yaml prereqs)})]
    {:target :pdf :path (:pdf out) :paths out}))

(defn- run-step! [base {:keys [target output-dir]}]
  (let [ctx (assoc base :output-dir output-dir)]
    (case target
      :site (build-site! ctx)
      :pdf  (build-pdf! ctx))))

(defn execute!
  "Perform a Plan: emit shared prerequisites, run each target step, and
   write the manifest. Returns the manifest map."
  [{:keys [book-root manuscript paths target-steps manifest-skeleton]}]
  (let [started       (Instant/now)
        prereqs       (emit-shared-prereqs! book-root manuscript paths)
        base          {:book-root  book-root
                       :manuscript manuscript
                       :paths      paths
                       :prereqs    prereqs}
        artifacts-out (mapv #(run-step! base %) target-steps)
        finished      (Instant/now)]
    (artifacts/write!
      {:output-dir  (:book-output-dir paths)
       :config      (:config manuscript)
       :targets     (:build/targets manifest-skeleton)
       :artifacts   artifacts-out
       :started-at  started
       :finished-at finished
       :metadata    (:metadata manifest-skeleton)})))

(defn build
  "Execute the requested target builds and return the manifest map.
   build = execute! ∘ plan ∘ prepare.

   With `:dry-run` truthy in the request, return the inspectable plan
   value instead of performing the build: the manuscript is still loaded
   and validated, but no targets are rendered and no artifacts written."
  [request]
  (let [the-plan (-> request prepare plan/plan)]
    (if (:dry-run request)
      the-plan
      (execute! the-plan))))

(defn validate
  "Run validation only. Returns `{:status :ok :warnings [...]}` or
   throws."
  [request]
  (let [{:keys [manuscript]} (prepare request)]
    {:status   :ok
     :config   (:config-file manuscript)
     :tokens   (:tokens manuscript)
     :warnings (:warnings manuscript)}))
