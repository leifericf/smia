(ns clj-book.md.parse
  "Imperative shell: the only namespace that touches the commonmark-java
   parser. Parse a Markdown source string into a normalized Clojure-data
   AST, walking the Java node tree once and attaching source positions
   (1-based `{:line :col}`). Every semantic decision happens downstream in
   clj-book.md.compile, on data — this layer only gets us from text to
   data as directly as possible."
  (:import
   (org.commonmark.parser Parser IncludeSourceSpans)
   (org.commonmark.node Node SourceSpan
                        Document Heading Paragraph Text
                        StrongEmphasis Emphasis Code
                        BulletList OrderedList ListItem BlockQuote
                        Link Image ThematicBreak
                        SoftLineBreak HardLineBreak
                        FencedCodeBlock IndentedCodeBlock
                        HtmlBlock HtmlInline)))

(defn- pos
  "1-based `{:line :col}` of a node's first source span, or nil."
  [^Node n]
  (when-let [^SourceSpan s (first (.getSourceSpans n))]
    {:line (inc (.getLineIndex s))
     :col  (inc (.getColumnIndex s))}))

(defn- child-nodes [^Node n]
  (loop [c (.getFirstChild n) acc []]
    (if (nil? c) acc (recur (.getNext c) (conj acc c)))))

(declare node->data)

(defn- kids [n] (mapv node->data (child-nodes n)))

(defn- node->data
  "Normalize one commonmark Node into a Clojure-data map keyed by `:type`,
   carrying `:pos` and (for containers) `:children`."
  [^Node n]
  (let [base (cond-> {} (pos n) (assoc :pos (pos n)))]
    (condp instance? n
      Document          (assoc base :type :document :children (kids n))
      Heading           (assoc base :type :heading :level (.getLevel ^Heading n) :children (kids n))
      Paragraph         (assoc base :type :paragraph :children (kids n))
      Text              (assoc base :type :text :literal (.getLiteral ^Text n))
      StrongEmphasis    (assoc base :type :strong :children (kids n))
      Emphasis          (assoc base :type :emphasis :children (kids n))
      Code              (assoc base :type :code :literal (.getLiteral ^Code n))
      BulletList        (assoc base :type :bullet-list :children (kids n))
      OrderedList       (assoc base :type :ordered-list :children (kids n))
      ListItem          (assoc base :type :list-item :children (kids n))
      BlockQuote        (assoc base :type :block-quote :children (kids n))
      Link              (assoc base :type :link
                               :destination (.getDestination ^Link n)
                               :title (.getTitle ^Link n)
                               :children (kids n))
      Image             (assoc base :type :image
                               :destination (.getDestination ^Image n)
                               :title (.getTitle ^Image n)
                               :children (kids n))
      ThematicBreak     (assoc base :type :thematic-break)
      SoftLineBreak     (assoc base :type :soft-line-break)
      HardLineBreak     (assoc base :type :hard-line-break)
      FencedCodeBlock   (assoc base :type :fenced-code-block
                               :info (.getInfo ^FencedCodeBlock n)
                               :literal (.getLiteral ^FencedCodeBlock n))
      IndentedCodeBlock (assoc base :type :indented-code-block
                               :literal (.getLiteral ^IndentedCodeBlock n))
      HtmlBlock         (assoc base :type :html-block :literal (.getLiteral ^HtmlBlock n))
      HtmlInline        (assoc base :type :html-inline :literal (.getLiteral ^HtmlInline n))
      ;; Anything else is surfaced verbatim so compile can fail with context.
      (assoc base :type :unknown :node-class (.getName (class n)) :children (kids n)))))

(def ^:private parser
  "A reusable commonmark parser with block+inline source spans enabled."
  (delay (-> (Parser/builder)
             (.includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
             (.build))))

(defn parse
  "Parse Markdown `source` into a normalized `:document` AST map.
   `source-name` is recorded on the document for error context."
  [source source-name]
  (-> (.parse ^Parser @parser source)
      node->data
      (assoc :source-name source-name)))
