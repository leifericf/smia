(ns clj-book.book.assemble
  "Pure core: assemble ordered chapter Hiccup plus book metadata and a
   compiled theme into a single `:fo/root` FO-Hiccup tree.

   It emits the book machinery authors do not write by hand: the
   layout-master-set, a title page and table of contents (chapter links +
   dotted leaders + page-number citations), a PDF bookmark tree, and one
   `fo:page-sequence` per chapter with running heads (via
   `fo:marker`/`fo:retrieve-marker`) and page numbers. Chapter bodies are
   embedded as authored sugar; the expansion pass that runs afterwards
   turns that sugar into FO. Cross-references are resolved here: an
   `[:xref {:to id}]` to an unknown id is a hard error. No IO."
  (:require
   [clj-book.error :as error]
   [clojure.string :as str]))

(defn- as-id [v] (if (keyword? v) (name v) (str v)))

;; --- chapter parsing ------------------------------------------------------

(defn- parse-chapter [form]
  (when-not (and (vector? form) (= :chapter (first form)))
    (throw (error/ex :clj-book.book.assemble/invalid-chapter
                     "A chapter must be a [:chapter {:id .. :title ..} ..] form."
                     {:chapter form})))
  (let [[_ attrs & body] form
        attrs (or attrs {})]
    (when-not (keyword? (:id attrs))
      (throw (error/ex :clj-book.book.assemble/missing-chapter-id
                       "Each :chapter needs a keyword :id."
                       {:chapter form})))
    (when-not (string? (:title attrs))
      (throw (error/ex :clj-book.book.assemble/missing-chapter-title
                       "Each :chapter needs a string :title."
                       {:chapter form})))
    {:id (:id attrs) :title (:title attrs) :body (vec body)}))

;; --- cross-reference resolution -------------------------------------------

(defn- collect-ids [node]
  (cond
    (vector? node)
    (let [[_ attrs] node]
      (concat (when (and (map? attrs) (:id attrs)) [(as-id (:id attrs))])
              (mapcat collect-ids node)))
    (seq? node) (mapcat collect-ids node)
    :else nil))

(defn- collect-xref-targets [node]
  (cond
    (vector? node)
    (let [[tag attrs] node]
      (concat (when (and (= :xref tag) (map? attrs) (:to attrs))
                [(as-id (:to attrs))])
              (mapcat collect-xref-targets node)))
    (seq? node) (mapcat collect-xref-targets node)
    :else nil))

(defn- resolve-xrefs!
  "Throw if any `[:xref {:to id}]` points at an id no chapter or element
   defines."
  [chapters]
  (let [defined (set (concat (map (comp name :id) chapters)
                             (mapcat #(collect-ids (:body %)) chapters)))
        used    (mapcat #(collect-xref-targets (:body %)) chapters)
        missing (vec (distinct (remove defined used)))]
    (when (seq missing)
      (throw (error/ex :clj-book.book.assemble/unresolved-xref
                       (str "Cross-reference(s) to unknown id(s): "
                            (str/join ", " missing))
                       {:missing missing :defined (vec (sort defined))})))))

;; --- fragments ------------------------------------------------------------

(defn- bookmark-tree [chapters]
  (into [:fo/bookmark-tree]
        (for [{:keys [id title]} chapters]
          [:fo/bookmark {:internal-destination (name id)}
           [:fo/bookmark-title title]])))

(defn- toc-entry [{:keys [id title]}]
  [:fo/block {:text-align-last "justify" :space-after "4pt"}
   [:fo/basic-link {:internal-destination (name id) :color "#1a0dab"} title]
   [:fo/leader {:leader-pattern         "dots"
                :leader-length.minimum  "12pt"
                :leader-length.optimum  "400pt"
                :leader-length.maximum  "400pt"}]
   [:fo/page-number-citation {:ref-id (name id)}]])

(defn- front-matter [title author chapters master-ref body-style]
  [:fo/page-sequence {:master-reference master-ref :format "i"}
   [:fo/static-content {:flow-name "xsl-region-after"}
    [:fo/block {:text-align "center" :font-size "9pt"} [:fo/page-number]]]
   (into [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)]
         (concat
           [[:fo/block {:font-size "30pt" :font-weight "bold"
                        :space-before "48pt" :space-after "12pt"} title]]
           (when author
             [[:fo/block {:font-size "14pt" :space-after "36pt"} author]])
           [[:fo/block {:font-size "18pt" :font-weight "bold"
                        :break-before "page" :space-after "10pt"} "Contents"]]
           (map toc-entry chapters)))])

(defn- chapter-heading [id title style]
  [:fo/block (merge (get style :h1) {:id (name id) :break-before "page"
                                     :space-before "0pt"})
   [:fo/marker {:marker-class-name "chapter-title"} title]
   title])

(defn- chapter-sequence [{:keys [id title body]} master-ref style body-style first?]
  [:fo/page-sequence (cond-> {:master-reference master-ref}
                       first? (assoc :initial-page-number "1" :format "1"))
   [:fo/static-content {:flow-name "xsl-region-before"}
    [:fo/block {:text-align "center" :font-size "9pt" :color "#666666"}
     [:fo/retrieve-marker {:retrieve-class-name "chapter-title"}]]]
   [:fo/static-content {:flow-name "xsl-region-after"}
    [:fo/block {:text-align "center" :font-size "9pt"} [:fo/page-number]]]
   (into [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)]
         (cons (chapter-heading id title style) body))])

;; --- assembly -------------------------------------------------------------

(defn assemble
  "Assemble `manuscript` (`{:title :author :chapters}`, chapters being
   `[:chapter {:id :title} ..]` Hiccup forms) and a compiled `theme`
   (from `clj-book.book.theme/compile-theme`) into one `:fo/root` tree.
   Bodies remain authored sugar for the later expansion pass."
  [{:keys [title author chapters]} {:keys [style master-reference masters]}]
  (let [parsed     (mapv parse-chapter chapters)
        _          (resolve-xrefs! parsed)
        body-style (get style :body)]
    (into [:fo/root {:font-family (:font-family body-style)
                     :font-size   (:font-size body-style)
                     :line-height (:line-height body-style)}]
          (concat
            [(into [:fo/layout-master-set] masters)]
            [(bookmark-tree parsed)]
            [(front-matter title author parsed master-reference body-style)]
            (map-indexed
              (fn [i ch]
                (chapter-sequence ch master-reference style body-style (zero? i)))
              parsed)))))
