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
   [smia.error :as error]
   [smia.fo.hiccup :as hiccup]
   [smia.highlight.registry :as highlight]
   [clojure.string :as str]))

(declare expand-all expanders)

(def default-ctx
  "Fragment-friendly defaults: same-page fragment links, no highlighting."
  {:resolve (fn [id] (str "#" id))
   :highlight? false})

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

(defn- flatten-children
  "Flatten one level of seqs (e.g. produced by `for`) among children."
  [children]
  (mapcat (fn [c] (if (seq? c) c [c])) children))

(defn- expand-all [children ctx]
  (->> (flatten-children children)
       (map #(expand % ctx))
       (remove nil?)
       vec))

(defn- as-id [v]
  (when (some? v) (if (keyword? v) (name v) (str v))))

(defn- id-attrs
  "The (possibly empty) HTML attrs carrying only the author's anchor id —
   book-internal attrs like `:number`/`:label` never leak into HTML."
  [author]
  (cond-> {} (:id author) (assoc :id (as-id (:id author)))))

(defn- passthrough
  "An expander for a tag that renders as itself: anchor id, expanded
   children, nothing else."
  [tag]
  (fn [a c ctx] (into [tag (id-attrs a)] (expand-all c ctx))))

;; --- captions, figures, tables ----------------------------------------------

(defn- captioned?
  "True when a node carries a caption or a numbering-pass label."
  [author]
  (or (:caption author) (:label author)))

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
          (when (captioned? author) [(caption-node :figcaption author)]))))

(defn- table-block [author children ctx]
  (into [:table (id-attrs author)]
        (concat
          (when (captioned? author) [(caption-node :caption author)])
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

(defn- code-text [children] (apply str (filter string? children)))

(defn- code-attrs [lang]
  (cond-> {} lang (assoc :class (str "language-" (name lang)))))

(defn- annotation-mark
  "The badge carrying an annotation's ordinal `n`, at a line's end and
   beside its note in the list."
  [n]
  [:sup {:class "annotation-mark"} (str n)])

(defn- annotations->by-line
  "Validate a listing's `:annotations` and index them as `{line -> {:n
   ordinal :note note}}` (mirrors `fo.expand`, with this format's error
   type)."
  [annotations line-count id]
  (reduce
    (fn [acc [i {:keys [line note]}]]
      (when-not (and (integer? line) (<= 1 line line-count))
        (throw (error/ex :smia.html.expand/invalid-annotation
                         (str "Annotation " (inc i) " references line "
                              (pr-str line) ", outside the listing's "
                              "1.." line-count " lines.")
                         {:listing id :line line :lines line-count})))
      (when (contains? acc line)
        (throw (error/ex :smia.html.expand/invalid-annotation
                         (str "Line " line " carries more than one annotation.")
                         {:listing id :line line})))
      (assoc acc line {:n (inc i) :note note}))
    {}
    (map-indexed vector annotations)))

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
        text (code-text children)]
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
                      (annotations->by-line
                        annotations
                        (count (str/split (code-text children) #"\n" -1))
                        (as-id (:id author))))]
    (into [:figure (assoc (id-attrs author) :class "listing")]
          (concat
            (when-let [file (:file author)]
              [[:div {:class "file-bar"} file]])
            [(code-block (dissoc author :id) children ctx by-line)]
            (when by-line [(annotation-list by-line ctx)])
            (when (captioned? author) [(caption-node :figcaption author)])))))

(defn- pre-block [a c ctx]
  (if (or (:file a) (captioned? a) (:annotations a))
    (listing-block a c ctx)
    (code-block a c ctx nil)))

;; --- book extensions ----------------------------------------------------------

(def ^:private admonition-labels
  {:note "Note" :tip "Tip" :warning "Warning"
   :important "Important" :caution "Caution"})

(defn- callout-title
  "The title bar text for a sidebar/admonition: an explicit `:title`, else
   the label for a known `:kind`, else the capitalized kind, else nil."
  [{:keys [title kind]}]
  (cond
    title title
    kind  (get admonition-labels kind (str/capitalize (name kind)))
    :else nil))

(defn- callout
  "A classed `<aside>` callout with an optional title bar: the shared
   shape of admonitions and sidebars."
  [author children ctx attrs title-class]
  (let [title (callout-title author)
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
  (let [title (get author :title "Overview")]
    (into [:aside (assoc (id-attrs author) :class "overview")]
          (concat
            (when title [[:div {:class "overview-title"} title]])
            (expand-all children ctx)))))

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
  (let [dest (as-id (:to author))]
    (when-not dest
      (throw (error/ex :smia.html.expand/invalid-xref
                       ":xref requires a :to target id."
                       {:attrs author})))
    (into [:a {:class "xref" :href ((:resolve ctx) dest)}]
          (if (seq (flatten-children children))
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
   :ol         (passthrough :ol)
   :li         (passthrough :li)
   :dl         (passthrough :dl)
   :dt         (passthrough :dt)
   :dd         (passthrough :dd)
   :strong     (passthrough :strong)
   :em         (passthrough :em)
   :code       (passthrough :code)
   :span       (passthrough :span)
   :blockquote (passthrough :blockquote)
   :thead      (passthrough :thead)
   :tbody      (passthrough :tbody)
   :tr         (passthrough :tr)
   :td         (passthrough :td)
   :th         (passthrough :th)
   :a          (fn [a c ctx] (into [:a {:href (:href a)}] (expand-all c ctx)))
   :br         (fn [_ _ _] [:br {}])
   :hr         (fn [_ _ _] [:hr {}])
   :img        (fn [a _ _]
                 (when-not (:alt a)
                   (throw (error/ex :smia.html.expand/missing-alt-text
                                    (str "Image " (pr-str (:src a)) " has no "
                                         ":alt text. Every image needs alt "
                                         "text (\"\" for a decorative one).")
                                    {:src (:src a)})))
                 [:img (cond-> {:src (:src a) :alt (:alt a)}
                         (:width a)  (assoc :width (:width a))
                         (:height a) (assoc :height (:height a)))])
   :pre        pre-block
   :figure     figure-block
   :table      table-block
   :admonition admonition-block
   :sidebar    sidebar-block
   :overview   overview-block
   :epigraph   epigraph-block
   :footnote   (fn [a _ _] (footnote-ref a))
   :xref       xref
   :cite       (fn [a _ ctx] (cite a ctx))
   :index      (fn [a _ _] (index-mark a))
   :page-break (fn [_ _ _] [:div {:class "page-break"}])
   :keep-together
   (fn [a c ctx]
     (into [:div (assoc (id-attrs a) :class "keep-together")]
           (expand-all c ctx)))})
