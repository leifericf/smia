(ns clj-book.highlight.lexer
  "Pure core: a tiny ordered-rule scanner shared by the language tokenizers.

   A tokenizer is a function `code -> [{:kind :text} …]` whose token `:text`
   parts concatenate back to the original source (whitespace and punctuation
   preserved). `scan` walks the source left to right, at each position taking
   the first rule whose regex matches; unmatched characters fall through as
   plain `:text`. Adjacent same-kind tokens are merged. No IO — the output is
   pure data the renderer colors from the theme palette."
  (:require
   [clojure.string :as str]))

(defn- merge-runs
  "Coalesce adjacent tokens of the same kind into one."
  [toks]
  (->> toks
       (partition-by :kind)
       (map (fn [g] {:kind (:kind (first g)) :text (apply str (map :text g))}))))

(defn scan
  "Tokenize `code` with ordered `rules`: a vector of `[pattern kind]`, where
   `pattern` is a `java.util.regex.Pattern` and `kind` is a keyword or a
   `(fn [matched-text] -> kind)`. The first rule matching at the current
   position wins; an unmatched character becomes a one-char `:text` token."
  [^String code rules]
  (let [n        (count code)
        matchers (mapv (fn [[p kind]]
                         [(.matcher ^java.util.regex.Pattern p code) kind])
                       rules)]
    (merge-runs
      (loop [i 0, acc (transient [])]
        (if (>= i n)
          (persistent! acc)
          (let [hit (some (fn [[^java.util.regex.Matcher m kind]]
                            (.region m i n)
                            (when (and (.lookingAt m) (> (.end m) i))
                              (let [s (subs code i (.end m))]
                                {:kind (if (fn? kind) (kind s) kind) :text s})))
                          matchers)]
            (if hit
              (recur (+ i (count (:text hit))) (conj! acc hit))
              (recur (inc i)
                     (conj! acc {:kind :text :text (subs code i (inc i))})))))))))

(defn keyword-classifier
  "A `kind` fn for identifier rules: `:keyword` when the identifier is in
   `kw-set`, otherwise `:text`."
  [kw-set]
  (fn [s] (if (contains? kw-set s) :keyword :text)))

(def c-like-rules-base
  "Shared lexical rules for C-family languages (comments, strings, numbers),
   as `[regex-string kind]`; the identifier rule is appended per language."
  [["//[^\\n]*"               :comment]
   ["/\\*[\\s\\S]*?\\*/"      :comment]
   ["\"(?:\\\\.|[^\"\\\\])*\"" :string]
   ["'(?:\\\\.|[^'\\\\])*'"   :string]
   ["\\d[\\d_]*\\.?\\d*(?:[eE][+-]?\\d+)?[fFlLdD]?" :number]])

(defn c-like
  "A tokenizer for a C-family language whose reserved words are `keywords`."
  [keywords]
  (let [rules (-> (mapv (fn [[p k]] [(re-pattern p) k]) c-like-rules-base)
                  (conj [(re-pattern "[A-Za-z_$][A-Za-z0-9_$]*")
                         (keyword-classifier keywords)]))]
    (fn [code] (scan code rules))))
