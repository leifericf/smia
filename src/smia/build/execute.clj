(ns smia.build.execute
  "Build context (shell): load the manuscript, plan the build, and
   perform it.

   `build` = execute! ∘ plan ∘ prepare. The pure decisions live in
   smia.build.plan and the pure cores (book assembly, FO expansion
   and serialization); all IO — reading inputs, evaluating chapters, and
   FOP writing PDF bytes — happens here. Chapters are loaded once and
   reused across editions; each edition step dispatches on its
   descriptor's `:format`."
  (:require
   [smia.book.assemble :as assemble]
   [smia.book.config :as config]
   [smia.book.load :as book-load]
   [smia.book.number :as number]
   [smia.build.artifacts :as artifacts]
   [smia.build.plan :as plan]
   [smia.build.request :as request]
   [smia.epub.assemble :as epub-assemble]
   [smia.epub.zip :as epub-zip]
   [smia.error :as error]
   [smia.eval.registry :as eval-registry]
   [smia.eval.validate :as eval-validate]
   [smia.fo.expand :as expand]
   [smia.fo.fop-config :as fop-config]
   [smia.fo.render :as render]
   [smia.fo.schema :as fo-schema]
   [smia.fo.serialize :as serialize]
   [smia.math.resolve :as math-resolve]
   [smia.schema :as schema]
   [smia.site.assemble :as site-assemble]
   [smia.site.emit :as site-emit]
   [smia.theme.compile :as theme-compile]
   [smia.theme.load :as theme]
   [clojure.java.io :as io])
  (:import
   (java.time Instant)))

(declare build-paths load-book render-edition!)

(defn prepare
  "Shell: load and validate the manuscript and resolve output paths.
   Returns `{:request <normalized> :manuscript {...} :paths {...}}`."
  [request]
  (let [{:keys [config path warnings]} (config/load-config request)
        {:keys [tokens]}               (theme/load-tokens request)
        paths                          (build-paths request config)]
    (when (and (some #{:print-x} (:editions request))
               (not (:book/print-x config)))
      (throw (error/ex :smia.build.request/print-x-requires-config
                       (str "The :print-x edition needs a :book/print-x map in "
                            "book.edn (embedded fonts and an ICC output "
                            "intent) — PDF/X requires every font embedded.")
                       {:editions (:editions request) :config-file path})))
    {:request    request
     :manuscript {:config      config
                  :config-file path
                  :tokens      tokens
                  :warnings    (vec warnings)}
     :paths      (schema/check schema/Paths paths
                               :smia.build.execute/invalid-paths)}))

(defn execute!
  "Perform a Plan: load the book once, render each edition, and write the
   manifest. Returns the manifest map. When the plan enables code
   validation, the `:test` blocks are checked after loading and before
   assembly — a failing block aborts the build."
  [{:keys [book-root manuscript paths edition-steps manifest-skeleton validation
           licensee]}]
  (let [started       (Instant/now)
        book          (load-book book-root manuscript)
        _             (when (:enabled validation)
                        (eval-validate/validate-chapters! (:chapters book)))
        ;; Math renders once, after numbering and before the editions
        ;; split, so every edition carries the same SVG.
        numbered      (math-resolve/attach-svg (:manuscript (number/assign book)))
        base          {:book-root book-root :book numbered
                       :tokens (:tokens manuscript)
                       :config (:config manuscript)
                       :licensee licensee}
        artifacts-out (mapv #(render-edition! base %) edition-steps)
        finished      (Instant/now)]
    (artifacts/write!
      {:output-dir  (:book-output-dir paths)
       :config      (:config manuscript)
       :editions    (:build/editions manifest-skeleton)
       :artifacts   artifacts-out
       :started-at  started
       :finished-at finished
       :metadata    (:metadata manifest-skeleton)})))

(defn- delete-tree!
  "Recursively delete the file or directory at `path` (children before
   parents). A no-op when it does not exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [^java.io.File child (reverse (file-seq f))]
        (.delete child)))))

(defn build
  "Execute the requested edition builds and return the manifest map.
   build = execute! ∘ plan ∘ prepare.

   With `:dry-run` truthy in the request, return the inspectable plan
   value instead of performing the build: the manuscript is still loaded
   and validated, but nothing is rendered and no artifacts are written.

   With `:clean` truthy (and not a dry run), the book's output directory
   is removed before building — a guaranteed-fresh slate that also evicts
   editions and resources a prior build wrote but this one does not."
  [request]
  (let [prepared (prepare request)
        the-plan (plan/plan prepared)]
    (if (:dry-run request)
      ;; Surface the numbering summary and the validation plan (block counts
      ;; per language) by loading the book; nothing is rendered or evaluated.
      (let [book (load-book (:book-root (:request prepared)) (:manuscript prepared))]
        (cond-> (assoc-in the-plan [:numbering :counts]
                          (number/counts (number/assign book)))
          (get-in the-plan [:validation :enabled])
          (assoc-in [:validation :plan]
                    (eval-registry/plan-validation (:chapters book)))))
      (do
        (when (:clean request)
          (delete-tree! (get-in prepared [:paths :book-output-dir])))
        (execute! the-plan)))))

(defn validate
  "Run validation only: load and check the manuscript config, tokens, and
   chapter Hiccup (vocabulary + cross-reference resolution) without
   rendering. Returns `{:status :ok :warnings [...]}` or throws."
  [request]
  (let [{:keys [manuscript request]} (prepare request)
        book      (load-book (:book-root request) manuscript)
        numbered  (:manuscript (number/assign book))
        the-theme (theme-compile/compile-theme (:tokens manuscript) :screen)]
    ;; Structural check: numbering resolves every cross-reference and
    ;; citation (a hard error otherwise); assembly then builds the tree.
    (assemble/assemble numbered the-theme)
    ;; Vocabulary check: each chapter body element conforms (humanized).
    (doseq [chapter (:chapters book)
            :let    [[_ _ & body] chapter]
            form    body]
      (fo-schema/check form :smia.build.execute/invalid-chapter-content))
    ;; Opt-in: validate marked code blocks (runs author code; see eval.*).
    (let [validation (when (:validate-code request)
                       (eval-validate/validate-chapters! (:chapters book)))]
      {:status     :ok
       :config     (:config-file manuscript)
       :tokens     (:tokens manuscript)
       :warnings   (:warnings manuscript)
       :validation validation})))

;; --- private helpers -------------------------------------------------------

(defn- build-paths [{:keys [output-root]} config]
  (let [slug (:book/slug config)
        root (io/file output-root slug)]
    {:book-output-dir  (.getPath root)
     :intermediate-dir (.getPath (io/file root "intermediate"))
     :pdf-output-dir   (.getPath (io/file root "pdf"))}))

(defn- load-book
  "Shell: load the book's structure and chapter files into the typed,
   assemble-ready manuscript value (see `book.load/load-manuscript`).
   The theme's `:type` group tunes the Markdown front-end: smart
   punctuation is on unless `{:smart-punctuation false}`."
  [book-root {:keys [config tokens]}]
  (book-load/load-manuscript
    book-root config
    {:smart-punctuation (get-in tokens [:type :smart-punctuation] true)}))

(defn- render-pdf-edition!
  "Assemble -> expand -> serialize -> FOP for one PDF edition. The page
   layout comes from the edition's descriptor; when the book configures
   `:book/print-x`, its fonts are embedded in every PDF edition and the
   `:print-x` descriptor additionally turns on PDF/X conformance. Writes
   the intermediate FO and the final PDF; returns the artifact entry."
  [{:keys [book-root book tokens config licensee]} {:keys [edition fo-path pdf-path]} descriptor]
  (let [the-theme (cond-> (theme-compile/compile-theme tokens (:layout descriptor))
                    ;; PDF/X forbids link annotations: render references
                    ;; as text and let the page citations locate them.
                    (:pdf-x descriptor) (-> (assoc :links? false)
                                            (assoc-in [:style :links?] false)))
        fo-xml    (-> (assemble/assemble (assoc book :licensee licensee) the-theme)
                      (expand/expand (:style the-theme))
                      (serialize/serialize))]
    (io/make-parents (io/file fo-path))
    (spit fo-path fo-xml)
    (io/make-parents (io/file pdf-path))
    (let [result (with-open [out (io/output-stream pdf-path)]
                   (render/render-pdf!
                     fo-xml out
                     (cond-> {:base-dir book-root
                              :title    (:title book)
                              :author   (:author book)}
                       (:book/print-x config)
                       (assoc :fop-config
                              (fop-config/xconf (:book/print-x config)
                                                {:pdf-x? (boolean (:pdf-x descriptor))})))))]
      {:edition  edition
       :path     pdf-path
       :paths    {:pdf pdf-path :fo fo-path}
       :warnings (:warnings result)})))

(defn- render-site!
  "Assemble the static site (pure) and write its page map (shell).
   Returns the artifact entry."
  [{:keys [book-root book tokens config]} {:keys [edition out-dir]}]
  (let [{:keys [pages resources]} (site-assemble/assemble
                                    book tokens
                                    {:downloads (:book/downloads config)
                                     :redirects (:book/redirects config)
                                     :site-url  (:book/site-url config)})
        result (site-emit/emit! {:out-dir   out-dir
                                 :book-root book-root
                                 :pages     pages
                                 :resources resources})]
    {:edition  edition
     :path     out-dir
     :paths    {:dir out-dir :index (str out-dir "/index.html")}
     :warnings (:warnings result)}))

(defn- render-epub!
  "Assemble the EPUB package contents (pure) and write the deterministic
   archive (shell). Returns the artifact entry."
  [{:keys [book-root book tokens config]} {:keys [edition epub-path]}]
  (let [{:keys [entries]}
        (epub-assemble/assemble
          book tokens
          {:identifier    (or (:book/identifier config)
                              (str "urn:smia:" (:book/slug config)))
           :language      (:book/language config)
           :accessibility (:book/accessibility config)})
        result (epub-zip/write! {:entries   entries
                                 :epub-path epub-path
                                 :book-root book-root})]
    {:edition  edition
     :path     epub-path
     :paths    {:epub epub-path}
     :warnings (:warnings result)}))

(defn- render-edition!
  "Render one edition step, dispatching on its descriptor's `:format`."
  [base {:keys [edition] :as step}]
  (let [descriptor (get request/edition-descriptors edition)]
    (case (:format descriptor)
      :pdf  (render-pdf-edition! base step descriptor)
      :html (render-site! base step)
      :epub (render-epub! base step))))
