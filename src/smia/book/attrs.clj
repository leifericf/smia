(ns smia.book.attrs
  "Pure core: document-attribute substitution, run before numbering.

   Every `[:attr :k]` reference in the manuscript is replaced with its value
   from a per-chapter context: the book's built-in facts (title, author) and
   its declared `:book/attributes`, overridden by the chapter's own
   front-matter, then by the build context (licensee, language). A value may
   be a string or author Hiccup.

   Substitution precedes the numbering pass, so an attribute whose value
   carries a numbered float is numbered like any other content, and every
   edition substitutes identically (attributes do not vary by edition). m1p
   does the tree walk and the lookup: an `[:attr :k]` tuple is a dictionary
   reference into the `:attr` dictionary built from the context. An unknown
   key is a structured error rather than m1p's inline marker. No IO."
  (:require
   [smia.error :as error]
   [m1p.core :as m1p]))

(defn- attr-node? [n]
  (and (vector? n) (= :attr (first n))))

(defn- attr-refs
  "Every `[:attr …]` node in `form`, for shape and membership checks. Both
   vectors and maps are branches — so an `[:attr :k]` reference inside an
   element's attribute map (e.g. `[:img {:alt [:attr :name]}]`) is collected,
   validated, and substituted the same as one in element-child position. m1p
   already interpolates map values, so collecting them here keeps validation
   and the substitution loop in step with what actually gets rewritten."
  [form]
  (filter attr-node?
          (tree-seq (some-fn vector? map?)
                    (fn [n] (if (map? n) (vals n) (seq n)))
                    form)))

(defn resolve-context
  "The attribute context for one chapter: `book-context` (built-in facts and
   declared attributes) overridden by `front-matter` (the chapter's attrs)
   and then `build-context` (licensee, language). nil values drop out so an
   absent build option never shadows a declared attribute."
  [book-context front-matter build-context]
  (into {} (remove (comp nil? val))
        (merge book-context front-matter build-context)))

(defn- check-refs!
  "Validate every `[:attr …]` reference in `form`: each must be `[:attr
   <keyword>]` naming a key present in `context`. Throws a structured error
   otherwise — `:invalid-attribute` for a malformed reference,
   `:unknown-attribute` for an undeclared key."
  [form context]
  (doseq [node (attr-refs form)]
    (let [k (second node)]
      (when-not (and (= 2 (count node)) (keyword? k))
        (throw (error/ex :smia.book.attrs/invalid-attribute
                         (str "An :attr reference must be [:attr <keyword>]: "
                              (pr-str node))
                         {:node node})))
      (when-not (contains? context k)
        (throw (error/ex :smia.book.attrs/unknown-attribute
                         (str "Unknown document attribute " (pr-str k)
                              ". Declare it in :book/attributes or front-matter.")
                         {:attribute k :known (vec (sort (keys context)))}))))))

(defn substitute-form
  "Resolve every `[:attr :k]` in one chapter `form` against `context`.
   An attribute value may itself carry `[:attr …]` references, so the
   pass repeats until none remain; a reference cycle is a structured
   error (a chain can be at most one hop per declared attribute)."
  [form context]
  (let [opts {:dictionaries {:attr (m1p/prepare-dictionary context)}}]
    (loop [form form, hops 0]
      (check-refs! form context)
      (cond
        (empty? (attr-refs form)) form
        (> hops (count context))
        (throw (error/ex :smia.book.attrs/circular-attribute
                         (str "Document attributes reference each other in a "
                              "cycle: " (pr-str (vec (sort (distinct (map second (attr-refs form)))))))
                         {:attributes (vec (sort (distinct (map second (attr-refs form)))))}))
        :else (recur (m1p/interpolate form opts) (inc hops))))))

(defn substitute
  "Resolve document attributes across a loaded `manuscript`, in both
   `:sections` content and `:chapters`. `book-context` and `build-context`
   are the book-wide halves of the per-chapter context; each chapter's own
   attrs (front-matter) are merged between them. A manuscript with no `:attr`
   reference is returned unchanged (m1p is never invoked)."
  [manuscript book-context build-context]
  (if-not (or (some #(seq (attr-refs (:content %))) (:sections manuscript))
              (seq (mapcat attr-refs (:chapters manuscript))))
    manuscript
    (let [sub (fn [form]
                (let [fm (if (map? (second form)) (second form) {})]
                  (substitute-form form
                                   (resolve-context book-context fm build-context))))]
      (-> manuscript
          (update :sections
                  (fn [sections]
                    (mapv #(cond-> % (:content %) (update :content sub)) sections)))
          (update :chapters (fn [chapters] (mapv sub chapters)))))))
