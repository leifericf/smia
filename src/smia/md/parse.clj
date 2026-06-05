(ns smia.md.parse
  "Imperative shell: the only namespace that touches the commonmark-java
   parser. Parse a Markdown source string into a normalized Clojure-data
   AST, walking the Java node tree once and attaching source positions
   (1-based `{:line :col}`). Every semantic decision happens downstream in
   smia.md.compile, on data.

   Enabled syntax beyond CommonMark: GFM tables, footnotes, and a curated
   `:::name {edn}` fenced container (a custom block parser) used for
   admonitions. The container's directive name and raw attribute string are
   captured verbatim here; reading the EDN and giving meaning to the
   directive is the pure compiler's job."
  (:require
   [clojure.string :as str])
  (:import
   (org.commonmark.parser Parser IncludeSourceSpans)
   (org.commonmark.parser.block BlockParser BlockParserFactory
                                AbstractBlockParser BlockStart BlockContinue)
   (org.commonmark.ext.gfm.tables TablesExtension TableBlock TableHead
                                  TableBody TableRow TableCell)
   (org.commonmark.ext.footnotes FootnotesExtension FootnoteReference
                                 FootnoteDefinition InlineFootnote)
   (org.commonmark.node Node SourceSpan CustomBlock
                        Document Heading Paragraph Text
                        StrongEmphasis Emphasis Code
                        ListBlock BulletList OrderedList ListItem BlockQuote
                        Link Image ThematicBreak
                        SoftLineBreak HardLineBreak
                        FencedCodeBlock IndentedCodeBlock
                        LinkReferenceDefinition
                        HtmlBlock HtmlInline)))

;; --- the :::name {edn} fenced container -----------------------------------

(def ^:private open-re
  #"^:::([A-Za-z][A-Za-z0-9_-]*)[ \t]*(\{.*\})?[ \t]*$")

(defn- closing-fence? [s] (boolean (re-matches #":::[ \t]*" s)))

(def ^:private directive-meta
  "Side table mapping a directive `CustomBlock` node to its captured
   `{:name :attrs-string}`. A `WeakHashMap` keyed by node identity, so
   entries are reclaimed with their nodes and never collide across parses."
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn- innermost-open-directive
  "The nearest enclosing directive block at or above `node` whose closing
   fence has not appeared yet, or nil. A closing fence belongs to the
   innermost open directive, so an outer parser leaves it alone."
  [^Node node]
  (loop [n node]
    (when n
      (let [m (get directive-meta n)]
        (if (and m (not (:closed m)))
          n
          (recur (.getParent n)))))))

(defn- verbatim-leaf?
  "True when `node` is a leaf block that owns its lines verbatim — a
   `:::` line inside one is content, not a closing fence."
  [node]
  (or (instance? FencedCodeBlock node)
      (instance? IndentedCodeBlock node)
      (instance? HtmlBlock node)))

(defn- directive-block-parser [block]
  (let [finished (volatile! false)]
    (proxy [AbstractBlockParser] []
      (isContainer [] true)
      (canContain [_] true)
      (getBlock [] block)
      (tryContinue [state]
        (if @finished
          (BlockContinue/none)
          (let [content (.toString (.getContent (.getLine state)))
                idx     (.getNextNonSpaceIndex state)
                active  (.getBlock (.getActiveBlockParser state))]
            (if (and (closing-fence? (subs content idx))
                     (< (.getIndent state) 4)
                     ;; a fence inside an open code/HTML leaf is content
                     (not (verbatim-leaf? active))
                     ;; and it closes the innermost open directive only
                     (identical? block (innermost-open-directive active)))
              (do (vreset! finished true)
                  (.put directive-meta block
                        (assoc (get directive-meta block) :closed true))
                  ;; consume the whole closing fence line so it is not
                  ;; re-parsed as a stray ":::" paragraph
                  (BlockContinue/atIndex (count content)))
              ;; stay open at the current index; children parse the line
              (BlockContinue/atIndex (.getIndex state)))))))))

(defn- directive-factory []
  (reify BlockParserFactory
    (tryStart [_ state _matched]
      (let [content (.toString (.getContent (.getLine state)))
            idx     (.getNextNonSpaceIndex state)
            m       (re-matches open-re (subs content idx))]
        (if (and m (< (.getIndent state) 4))
          (let [block (proxy [CustomBlock] [])]
            (.put directive-meta block {:name (nth m 1) :attrs-string (nth m 2)})
            (-> (BlockStart/of (into-array BlockParser [(directive-block-parser block)]))
                (.atIndex (count content))))
          (BlockStart/none))))))

;; --- positions and traversal ----------------------------------------------

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

(defn- alignment->kw [a]
  (when a (-> a str str/lower-case keyword)))

(defn- node->data
  "Normalize one commonmark Node into a Clojure-data map keyed by `:type`,
   carrying `:pos` and (for containers) `:children`."
  [^Node n]
  (let [base (cond-> {} (pos n) (assoc :pos (pos n)))]
    (condp instance? n
      Document           (assoc base :type :document :children (kids n))
      Heading            (assoc base :type :heading
                                :level (.getLevel ^Heading n) :children (kids n))
      Paragraph          (assoc base :type :paragraph :children (kids n))
      Text               (assoc base :type :text :literal (.getLiteral ^Text n))
      StrongEmphasis     (assoc base :type :strong :children (kids n))
      Emphasis           (assoc base :type :emphasis :children (kids n))
      Code               (assoc base :type :code :literal (.getLiteral ^Code n))
      BulletList         (assoc base :type :bullet-list
                                :tight (.isTight ^ListBlock n) :children (kids n))
      OrderedList        (assoc base :type :ordered-list
                                :tight (.isTight ^ListBlock n) :children (kids n))
      ListItem           (assoc base :type :list-item :children (kids n))
      BlockQuote         (assoc base :type :block-quote :children (kids n))
      Link               (assoc base :type :link
                                :destination (.getDestination ^Link n)
                                :title (.getTitle ^Link n)
                                :children (kids n))
      Image              (assoc base :type :image
                                :destination (.getDestination ^Image n)
                                :title (.getTitle ^Image n)
                                :children (kids n))
      ThematicBreak      (assoc base :type :thematic-break)
      SoftLineBreak      (assoc base :type :soft-line-break)
      HardLineBreak      (assoc base :type :hard-line-break)
      FencedCodeBlock    (assoc base :type :fenced-code-block
                                :info (.getInfo ^FencedCodeBlock n)
                                :literal (.getLiteral ^FencedCodeBlock n))
      IndentedCodeBlock  (assoc base :type :indented-code-block
                                :literal (.getLiteral ^IndentedCodeBlock n))
      LinkReferenceDefinition (assoc base :type :link-reference-definition
                                     :label (.getLabel ^LinkReferenceDefinition n))
      ;; GFM tables
      TableBlock         (assoc base :type :table :children (kids n))
      TableHead          (assoc base :type :table-head :children (kids n))
      TableBody          (assoc base :type :table-body :children (kids n))
      TableRow           (assoc base :type :table-row :children (kids n))
      TableCell          (assoc base :type :table-cell
                                :header (.isHeader ^TableCell n)
                                :alignment (alignment->kw (.getAlignment ^TableCell n))
                                :children (kids n))
      ;; Footnotes
      FootnoteReference  (assoc base :type :footnote-reference
                                :label (.getLabel ^FootnoteReference n))
      FootnoteDefinition (assoc base :type :footnote-definition
                                :label (.getLabel ^FootnoteDefinition n)
                                :children (kids n))
      InlineFootnote     (assoc base :type :inline-footnote :children (kids n))
      ;; The :::name {edn} fenced container
      CustomBlock        (let [{:keys [name attrs-string]} (get directive-meta n)]
                           (assoc base :type :directive
                                  :name name :attrs-string attrs-string
                                  :children (kids n)))
      HtmlBlock          (assoc base :type :html-block :literal (.getLiteral ^HtmlBlock n))
      HtmlInline         (assoc base :type :html-inline :literal (.getLiteral ^HtmlInline n))
      ;; Anything else is surfaced verbatim so compile can fail with context.
      (assoc base :type :unknown :node-class (.getName (class n)) :children (kids n)))))

(def ^:private parser
  "A reusable commonmark parser: block+inline source spans, GFM tables and
   footnotes, plus the `:::` directive container."
  (delay
    (-> (Parser/builder)
        (.includeSourceSpans IncludeSourceSpans/BLOCKS_AND_INLINES)
        (.extensions [(TablesExtension/create)
                      (-> (FootnotesExtension/builder)
                          (.inlineFootnotes true)
                          (.build))])
        (.customBlockParserFactory (directive-factory))
        (.build))))

(defn parse
  "Parse Markdown `source` into a normalized `:document` AST map.
   `source-name` is recorded on the document for error context."
  [source source-name]
  (-> (.parse ^Parser @parser source)
      node->data
      (assoc :source-name source-name)))
