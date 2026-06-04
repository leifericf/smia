(ns smia.fo.hiccup
  "Pure helpers for the Hiccup vector shape `[tag attrs? & children]` shared
   by FO expansion and serialization.")

(defn parse-node
  "Split a Hiccup vector into `[tag attrs children]` (attrs may be nil)."
  [[tag & more]]
  (if (map? (first more))
    [tag (first more) (next more)]
    [tag nil more]))
