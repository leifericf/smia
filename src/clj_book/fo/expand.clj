(ns clj-book.fo.expand
  "Pure expansion of the author Hiccup vocabulary into FO-Hiccup.

   Three concentric layers in one syntax:

   1. Raw FO floor: a `:fo/*` tag passes through unchanged (its children
      are still expanded, so sugar may be nested inside raw FO).
   2. HTML-flavored sugar (`:p :h1`–`:h6 :ul :ol :li :strong :em :code
      :pre :a :blockquote :img :hr :table :thead :tbody :tr :td :th`)
      expands to FO.
   3. Book extensions (`:admonition :footnote :xref`) expand to the FO
      that HTML can't name.

   Styling comes from a `style` map (tag -> FO property map). A base-14,
   zero-config `default-style` is provided; the book layer passes a
   theme-derived style. An unknown bare tag is a hard, structured error.
   This namespace is a pure core: no IO, no FOP."
  (:require
   [clj-book.error :as error]
   [clj-book.fo.hiccup :as hiccup]
   [clj-book.highlight.registry :as highlight]
   [clojure.string :as str]))

(declare expand)

;; --- node parsing ---------------------------------------------------------

(defn- flatten-children
  "Flatten one level of seqs (e.g. produced by `for`) among children."
  [children]
  (mapcat (fn [c] (if (seq? c) c [c])) children))

(defn- expand-all [children style]
  (->> (flatten-children children)
       (map #(expand % style))
       (remove nil?)
       vec))

(defn- as-id [v]
  (when (some? v) (if (keyword? v) (name v) (str v))))

;; --- default (base-14) style ----------------------------------------------

(def default-style
  "Zero-config base-14 styling: every block carries its own FO
   properties (FO has no CSS cascade). The book layer overrides this with
   a theme-derived style."
  {:body       {:font-family "serif" :font-size "11pt" :line-height "1.35"}
   :p          {:space-after "6pt"}
   :h1         {:font-size "20pt" :font-weight "bold" :space-before "18pt"
                :space-after "8pt" :keep-with-next.within-page "always"}
   :h2         {:font-size "16pt" :font-weight "bold" :space-before "14pt"
                :space-after "6pt" :keep-with-next.within-page "always"}
   :h3         {:font-size "13pt" :font-weight "bold" :space-before "12pt"
                :space-after "4pt" :keep-with-next.within-page "always"}
   :h4         {:font-size "12pt" :font-weight "bold" :space-before "10pt"
                :space-after "4pt"}
   :h5         {:font-size "11pt" :font-weight "bold" :space-before "8pt"
                :space-after "3pt"}
   :h6         {:font-size "11pt" :font-style "italic" :space-before "8pt"
                :space-after "3pt"}
   :code       {:font-family "monospace"}
   :pre        {:font-family "monospace" :white-space "pre" :space-before "6pt"
                :space-after "8pt" :background-color "#f4f4f4" :padding "6pt"
                :font-size "9.5pt"}
   :blockquote {:start-indent "18pt" :end-indent "18pt" :font-style "italic"
                :space-before "6pt" :space-after "6pt"}
   :ul         {:provisional-distance-between-starts "16pt"
                :provisional-label-separation "5pt" :space-after "6pt"}
   :ol         {:provisional-distance-between-starts "16pt"
                :provisional-label-separation "5pt" :space-after "6pt"}
   :dl         {:space-before "6pt" :space-after "8pt"}
   :dt         {:font-weight "bold" :space-before "4pt"}
   :dd         {:start-indent "18pt" :space-after "4pt"}
   :hr         {:border-top "0.5pt solid #999999" :space-before "8pt"
                :space-after "8pt"}
   :admonition {:border "0.75pt solid #999999" :padding "6pt"
                :space-before "8pt" :space-after "8pt"
                :background-color "#f7f7f7"}
   :overview   {:border-left "3pt solid #999999" :padding "8pt 10pt"
                :background-color "#f7f7f7" :space-before "8pt"
                :space-after "12pt"}
   :table      {:table-layout "fixed" :width "100%" :border-collapse "collapse"
                :space-before "6pt" :space-after "8pt"}
   :table-cell {:border "0.5pt solid #cccccc" :padding "4pt"}
   :figure     {:space-before "10pt" :space-after "10pt" :text-align "center"}
   :caption    {:font-size "9pt" :color "#666666" :space-before "4pt"
                :text-align "center"}
   :listing    {:space-before "6pt" :space-after "8pt"}
   :file-bar   {:font-family "monospace" :font-size "8pt" :font-weight "bold"
                :background-color "#e8e8e8" :padding "3pt 6pt"}
   :annotation-mark {:font-family "sans-serif" :font-size "7.5pt"
                     :font-weight "bold" :color "#ffffff"
                     :background-color "#555555" :padding "0pt 3pt"
                     :baseline-shift "super"}
   :annotation-list {:provisional-distance-between-starts "20pt"
                     :provisional-label-separation "6pt"
                     :font-size "9.5pt" :space-before "4pt" :space-after "6pt"}})

;; --- shared builders ------------------------------------------------------

(defn- styled-block
  "An `fo:block` carrying the style for `tag` plus `extra`, optionally an
   `id` from the author attrs, wrapping the expanded children."
  [tag author children style extra]
  (into [:fo/block (cond-> (merge (get style tag) extra)
                     (:id author) (assoc :id (as-id (:id author))))]
        (expand-all children style)))

(defn- styled-inline [props children style]
  (into [:fo/inline props] (expand-all children style)))

(defn- heading-with-marker
  "A styled heading block that also emits an `fo:marker` carrying its plain
   text, so running heads can retrieve the current section title."
  [tag marker-class author children style]
  (let [expanded (expand-all children style)
        text     (apply str (filter string? (tree-seq vector? seq (vec expanded))))]
    (into [:fo/block (cond-> (get style tag)
                       (:id author) (assoc :id (as-id (:id author))))]
          (cons [:fo/marker {:marker-class-name marker-class} text] expanded))))

;; --- lists ----------------------------------------------------------------

(defn- list-items [children]
  (filter #(and (vector? %) (= :li (first %))) (flatten-children children)))

(defn- list-block [list-type author children style]
  (let [base (cond-> (get style list-type)
               (:id author) (assoc :id (as-id (:id author))))]
    (into [:fo/list-block base]
          (map-indexed
            (fn [i item]
              (let [[_ _ item-children] (hiccup/parse-node item)
                    label (if (= list-type :ol) (str (inc i) ".") "•")]
                [:fo/list-item
                 [:fo/list-item-label {:end-indent "label-end()"}
                  [:fo/block label]]
                 [:fo/list-item-body {:start-indent "body-start()"}
                  (into [:fo/block] (expand-all item-children style))]]))
            (list-items children)))))

;; --- description lists -----------------------------------------------------

(defn- dl-block
  "A description list: a wrapping block whose children alternate a bold
   term (`:dt`) block and an indented definition (`:dd`) block. Non-`:dt`/
   `:dd` children are ignored, so whitespace and stray nodes are harmless."
  [author children style]
  (into [:fo/block (cond-> (get style :dl)
                     (:id author) (assoc :id (as-id (:id author))))]
        (keep (fn [child]
                (when (vector? child)
                  (let [[tag _ kids] (hiccup/parse-node child)]
                    (case tag
                      :dt (into [:fo/block (get style :dt)] (expand-all kids style))
                      :dd (into [:fo/block (get style :dd)] (expand-all kids style))
                      nil))))
              (flatten-children children))))

;; --- tables ---------------------------------------------------------------

(defn- cells-of [tr]
  (let [[_ _ children] (hiccup/parse-node tr)]
    (filter #(and (vector? %) (#{:td :th} (first %)))
            (flatten-children children))))

(defn- rows-of [section]
  (let [[_ _ children] (hiccup/parse-node section)]
    (filter #(and (vector? %) (= :tr (first %))) (flatten-children children))))

(defn- cell->fo [cell style]
  (let [[tag _ children] (hiccup/parse-node cell)]
    [:fo/table-cell (get style :table-cell)
     (into [:fo/block (when (= tag :th) {:font-weight "bold"})]
           (expand-all children style))]))

(defn- row->fo [tr style]
  (into [:fo/table-row] (map #(cell->fo % style) (cells-of tr))))

(defn- column-weights
  "Per-column proportional weights for a table. With no author `:cols`,
   every column is weight 1 (equal width). A `:cols` vector overrides the
   leading columns; any unspecified trailing columns default to 1, and any
   extra weights are ignored. FOP supports only fixed table layout, so
   explicit weights are the only way to widen a column for content (such as
   a long monospace identifier) that cannot wrap."
  [cols ncols]
  (when (and (some? cols)
             (not (and (vector? cols)
                       (every? #(and (number? %) (pos? %)) cols))))
    (throw (error/ex :clj-book.fo.expand/invalid-cols
                     ":table :cols must be a vector of positive numbers, one weight per column."
                     {:cols cols})))
  (vec (take ncols (concat cols (repeat 1)))))

(defn- table-block [author children style]
  (let [kids        (flatten-children children)
        find1       (fn [t] (first (filter #(and (vector? %) (= t (first %))) kids)))
        thead       (find1 :thead)
        tbody       (find1 :tbody)
        direct-rows (filter #(and (vector? %) (= :tr (first %))) kids)
        header-rows (when thead (rows-of thead))
        body-rows   (cond (some? tbody)     (rows-of tbody)
                          (seq direct-rows) direct-rows
                          :else             [])
        ncols       (apply max 0 (map #(count (cells-of %))
                                      (concat header-rows body-rows)))
        weights     (column-weights (:cols author) ncols)
        ;; FOP requires a non-empty fo:table-body. A valid header-only table
        ;; (e.g. a GFM table with no data rows) has no body rows, so render
        ;; its header as the body rather than emit an empty body FOP rejects.
        [header-rows body-rows] (if (seq body-rows)
                                  [header-rows (vec body-rows)]
                                  [nil (vec header-rows)])]
    (when (empty? body-rows)
      (throw (error/ex :clj-book.fo.expand/empty-table
                       "A :table needs at least one row."
                       {:table (into [:table] children)})))
    (into [:fo/table (get style :table)]
          (concat
            (map (fn [w] [:fo/table-column
                          {:column-width (str "proportional-column-width(" w ")")}])
                 weights)
            (when (seq header-rows)
              [(into [:fo/table-header] (map #(row->fo % style) header-rows))])
            [(into [:fo/table-body] (map #(row->fo % style) body-rows))]))))

;; --- captions, figures, listings ------------------------------------------

(defn- caption-block
  "A numbered caption: a bold \"Figure 3.\" / \"Table 1.\" / \"Listing 2.\"
   label (from the numbering pass) followed by the caption text."
  [author style]
  (into [:fo/block (get style :caption)]
        (concat
          (when-let [label (:label author)]
            [[:fo/inline {:font-weight "bold"} (str label ". ")]])
          (when-let [caption (:caption author)] [caption]))))

(defn- captioned?
  "True when a node carries a caption or a numbering-pass label."
  [author]
  (or (:caption author) (:label author)))

(defn- figure-block [author children style]
  (into [:fo/block (cond-> (get style :figure)
                     (:id author)    (assoc :id (as-id (:id author)))
                     (:float author) (assoc :float (name (:float author))))]
        (concat
          (expand-all children style)
          (when (captioned? author) [(caption-block author style)]))))

;; --- code rendering (syntax highlighting, line numbers) -------------------

(defn- code-content
  "Inline FO for a blob of code: colored highlight runs when the language is
   supported and the theme enables highlighting (`:highlight?`), otherwise
   the text verbatim. Whitespace is preserved by the tokenizer."
  [lang text style]
  (if-let [toks (and (:highlight? style) lang (highlight/tokenize lang text))]
    (mapv (fn [{:keys [kind text]}]
            (if-let [color (get-in style [:code-colors kind])]
              [:fo/inline {:color color} text]
              text))
          toks)
    [text]))

(defn- code-text [children] (apply str (filter string? children)))

(defn- code-block [author children style]
  (into [:fo/block (cond-> (get style :pre)
                     (:id author) (assoc :id (as-id (:id author))))]
        (code-content (:lang author) (code-text children) style)))

(defn- annotation-mark
  "A small theme-styled badge carrying an annotation's ordinal `n`. The same
   mark appears at the end of a code line and beside its note in the list."
  [n style]
  [:fo/inline (get style :annotation-mark) (str n)])

(defn- annotations->by-line
  "Validate a listing's `:annotations` and index them as `{line -> {:n
   ordinal :note note}}`. Ordinals are 1-based by vector order. Throws
   `:clj-book.fo.expand/invalid-annotation` for a line outside `1..line-count`
   or a line carrying more than one note."
  [annotations line-count id]
  (reduce
    (fn [acc [i {:keys [line note]}]]
      (when-not (and (integer? line) (<= 1 line line-count))
        (throw (error/ex :clj-book.fo.expand/invalid-annotation
                         (str "Annotation " (inc i) " references line "
                              (pr-str line) ", outside the listing's "
                              "1.." line-count " lines.")
                         {:listing id :line line :lines line-count})))
      (when (contains? acc line)
        (throw (error/ex :clj-book.fo.expand/invalid-annotation
                         (str "Line " line " carries more than one annotation.")
                         {:listing id :line line})))
      (assoc acc line {:n (inc i) :note note}))
    {}
    (map-indexed vector annotations)))

(defn- code-lines-block
  "Render code as one block per line. `gutter?` prefixes each line with a
   muted right-aligned line number; `marks` (a `{line -> ordinal}` map)
   appends an annotation mark at the end of each annotated line."
  [author lines style gutter? marks]
  (let [width (count (str (count lines)))
        lang  (:lang author)]
    (into [:fo/block (cond-> (get style :pre)
                       (:id author) (assoc :id (as-id (:id author))))]
          (map-indexed
            (fn [i line]
              (let [n (inc i)]
                (cond-> (into (cond-> [:fo/block {:white-space "pre"}]
                                gutter? (conj [:fo/inline {:color "#999999"}
                                               (str (format (str "%" width "d") n) "  ")]))
                              (code-content lang line style))
                  (get marks n) (conj (annotation-mark (get marks n) style)))))
            lines))))

(defn- render-code
  "Render a code body. Line numbers and/or annotation marks force per-line
   blocks; otherwise the code is one block. `by-line` (line -> {:n :note},
   or nil) supplies the annotation marks."
  [author children style by-line]
  (if (or (:line-numbers author) (seq by-line))
    (code-lines-block author (str/split (code-text children) #"\n" -1) style
                      (boolean (:line-numbers author))
                      (reduce-kv (fn [m line {:keys [n]}] (assoc m line n)) {}
                                 (or by-line {})))
    (code-block author children style)))

(defn- annotation-list-block
  "The ordered notes beneath an annotated listing: one item per annotation,
   each led by the same mark that appears at its line's end."
  [by-line style]
  (into [:fo/list-block (get style :annotation-list)]
        (map (fn [{:keys [n note]}]
               [:fo/list-item
                [:fo/list-item-label {:end-indent "label-end()"}
                 [:fo/block (annotation-mark n style)]]
                [:fo/list-item-body {:start-indent "body-start()"}
                 (into [:fo/block] (expand-all [note] style))]])
             (sort-by :n (vals by-line)))))

(defn- listing-block
  "A code listing: an optional filename header bar above the code block, an
   optional line-anchored annotation list beneath it, and an optional
   numbered caption. The wrapper carries the `:id` and is kept together."
  [author children style]
  (let [annotations (:annotations author)
        by-line     (when (seq annotations)
                      (annotations->by-line
                        annotations
                        (count (str/split (code-text children) #"\n" -1))
                        (as-id (:id author))))]
    (into [:fo/block (cond-> (assoc (get style :listing)
                                    :keep-together.within-page "always")
                       (:id author) (assoc :id (as-id (:id author))))]
          (concat
            (when-let [file (:file author)]
              [[:fo/block (get style :file-bar) file]])
            [(render-code (dissoc author :id) children style by-line)]
            (when by-line [(annotation-list-block by-line style)])
            (when (captioned? author) [(caption-block author style)])))))

;; --- book extensions ------------------------------------------------------

(def ^:private admonition-labels
  {:note "Note" :tip "Tip" :warning "Warning"
   :important "Important" :caution "Caution"})

(defn- sidebar-title
  "The title bar text for a sidebar/admonition: an explicit `:title`, else
   the label for a known `:kind`, else the capitalized kind, else nil."
  [{:keys [title kind]}]
  (cond
    title title
    kind  (get admonition-labels kind (str/capitalize (name kind)))
    :else nil))

(defn- sidebar-block
  "A bordered callout with an optional bold title bar (with optional icon)
   and a rich body. Generalizes the admonition: `:admonition` is the
   kind-titled special case, `:sidebar` adds an arbitrary `:title`."
  [author children style]
  (let [title (sidebar-title author)
        icon  (:icon author)]
    (into [:fo/block (cond-> (get style :admonition)
                       (:id author) (assoc :id (as-id (:id author))))]
          (concat
            (when title
              [[:fo/block {:font-weight "bold" :space-after "3pt"}
                (if icon (str icon " " title) title)]])
            (expand-all children style)))))

(defn- overview-block
  "A chapter-opening panel summarizing what the chapter covers: a bold label
   bar (default \"Overview\", overridable via `:title`; an explicit `:title
   nil` drops it) above a rich body."
  [author children style]
  (let [title (get author :title "Overview")]
    (into [:fo/block (cond-> (get style :overview)
                       (:id author) (assoc :id (as-id (:id author))))]
          (concat
            (when title
              [[:fo/block {:font-weight "bold" :space-after "4pt"} title]])
            (expand-all children style)))))

(defn- epigraph-block
  "A chapter/part opening quotation: the quote in italic, with an optional
   right-aligned attribution beneath it."
  [author children style]
  (into [:fo/block (cond-> {:start-indent "24pt" :font-style "italic"
                            :space-before "12pt" :space-after "18pt"}
                     (:id author) (assoc :id (as-id (:id author))))]
        (concat
          (expand-all children style)
          (when-let [attr (:attribution author)]
            [[:fo/block {:font-style "normal" :text-align "end"
                         :space-before "4pt"} (str "— " attr)]]))))

(defn- footnote [_author children style]
  [:fo/footnote
   [:fo/inline {:baseline-shift "super" :font-size "8pt"} "*"]
   [:fo/footnote-body
    (into [:fo/block {:font-size "9pt"}]
          (cons [:fo/inline {:baseline-shift "super" :font-size "8pt"} "* "]
                (expand-all children style)))]])

(defn- composed-xref
  "Build the inline content of a childless cross-reference from the
   label/title the numbering pass resolved: \"Chapter 2\" by default,
   \"Chapter 2: Title\" with `:style :full`, optionally followed by
   \", on page N\" when `:page` is set. With nothing resolved, fall back to
   a bare page-number citation (the pre-numbering behavior)."
  [author dest]
  (let [label (:label author)
        title (:title author)
        text  (cond
                (and (= :full (:style author)) label title) (str label ": " title)
                label label
                title title)]
    (if text
      (cond-> [text]
        (:page author) (conj ", on page " [:fo/page-number-citation {:ref-id dest}]))
      [[:fo/page-number-citation {:ref-id dest}]])))

(defn- cite [author]
  (let [key (:key author)]
    (when-not key
      (throw (error/ex :clj-book.fo.expand/invalid-cite
                       ":cite requires a :key." {:attrs author})))
    (let [ref-id (or (:ref-id author) (str "ref-" (name key)))
          label  (or (:label author) (name key))]
      [:fo/basic-link {:internal-destination ref-id :color "#1a0dab"} label])))

(defn- index-mark [author]
  ;; A zero-width anchor the index page-cites; invisible in the flow.
  [:fo/inline (cond-> {} (:id author) (assoc :id (as-id (:id author))))])

(defn- xref [author children style]
  (let [dest (as-id (:to author))]
    (when-not dest
      (throw (error/ex :clj-book.fo.expand/invalid-xref
                       ":xref requires a :to target id."
                       {:attrs author})))
    (into [:fo/basic-link {:internal-destination dest :color "#1a0dab"}]
          (if (seq (flatten-children children))
            (expand-all children style)
            (composed-xref author dest)))))

;; --- the expander table ---------------------------------------------------

(def expanders
  "Tag -> `(fn [attrs children style] -> fo-hiccup)`. A plain map so the
   book layer can introspect or extend it."
  (let [head (fn [tag] (fn [a c s] (styled-block tag a c s {})))]
    {:p          (fn [a c s] (styled-block :p a c s {}))
     :h1         (head :h1)
     :h2         (fn [a c s] (heading-with-marker :h2 "section-title" a c s))
     :h3         (head :h3)
     :h4         (head :h4)
     :h5         (head :h5)
     :h6         (head :h6)
     :pre        (fn [a c s] (if (or (:file a) (captioned? a) (:annotations a))
                               (listing-block a c s)
                               (render-code a c s nil)))
     :blockquote (fn [a c s] (styled-block :blockquote a c s {}))
     :li         (fn [a c s] (styled-block :p a c s {}))
     :hr         (fn [_ _ s] [:fo/block (get s :hr)])
     :strong     (fn [_ c s] (styled-inline {:font-weight "bold"} c s))
     :em         (fn [_ c s] (styled-inline {:font-style "italic"} c s))
     :code       (fn [_ c s] (styled-inline (get s :code) c s))
     :span       (fn [_ c s] (into [:fo/inline] (expand-all c s)))
     :br         (fn [_ _ _] [:fo/block])
     :a          (fn [a c s] (styled-inline
                              {:external-destination (str "url('" (:href a) "')")
                               :color "#1a0dab" :text-decoration "underline"}
                              c s))
     :img        (fn [a _ _] [:fo/external-graphic
                              (cond-> {:src (str "url('" (:src a) "')")}
                                (:width a)  (assoc :content-width (:width a))
                                (:height a) (assoc :content-height (:height a)))])
     :ul         (fn [a c s] (list-block :ul a c s))
     :ol         (fn [a c s] (list-block :ol a c s))
     :dl         (fn [a c s] (dl-block a c s))
     :dt         (fn [a c s] (styled-block :dt a c s {}))
     :dd         (fn [a c s] (styled-block :dd a c s {}))
     :figure     (fn [a c s] (figure-block a c s))
     :table      (fn [a c s]
                   (let [tbl (table-block a c s)]
                     (if (captioned? a)
                       (into [:fo/block (cond-> {:space-before "6pt" :space-after "8pt"}
                                          (:id a) (assoc :id (as-id (:id a))))]
                             [(caption-block a s) tbl])
                       tbl)))
     :thead      (fn [a c s] (styled-block :p a c s {}))
     :tbody      (fn [a c s] (styled-block :p a c s {}))
     :tr         (fn [a c s] (styled-block :p a c s {}))
     :td         (fn [a c s] (styled-block :p a c s {}))
     :th         (fn [a c s] (styled-block :p a c s {}))
     :admonition (fn [a c s] (sidebar-block (update a :kind #(or % :note)) c s))
     :sidebar    (fn [a c s] (sidebar-block a c s))
     :overview   (fn [a c s] (overview-block a c s))
     :epigraph   (fn [a c s] (epigraph-block a c s))
     :footnote   (fn [a c s] (footnote a c s))
     :xref       (fn [a c s] (xref a c s))
     :cite       (fn [a _ _] (cite a))
     :index      (fn [a _ _] (index-mark a))
     :page-break (fn [_ _ _] [:fo/block {:break-before "page"}])
     :keep-together
     (fn [a c s]
       (into [:fo/block (cond-> {:keep-together.within-page "always"}
                          (:id a) (assoc :id (as-id (:id a))))]
             (expand-all c s)))}))

;; --- the public transform -------------------------------------------------

(defn expand
  "Expand author Hiccup `node` into FO-Hiccup using `style` (defaults to
   `default-style`). `:fo/*` passes through (children still expanded);
   known sugar/book tags are expanded; an unknown bare tag throws a
   structured `:clj-book.fo.expand/unknown-tag` error."
  ([node] (expand node default-style))
  ([node style]
   (cond
     (nil? node)    nil
     (string? node) node
     (number? node) node
     (vector? node)
     (let [[tag attrs children] (hiccup/parse-node node)]
       (cond
         (and (keyword? tag) (= "fo" (namespace tag)))
         (let [expanded (expand-all children style)]
           (if attrs (into [tag attrs] expanded) (into [tag] expanded)))

         (contains? expanders tag)
         ((get expanders tag) attrs children style)

         :else
         (throw (error/ex :clj-book.fo.expand/unknown-tag
                          (str "Unknown element tag: " (pr-str tag)
                               ". Use a known sugar/book tag or a raw "
                               ":fo/* element.")
                          {:tag tag}))))
     (seq? node) (vec (expand-all node style))
     :else
     (throw (error/ex :clj-book.fo.expand/invalid-node
                      (str "Cannot expand node of type "
                           (some-> node class .getName))
                      {:node node})))))
