(ns smia.book.conditional
  "Pure core: conditional content. A `[:when <condition> & body]` node keeps
   or drops its body based on a condition evaluated against a context map —
   the chapter's document attributes and front-matter, plus the current
   `:edition`. Pruning runs before numbering, so a kept or dropped numbered
   float changes the sequence only for the editions it actually appears in.

   The condition vocabulary is a small closed set of maps:

     {:defined :k}          the key :k is present in the context
     {:equals [:k v]}       the context value at :k equals v
     {:any-of [c …]}        any subcondition holds (logical or)
     {:all-of [c …]}        every subcondition holds (logical and)
     {:not c}               the subcondition does not hold

   A condition that names `:edition` is *edition-dependent*: the build prunes
   it per edition (and numbers per edition). A condition that names none is
   resolved once, before numbering, so every edition still agrees on the
   apparatus. No IO."
  (:require
   [smia.book.attrs :as attrs]
   [smia.error :as error]
   [smia.schema :as schema]
   [malli.core :as m]))

(def Condition
  "An author conditional. Exactly one operator key per map (closed), nested
   recursively under the boolean combinators."
  (m/schema
    [:schema
     {:registry
      {::cond [:or
               [:map {:closed true} [:defined :keyword]]
               [:map {:closed true} [:equals [:tuple :keyword :any]]]
               [:map {:closed true} [:any-of [:sequential [:ref ::cond]]]]
               [:map {:closed true} [:all-of [:sequential [:ref ::cond]]]]
               [:map {:closed true} [:not [:ref ::cond]]]]}}
     ::cond]))

(defn check-condition
  "Return `condition` when it conforms to `Condition`; otherwise throw a
   structured `:smia.book.conditional/invalid-condition` error."
  [condition]
  (schema/check Condition condition :smia.book.conditional/invalid-condition))

(defn eval-condition
  "Evaluate a (valid) `condition` against `context`, returning a boolean."
  [condition context]
  (cond
    (contains? condition :defined) (contains? context (:defined condition))
    (contains? condition :equals)  (let [[k v] (:equals condition)]
                                     (= (get context k) v))
    (contains? condition :any-of)  (boolean (some #(eval-condition % context)
                                                  (:any-of condition)))
    (contains? condition :all-of)  (every? #(eval-condition % context)
                                           (:all-of condition))
    (contains? condition :not)     (not (eval-condition (:not condition) context))
    :else (throw (error/ex :smia.book.conditional/invalid-condition
                           (str "Not a condition: " (pr-str condition))
                           {:condition condition}))))

(defn mentions-edition?
  "True when `condition` references the `:edition` key anywhere."
  [condition]
  (cond
    (contains? condition :defined) (= :edition (:defined condition))
    (contains? condition :equals)  (= :edition (first (:equals condition)))
    (contains? condition :any-of)  (boolean (some mentions-edition? (:any-of condition)))
    (contains? condition :all-of)  (boolean (some mentions-edition? (:all-of condition)))
    (contains? condition :not)     (mentions-edition? (:not condition))
    :else false))

;; --- pruning ----------------------------------------------------------------

(defn- when-node? [n]
  (and (vector? n) (= :when (first n))))

(declare prune)

(defn- prune-seq
  "Prune a child sequence, expanding each `[:when …]` child inline: a kept
   condition splices its (pruned) body, a failed one drops it. When `skip?`
   is non-nil and holds for a condition, that `:when` is left in place (its
   body still pruned within) for a later pass."
  [children context skip?]
  (mapcat
    (fn [child]
      (if (when-node? child)
        (let [condition (check-condition (second child))
              body      (drop 2 child)]
          (if (and skip? (skip? condition))
            [(into [:when condition] (prune-seq body context skip?))]
            (if (eval-condition condition context)
              (prune-seq body context skip?)
              [])))
        [(prune child context skip?)]))
    children))

(defn- check-attrs!
  "A `[:when …]` in attribute-value position has nowhere to splice and
   would otherwise surface much later as an unknown-tag render error;
   reject it here with the attribute named."
  [attrs]
  (doseq [[k v] attrs]
    (when (when-node? v)
      (throw (error/ex :smia.book.conditional/conditional-in-attribute
                       (str "Conditional content is not supported inside an "
                            "attribute value (" (pr-str k) "); wrap the "
                            "element in [:when …] instead.")
                       {:attribute k :value v})))))

(defn prune
  "Resolve `[:when …]` nodes in one chapter `form` against `context`. `skip?`
   (a predicate on a condition, or nil) leaves matching conditions in place
   for a later pass; otherwise every condition is evaluated."
  [form context skip?]
  (if (vector? form)
    (let [[tag & more] form
          attrs        (when (map? (first more)) (first more))
          kids         (if attrs (rest more) more)]
      (when attrs (check-attrs! attrs))
      (into (if attrs [tag attrs] [tag])
            (prune-seq kids context skip?)))
    form))

;; --- manuscript-wide helpers ------------------------------------------------

(defn- when-nodes [form]
  (filter when-node? (tree-seq vector? seq form)))

(defn- forms [manuscript]
  (concat (keep :content (:sections manuscript)) (:chapters manuscript)))

(defn has-conditions?
  "True when the manuscript carries any `[:when …]` node."
  [manuscript]
  (boolean (some #(seq (when-nodes %)) (forms manuscript))))

(defn edition-dependent?
  "True when any `[:when …]` in the manuscript names `:edition` — meaning the
   content (and so the numbering) varies by edition."
  [manuscript]
  (boolean (some (fn [form]
                   (some (comp mentions-edition? second) (when-nodes form)))
                 (forms manuscript))))

(defn prune-manuscript
  "Prune `[:when …]` across a `manuscript`, per chapter, in both `:sections`
   content and `:chapters`. The per-chapter context is `book-context`
   overridden by the chapter's front-matter and then `build-context` plus
   `extra-context` (e.g. `{:edition :epub}`). `skip?` (or nil) defers
   matching conditions to a later pass. A manuscript with no `:when` is
   returned unchanged."
  [manuscript book-context build-context extra-context skip?]
  (if-not (has-conditions? manuscript)
    manuscript
    (let [do-form (fn [form]
                    (let [fm  (if (map? (second form)) (second form) {})
                          ctx (attrs/resolve-context
                                book-context fm (merge build-context extra-context))]
                      (prune form ctx skip?)))]
      (-> manuscript
          (update :sections
                  (fn [sections]
                    (mapv #(cond-> % (:content %) (update :content do-form)) sections)))
          (update :chapters (fn [chapters] (mapv do-form chapters)))))))
