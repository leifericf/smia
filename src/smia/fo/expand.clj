(ns smia.fo.expand
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
   [smia.book.dictionary :as dictionary]
   [smia.error :as error]
   [smia.hiccup :as hiccup]
   [smia.highlight.registry :as highlight]
   [smia.svg.resolve :as svg-resolve]
   [clojure.string :as str]))

(declare expand-all expanders default-style)

;; --- breaking and hyphenation of code and URLs ----------------------------
;;
;; Long monospace tokens and URLs are the two things FOP mishandles in
;; justified text. Left alone it hyphenates them like prose, inserting a
;; hyphen that corrupts an identifier or a URL (clojure-inter- / views/).
;; Forbid that and they become unbreakable boxes that stretch the line they
;; sit at the end of. The remedy is the same for both: word joiners (U+2060)
;; inside each letter run forbid hyphenation, and a zero-width space (U+200B)
;; at every clean boundary - a delimiter or a camelCase hump - gives FOP a
;; place to wrap with no hyphen. Both marks are invisible and live only in
;; display text; an href comes from attrs, so live links stay intact.

(defn- no-hyphenate
  "Join each letter run of `s` with word joiners (U+2060) so FOP cannot find
   a hyphenation point inside it. FOP resolves hyphenation per block and
   ignores `hyphenate=\"false\"` on an `fo:inline`, so this is the only thing
   that reliably stops a code identifier or URL from being hyphenated."
  [s]
  (str/replace s #"\p{L}{2,}" #(str/join "\u2060" %)))

(def ^:private url-token-re
  "A bare URL or www-host run in body text. Footnote citations carry these
   as plain strings (CommonMark here does not autolink)."
  #"(?i)(?:https?://|www\.)\S+")

(def ^:private url-break-after-re
  "URL delimiter characters after which a mid-URL line break reads cleanly."
  #"([/.?#&=_~%+-])")

(defn- break-long-urls
  "Make any URL-like run in `s` wrap at its delimiters and never hyphenate.
   Non-URL text returns as-is, guarded by a cheap substring check so prose
   pays nothing."
  [s]
  (if (or (str/includes? s "://") (str/includes? s "www."))
    (str/replace s url-token-re
                 (fn [tok] (-> tok
                               (str/replace url-break-after-re "$1\u200b")
                               no-hyphenate)))
    s))

(def ^:private code-break-after-re
  "Internal separators in code after which a line break reads cleanly. A
   leading sigil (@, :, #) is deliberately excluded so it never dangles at a
   line end; camelCase humps cover those tokens instead."
  #"([/._-])")

(def ^:private camel-hump-re
  "A camelCase/PascalCase hump: a lowercase letter or digit then an uppercase
   letter, e.g. the `hV` in `@PathVariable`."
  #"([\p{Ll}\p{Nd}])(\p{Lu})")

(defn- protect-code
  "Make an inline-code string break cleanly and never hyphenate. A zero-width
   space after each separator and at each camelCase hump gives FOP a place to
   wrap a long identifier (`@PathVariable` becomes `@Path` / `Variable`) with
   no hyphen; word joiners inside each run forbid hyphenation (see
   `no-hyphenate`). Without a wrap point such a token is an unbreakable box
   that stretches the justified line it ends."
  [s]
  (-> s
      (str/replace code-break-after-re "$1\u200b")
      (str/replace camel-hump-re "$1\u200b$2")
      no-hyphenate))

;; --- the public transform -------------------------------------------------

(defn expand
  "Expand author Hiccup `node` into FO-Hiccup using `style` (defaults to
   `default-style`). `:fo/*` passes through (children still expanded);
   known sugar/book tags are expanded; an unknown bare tag throws a
   structured `:smia.fo.expand/unknown-tag` error."
  ([node] (expand node default-style))
  ([node style]
   (cond
     (nil? node)    nil
     (string? node) (break-long-urls node)
     (number? node) node
     (vector? node)
     (let [[tag attrs children] (hiccup/parse-node node)]
       (cond
         (and (keyword? tag) (= "fo" (namespace tag)))
         (let [expanded (expand-all children style)]
           (if attrs (into [tag attrs] expanded) (into [tag] expanded)))

         (and (keyword? tag) (= "html" (namespace tag)))
         (throw (error/ex :smia.fo.expand/html-tag-in-pdf
                          (str "Raw HTML element " (pr-str tag) " has no PDF "
                               "rendering. Use portable sugar, or the :fo/* "
                               "hatch for this edition.")
                          {:tag tag}))

         (contains? expanders tag)
         ((get expanders tag) attrs children style)

         :else
         (throw (error/ex :smia.fo.expand/unknown-tag
                          (str "Unknown element tag: " (pr-str tag)
                               ". Use a known sugar/book tag or a raw "
                               ":fo/* element.")
                          {:tag tag}))))
     (seq? node) (vec (expand-all node style))
     :else
     (throw (error/ex :smia.fo.expand/invalid-node
                      (str "Cannot expand node of type "
                           (some-> node class .getName))
                      {:node node})))))

;; --- node parsing ---------------------------------------------------------

(def ^:private flush-after?
  "Block-level tags that end a paragraph run: a `:p` following one of these
   (or opening its parent) is the run's first paragraph and sets flush, in
   the classical style where only a paragraph after another paragraph takes
   the first-line indent. Inline tags and strings are not displayed
   material, so they leave the run intact."
  #{:h1 :h2 :h3 :h4 :h5 :h6 :ul :ol :dl :pre :blockquote :hr :table
    :figure :admonition :sidebar :overview :example :details :open
    :epigraph :page-break :keep-together :img :diagram})

(defn- expand-all
  "Expand `children` left-to-right, rewriting the first `:p` of each
   paragraph run to `:p-first` (styled flush under an indent-mode theme;
   identical to `:p` otherwise). The expander table stays a pure tag->fn
   map — the sibling awareness lives only in this walk."
  [children style]
  (->> (hiccup/flatten-children children)
       (reduce (fn [[out prev] node]
                 (let [tag      (when (vector? node) (first node))
                       node     (if (and (= :p tag) (not= :p prev))
                                  (assoc node 0 :p-first)
                                  node)
                       expanded (expand node style)
                       prev     (cond
                                  (#{:p :p-first} tag) :p
                                  (flush-after? tag)   :other
                                  :else                prev)]
                   [(cond-> out (some? expanded) (conj expanded)) prev]))
               [[] nil])
       first))

(defn- links?
  "Should references render as live links? On unless the style says
   `:links? false` — PDF/X forbids link annotations, so the print-x
   edition renders reference text (and page citations) without them."
  [style]
  (not (false? (:links? style))))

(defn- line-numbers?
  "Should this listing carry a line-number gutter? An explicit per-listing
   `:line-numbers` wins; otherwise the theme default (`:line-numbers?` on
   the style); otherwise on. The gutter is the continuity cue when a
   listing splits across a page break."
  [author style]
  (if (contains? author :line-numbers)
    (boolean (:line-numbers author))
    (not (false? (:line-numbers? style)))))

;; --- default (base-14) style ----------------------------------------------

(def default-style
  "Zero-config base-14 styling: every block carries its own FO
   properties (FO has no CSS cascade). The book layer overrides this with
   a theme-derived style."
  {:body       {:font-family "serif" :font-size "11pt" :line-height "1.35"}
   :p          {:space-after "6pt"}
   ;; The first paragraph of a run (after a heading or displayed material).
   ;; Identical here — the distinction matters only to an indent-mode theme,
   ;; which styles :p with a first-line indent and :p-first flush.
   :p-first    {:space-after "6pt"}
   :li         {:space-after "6pt"}
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
   ;; Inline code sits inside serif body text; monospace faces carry a
   ;; larger x-height, so equal sizes make code tower over the prose.
   ;; Block code (`:pre`) styles itself — this never applies inside it.
   ;; hyphenate="false" stops hyphenation for block-level code, which FOP
   ;; honors. It does NOT help inline code: FOP resolves hyphenation per
   ;; block and ignores the flag on an fo:inline, so inline code is also
   ;; word-joined at render time (see `protect-code`). The flag stays for
   ;; correctness and any non-FOP consumer of the style.
   :code       {:font-family "monospace" :font-size "0.85em" :hyphenate "false"}
   :pre        {:font-family "monospace" :white-space "pre" :wrap-option "wrap"
                :hyphenate "false"
                :space-before "6pt" :space-after "8pt"
                :background-color "#f4f4f4" :padding "6pt" :font-size "9.5pt"}
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
   :example    {:border-left "3pt solid #999999" :padding "6pt 10pt"
                :space-before "8pt" :space-after "8pt"}
   :details    {:border-left "1pt solid #cccccc" :padding "6pt 10pt"
                :space-before "8pt" :space-after "8pt"}
   ;; A table that spans a page break repeats its column header on the
   ;; continuation page (FOP's default, stated to guard against a theme
   ;; override and to signal the table continues).
   :table      {:table-layout "fixed" :width "100%" :border-collapse "collapse"
                :table-omit-header-at-break "false"
                :space-before "6pt" :space-after "8pt"}
   :table-cell {:border "0.5pt solid #cccccc" :padding "4pt"}
   :figure     {:space-before "10pt" :space-after "10pt" :text-align "center"}
   :caption    {:font-size "9pt" :color "#666666" :space-before "4pt"
                :text-align "center"}
   :listing    {:space-before "6pt" :space-after "8pt"}
   :file-bar   {:font-family "monospace" :font-size "8pt" :font-weight "bold"
                :hyphenate "false"
                :background-color "#e8e8e8" :padding "3pt 6pt"}
   ;; The badge box is the inline's background: the digit's glyph box sits
   ;; flush at the font ascent with the descent's empty space below, so the
   ;; top padding carries the descent's worth extra to center it optically.
   :annotation-mark {:font-family "sans-serif" :font-size "7.5pt"
                     :font-weight "bold" :color "#ffffff"
                     :background-color "#555555"
                     :padding-top "2pt" :padding-bottom "0.5pt"
                     :padding-left "2pt" :padding-right "2pt"}
   :annotation-list {:provisional-distance-between-starts "20pt"
                     :provisional-label-separation "6pt"
                     :font-size "9.5pt" :space-before "4pt" :space-after "6pt"}
   ;; The note body hangs its text under the marker: the negative
   ;; text-indent pulls the first line (the label) back to the margin.
   :footnote     {:font-size "9pt" :start-indent "9pt" :text-indent "-9pt"}
   :footnote-ref {:baseline-shift "super" :font-size "8pt"}})

;; --- shared builders ------------------------------------------------------

(defn- styled-block
  "An `fo:block` carrying the style for `tag` plus `extra`, optionally an
   `id` from the author attrs, wrapping the expanded children."
  [tag author children style extra]
  (into [:fo/block (cond-> (merge (get style tag) extra)
                     (:id author) (assoc :id (hiccup/as-id (:id author))))]
        (expand-all children style)))

(defn- styled-inline [props children style]
  (into [:fo/inline props] (expand-all children style)))

(defn- styled-code-inline
  "An inline for monospace/code text whose string content is hyphenation-
   protected (see `protect-code`); other children expand normally."
  [props children style]
  (into [:fo/inline props]
        (map (fn [n] (if (string? n) (protect-code n) (expand n style))) children)))

;; Interface-vocabulary inline styling. Geometric separators are avoided in
;; the menu path: the base-14 serif has no triangle glyph, so a portable
;; ASCII ">" keeps the PDF (and PDF/X) free of missing-glyph boxes.
(def ^:private kbd-style
  {:font-family "monospace" :font-size "0.85em" :background-color "#eeeeee"
   :hyphenate "false"
   :border "0.5pt solid #cccccc" :padding "0pt 2pt"})

(def ^:private button-style
  {:font-size "0.85em" :background-color "#e8e8e8"
   :border "0.5pt solid #bbbbbb" :padding "0pt 4pt"})

(def ^:private menu-separator " > ")

(defn- heading-with-marker
  "A styled heading block that also emits an `fo:marker` carrying its plain
   text, so running heads can retrieve the current section title."
  [tag marker-class author children style]
  (let [expanded (expand-all children style)
        text     (apply str (filter string? (tree-seq vector? seq (vec expanded))))]
    (into [:fo/block (cond-> (get style tag)
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
          (cons [:fo/marker {:marker-class-name marker-class} text] expanded))))

;; --- lists ----------------------------------------------------------------

(defn- list-items [children]
  (filter #(and (vector? %) (= :li (first %))) (hiccup/flatten-children children)))

(defn- list-block [list-type author children style]
  (when (and (= list-type :ol) (some? (:start author))
             (not (integer? (:start author))))
    (throw (error/ex :smia.fo.expand/invalid-list-start
                     (str "An :ol :start must be an integer, got: "
                          (pr-str (:start author)))
                     {:start (:start author)})))
  (let [base  (cond-> (get style list-type)
                (:id author) (assoc :id (hiccup/as-id (:id author))))
        start (if (= list-type :ol) (or (:start author) 1) 1)]
    (into [:fo/list-block base]
          (map-indexed
            (fn [i item]
              (let [[_ _ item-children] (hiccup/parse-node item)
                    label (if (= list-type :ol) (str (+ start i) ".") "•")]
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
                     (:id author) (assoc :id (hiccup/as-id (:id author))))]
        (keep (fn [child]
                (when (vector? child)
                  (let [[tag _ kids] (hiccup/parse-node child)]
                    (case tag
                      :dt (into [:fo/block (get style :dt)] (expand-all kids style))
                      :dd (into [:fo/block (get style :dd)] (expand-all kids style))
                      nil))))
              (hiccup/flatten-children children))))

;; --- tables ---------------------------------------------------------------

(defn- cells-of [tr]
  (let [[_ _ children] (hiccup/parse-node tr)]
    (filter #(and (vector? %) (#{:td :th} (first %)))
            (hiccup/flatten-children children))))

(defn- rows-of [section]
  (let [[_ _ children] (hiccup/parse-node section)]
    (filter #(and (vector? %) (= :tr (first %))) (hiccup/flatten-children children))))

(defn- display-align
  "Map an HTML-style vertical alignment to FO's `display-align`. An
   unknown value is dropped (left to FOP's default)."
  [valign]
  (case (keyword valign)
    :top    "before"
    (:middle :center) "center"
    :bottom "after"
    nil))

(def ^:private cell-aligns
  "The horizontal alignments a cell accepts; FOP rejects anything else at
   render time, far from the authoring error."
  #{:left :center :right :justify})

(defn- checked-span
  "Validate a `:colspan`/`:rowspan` value: a positive integer or nil."
  [k v]
  (when (and (some? v) (not (and (integer? v) (pos? v))))
    (throw (error/ex :smia.fo.expand/invalid-cell-attr
                     (str "A cell " k " must be a positive integer, got: "
                          (pr-str v))
                     {:attr k :value v})))
  v)

(defn- cell->fo [cell style]
  (let [[tag attrs children] (hiccup/parse-node cell)
        align (:align attrs)
        _     (when (and align (not (cell-aligns (keyword align))))
                (throw (error/ex :smia.fo.expand/invalid-cell-attr
                                 (str "A cell :align must be one of "
                                      "left, center, right, justify; got: "
                                      (pr-str align))
                                 {:attr :align :value align})))
        _     (checked-span :colspan (:colspan attrs))
        _     (checked-span :rowspan (:rowspan attrs))
        da    (display-align (:valign attrs))
        block (cond-> {}
                (= tag :th) (assoc :font-weight "bold")
                align       (assoc :text-align (name align)))]
    [:fo/table-cell (cond-> (get style :table-cell)
                      (:colspan attrs) (assoc :number-columns-spanned (:colspan attrs))
                      (:rowspan attrs) (assoc :number-rows-spanned (:rowspan attrs))
                      da               (assoc :display-align da))
     (into [:fo/block (when (seq block) block)]
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
    (throw (error/ex :smia.fo.expand/invalid-cols
                     ":table :cols must be a vector of positive numbers, one weight per column."
                     {:cols cols})))
  (vec (take ncols (concat cols (repeat 1)))))

(def ^:private auto-floor
  "The narrowest an auto-fit column may get, so a short column keeps a sane
   minimum and is not crushed by a wide neighbor." 3)

(def ^:private auto-ceil
  "The widest weight an auto-fit column may reach, so one very long cell
   cannot squash the others into slivers." 40)

(defn- cell-width
  "A width proxy for a cell: the length of its longest text line. Only the
   cell's string content counts toward the column width; markup adds no
   glyphs of its own."
  [cell]
  (let [text (apply str (filter string? (tree-seq vector? seq cell)))]
    (->> (str/split text #"\n" -1) (map count) (apply max 0))))

(defn- content-weights
  "Content-fit column weights for `:cols :auto`: each column's weight is its
   widest cell (by longest text line), clamped to `[auto-floor, auto-ceil]`.
   Cells are taken by position, so the heuristic ignores spans — a spanned
   table should set explicit `:cols`. FOP proportional widths sum to the
   table width, so the table never overflows the page; the clamp only keeps
   the proportions readable, and a single unbreakable token wider than its
   column still overflows the cell (use explicit `:cols` for that)."
  [rows ncols]
  (->> rows
       (reduce (fn [acc row]
                 (reduce (fn [m [i cell]] (update m i (fnil max 0) (cell-width cell)))
                         acc (map-indexed vector (cells-of row))))
               (vec (repeat ncols 0)))
       (mapv (fn [w] (-> w (max auto-floor) (min auto-ceil))))))

(defn- table-block [author children style]
  (let [kids        (hiccup/flatten-children children)
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
        weights     (if (= :auto (:cols author))
                      (content-weights (concat header-rows body-rows) ncols)
                      (column-weights (:cols author) ncols))
        ;; FOP requires a non-empty fo:table-body. A valid header-only table
        ;; (e.g. a GFM table with no data rows) has no body rows, so render
        ;; its header as the body rather than emit an empty body FOP rejects.
        [header-rows body-rows] (if (seq body-rows)
                                  [header-rows (vec body-rows)]
                                  [nil (vec header-rows)])]
    (when (empty? body-rows)
      (throw (error/ex :smia.fo.expand/empty-table
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

(defn- figure-block [author children style]
  (into [:fo/block (cond-> (get style :figure)
                     (:id author)    (assoc :id (hiccup/as-id (:id author)))
                     (:float author) (assoc :float (name (:float author))))]
        (concat
          (expand-all children style)
          (when (hiccup/captioned? author) [(caption-block author style)]))))

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

(defn- code-keep
  "Keep-together for a code block short enough to sit on one page, so a
   short listing slides to the next page whole instead of stranding a line
   or two across a break. A longer listing drops the keep so FOP may split
   it, with the line-number gutter as the continuity cue. The threshold is
   the theme's `:listing-keep-lines`."
  [line-count style]
  (when (<= line-count (get style :listing-keep-lines 25))
    {:keep-together.within-page "always"}))

(defn- code-block [author children style]
  (let [text (hiccup/code-text children)]
    (into [:fo/block (cond-> (merge (get style :pre)
                                    (code-keep (count (str/split text #"\n" -1)) style))
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
          (code-content (:lang author) text style))))

(defn- annotation-mark
  "A small theme-styled badge carrying an annotation's ordinal `n`. The same
   mark appears at the end of a code line and beside its note in the list."
  [n style]
  [:fo/inline (get style :annotation-mark) (str n)])

(defn- code-line-block
  "One per-line `:fo/block`: an optional right-aligned line-number gutter of
   `width` digits, the highlighted `line`, and an optional trailing
   annotation `mark`, set off from the code by a gap matching the badge's
   own inner padding."
  [lang line width n gutter? mark style]
  (cond-> (into (cond-> [:fo/block {:white-space "pre" :wrap-option "wrap"}]
                  gutter? (conj [:fo/inline {:color "#999999"}
                                 (str (format (str "%" width "d") n) "  ")]))
                (code-content lang line style))
    mark (conj (assoc-in (annotation-mark mark style) [1 :space-start] "2pt"))))

(defn- code-lines-block
  "Render code as one block per line. `gutter?` prefixes each line with a
   muted right-aligned line number; `marks` (a `{line -> ordinal}` map)
   appends an annotation mark at the end of each annotated line."
  [author lines style gutter? marks]
  (let [width (count (str (count lines)))
        lang  (:lang author)]
    (into [:fo/block (cond-> (merge (get style :pre)
                                    (code-keep (count lines) style))
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
          (map-indexed
            (fn [i line]
              (let [n (inc i)]
                (code-line-block lang line width n gutter? (get marks n) style)))
            lines))))

(defn- render-code
  "Render a code body. Line numbers and/or annotation marks force per-line
   blocks; otherwise the code is one block. `by-line` (line -> {:n :note},
   or nil) supplies the annotation marks."
  [author children style by-line]
  (if (or (line-numbers? author style) (seq by-line))
    (code-lines-block author (str/split (hiccup/code-text children) #"\n" -1) style
                      (line-numbers? author style)
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
        line-count  (count (str/split (hiccup/code-text children) #"\n" -1))
        by-line     (when (seq annotations)
                      (hiccup/annotations->by-line
                        :smia.fo.expand/invalid-annotation
                        annotations
                        line-count
                        (hiccup/as-id (:id author))))
        ;; A short listing is kept whole on one page; a longer one drops the
        ;; keep-together so FOP can split it, with the gutter as the cue.
        keep-whole? (<= line-count (get style :listing-keep-lines 25))]
    (into [:fo/block (cond-> (get style :listing)
                       keep-whole? (assoc :keep-together.within-page "always")
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
          (concat
            (when-let [file (:file author)]
              [[:fo/block (get style :file-bar) file]])
            [(render-code (dissoc author :id) children style by-line)]
            (when by-line [(annotation-list-block by-line style)])
            (when (hiccup/captioned? author) [(caption-block author style)])))))

;; --- book extensions ------------------------------------------------------

(defn- sidebar-title
  "The title bar text for a sidebar/admonition: an explicit `:title`, else
   the localized label for a known `:kind`, else the capitalized kind, else
   nil. The label is localized to the book's `:language` carried on `style`."
  [{:keys [title kind]} style]
  (cond
    title title
    kind  (dictionary/localize (:language style) kind (str/capitalize (name kind)))
    :else nil))

(defn- sidebar-block
  "A bordered callout with an optional bold title bar (with optional icon)
   and a rich body. Generalizes the admonition: `:admonition` is the
   kind-titled special case, `:sidebar` adds an arbitrary `:title`. The box
   is kept on one page so its title bar cannot strand at a page foot."
  [author children style]
  (let [title (sidebar-title author style)
        icon  (:icon author)]
    (into [:fo/block (cond-> (assoc (get style :admonition)
                                    :keep-together.within-page "always")
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
          (concat
            (when title
              [[:fo/block {:font-weight "bold" :space-after "3pt"}
                (if icon (str icon " " title) title)]])
            (expand-all children style)))))

(defn- overview-block
  "A chapter-opening panel summarizing what the chapter covers: a bold label
   bar (default \"Overview\", overridable via `:title`; an explicit `:title
   nil` drops it) above a rich body. Kept on one page like the other
   callout boxes."
  [author children style]
  (let [title (get author :title
                    (dictionary/localize (:language style) :overview))]
    (into [:fo/block (cond-> (assoc (get style :overview)
                                    :keep-together.within-page "always")
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
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
                     (:id author) (assoc :id (hiccup/as-id (:id author))))]
        (concat
          (expand-all children style)
          (when-let [attr (:attribution author)]
            [[:fo/block {:font-style "normal" :text-align "end"
                         :space-before "4pt"} (str "— " attr)]]))))

(defn- example-block
  "A worked-example callout: an optional bold title bar above a rich body,
   kept on one page like the other callouts."
  [author children style]
  (into [:fo/block (cond-> (assoc (get style :example)
                                  :keep-together.within-page "always")
                     (:id author) (assoc :id (hiccup/as-id (:id author))))]
        (concat
          (when-let [title (:title author)]
            [[:fo/block {:font-weight "bold" :space-after "3pt"} title]])
          (expand-all children style))))

(defn- disclosure-block
  "A foldable disclosure. Print has no interactivity, so it renders as a
   light block with the summary as a bold lead-in above the always-visible
   body; the open/closed distinction is a site affordance only."
  [author children style]
  (let [summary (or (:summary author) (:title author)
                    (dictionary/localize (:language style) :details))]
    (into [:fo/block (cond-> (get style :details)
                       (:id author) (assoc :id (hiccup/as-id (:id author))))]
          (cons [:fo/block {:font-weight "bold" :space-after "3pt"} summary]
                (expand-all children style)))))

(defn- checked-image-src
  "Return a local image `src` after verifying it stays inside the book
   root (FOP resolves it against the book's base URI): it must be relative
   with no `..` segments. A remote source (any URI scheme) passes through —
   mirroring the site edition's resource rule, so the same book builds in
   both formats."
  [src]
  (when (and src
             (not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" src))
             (or (str/starts-with? src "/")
                 (some #{".."} (str/split src #"[/\\]"))))
    (throw (error/ex :smia.fo.expand/unsafe-image-src
                     (str "Image source " (pr-str src) " escapes the book "
                          "directory. Image paths must be relative and stay "
                          "within the book.")
                     {:src src})))
  src)

(defn- footnote
  "A footnote: the in-text noteref carries the per-chapter ordinal the
   numbering pass stamped as `:n`; without one (the pure base-14 path, or
   `:book/numbering {:footnotes false}`) it falls back to a `*`. The note
   body hangs its text under a matching label."
  [author children style]
  (let [marker (if-let [n (:n author)] (str n) "*")]
    [:fo/footnote
     [:fo/inline (get style :footnote-ref) marker]
     [:fo/footnote-body
      (into [:fo/block (get style :footnote)]
            (cons [:fo/inline (get style :footnote-ref) (str marker " ")]
                  (expand-all children style)))]]))

(defn- composed-xref
  "Build the inline content of a childless cross-reference from the
   label/title the numbering pass resolved: \"Chapter 2\" by default,
   \"Chapter 2: Title\" with `:style :full`, optionally followed by
   \", on page N\" when `:page` is set. With nothing resolved, fall back to
   a bare page-number citation (the pre-numbering behavior)."
  [author dest style]
  (let [label (:label author)
        title (:title author)
        text  (cond
                (and (= :full (:style author)) label title) (str label ": " title)
                label label
                title title)]
    (if text
      (cond-> [text]
        (:page author)
        (conj (str ", " (dictionary/localize (:language style) :on-page) " ")
              [:fo/page-number-citation {:ref-id dest}]))
      [[:fo/page-number-citation {:ref-id dest}]])))

(defn- cite [author style]
  (let [key (:key author)]
    (when-not key
      (throw (error/ex :smia.fo.expand/invalid-cite
                       ":cite requires a :key." {:attrs author})))
    (let [ref-id (or (:ref-id author) (str "ref-" (name key)))
          label  (or (:label author) (name key))]
      (if (links? style)
        [:fo/basic-link {:internal-destination ref-id :color "#1a0dab"} label]
        [:fo/inline label]))))

(defn- index-mark [author]
  ;; A zero-width anchor the index page-cites; invisible in the flow.
  [:fo/inline (cond-> {} (:id author) (assoc :id (hiccup/as-id (:id author))))])

(defn- xref [author children style]
  (let [dest (hiccup/as-id (:to author))]
    (when-not dest
      (throw (error/ex :smia.fo.expand/invalid-xref
                       ":xref requires a :to target id."
                       {:attrs author})))
    (into (if (links? style)
            [:fo/basic-link {:internal-destination dest :color "#1a0dab"}]
            [:fo/inline])
          (if (seq (hiccup/flatten-children children))
            (expand-all children style)
            (composed-xref author dest style)))))

;; --- the expander table ---------------------------------------------------

(def expanders
  "Tag -> `(fn [attrs children style] -> fo-hiccup)`. A plain map so the
   book layer can introspect or extend it."
  (let [head (fn [tag] (fn [a c s] (styled-block tag a c s {})))]
    {:p          (fn [a c s] (styled-block :p a c s {}))
     :p-first    (fn [a c s] (styled-block :p-first a c s {}))
     :h1         (head :h1)
     :h2         (fn [a c s] (heading-with-marker :h2 "section-title" a c s))
     :h3         (head :h3)
     :h4         (head :h4)
     :h5         (head :h5)
     :h6         (head :h6)
     :pre        (fn [a c s] (if (or (:file a) (hiccup/captioned? a) (:annotations a))
                               (listing-block a c s)
                               (render-code a c s nil)))
     :blockquote (fn [a c s] (styled-block :blockquote a c s {}))
     :li         (fn [a c s] (styled-block :li a c s {}))
     :hr         (fn [_ _ s] [:fo/block (get s :hr)])
     :strong     (fn [_ c s] (styled-inline {:font-weight "bold"} c s))
     :em         (fn [_ c s] (styled-inline {:font-style "italic"} c s))
     :code       (fn [_ c s] (styled-code-inline (get s :code) c s))
     :span       (fn [_ c s] (into [:fo/inline] (expand-all c s)))
     :kbd        (fn [_ c s] (styled-code-inline kbd-style c s))
     :menu       (fn [_ c s] (into [:fo/inline {}]
                                   (interpose menu-separator (expand-all c s))))
     :button     (fn [_ c s] (styled-inline button-style c s))
     :mark       (fn [_ c s] (styled-inline {:background-color "#fff3b0"} c s))
     :sub        (fn [_ c s] (styled-inline {:baseline-shift "sub"
                                             :font-size "0.75em"} c s))
     :sup        (fn [_ c s] (styled-inline {:baseline-shift "super"
                                             :font-size "0.75em"} c s))
     :br         (fn [_ _ _] [:fo/block])
     :a          (fn [a c s] (if (links? s)
                               (into [:fo/basic-link
                                      {:external-destination (str "url('" (:href a) "')")
                                       :color "#1a0dab" :text-decoration "underline"}]
                                     (expand-all c s))
                               (styled-inline {} c s)))
     :img        (fn [a _ _] [:fo/external-graphic
                              (cond-> {:src (str "url('" (checked-image-src (:src a)) "')")}
                                (:width a)  (assoc :content-width (:width a))
                                (:height a) (assoc :content-height (:height a)))])
     :ul         (fn [a c s] (list-block :ul a c s))
     :ol         (fn [a c s] (list-block :ol a c s))
     :dl         dl-block
     :dt         (fn [a c s] (styled-block :dt a c s {}))
     :dd         (fn [a c s] (styled-block :dd a c s {}))
     :figure     figure-block
     :table      (fn [a c s]
                   (let [tbl (table-block a c s)]
                     (if (hiccup/captioned? a)
                       (into [:fo/block (cond-> {:space-before "6pt" :space-after "8pt"}
                                          (:id a) (assoc :id (hiccup/as-id (:id a))))]
                             [(caption-block a s) tbl])
                       tbl)))
     :thead      (fn [a c s] (styled-block :p a c s {}))
     :tbody      (fn [a c s] (styled-block :p a c s {}))
     :tr         (fn [a c s] (styled-block :p a c s {}))
     :td         (fn [a c s] (styled-block :p a c s {}))
     :th         (fn [a c s] (styled-block :p a c s {}))
     :admonition (fn [a c s] (sidebar-block (update a :kind #(or % :note)) c s))
     :sidebar    sidebar-block
     :overview   overview-block
     :example    example-block
     :details    disclosure-block
     :open       disclosure-block
     :epigraph   epigraph-block
     :footnote   footnote
     :xref       xref
     :cite       (fn [a _ s] (cite a s))
     :index      (fn [a _ _] (index-mark a))
     :math       (fn [a _ _]
                   (let [obj [:fo/instream-foreign-object
                              {:alignment-adjust "middle"}
                              (svg-resolve/rendered-svg :math a)]]
                     (if (:display a)
                       [:fo/block (cond-> {:text-align "center"
                                           :space-before "6pt"
                                           :space-after "6pt"}
                                    (:id a) (assoc :id (hiccup/as-id (:id a))))
                        obj]
                       obj)))
     :diagram    (fn [a _ s]
                   ;; A client-rendered (mermaid) diagram has no build-time
                   ;; SVG; print shows its source as a code block instead.
                   (if (= :mermaid (:engine a))
                     [:fo/block (cond-> (get s :pre)
                                  (:id a) (assoc :id (hiccup/as-id (:id a))))
                      (:source a)]
                     [:fo/block (cond-> {:text-align "center"
                                         :space-before "6pt"
                                         :space-after "6pt"}
                                  (:id a) (assoc :id (hiccup/as-id (:id a))))
                      [:fo/instream-foreign-object {}
                       (svg-resolve/rendered-svg :diagram a)]]))
     :page-break (fn [_ _ _] [:fo/block {:break-before "page"}])
     :keep-together
     (fn [a c s]
       (into [:fo/block (cond-> {:keep-together.within-page "always"}
                          (:id a) (assoc :id (hiccup/as-id (:id a))))]
             (expand-all c s)))}))
