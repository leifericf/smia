(ns clj-book.md.compile
  "Pure core: compile a normalized Markdown AST (clj-book.md.parse data)
   into author (sugar) Hiccup — the same vocabulary a `.clj` chapter
   yields.

   A `compilers` map dispatches on a node's `:type`, mirroring
   clj-book.fo.expand/expanders so the front-end is introspectable and
   extensible as data rather than a `cond`. The first level-1 heading
   becomes the chapter `:title` and is dropped from the body (the book
   layer renders the chapter heading).

   Beyond base CommonMark the compiler understands the curated extension
   grammar: `:::admonition {edn}` directives, GFM tables (with an optional
   bare-EDN-map line directly above supplying `:cols`), footnotes, links to
   `#id` as `:xref`, fenced code with an EDN attribute map (`:lang`,
   `:test`, `:include`), and the `{=hiccup}` / `{=fo}` raw escapes (block
   via a fence info string, inline via a code span followed by the marker).

   No IO. `:include` is resolved later in the shell (clj-book.book.load);
   commonmark-java is never touched here."
  (:refer-clojure :exclude [compile])
  (:require
   [clj-book.error :as error]
   [clj-book.md.schema :as schema]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(declare compile-node compile-inline-seq compile-block-seq)

(def ^:dynamic *footnote-defs*
  "Label -> footnote-definition node, bound during a `compile` call so an
   inline `[^label]` reference can inline its definition's content."
  {})

;; --- helpers --------------------------------------------------------------

(defn- err [type message ctx node]
  (throw (error/ex type message (cond-> ctx (:pos node) (assoc :pos (:pos node))))))

(defn- read-edn-1
  "Read exactly one EDN form from `s`, or throw `error-type` with `node`'s
   position."
  [s error-type message node]
  (try
    (edn/read-string s)
    (catch Exception e
      (err error-type (str message ": " (.getMessage e)) {:source s} node))))

(defn- inline-text
  "Concatenate the plain text of an inline subtree (headings, image alt,
   the bare-EDN `:cols` line)."
  [node]
  (case (:type node)
    (:text :code)                       (:literal node)
    (:soft-line-break :hard-line-break) " "
    (apply str (map inline-text (:children node)))))

;; --- heading attributes ----------------------------------------------------

(defn- split-trailing-edn-map
  "If string `s` ends with a bare EDN map (`… {:id :x}`), return `[text-before
   attrs]`; otherwise nil. Additive to CommonMark: a heading with no trailing
   map is untouched."
  [s]
  (when (string? s)
    (let [t (str/trimr s)]
      (when-let [open (and (str/ends-with? t "}") (str/index-of t "{"))]
        (let [parsed (try (edn/read-string (subs t open)) (catch Exception _ nil))]
          (when (map? parsed)
            [(str/trimr (subs t 0 open)) parsed]))))))

(defn- clean-heading-text
  "Strip a trailing EDN attribute map from heading text (used for the
   chapter `:title`)."
  [s]
  (if-let [[text _] (split-trailing-edn-map s)] text s))

(defn- extract-heading-attrs
  "Pull a trailing EDN attribute map off a heading's compiled inline
   children, returning `[attrs children']` (attrs nil when none)."
  [kids]
  (let [last-child (last kids)]
    (if-let [[text attrs] (and (string? last-child) (split-trailing-edn-map last-child))]
      [attrs (cond-> (vec (butlast kids)) (seq text) (conj text))]
      [nil kids])))

;; --- inline raw escapes ----------------------------------------------------

(def ^:private inline-escape-re #"^\{=(hiccup|fo)\}")

(defn- fold-inline-escapes
  "Fold an inline `[:code payload]` immediately followed by a `{=hiccup}` /
   `{=fo}` marker into spliced raw content. The code span's literal is read
   as EDN; `{=hiccup}` re-enters expansion, `{=fo}` is verbatim FO. Any text
   after the marker is preserved."
  [items]
  (loop [items (seq items), acc []]
    (if (nil? items)
      acc
      (let [a (first items)
            b (second items)]
        (if (and (vector? a) (= :code (first a))
                 (string? b) (re-find inline-escape-re b))
          (let [payload (second a)
                form    (try (edn/read-string payload)
                             (catch Exception e
                               (throw (error/ex :clj-book.md.compile/invalid-raw-escape
                                                (str "Inline raw escape is not readable EDN: "
                                                     (.getMessage e))
                                                {:source payload}))))
                rest-tx (str/replace-first b inline-escape-re "")
                more    (nnext items)]
            (recur (if (seq rest-tx) (cons rest-tx more) more)
                   (conj acc form)))
          (recur (next items) (conj acc a)))))))

;; --- fenced code blocks ----------------------------------------------------

(defn- parse-fence-info
  "Parse a fenced-code info string into an attribute map. `\"clojure\"` ->
   `{:lang :clojure}`; `\"clojure {:test true}\"` -> `{:lang :clojure :test
   true}`; `\"{=hiccup}\"` / `\"{=fo}\"` -> `{:raw :hiccup|:fo}`; empty ->
   `{}`."
  [info node]
  (let [info (str/trim (or info ""))]
    (cond
      (str/blank? info) {}
      (str/starts-with? info "{=")
      (if-let [[_ kind] (re-matches #"\{=([A-Za-z0-9_-]+)\}" info)]
        {:raw (keyword kind)}
        (err :clj-book.md.compile/invalid-fence-info
             (str "Unrecognized raw-escape fence info: " info) {:info info} node))
      :else
      (let [brace (str/index-of info "{")
            lang  (str/trim (if brace (subs info 0 brace) info))
            attrs (when brace
                    (let [m (read-edn-1 (subs info brace)
                                        :clj-book.md.compile/invalid-fence-info
                                        "Fenced-code attribute map is not readable EDN" node)]
                      (when-not (map? m)
                        (err :clj-book.md.compile/invalid-fence-info
                             "Fenced-code attributes must be an EDN map." {:info info} node))
                      m))]
        (cond-> (or attrs {})
          (seq lang) (assoc :lang (keyword lang)))))))

(defn- strip-trailing-newline
  "Drop the single newline a fence places before its closing delimiter; it
   is not part of the sample, and would otherwise render a trailing blank
   line. Internal blank lines are preserved."
  [s]
  (if (str/ends-with? s "\n") (subs s 0 (dec (count s))) s))

(defn- compile-fenced-code [node]
  (let [{:keys [raw] :as attrs} (parse-fence-info (:info node) node)
        literal (strip-trailing-newline (:literal node))]
    (cond
      (= raw :hiccup) (read-edn-1 literal :clj-book.md.compile/invalid-raw-escape
                                  "{=hiccup} block is not readable EDN" node)
      (= raw :fo)     (read-edn-1 literal :clj-book.md.compile/invalid-raw-escape
                                  "{=fo} block is not readable EDN" node)
      ;; :include resolves to slurped source in the shell; emit body-less.
      (:include attrs) [:pre attrs]
      :else            [:pre attrs literal])))

;; --- tables ----------------------------------------------------------------

(defn- table-row->hiccup [row]
  (into [:tr]
        (map (fn [cell]
               (into [(if (:header cell) :th :td)]
                     (compile-inline-seq (:children cell))))
             (:children row))))

(defn- compile-table [node attrs]
  (let [head (first (filter #(= :table-head (:type %)) (:children node)))
        body (first (filter #(= :table-body (:type %)) (:children node)))]
    (into [:table attrs]
          (concat
           (when head [(into [:thead] (map table-row->hiccup (:children head)))])
           (when body [(into [:tbody] (map table-row->hiccup (:children body)))])))))

(defn- table-attrs-paragraph
  "If `node` is a paragraph whose entire text is a bare EDN map, return that
   map (table attributes); otherwise nil."
  [node]
  (when (= :paragraph (:type node))
    (let [t (str/trim (inline-text node))]
      (when (and (str/starts-with? t "{") (str/ends-with? t "}"))
        (let [m (try (edn/read-string t) (catch Exception _ nil))]
          (when (map? m) m))))))

;; --- directives (admonitions) ----------------------------------------------

(defn- directive-attrs [node]
  (if-let [s (:attrs-string node)]
    (let [m (read-edn-1 s :clj-book.md.compile/invalid-directive-attrs
                        "Directive attribute map is not readable EDN" node)]
      (when-not (map? m)
        (err :clj-book.md.compile/invalid-directive-attrs
             "Directive attributes must be an EDN map." {:attrs-string s} node))
      m)
    {}))

(defn- compile-directive [node]
  (case (:name node)
    "admonition"
    (let [attrs (schema/check-admonition (directive-attrs node)
                                         :clj-book.md.compile/invalid-admonition)]
      (into [:admonition attrs] (compile-block-seq (:children node))))

    "sidebar"
    (into [:sidebar (directive-attrs node)] (compile-block-seq (:children node)))

    "epigraph"
    (into [:epigraph (directive-attrs node)] (compile-block-seq (:children node)))

    "keep-together"
    (into [:keep-together (directive-attrs node)] (compile-block-seq (:children node)))

    "page-break"
    [:page-break]

    (err :clj-book.md.compile/unknown-directive
         (str "Unknown directive: :::" (:name node)) {:name (:name node)} node)))

;; --- footnotes --------------------------------------------------------------

(defn- collect-footnote-defs
  "Walk the whole AST collecting `label -> footnote-definition node`."
  [node]
  (cond
    (= :footnote-definition (:type node)) {(:label node) node}
    (:children node) (reduce merge {} (map collect-footnote-defs (:children node)))
    :else {}))

(defn- footnote-body
  "Compile a footnote definition's content. A single-paragraph definition is
   inlined; otherwise its blocks are kept."
  [def-node]
  (let [kids (:children def-node)]
    (if (and (= 1 (count kids)) (= :paragraph (:type (first kids))))
      (compile-inline-seq (:children (first kids)))
      (compile-block-seq kids))))

(defn- compile-footnote-reference [node]
  (if-let [d (get *footnote-defs* (:label node))]
    (into [:footnote] (footnote-body d))
    (err :clj-book.md.compile/unknown-footnote
         (str "No definition for footnote [^" (:label node) "].")
         {:label (:label node)} node)))

;; --- links ------------------------------------------------------------------

(defn- compile-link [node]
  (let [dest (:destination node)]
    (if (and dest (str/starts-with? dest "#"))
      (into [:xref {:to (keyword (subs dest 1))}] (compile-inline-seq (:children node)))
      (into [:a {:href dest}] (compile-inline-seq (:children node))))))

(defn- compile-image [node]
  (let [alt (inline-text node)]
    [:img (cond-> {:src (:destination node)} (seq alt) (assoc :alt alt))]))

;; --- lists ------------------------------------------------------------------

(defn- compile-list-item
  "Compile a list item. In a `tight` list a single-paragraph item is
   inlined (no inner `[:p]`); in a loose list, or when the item holds more
   than one block, the blocks (paragraphs included) are kept — matching
   hand-written `[:li [:p …]]` Hiccup and CommonMark's tight/loose rule."
  [node tight]
  (let [kids (:children node)]
    (into [:li]
          (if (and tight (= 1 (count kids)) (= :paragraph (:type (first kids))))
            (compile-inline-seq (:children (first kids)))
            (compile-block-seq kids)))))

;; --- the compiler table -----------------------------------------------------

(def compilers
  "Node `:type` -> `(fn [node] -> author-hiccup)`. A plain map so the
   vocabulary can be introspected and extended as data."
  {:paragraph           (fn [n] (into [:p] (compile-inline-seq (:children n))))
   :heading             (fn [n] (let [tag (keyword (str "h" (:level n)))
                                       [attrs kids] (extract-heading-attrs
                                                     (compile-inline-seq (:children n)))]
                                   (into (if attrs [tag attrs] [tag]) kids)))
   :text                (fn [n] (:literal n))
   :strong              (fn [n] (into [:strong] (compile-inline-seq (:children n))))
   :emphasis            (fn [n] (into [:em] (compile-inline-seq (:children n))))
   :code                (fn [n] [:code (:literal n)])
   :soft-line-break     (fn [_] " ")
   :hard-line-break     (fn [_] [:br])
   :thematic-break      (fn [_] [:hr])
   :bullet-list         (fn [n] (into [:ul] (map #(compile-list-item % (:tight n)) (:children n))))
   :ordered-list        (fn [n] (into [:ol] (map #(compile-list-item % (:tight n)) (:children n))))
   :list-item           (fn [n] (compile-list-item n true))
   :block-quote         (fn [n] (into [:blockquote] (compile-block-seq (:children n))))
   :link                compile-link
   :image               compile-image
   :fenced-code-block   compile-fenced-code
   :indented-code-block (fn [n] [:pre {} (strip-trailing-newline (:literal n))])
   :table               (fn [n] (compile-table n {}))
   :directive           compile-directive
   :footnote-reference  compile-footnote-reference
   :inline-footnote     (fn [n] (into [:footnote] (compile-inline-seq (:children n))))})

(defn- compile-node [node]
  (if-let [f (get compilers (:type node))]
    (f node)
    (err :clj-book.md.compile/unsupported-node
         (str "Unsupported Markdown construct: " (:type node)
              (when (= :unknown (:type node)) (str " (" (:node-class node) ")"))
              (when (#{:html-block :html-inline} (:type node))
                ". Raw HTML is not supported; use a {=hiccup} or {=fo} escape."))
         {:node-type (:type node)} node)))

(defn- compile-inline-seq [nodes]
  (->> nodes (map compile-node) (remove nil?) fold-inline-escapes vec))

(defn- compile-block-seq
  "Compile a sequence of block nodes. Drops footnote definitions (folded in
   at their reference sites) and folds a bare-EDN `:cols` paragraph into the
   table that immediately follows it."
  [nodes]
  (loop [ns nodes, acc []]
    (if (empty? ns)
      acc
      (let [n    (first ns)
            nxt  (second ns)
            tbl-attrs (when (= :table (:type nxt)) (table-attrs-paragraph n))]
        (cond
          ;; Footnote and link/image reference definitions are metadata that
          ;; resolves at the use site and produces no output of its own.
          (#{:footnote-definition :link-reference-definition} (:type n))
          (recur (rest ns) acc)

          tbl-attrs (recur (drop 2 ns) (conj acc (compile-table nxt tbl-attrs)))
          :else     (recur (rest ns) (conj acc (compile-node n))))))))

;; --- public transform -------------------------------------------------------

(defn compile
  "Compile a parsed Markdown `document` AST into a `[:chapter {…} & body]`
   author-Hiccup form. The first level-1 heading becomes `:title` and is
   removed from the body. `:id` is not derived here (it depends on the
   filename); the shell merges it in clj-book.book.load."
  [document]
  (when-not (= :document (:type document))
    (throw (error/ex :clj-book.md.compile/not-a-document
                     "compile expects a parsed Markdown :document node."
                     {:node document})))
  (binding [*footnote-defs* (collect-footnote-defs document)]
    (let [blocks   (vec (:children document))
          h1-index (first (keep-indexed
                           (fn [i b] (when (and (= :heading (:type b))
                                                (= 1 (:level b))) i))
                           blocks))
          title    (when h1-index (clean-heading-text (inline-text (nth blocks h1-index))))
          body     (compile-block-seq
                    (if h1-index
                      (concat (subvec blocks 0 h1-index) (subvec blocks (inc h1-index)))
                      blocks))]
      (into [:chapter (cond-> {} title (assoc :title title))] body))))
