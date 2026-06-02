(ns clj-book.md.compile
  "Pure core: compile a normalized Markdown AST (clj-book.md.parse data)
   into author (sugar) Hiccup — the same vocabulary a `.clj` chapter
   yields.

   A `compilers` map dispatches on a node's `:type`, mirroring
   clj-book.fo.expand/expanders so the front-end is introspectable and
   extensible as data rather than a `cond`. The first level-1 heading
   becomes the chapter `:title` and is dropped from the body (the book
   layer renders the chapter heading). No IO; commonmark-java is never
   touched here."
  (:refer-clojure :exclude [compile])
  (:require
   [clj-book.error :as error]))

(declare compile-node compile-children)

;; --- plain-text extraction ------------------------------------------------

(defn- inline-text
  "Concatenate the plain text of an inline subtree (used for headings and
   image alt text)."
  [node]
  (case (:type node)
    (:text :code)                    (:literal node)
    (:soft-line-break :hard-line-break) " "
    (apply str (map inline-text (:children node)))))

;; --- node compilers -------------------------------------------------------

(defn- compile-list-item
  "A list item with a single paragraph child is tight: inline that
   paragraph's content directly. Otherwise (loose item or nested list)
   keep the block children."
  [node]
  (let [kids (:children node)]
    (into [:li]
          (if (and (= 1 (count kids)) (= :paragraph (:type (first kids))))
            (compile-children (first kids))
            (map compile-node kids)))))

(defn- compile-link [node]
  (into [:a {:href (:destination node)}] (compile-children node)))

(defn- compile-image [node]
  (let [alt (inline-text node)]
    [:img (cond-> {:src (:destination node)}
            (seq alt) (assoc :alt alt))]))

(def compilers
  "Node `:type` -> `(fn [node] -> author-hiccup)`. A plain map so the
   vocabulary can be introspected and extended as data."
  {:paragraph           (fn [n] (into [:p] (compile-children n)))
   :heading             (fn [n] (into [(keyword (str "h" (:level n)))] (compile-children n)))
   :text                (fn [n] (:literal n))
   :strong              (fn [n] (into [:strong] (compile-children n)))
   :emphasis            (fn [n] (into [:em] (compile-children n)))
   :code                (fn [n] [:code (:literal n)])
   :soft-line-break     (fn [_] " ")
   :hard-line-break     (fn [_] [:br])
   :thematic-break      (fn [_] [:hr])
   :bullet-list         (fn [n] (into [:ul] (map compile-node (:children n))))
   :ordered-list        (fn [n] (into [:ol] (map compile-node (:children n))))
   :list-item           compile-list-item
   :block-quote         (fn [n] (into [:blockquote] (compile-children n)))
   :link                compile-link
   :image               compile-image
   ;; Phase 2 refines fenced blocks (info string -> :lang/:test/:include).
   :fenced-code-block   (fn [n] [:pre (:literal n)])
   :indented-code-block (fn [n] [:pre (:literal n)])})

(defn- compile-node [node]
  (if-let [f (get compilers (:type node))]
    (f node)
    (throw (error/ex :clj-book.md.compile/unsupported-node
                     (str "Unsupported Markdown construct: " (:type node)
                          (when (= :unknown (:type node))
                            (str " (" (:node-class node) ")"))
                          (when (#{:html-block :html-inline} (:type node))
                            ". Raw HTML is not supported; use a {=hiccup} or "
                            "{=fo} escape block."))
                     (cond-> {:node-type (:type node)}
                       (:pos node) (assoc :pos (:pos node)))))))

(defn- compile-children [node]
  (->> (:children node) (map compile-node) (remove nil?) vec))

;; --- public transform -----------------------------------------------------

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
  (let [blocks   (vec (:children document))
        h1-index (first (keep-indexed
                          (fn [i b] (when (and (= :heading (:type b))
                                               (= 1 (:level b))) i))
                          blocks))
        title    (when h1-index (inline-text (nth blocks h1-index)))
        body     (->> (if h1-index
                        (concat (subvec blocks 0 h1-index)
                                (subvec blocks (inc h1-index)))
                        blocks)
                      (map compile-node)
                      (remove nil?)
                      vec)]
    (into [:chapter (cond-> {} title (assoc :title title))] body)))
