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
   [clojure.string :as str]))

(declare expand)

;; --- node parsing ---------------------------------------------------------

(defn- parse-node
  "Split a Hiccup vector into `[tag attrs children]` (attrs may be nil)."
  [[tag & more]]
  (if (map? (first more))
    [tag (first more) (next more)]
    [tag nil more]))

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
   :hr         {:border-top "0.5pt solid #999999" :space-before "8pt"
                :space-after "8pt"}
   :admonition {:border "0.75pt solid #999999" :padding "6pt"
                :space-before "8pt" :space-after "8pt"
                :background-color "#f7f7f7"}
   :table      {:table-layout "fixed" :width "100%" :border-collapse "collapse"
                :space-before "6pt" :space-after "8pt"}
   :table-cell {:border "0.5pt solid #cccccc" :padding "4pt"}})

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

;; --- lists ----------------------------------------------------------------

(defn- list-items [children]
  (filter #(and (vector? %) (= :li (first %))) (flatten-children children)))

(defn- list-block [list-type author children style]
  (let [base (cond-> (get style list-type)
               (:id author) (assoc :id (as-id (:id author))))]
    (into [:fo/list-block base]
          (map-indexed
            (fn [i item]
              (let [[_ _ item-children] (parse-node item)
                    label (if (= list-type :ol) (str (inc i) ".") "•")]
                [:fo/list-item
                 [:fo/list-item-label {:end-indent "label-end()"}
                  [:fo/block label]]
                 [:fo/list-item-body {:start-indent "body-start()"}
                  (into [:fo/block] (expand-all item-children style))]]))
            (list-items children)))))

;; --- tables ---------------------------------------------------------------

(defn- cells-of [tr]
  (let [[_ _ children] (parse-node tr)]
    (filter #(and (vector? %) (#{:td :th} (first %)))
            (flatten-children children))))

(defn- rows-of [section]
  (let [[_ _ children] (parse-node section)]
    (filter #(and (vector? %) (= :tr (first %))) (flatten-children children))))

(defn- cell->fo [cell style]
  (let [[tag _ children] (parse-node cell)]
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

;; --- book extensions ------------------------------------------------------

(def ^:private admonition-labels
  {:note "Note" :tip "Tip" :warning "Warning"
   :important "Important" :caution "Caution"})

(defn- admonition-block [author children style]
  (let [kind  (or (:kind author) :note)
        label (get admonition-labels kind (str/capitalize (name kind)))]
    (into [:fo/block (get style :admonition)]
          (cons [:fo/block {:font-weight "bold" :space-after "3pt"} label]
                (expand-all children style)))))

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
     :h2         (head :h2)
     :h3         (head :h3)
     :h4         (head :h4)
     :h5         (head :h5)
     :h6         (head :h6)
     :pre        (fn [a c s] (styled-block :pre a c s {}))
     :blockquote (fn [a c s] (styled-block :blockquote a c s {}))
     :li         (fn [a c s] (styled-block :p a c s {}))
     :hr         (fn [_ _ s] [:fo/block (get s :hr)])
     :strong     (fn [_ c s] (styled-inline {:font-weight "bold"} c s))
     :em         (fn [_ c s] (styled-inline {:font-style "italic"} c s))
     :code       (fn [_ c s] (styled-inline (get s :code) c s))
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
     :table      (fn [a c s] (table-block a c s))
     :thead      (fn [a c s] (styled-block :p a c s {}))
     :tbody      (fn [a c s] (styled-block :p a c s {}))
     :tr         (fn [a c s] (styled-block :p a c s {}))
     :td         (fn [a c s] (styled-block :p a c s {}))
     :th         (fn [a c s] (styled-block :p a c s {}))
     :admonition (fn [a c s] (admonition-block a c s))
     :footnote   (fn [a c s] (footnote a c s))
     :xref       (fn [a c s] (xref a c s))}))

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
     (let [[tag attrs children] (parse-node node)]
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
