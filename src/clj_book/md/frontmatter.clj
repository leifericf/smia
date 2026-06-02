(ns clj-book.md.frontmatter
  "Split an optional leading bare EDN map (front-matter) off a Markdown
   chapter source.

   Front-matter is EDN, never YAML and never evaluated: the leading map is
   read with `clojure.edn/read` (data only). The returned `:body` preserves
   the source's line numbering — the consumed front-matter region is
   replaced by an equal number of blank lines — so parser positions stay
   absolute to the original file."
  (:require
   [clj-book.error :as error]
   [clojure.edn :as edn])
  (:import
   (java.io PushbackReader StringReader)))

(defn- first-non-ws
  "The first non-whitespace character of `s`, or nil."
  [^String s]
  (loop [i 0]
    (cond
      (>= i (.length s))                    nil
      (Character/isWhitespace (.charAt s i)) (recur (inc i))
      :else                                  (.charAt s i))))

(defn split
  "Return `{:attrs <map|nil> :body <string>}`. When `source` begins (after
   optional whitespace) with a bare `{…}` EDN map, read it as the
   front-matter `:attrs` and remove it from `:body`; otherwise `:attrs` is
   nil and `:body` is `source` unchanged. A leading `{` that is not a
   readable EDN map is a structured error."
  [^String source]
  (if (not= \{ (first-non-ws source))
    {:attrs nil :body source}
    (let [reader (PushbackReader. (StringReader. source))
          attrs  (try
                   (edn/read reader)
                   (catch Exception e
                     (throw (error/ex :clj-book.md.frontmatter/invalid-front-matter
                                      (str "Leading EDN front-matter is not readable: "
                                           (.getMessage e))
                                      {:cause (.getMessage e)}))))]
      (when-not (map? attrs)
        (throw (error/ex :clj-book.md.frontmatter/invalid-front-matter
                         "Leading EDN front-matter must be a map."
                         {:value attrs})))
      (let [remaining (slurp reader)
            consumed  (subs source 0 (- (count source) (count remaining)))
            newlines  (count (filter #(= \newline %) consumed))]
        {:attrs attrs
         :body  (str (apply str (repeat newlines \newline)) remaining)}))))
