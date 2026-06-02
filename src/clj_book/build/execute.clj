(ns clj-book.build.execute
  "Build context (shell): load the manuscript, plan the build, and
   perform it.

   `build` = execute! ∘ plan ∘ prepare. The pure decisions live in
   clj-book.build.plan and the pure cores (book assembly, FO expansion
   and serialization); all IO — reading inputs, evaluating chapters, and
   FOP writing PDF bytes — happens here. Chapters are loaded once and
   reused across profiles."
  (:require
   [clj-book.artifacts :as artifacts]
   [clj-book.book.assemble :as assemble]
   [clj-book.book.load :as book-load]
   [clj-book.book.theme :as book-theme]
   [clj-book.build.plan :as plan]
   [clj-book.config :as config]
   [clj-book.eval.registry :as eval-registry]
   [clj-book.eval.validate :as eval-validate]
   [clj-book.fo.expand :as expand]
   [clj-book.fo.render :as render]
   [clj-book.fo.schema :as fo-schema]
   [clj-book.fo.serialize :as serialize]
   [clj-book.schema :as schema]
   [clj-book.theme.load :as theme]
   [clojure.java.io :as io])
  (:import
   (java.time Instant)))

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

(defn- load-book
  "Shell: load chapter `.clj` files and shape the assemble-ready
   manuscript value `{:title :author :chapters}`."
  [book-root {:keys [config]}]
  {:title    (:book/title config)
   :author   (:book/author config)
   :chapters (book-load/load-chapters book-root (:book/chapters config))})

(defn- render-profile!
  "Assemble -> expand -> serialize -> FOP for one profile. Writes the
   intermediate FO and the final PDF; returns the artifact entry."
  [{:keys [book-root book tokens]} {:keys [profile fo-path pdf-path]}]
  (let [the-theme (book-theme/compile-theme tokens profile)
        fo-xml    (-> (assemble/assemble book the-theme)
                      (expand/expand (:style the-theme))
                      (serialize/serialize))]
    (io/make-parents (io/file fo-path))
    (spit fo-path fo-xml)
    (io/make-parents (io/file pdf-path))
    (let [result (with-open [out (io/output-stream pdf-path)]
                   (render/render-pdf! fo-xml out
                                       {:base-dir book-root
                                        :title    (:title book)
                                        :author   (:author book)}))]
      {:profile  profile
       :path     pdf-path
       :paths    {:pdf pdf-path :fo fo-path}
       :warnings (:warnings result)})))

(defn execute!
  "Perform a Plan: load the book once, render each profile, and write the
   manifest. Returns the manifest map. When the plan enables code
   validation, the `:test` blocks are checked after loading and before
   assembly — a failing block aborts the build."
  [{:keys [book-root manuscript paths profile-steps manifest-skeleton validation]}]
  (let [started       (Instant/now)
        book          (load-book book-root manuscript)
        _             (when (:enabled validation)
                        (eval-validate/validate-chapters! (:chapters book)))
        base          {:book-root book-root :book book :tokens (:tokens manuscript)}
        artifacts-out (mapv #(render-profile! base %) profile-steps)
        finished      (Instant/now)]
    (artifacts/write!
      {:output-dir  (:book-output-dir paths)
       :config      (:config manuscript)
       :profiles    (:build/profiles manifest-skeleton)
       :artifacts   artifacts-out
       :started-at  started
       :finished-at finished
       :metadata    (:metadata manifest-skeleton)})))

(defn build
  "Execute the requested profile builds and return the manifest map.
   build = execute! ∘ plan ∘ prepare.

   With `:dry-run` truthy in the request, return the inspectable plan
   value instead of performing the build: the manuscript is still loaded
   and validated, but nothing is rendered and no artifacts are written."
  [request]
  (let [prepared (prepare request)
        the-plan (plan/plan prepared)]
    (if (:dry-run request)
      ;; Surface the validation plan (block counts per language) by loading
      ;; the book; nothing is rendered or evaluated.
      (cond-> the-plan
        (get-in the-plan [:validation :enabled])
        (assoc-in [:validation :plan]
                  (eval-registry/plan-validation
                   (:chapters (load-book (:book-root (:request prepared))
                                         (:manuscript prepared))))))
      (execute! the-plan))))

(defn validate
  "Run validation only: load and check the manuscript config, tokens, and
   chapter Hiccup (vocabulary + cross-reference resolution) without
   rendering. Returns `{:status :ok :warnings [...]}` or throws."
  [request]
  (let [{:keys [manuscript request]} (prepare request)
        book      (load-book (:book-root request) manuscript)
        the-theme (book-theme/compile-theme (:tokens manuscript) :screen)]
    ;; Structural check: chapter shape and cross-reference resolution.
    (assemble/assemble book the-theme)
    ;; Vocabulary check: each chapter body element conforms (humanized).
    (doseq [chapter (:chapters book)
            :let    [[_ _ & body] chapter]
            form    body]
      (fo-schema/check form :clj-book.build.execute/invalid-chapter-content))
    ;; Opt-in: validate marked code blocks (runs author code; see eval.*).
    (let [validation (when (:validate-code request)
                       (eval-validate/validate-chapters! (:chapters book)))]
      {:status     :ok
       :config     (:config-file manuscript)
       :tokens     (:tokens manuscript)
       :warnings   (:warnings manuscript)
       :validation validation})))
