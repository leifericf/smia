(ns smia.fo.attrs
  "Pure helpers for turning Hiccup attribute maps into XSL-FO attribute
   name/value pairs.

   FO property names are written as plain keywords, including the dotted
   compound names FO uses (`:keep-together.within-page`); those pass
   through verbatim. Namespaced keywords render as `ns:name`. Values are
   stringified: keywords become their name, numbers and ratios stringify,
   strings pass through. Pairs are emitted in a deterministic (sorted)
   order so serialized FO is reproducible."
  (:require
   [clojure.string :as str]))

(defn attr-name
  "FO attribute name for a property keyword. Compound (dotted) names like
   `:keep-together.within-page` pass through verbatim; namespaced
   keywords render as `ns:name`."
  [k]
  (if-let [ns (namespace k)]
    (str ns ":" (name k))
    (name k)))

(defn attr-value
  "Stringify an FO attribute value. Keywords render as their name (a
   namespaced keyword keeps its namespace), numbers stringify, ratios
   render as doubles, strings pass through."
  [v]
  (cond
    (keyword? v) (if-let [ns (namespace v)] (str ns "/" (name v)) (name v))
    (string? v)  v
    (ratio? v)   (str (double v))
    :else        (str v)))

(defn pairs
  "Deterministically ordered `[name value]` string pairs for attribute
   map `attrs`, dropping entries whose value is nil. Returns a (possibly
   empty) sequence."
  [attrs]
  (->> attrs
       (keep (fn [[k v]] (when (some? v) [(attr-name k) (attr-value v)])))
       (sort-by first)))
