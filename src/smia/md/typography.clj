(ns smia.md.typography
  "Pure core: smart punctuation for Markdown prose.

   A post-pass over compiled chapter Hiccup that turns typewriter
   punctuation into its typographic form: straight quotes into curly
   pairs, apostrophes into right single quotes, `--` into an en dash,
   `---` into an em dash, and `...` into an ellipsis.

   Only prose is touched. `:pre` and `:code` subtrees are exempt (a flag
   like `--clean` set in a code span keeps its hyphens), as is any
   namespaced tag (the `:fo/*` and `:html/*` raw escapes are authored
   exactly). Attribute maps are never rewritten.

   Quote pairing is a small state machine threading the previously seen
   character through each inline run, so a quote that opens before an
   emphasis span still closes after it. The state resets at every block
   element: an unbalanced quote cannot leak across paragraphs.

   Markdown front-end only — `.clj` chapters are authored exactly and
   never pass through here."
  (:require
   [clojure.string :as str]))

(def ^:private en-dash "–")
(def ^:private em-dash "—")
(def ^:private ellipsis "…")

;; After a skipped subtree (an inline code span), prose continues
;; mid-sentence: stand in for "the last character was part of a word" so
;; a following apostrophe reads as a contraction, not an opening quote.
(def ^:private word-boundary \a)

(defn- replace-dashes-and-ellipsis [s]
  (-> s
      (str/replace "---" em-dash)
      (str/replace "--" en-dash)
      (str/replace "..." ellipsis)))

(defn- opening-context?
  "True when a quote at this position opens: at the start of a run, after
   whitespace, or after an opening bracket, dash, or open quote."
  [prev]
  (or (nil? prev)
      (Character/isWhitespace ^char prev)
      (contains? #{\( \[ \{ \- \– \— \‘ \“} prev)))

(defn- word-char? [c]
  (and c (Character/isLetterOrDigit ^char c)))

(defn- smarten-quotes
  "Curl the straight quotes in `s`, threading `prev` (the last visible
   character before this string, nil at a block start). Returns
   `[prev' s']`."
  [prev s]
  (let [n  (count s)
        sb (StringBuilder. n)]
    (loop [i 0, prev prev]
      (if (= i n)
        [prev (str sb)]
        (let [c      (.charAt ^String s i)
              next-c (when (< (inc i) n) (.charAt ^String s (inc i)))
              out    (case c
                       \" (if (opening-context? prev) \“ \”)
                       \' (cond
                            (word-char? prev) \’
                            (and (opening-context? prev)
                                 next-c
                                 (Character/isLetter ^char next-c)) \‘
                            :else \’)
                       c)]
          (.append sb out)
          (recur (inc i) out))))))

(defn- smarten-text [prev s]
  (smarten-quotes prev (replace-dashes-and-ellipsis s)))

;; --- the walk ---------------------------------------------------------------

(def ^:private skip-tags
  "Subtrees whose text is verbatim, never prose."
  #{:pre :code})

(def ^:private inline-tags
  "Tags that continue the surrounding inline run: quote state threads
   through them. Every other tag is a block and starts a fresh run."
  #{:em :strong :a :xref :footnote :cite :index :term :br
    :sub :sup :small :s :q :kbd})

(declare walk)

(defn- walk-children [prev kids]
  (reduce (fn [[p acc] kid]
            (let [[p' kid'] (walk p kid)]
              [p' (conj acc kid')]))
          [prev []]
          kids))

(defn- walk
  "Smarten one `node`, threading `prev` (the last visible character of the
   inline run, nil at a block start). Returns `[prev' node']`."
  [prev node]
  (cond
    (string? node)
    (smarten-text prev node)

    (vector? node)
    (let [tag (first node)]
      (if (or (qualified-keyword? tag) (contains? skip-tags tag))
        [word-boundary node]
        (let [attrs     (when (map? (second node)) (second node))
              kids      (if attrs (drop 2 node) (rest node))
              inline?   (contains? inline-tags tag)
              [p kids'] (walk-children (when inline? prev) kids)
              node'     (into (if attrs [tag attrs] [tag]) kids')]
          ;; A block's internal state never escapes it.
          [(if inline? p prev) node'])))

    :else [prev node]))

;; --- public transform -------------------------------------------------------

(defn smarten
  "Smarten the punctuation in a sequence of compiled body blocks,
   returning a vector. Pure."
  [forms]
  (mapv #(second (walk nil %)) forms))

(defn smarten-string
  "Smarten one prose string in isolation (a chapter title)."
  [s]
  (second (smarten-text nil s)))
