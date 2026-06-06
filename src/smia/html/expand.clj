(ns smia.html.expand
  "Pure expansion of the author Hiccup vocabulary into HTML-Hiccup.

   The sibling of `smia.fo.expand`: the same three concentric layers,
   rendered for reflowable output.

   1. Raw HTML floor: a `:html/*` tag passes through with its namespace
      stripped (children still expanded). A live `:fo/*` tag here is a
      structured error — the PDF hatch has no HTML meaning (and vice
      versa in `fo.expand`).
   2. HTML-flavored sugar passes through as clean HTML, dropping the
      book-internal attrs the numbering pass stamps (`:number`,
      `:label`, `:cols`, …).
   3. Book extensions expand to classed HTML idioms; styling lives in
      CSS (see `smia.theme.css`), never inline.

   The `ctx` carries `:resolve` — `(fn [id] href)`, built by the
   assembler from `smia.html.links` — and `:highlight?`. Footnotes
   must be numbered (given `:n`) by the assembler before expansion: HTML
   has no page foot, so the assembler collects the note bodies into an
   end-of-chapter block and leaves a numbered noteref behind. An unknown
   bare tag is a hard, structured error. No IO."
  (:require
   [smia.book.dictionary :as dictionary]
   [smia.error :as error]
   [smia.hiccup :as hiccup]
   [smia.highlight.registry :as highlight]
   [smia.svg.resolve :as svg-resolve]
   [clojure.string :as str]))

(declare expand-all expanders)

(def default-ctx
  "Fragment-friendly defaults: same-page fragment links, no highlighting."
  {:resolve (fn [id] (str "#" id))
   :highlight? false})

(defn- asset-src
  "Resolve an image `src` against the page's `:asset-base` (the relative
   prefix from the page back to the site root). A remote (`https:`,
   `data:`, …) or absolute (`/…`) source is used as-is; a local one is
   prefixed so it resolves from a nested page directory. With no base (the
   flat default) the source is unchanged."
  [ctx src]
  (let [base (:asset-base ctx)]
    (if (or (str/blank? base)
            (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" src)
            (str/starts-with? src "/"))
      src
      (str base src))))

;; --- the public transform ---------------------------------------------------

(defn expand
  "Expand author Hiccup `node` into HTML-Hiccup using `ctx` (defaults to
   `default-ctx`). `:html/*` passes through (namespace stripped, children
   still expanded); known sugar/book tags expand; a live `:fo/*` or an
   unknown bare tag throws a structured error."
  ([node] (expand node default-ctx))
  ([node ctx]
   (let [ctx (merge default-ctx ctx)]
     (cond
       (nil? node)    nil
       (string? node) node
       (number? node) node
       (vector? node)
       (let [[tag attrs children] (hiccup/parse-node node)]
         (cond
           (and (keyword? tag) (= "html" (namespace tag)))
           (let [expanded (expand-all children ctx)]
             (into [(keyword (name tag)) (or attrs {})] expanded))

           (and (keyword? tag) (= "fo" (namespace tag)))
           (throw (error/ex :smia.html.expand/fo-tag-in-html
                            (str "Raw FO element " (pr-str tag) " has no HTML "
                                 "rendering. Use portable sugar, or the "
                                 ":html/* hatch for this edition.")
                            {:tag tag}))

           (contains? expanders tag)
           ((get expanders tag) attrs children ctx)

           :else
           (throw (error/ex :smia.html.expand/unknown-tag
                            (str "Unknown element tag: " (pr-str tag)
                                 ". Use a known sugar/book tag or a raw "
                                 ":html/* element.")
                            {:tag tag}))))
       (seq? node) (vec (expand-all node ctx))
       :else
       (throw (error/ex :smia.html.expand/invalid-node
                        (str "Cannot expand node of type "
                             (some-> node class .getName))
                        {:node node}))))))

;; --- node helpers -----------------------------------------------------------

(defn- expand-all [children ctx]
  (->> (hiccup/flatten-children children)
       (map #(expand % ctx))
       (remove nil?)
       vec))

(defn- id-attrs
  "The (possibly empty) HTML attrs carrying only the author's anchor id —
   book-internal attrs like `:number`/`:label` never leak into HTML."
  [author]
  (cond-> {} (:id author) (assoc :id (hiccup/as-id (:id author)))))

(defn- passthrough
  "An expander for a tag that renders as itself: anchor id, expanded
   children, nothing else."
  [tag]
  (fn [a c ctx] (into [tag (id-attrs a)] (expand-all c ctx))))

(def ^:private align-values
  "The CSS `text-align` values a cell `:align` may take. Anything else is
   dropped rather than spliced into the inline style verbatim — an alignment
   is a constrained keyword, not a styling hatch."
  #{"left" "right" "center" "justify"})

(def ^:private valign-values
  "The CSS `vertical-align` values a cell `:valign` may take."
  #{"top" "middle" "center" "bottom" "baseline"})

(defn- cell-style
  "An inline `style` for a cell's `:align`/`:valign`, or nil. The
   declarations are emitted in a fixed order so the markup is
   deterministic. Alignment is per-cell content, not theme styling, so it
   rides an inline style rather than a CSS class. Only the recognized
   alignment keywords pass through — an unknown value is dropped, so the
   inline style can never carry arbitrary CSS."
  [a]
  (let [align  (when-let [v (:align a)] (align-values (name v)))
        valign (when-let [v (:valign a)] (valign-values (name v)))
        decls  (cond-> []
                 align  (conj (str "text-align: " align))
                 valign (conj (str "vertical-align: " valign)))]
    (when (seq decls) (str/join "; " decls))))

(defn- cell
  "A table cell (`:td`/`:th`) that keeps its anchor id, any
   `:colspan`/`:rowspan` spanning attributes, and any per-cell
   `:align`/`:valign` alignment."
  [tag]
  (fn [a c ctx]
    (let [style (cell-style a)]
      (into [tag (cond-> (id-attrs a)
                   (:colspan a) (assoc :colspan (:colspan a))
                   (:rowspan a) (assoc :rowspan (:rowspan a))
                   style        (assoc :style style))]
            (expand-all c ctx)))))

;; --- captions, figures, tables ----------------------------------------------

(defn- caption-node
  "A caption element (`:figcaption` or table `:caption`): the bold
   \"Figure 3.\" label from the numbering pass, then the caption text."
  [tag author]
  (into [tag {}]
        (concat
          (when-let [label (:label author)]
            [[:span {:class "caption-label"} (str label ". ")]])
          (when-let [caption (:caption author)] [caption]))))

(defn- figure-block [author children ctx]
  (into [:figure (id-attrs author)]
        (concat
          (expand-all children ctx)
          (when (hiccup/captioned? author) [(caption-node :figcaption author)]))))

(defn- table-block [author children ctx]
  (into [:table (id-attrs author)]
        (concat
          (when (hiccup/captioned? author) [(caption-node :caption author)])
          (expand-all children ctx))))

;; --- code rendering (syntax highlighting, line numbers, annotations) ---------

(defn- code-runs
  "Inline HTML for a blob of code: `tok-*` classed spans when the language
   is supported and highlighting is on, otherwise the text verbatim."
  [lang text ctx]
  (if-let [toks (and (:highlight? ctx) lang (highlight/tokenize lang text))]
    (mapv (fn [{:keys [kind text]}]
            (if (= :text kind)
              text
              [:span {:class (str "tok-" (name kind))} text]))
          toks)
    [text]))

(defn- code-attrs [lang]
  (cond-> {} lang (assoc :class (str "language-" (name lang)))))

(defn- annotation-mark
  "The badge carrying an annotation's ordinal `n`, at a line's end and
   beside its note in the list."
  [n]
  [:sup {:class "annotation-mark"} (str n)])

(defn- code-lines
  "Per-line inline content: an optional right-aligned line-number gutter,
   the highlighted line, an optional trailing annotation mark, and the
   newline the `<pre>` preserves."
  [lang lines ctx gutter? marks]
  (let [width (count (str (count lines)))]
    (->> lines
         (map-indexed
           (fn [i line]
             (let [n (inc i)]
               (concat
                 (when gutter?
                   [[:span {:class "line-no"}
                     (str (format (str "%" width "d") n) "  ")]])
                 (code-runs lang line ctx)
                 (when-let [mark (get marks n)] [(annotation-mark mark)])
                 (when (< n (count lines)) ["\n"])))))
         (apply concat)
         vec)))

(defn- code-block
  "`<pre><code>` for a code body. Line numbers and/or annotation marks
   force per-line rendering; otherwise the code is one run."
  [author children ctx by-line]
  (let [lang (:lang author)
        text (hiccup/code-text children)]
    [:pre (id-attrs author)
     (into [:code (code-attrs lang)]
           (if (or (:line-numbers author) (seq by-line))
             (code-lines lang (str/split text #"\n" -1) ctx
                         (boolean (:line-numbers author))
                         (reduce-kv (fn [m line {:keys [n]}] (assoc m line n))
                                    {} (or by-line {})))
             (code-runs lang text ctx)))]))

(defn- annotation-list
  "The ordered notes beneath an annotated listing, each led by its mark."
  [by-line ctx]
  (into [:ol {:class "annotations"}]
        (map (fn [{:keys [n note]}]
               (into [:li {} (annotation-mark n) " "]
                     (expand-all [note] ctx)))
             (sort-by :n (vals by-line)))))

(defn- listing-block
  "A code listing: a `<figure class=\"listing\">` wrapping an optional
   filename bar, the code, an optional annotation list, and an optional
   numbered caption."
  [author children ctx]
  (let [annotations (:annotations author)
        by-line     (when (seq annotations)
                      (hiccup/annotations->by-line
                        :smia.html.expand/invalid-annotation
                        annotations
                        (count (str/split (hiccup/code-text children) #"\n" -1))
                        (hiccup/as-id (:id author))))]
    (into [:figure (assoc (id-attrs author) :class "listing")]
          (concat
            (when-let [file (:file author)]
              [[:div {:class "file-bar"} file]])
            [(code-block (dissoc author :id) children ctx by-line)]
            (when by-line [(annotation-list by-line ctx)])
            (when (hiccup/captioned? author) [(caption-node :figcaption author)])))))

(defn- pre-block
  "A code block, optionally foldable on the site. `:fold` wraps the listing
   in a native `<details>` with a `<summary>` (the `:fold` string, the
   caption, or a default); the browser folds it with no JavaScript, and PDF
   ignores `:fold` and always shows the full listing."
  [a c ctx]
  (let [rendered (if (or (:file a) (hiccup/captioned? a) (:annotations a))
                   (listing-block a c ctx)
                   (code-block a c ctx nil))]
    (if-let [fold (:fold a)]
      [:details {:class "fold"}
       [:summary {} (if (string? fold) fold (or (:caption a) "Show code"))]
       rendered]
      rendered)))

;; --- book extensions ----------------------------------------------------------

(defn- callout-title
  "The title bar text for a sidebar/admonition: an explicit `:title`, else
   the localized label for a known `:kind`, else the capitalized kind, else
   nil. The label is localized to the book's `:language` carried on `ctx`."
  [{:keys [title kind]} ctx]
  (cond
    title title
    kind  (dictionary/localize (:language ctx) kind (str/capitalize (name kind)))
    :else nil))

(defn- callout
  "A classed `<aside>` callout with an optional title bar: the shared
   shape of admonitions and sidebars."
  [author children ctx attrs title-class]
  (let [title (callout-title author ctx)
        icon  (:icon author)]
    (into [:aside (merge (id-attrs author) attrs)]
          (concat
            (when title
              [[:div {:class title-class}
                (if icon (str icon " " title) title)]])
            (expand-all children ctx)))))

(defn- admonition-block [author children ctx]
  (let [author (update author :kind #(or % :note))]
    (callout author children ctx
             {:class (str "admonition " (name (:kind author))) :role "note"}
             "admonition-title")))

(defn- sidebar-block [author children ctx]
  (callout author children ctx {:class "sidebar"} "sidebar-title"))

(defn- overview-block [author children ctx]
  (let [title (get author :title
                    (dictionary/localize (:language ctx) :overview))]
    (into [:aside (assoc (id-attrs author) :class "overview")]
          (concat
            (when title [[:div {:class "overview-title"} title]])
            (expand-all children ctx)))))

(defn- example-block [author children ctx]
  (into [:div (assoc (id-attrs author) :class "example")]
        (concat
          (when-let [title (:title author)] [[:div {:class "example-title"} title]])
          (expand-all children ctx))))

(defn- disclosure
  "A foldable `<details>` with a `<summary>` label (from `:summary`, falling
   back to `:title`). `open?` renders it expanded. With JavaScript disabled
   the browser's native disclosure still works."
  [author children ctx open?]
  (let [summary (or (:summary author) (:title author)
                    (dictionary/localize (:language ctx) :details))]
    (into [:details (cond-> (id-attrs author) open? (assoc :open "open"))]
          (cons [:summary {} summary] (expand-all children ctx)))))

(defn- menu-path
  "A menu path: the segments separated by a small caret in a classed span so
   the stylesheet can restyle or hide the separator."
  [children ctx]
  (into [:span {:class "menu"}]
        (interpose [:span {:class "menu-sep"} " ▸ "] (expand-all children ctx))))

(defn- epigraph-block [author children ctx]
  (into [:blockquote (assoc (id-attrs author) :class "epigraph")]
        (concat
          (expand-all children ctx)
          (when-let [attr (:attribution author)]
            [[:footer {:class "attribution"} (str "— " attr)]]))))

(defn- footnote-ref
  "The in-text noteref for a footnote the assembler numbered. The note
   body itself is collected into the chapter's footnote block (see
   `html.assemble`), linked by the page-local `fn-N`/`fnref-N` pair."
  [author]
  (let [n (:n author)]
    (when-not n
      (throw (error/ex :smia.html.expand/unnumbered-footnote
                       (str "Footnote reached HTML expansion without a number. "
                            "The assembler numbers and collects footnotes "
                            "before expanding a chapter body.")
                       {:attrs author})))
    [:a {:class "noteref" :role "doc-noteref"
         :id (str "fnref-" n) :href (str "#fn-" n)}
     [:sup {} (str n)]]))

(defn- composed-xref
  "The text of a childless cross-reference from the label/title the
   numbering pass resolved: \"Chapter 2\" by default, \"Chapter 2: Title\"
   with `:style :full`. HTML has no page numbers, so there is no page
   citation fallback — an unlabeled target falls back to its title or id."
  [author dest]
  (let [label (:label author)
        title (:title author)]
    (cond
      (and (= :full (:style author)) label title) (str label ": " title)
      label label
      title title
      :else dest)))

(defn- xref [author children ctx]
  (let [dest (hiccup/as-id (:to author))]
    (when-not dest
      (throw (error/ex :smia.html.expand/invalid-xref
                       ":xref requires a :to target id."
                       {:attrs author})))
    (into [:a {:class "xref" :href ((:resolve ctx) dest)}]
          (if (seq (hiccup/flatten-children children))
            (expand-all children ctx)
            [(composed-xref author dest)]))))

(defn- cite [author ctx]
  (let [key (:key author)]
    (when-not key
      (throw (error/ex :smia.html.expand/invalid-cite
                       ":cite requires a :key." {:attrs author})))
    (let [ref-id (or (:ref-id author) (str "ref-" (name key)))
          label  (or (:label author) (name key))]
      [:a {:class "cite" :href ((:resolve ctx) ref-id)} label])))

(defn- index-mark [author]
  ;; A zero-width anchor the index links to; invisible in the flow.
  [:span (id-attrs author)])

;; --- the expander table -------------------------------------------------------

(def expanders
  "Tag -> `(fn [attrs children ctx] -> html-hiccup)`. A plain map, the
   exact key set of `fo.expand/expanders` (pinned by the vocabulary
   parity test)."
  {:p          (passthrough :p)
   :h1         (passthrough :h1)
   :h2         (passthrough :h2)
   :h3         (passthrough :h3)
   :h4         (passthrough :h4)
   :h5         (passthrough :h5)
   :h6         (passthrough :h6)
   :ul         (passthrough :ul)
   :ol         (fn [a c ctx]
                 (into [:ol (cond-> (id-attrs a)
                              (:start a) (assoc :start (:start a)))]
                       (expand-all c ctx)))
   :li         (passthrough :li)
   :dl         (passthrough :dl)
   :dt         (passthrough :dt)
   :dd         (passthrough :dd)
   :strong     (passthrough :strong)
   :em         (passthrough :em)
   :code       (passthrough :code)
   :span       (passthrough :span)
   :kbd        (passthrough :kbd)
   :mark       (passthrough :mark)
   :sub        (passthrough :sub)
   :sup        (passthrough :sup)
   :menu       (fn [_ c ctx] (menu-path c ctx))
   :button     (fn [_ c ctx] (into [:span {:class "button"}] (expand-all c ctx)))
   :blockquote (passthrough :blockquote)
   :thead      (passthrough :thead)
   :tbody      (passthrough :tbody)
   :tr         (passthrough :tr)
   :td         (cell :td)
   :th         (cell :th)
   :a          (fn [a c ctx] (into [:a {:href (:href a)}] (expand-all c ctx)))
   :br         (fn [_ _ _] [:br {}])
   :hr         (fn [_ _ _] [:hr {}])
   :img        (fn [a _ ctx]
                 (when-not (:alt a)
                   (throw (error/ex :smia.html.expand/missing-alt-text
                                    (str "Image " (pr-str (:src a)) " has no "
                                         ":alt text. Every image needs alt "
                                         "text (\"\" for a decorative one).")
                                    {:src (:src a)})))
                 [:img (cond-> {:src (asset-src ctx (:src a)) :alt (:alt a)}
                         (:width a)  (assoc :width (:width a))
                         (:height a) (assoc :height (:height a)))])
   :pre        pre-block
   :figure     figure-block
   :table      table-block
   :admonition admonition-block
   :sidebar    sidebar-block
   :overview   overview-block
   :example    example-block
   :details    (fn [a c ctx] (disclosure a c ctx false))
   :open       (fn [a c ctx] (disclosure a c ctx true))
   :epigraph   epigraph-block
   :footnote   (fn [a _ _] (footnote-ref a))
   :xref       xref
   :cite       (fn [a _ ctx] (cite a ctx))
   :index      (fn [a _ _] (index-mark a))
   :math       (fn [a _ _]
                 (let [svg (update (svg-resolve/rendered-svg :math a) 1
                                   assoc
                                   :role "img"
                                   :aria-label (:notation a)
                                   :class "math")]
                   (if (:display a)
                     [:div (assoc (id-attrs a) :class "math-display") svg]
                     svg)))
   :diagram    (fn [a _ ctx]
                 ;; A mermaid diagram is client-rendered. On a site with the
                 ;; island on, emit `<pre class="mermaid">` (mermaid.js
                 ;; transforms it; with no JavaScript the source shows). Where
                 ;; the island is off (EPUB, or a site that did not opt in), it
                 ;; falls back to a plain source listing.
                 (if (= :mermaid (:engine a))
                   (if (:mermaid ctx)
                     [:pre (assoc (id-attrs a) :class "mermaid") (:source a)]
                     [:pre (id-attrs a) [:code {} (:source a)]])
                   (do
                     (when-not (:alt a)
                       (throw (error/ex :smia.html.expand/missing-alt-text
                                        (str "A diagram has no :alt text. Give the "
                                             "fence an :alt (or a :caption, which "
                                             "doubles as one).")
                                        {:source (:source a)})))
                     [:div (assoc (id-attrs a) :class "diagram")
                      (update (svg-resolve/rendered-svg :diagram a) 1
                              assoc
                              :role "img"
                              :aria-label (:alt a)
                              :class "diagram")])))
   :page-break (fn [_ _ _] [:div {:class "page-break"}])
   :keep-together
   (fn [a c ctx]
     (into [:div (assoc (id-attrs a) :class "keep-together")]
           (expand-all c ctx)))})
