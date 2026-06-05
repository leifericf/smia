(ns smia.hiccup
  "Pure helpers for the Hiccup vector shape `[tag attrs? & children]`
   shared by the FO and HTML pipelines — node parsing and the small
   node-shape predicates both expanders lean on. Format-specific
   rendering stays in `smia.fo.expand` and `smia.html.expand`; only the
   shape logic that is identical across formats lives here. No IO."
  (:require
   [smia.error :as error]))

(defn parse-node
  "Split a Hiccup vector into `[tag attrs children]` (attrs may be nil)."
  [[tag & more]]
  (if (map? (first more))
    [tag (first more) (next more)]
    [tag nil more]))

(defn flatten-children
  "Flatten one level of seqs (e.g. produced by `for`) among children."
  [children]
  (mapcat (fn [c] (if (seq? c) c [c])) children))

(defn as-id
  "An anchor id as a string: a keyword's name, anything else stringified,
   nil for nil."
  [v]
  (when (some? v) (if (keyword? v) (name v) (str v))))

(defn captioned?
  "True when a node carries a caption or a numbering-pass label."
  [author]
  (or (:caption author) (:label author)))

(defn code-text
  "The code body of a listing: its string children concatenated."
  [children]
  (apply str (filter string? children)))

(defn annotations->by-line
  "Validate a listing's `:annotations` and index them as `{line -> {:n
   ordinal :note note}}`. Ordinals are 1-based by vector order. Throws
   `error-type` (each format reports its own, e.g.
   `:smia.fo.expand/invalid-annotation`) for a line outside
   `1..line-count` or a line carrying more than one note."
  [error-type annotations line-count id]
  (reduce
    (fn [acc [i {:keys [line note]}]]
      (when-not (and (integer? line) (<= 1 line line-count))
        (throw (error/ex error-type
                         (str "Annotation " (inc i) " references line "
                              (pr-str line) ", outside the listing's "
                              "1.." line-count " lines.")
                         {:listing id :line line :lines line-count})))
      (when (contains? acc line)
        (throw (error/ex error-type
                         (str "Line " line " carries more than one annotation.")
                         {:listing id :line line})))
      (assoc acc line {:n (inc i) :note note}))
    {}
    (map-indexed vector annotations)))
