(ns smia.fo.expand-test
  (:require
   [smia.error :as error]
   [smia.fo.expand :as expand]
   [smia.fo.serialize :as ser]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- ex [node] (expand/expand node))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest paragraph-expands-to-block
  (let [out (ex [:p "hello"])]
    (is (= :fo/block (first out)))
    (is (= "hello" (last out)))))

(deftest inline-sugar-expands
  (is (= [:fo/inline {:font-weight "bold"} "x"] (ex [:strong "x"])))
  (is (= [:fo/inline {:font-style "italic"} "x"] (ex [:em "x"]))))

(deftest interface-vocabulary-inline-tags-expand
  (testing ":sub and :sup shift the baseline"
    (is (= [:fo/inline {:baseline-shift "sub" :font-size "0.75em"} "2"]
           (ex [:sub "2"])))
    (is (= [:fo/inline {:baseline-shift "super" :font-size "0.75em"} "n"]
           (ex [:sup "n"]))))
  (testing ":mark carries a highlight background"
    (is (= [:fo/inline {:background-color "#fff3b0"} "x"] (ex [:mark "x"]))))
  (testing ":kbd boxes a key in monospace"
    (let [[tag attrs] (ex [:kbd "Enter"])]
      (is (= :fo/inline tag))
      (is (= "monospace" (:font-family attrs)))))
  (testing ":menu joins its path segments with a portable ASCII caret"
    ;; the base-14 serif has no triangle glyph, so the PDF stays ASCII
    (is (= [:fo/inline {} "File" " > " "Export"] (ex [:menu "File" "Export"])))))

(deftest richer-blocks-expand
  (testing ":example is a kept-together callout with an optional title"
    (let [[tag attrs title body] (ex [:example {:title "Worked"} [:p "x"]])]
      (is (= :fo/block tag))
      (is (= "always" (:keep-together.within-page attrs)))
      (is (= [:fo/block {:font-weight "bold" :space-after "3pt"} "Worked"] title))
      (is (= [:fo/block {:space-after "6pt"} "x"] body))))
  (testing ":details/:open show the summary as a bold lead-in (print cannot fold)"
    (let [[_ _ summary] (ex [:details {:summary "More"} [:p "x"]])]
      (is (= [:fo/block {:font-weight "bold" :space-after "3pt"} "More"] summary)))
    (let [[_ _ summary] (ex [:open [:p "x"]])]
      (is (= [:fo/block {:font-weight "bold" :space-after "3pt"} "Details"] summary)))))

(deftest pre-validation-attrs-are-inert-to-expansion
  ;; The Markdown front-end tags code blocks with :lang/:test/:include for
  ;; the opt-in validation pass; those attrs must not affect rendering.
  (is (= (ex [:pre "code"])
         (ex [:pre {:lang :clojure :test true} "code"])
         (ex [:pre {:lang :clojure :include "x.clj"} "code"]))))

(deftest line-end-annotation-mark-is-set-off-from-the-code
  ;; The mark at a line's end carries a small gap (matching its own inner
  ;; padding) so it does not collide with the last code glyph; the copy in
  ;; the note list below takes its spacing from the list geometry instead.
  (let [out   (ex [:pre {:annotations [{:line 1 :note "n"}]} "(+ 1 2)"])
        marks (->> (tree-seq vector? seq out)
                   (filter #(and (vector? %) (= :fo/inline (first %))
                                 (map? (second %))
                                 (:background-color (second %)))))]
    (is (= 2 (count marks)) "one mark at the line end, one in the list")
    (is (= 1 (count (filter #(= "2pt" (:space-start (second %))) marks)))
        "only the line-end mark is set off from the code")))

(deftest code-blocks-soft-wrap-overlong-lines
  ;; `white-space "pre"` alone implies no-wrap, letting a long line run past
  ;; the column edge; an explicit wrap-option soft-wraps it instead.
  (testing "a plain code block preserves whitespace but wraps"
    (let [attrs (second (ex [:pre "x"]))]
      (is (= "pre" (:white-space attrs)))
      (is (= "wrap" (:wrap-option attrs)))))
  (testing "the per-line blocks of a numbered listing wrap too"
    (let [out   (ex [:pre {:line-numbers true} "aaa\nbbb"])
          lines (filter #(and (vector? %) (= :fo/block (first %))
                              (= "pre" (:white-space (second %))))
                        (drop 2 out))]
      (is (seq lines))
      (is (every? #(= "wrap" (:wrap-option (second %))) lines)))))

(defn- tag-children [tag out]
  (filter #(and (vector? %) (= tag (first %))) out))

(deftest header-only-table-promotes-header-to-body
  ;; A valid GFM header-only table has no body rows; FO requires a
  ;; non-empty fo:table-body, so the header is rendered as the body rather
  ;; than emitting an empty body that FOP rejects.
  (let [out    (ex [:table [:thead [:tr [:th "A"] [:th "B"]]]])
        bodies (tag-children :fo/table-body out)]
    (is (= 1 (count bodies)))
    (is (seq (tag-children :fo/table-row (first bodies)))
        "the table-body has at least one row")
    (is (empty? (tag-children :fo/table-header out))
        "no separate, empty header remains")))

(deftest table-with-body-keeps-its-header
  (let [out (ex [:table [:thead [:tr [:th "A"]]] [:tbody [:tr [:td "1"]]]])]
    (is (= 1 (count (tag-children :fo/table-header out))))
    (is (= 1 (count (tag-children :fo/table-body out))))))

(deftest table-with-no-rows-is-a-clean-error
  (let [d (catch-data #(ex [:table]))]
    (is (= :smia.fo.expand/empty-table (:error/type d)))))

(deftest nested-inline-inside-block
  (let [out (ex [:p "a " [:strong "b"] " c"])]
    (is (= :fo/block (first out)))
    (is (some #(= [:fo/inline {:font-weight "bold"} "b"] %) out))))

(deftest headings-carry-style-and-id
  (let [out (ex [:h2 {:id :intro} "Title"])
        attrs (second out)]
    (is (= :fo/block (first out)))
    (is (= "bold" (:font-weight attrs)))
    (is (= "intro" (:id attrs)))))

(deftest raw-fo-passes-through
  (testing "a :fo/* tag is emitted verbatim, attrs preserved"
    (is (= [:fo/block {:space-before "12pt"} "raw"]
           (ex [:fo/block {:space-before "12pt"} "raw"])))))

(deftest sugar-nested-inside-raw-fo-still-expands
  (let [out (ex [:fo/block {:role "x"} [:strong "b"]])]
    (is (= :fo/block (first out)))
    (is (= {:role "x"} (second out)))
    (is (= [:fo/inline {:font-weight "bold"} "b"] (last out)))))

(deftest unordered-list-expands-to-list-block
  (let [out (ex [:ul [:li "one"] [:li "two"]])]
    (is (= :fo/list-block (first out)))
    (let [items (filter #(and (vector? %) (= :fo/list-item (first %))) out)]
      (is (= 2 (count items))))))

(deftest ordered-list-numbers-items
  (let [xml (ser/serialize (ex [:ol [:li "a"] [:li "b"]])
                           {:xml-declaration? false})]
    (is (str/includes? xml ">1.</fo:block>"))
    (is (str/includes? xml ">2.</fo:block>"))))

(deftest table-expands-with-header-and-columns
  (let [out (ex [:table
                 [:thead [:tr [:th "A"] [:th "B"]]]
                 [:tbody [:tr [:td "1"] [:td "2"]]]])
        cols (filter #(and (vector? %) (= :fo/table-column (first %))) out)]
    (is (= :fo/table (first out)))
    (is (= 2 (count cols)) "one column per cell in the widest row")
    (is (some #(and (vector? %) (= :fo/table-header (first %))) out))
    (is (some #(and (vector? %) (= :fo/table-body (first %))) out))))

(deftest table-columns-default-to-equal-width
  (let [out  (ex [:table [:tr [:td "a"] [:td "b"] [:td "c"]]])
        cols (filter #(and (vector? %) (= :fo/table-column (first %))) out)]
    (is (= 3 (count cols)))
    (is (every? #(= "proportional-column-width(1)" (:column-width (second %)))
                cols)
        "with no :cols, every column is equal width")))

(deftest table-cols-set-proportional-widths
  (let [out  (ex [:table {:cols [3 2 1]}
                  [:tr [:td "a"] [:td "b"] [:td "c"]]])
        cols (mapv #(:column-width (second %))
                   (filter #(and (vector? %) (= :fo/table-column (first %))) out))]
    (is (= ["proportional-column-width(3)"
            "proportional-column-width(2)"
            "proportional-column-width(1)"]
           cols))))

(deftest table-cols-pads-missing-columns-with-one
  (let [out  (ex [:table {:cols [3]}
                  [:tr [:td "a"] [:td "b"] [:td "c"]]])
        cols (mapv #(:column-width (second %))
                   (filter #(and (vector? %) (= :fo/table-column (first %))) out))]
    (is (= ["proportional-column-width(3)"
            "proportional-column-width(1)"
            "proportional-column-width(1)"]
           cols)
        "unspecified trailing columns default to weight 1")))

(deftest table-cols-rejects-non-numeric-weights
  (is (= :smia.fo.expand/invalid-cols
         (:error/type
          (catch-data #(ex [:table {:cols [3 "wide"]}
                            [:tr [:td "a"] [:td "b"]]]))))))

(deftest admonition-expands-to-bordered-block-with-label
  (let [out (ex [:admonition {:kind :warning} [:p "careful"]])
        attrs (second out)]
    (is (= :fo/block (first out)))
    (is (str/includes? (:border attrs) "solid"))
    (is (some #(= "Warning" (last %)) (filter vector? out))
        "the admonition kind becomes a label")))

(deftest callout-boxes-keep-together-on-one-page
  ;; A bordered callout must not break across a page boundary — splitting
  ;; strands the title bar at the foot of one page and the body on the next.
  (testing "admonitions keep together"
    (is (= "always" (:keep-together.within-page
                     (second (ex [:admonition {:kind :note} [:p "x"]]))))))
  (testing "sidebars keep together"
    (is (= "always" (:keep-together.within-page
                     (second (ex [:sidebar {:title "T"} [:p "x"]]))))))
  (testing "overview panels keep together"
    (is (= "always" (:keep-together.within-page
                     (second (ex [:overview [:p "x"]])))))))

(deftest xref-without-body-emits-page-number-citation
  (is (= [:fo/basic-link {:internal-destination "ch-config" :color "#1a0dab"}
          [:fo/page-number-citation {:ref-id "ch-config"}]]
         (ex [:xref {:to :ch-config}]))))

(deftest xref-with-body-uses-body-as-link-text
  (let [out (ex [:xref {:to :ch-config} "Configuration"])]
    (is (= :fo/basic-link (first out)))
    (is (= "Configuration" (last out)))))

(deftest composed-xref-renders-the-resolved-label
  (testing "a numbered target's label becomes the link text"
    (is (= [:fo/basic-link {:internal-destination "ch-config" :color "#1a0dab"}
            "Chapter 2"]
           (ex [:xref {:to :ch-config :label "Chapter 2" :kind :chapter}]))))
  (testing ":style :full appends the title"
    (is (= "Chapter 2: Configuration"
           (nth (ex [:xref {:to :ch-config :label "Chapter 2"
                            :title "Configuration" :style :full}]) 2))))
  (testing ":page appends a page-number citation"
    (let [out (ex [:xref {:to :ch-config :label "Chapter 2" :page true}])]
      (is (= "Chapter 2" (nth out 2)))
      (is (= ", on page " (nth out 3)))
      (is (= [:fo/page-number-citation {:ref-id "ch-config"}] (last out)))))
  (testing "an unnumbered target falls back to its title"
    (is (= "Getting Set Up"
           (last (ex [:xref {:to :setup :title "Getting Set Up" :kind :section}]))))))

(deftest xref-without-target-is-structured-error
  (let [d (catch-data #(ex [:xref {} "x"]))]
    (is (= :smia.fo.expand/invalid-xref (:error/type d)))))

(deftest figure-wraps-image-with-a-numbered-caption
  (let [out (ex [:figure {:id :diagram :label "Figure 1" :caption "A widget"}
                 [:img {:src "w.png"}]])]
    (is (= :fo/block (first out)))
    (is (= "diagram" (:id (second out))))
    (is (some #(and (vector? %) (= :fo/external-graphic (first %)))
              (tree-seq vector? seq out)))
    (is (some #(= "Figure 1. " (last %))
              (filter vector? (tree-seq vector? seq out)))
        "the numbered label leads the caption")
    (is (some #(= "A widget" (last %)) (filter vector? (tree-seq vector? seq out))))))

(deftest figure-float-becomes-an-fo-float-property
  (is (= "start" (:float (second (ex [:figure {:float :start} [:img {:src "x"}]]))))))

(deftest code-listing-adds-a-filename-bar-and-caption
  (let [out (ex [:pre {:lang :clojure :id :ex1 :file "core.clj" :label "Listing 1"
                       :caption "The core"} "(+ 1 2)"])]
    (is (= "ex1" (:id (second out))))
    (is (some #(= "core.clj" (last %)) (filter vector? (tree-seq vector? seq out)))
        "the filename header bar")
    (is (some #(= "Listing 1. " (last %)) (filter vector? (tree-seq vector? seq out))))
    (testing "a plain code block (no file/caption) is unchanged"
      (is (= [:fo/block (get expand/default-style :pre) "code"]
             (ex [:pre "code"]))))))

(deftest annotated-listing-marks-lines-and-emits-a-bound-list
  (let [out (ex [:pre {:lang :clojure :id :ex :caption "Core"
                       :annotations [{:line 1 :note "Defines xs"}
                                     {:line 3 :note [:span "Folds with " [:code "reduce"]]}]}
                 "(def xs [1 2 3])\n;; ...\n(reduce + xs)"])
        inlines (->> (tree-seq vector? seq out)
                     (filter #(and (vector? %) (= :fo/inline (first %)))))]
    (is (= "ex" (:id (second out))))
    (testing "an annotation mark carrying the ordinal appears for each note"
      (is (some #(= "1" (last %)) inlines))
      (is (some #(= "2" (last %)) inlines)))
    (testing "a bound ordered annotation list is emitted"
      (is (some #(and (vector? %) (= :fo/list-block (first %)))
                (tree-seq vector? seq out)))
      (is (some #(= "Defines xs" (last %))
                (filter vector? (tree-seq vector? seq out))))
      (is (some #(= "reduce" (last %)) inlines)
          "rich inline note content is expanded"))
    (testing "the listing is kept together on a page"
      (is (= "always" (:keep-together.within-page (second out)))))))

(deftest annotations-compose-with-the-line-number-gutter
  (let [out (ex [:pre {:lang :clojure :line-numbers true
                       :annotations [{:line 2 :note "here"}]}
                 "a\nb\nc"])
        inlines (->> (tree-seq vector? seq out)
                     (filter #(and (vector? %) (= :fo/inline (first %)))))]
    (is (some #(str/starts-with? (str (last %)) "1") inlines) "gutter line numbers remain")
    (is (some #(= "1" (last %)) inlines) "the single annotation mark is present")))

(deftest annotation-referencing-a-missing-line-is-a-structured-error
  (let [d (catch-data #(ex [:pre {:lang :clojure :annotations [{:line 9 :note "x"}]}
                            "(+ 1 2)"]))]
    (is (= :smia.fo.expand/invalid-annotation (:error/type d)))))

(deftest two-annotations-on-one-line-is-a-structured-error
  (let [d (catch-data #(ex [:pre {:annotations [{:line 1 :note "a"} {:line 1 :note "b"}]}
                            "one\ntwo"]))]
    (is (= :smia.fo.expand/invalid-annotation (:error/type d)))))

(deftest code-highlighting-colors-tokens-when-enabled
  (let [style (assoc expand/default-style :highlight? true
                     :code-colors {:keyword "#00f" :string "#080"})
        out   (expand/expand [:pre {:lang :clojure} "(defn f \"s\")"] style)]
    (is (some #(and (vector? %) (= :fo/inline (first %))
                    (= "#00f" (:color (second %))) (= "defn" (last %)))
              (tree-seq vector? seq out))
        "the keyword 'defn' is colored")
    (testing "highlighting off leaves a single plain string child"
      (is (= [:fo/block (get expand/default-style :pre) "(defn f \"s\")"]
             (expand/expand [:pre {:lang :clojure} "(defn f \"s\")"]
                            expand/default-style))))))

(deftest line-numbers-add-a-gutter
  (let [out (expand/expand [:pre {:lang :clojure :line-numbers true} "a\nb\nc"]
                           expand/default-style)
        nums (->> (tree-seq vector? seq out)
                  (filter #(and (vector? %) (= :fo/inline (first %))))
                  (map last))]
    (is (some #(str/starts-with? (str %) "1") nums))
    (is (some #(str/starts-with? (str %) "3") nums))))

(deftest captioned-table-wraps-with-a-caption
  (let [out (ex [:table {:id :grid :label "Table 1" :caption "A grid"}
                 [:tr [:td "x"]]])]
    (is (= :fo/block (first out)))
    (is (= "grid" (:id (second out))))
    (is (some #(and (vector? %) (= :fo/table (first %))) (tree-seq vector? seq out)))
    (is (some #(= "Table 1. " (last %)) (filter vector? (tree-seq vector? seq out))))))

(deftest sidebar-has-an-arbitrary-title
  (let [out (ex [:sidebar {:title "On Determinism"} [:p "Stuff."]])]
    (is (= :fo/block (first out)))
    (is (some #(= "On Determinism" (last %)) (filter vector? out)))))

(deftest sidebar-icon-prefixes-the-title
  (let [out (ex [:sidebar {:title "Heads up" :icon "!"} [:p "x"]])]
    (is (some #(= "! Heads up" (last %)) (filter vector? out)))))

(deftest epigraph-renders-quote-and-attribution
  (let [out (ex [:epigraph {:attribution "A. Hacker"} [:p "Make it work."]])]
    (is (= :fo/block (first out)))
    (is (= "italic" (:font-style (second out))))
    (is (some #(= "— A. Hacker" (last %)) (filter vector? (tree-seq vector? seq out))))))

(deftest cite-links-to-the-bibliography-entry
  (testing "a bare key links to ref-<key> with the key as text"
    (is (= [:fo/basic-link {:internal-destination "ref-smith2020" :color "#1a0dab"}
            "smith2020"]
           (ex [:cite {:key :smith2020}]))))
  (testing "a resolved cite uses its label and ref-id"
    (is (= [:fo/basic-link {:internal-destination "ref-smith2020" :color "#1a0dab"}
            "Smith 2020"]
           (ex [:cite {:key :smith2020 :label "Smith 2020" :ref-id "ref-smith2020"}])))))

(deftest index-mark-is-an-anchor-with-its-id
  (is (= [:fo/inline {:id "idx-3"}] (ex [:index {:term "Determinism" :id "idx-3"}]))))

(deftest overview-renders-a-labeled-panel
  (let [out (ex [:overview [:ul [:li "The pipeline"] [:li "Where to start"]]])]
    (is (= :fo/block (first out)))
    (testing "a default bold 'Overview' label leads the panel"
      (is (some #(and (vector? %) (= "bold" (:font-weight (second %)))
                      (= "Overview" (last %)))
                out)))
    (testing "the body is expanded into the panel"
      (is (some #(and (vector? %) (= :fo/list-block (first %)))
                (tree-seq vector? seq out))))))

(deftest overview-title-overrides-the-label
  (let [out (ex [:overview {:title "In this chapter"} [:p "Stuff."]])]
    (is (some #(and (vector? %) (= "In this chapter" (last %))) out))
    (is (not (some #(= "Overview" (last %)) (filter vector? out)))
        "the default label is replaced, not duplicated")))

(deftest description-list-renders-terms-and-definitions
  (let [out (ex [:dl
                 [:dt "Manuscript"] [:dd "The normalized document structure."]
                 [:dt "Profile"]    [:dd "A layout variant."]])
        blocks (filter #(and (vector? %) (= :fo/block (first %))) out)]
    (is (= :fo/block (first out)))
    (is (= 4 (count blocks)) "one block per term and per definition, in order")
    (testing "terms are bold"
      (is (some #(and (= "bold" (:font-weight (second %)))
                      (= "Manuscript" (last %)))
                blocks)))
    (testing "definitions are indented"
      (is (some #(and (:start-indent (second %))
                      (= "The normalized document structure." (last %)))
                blocks)))
    (testing "order is preserved (term then its definition)"
      (is (= ["Manuscript" "The normalized document structure."
              "Profile" "A layout variant."]
             (map last blocks))))))

(deftest description-list-definitions-keep-inline-markup
  (let [out (ex [:dl [:dt "reduce"] [:dd "Folds with " [:code "reduce"] "."]])]
    (is (some #(and (vector? %) (= :fo/inline (first %)) (= "reduce" (last %)))
              (tree-seq vector? seq out))
        "inline markup in a definition is expanded")))

(deftest page-break-forces-a-break-before
  (is (= [:fo/block {:break-before "page"}] (ex [:page-break]))))

(deftest keep-together-wraps-its-body
  (let [out (ex [:keep-together [:p "a"] [:p "b"]])]
    (is (= :fo/block (first out)))
    (is (= "always" (:keep-together.within-page (second out))))
    (is (= 2 (count (filter #(and (vector? %) (= :fo/block (first %))) out))))))

(deftest unknown-tag-is-structured-error
  (let [d (catch-data #(ex [:marquee "no"]))]
    (is (= :smia.fo.expand/unknown-tag (:error/type d)))
    (is (= :marquee (:tag (:error/context d))))))

(deftest html-hatch-in-pdf-edition-is-structured-error
  (let [d (catch-data #(ex [:html/aside {:class "x"} "no"]))]
    (is (= :smia.fo.expand/html-tag-in-pdf (:error/type d)))
    (is (= :html/aside (:tag (:error/context d))))))

(deftest linkless-style-renders-references-as-plain-text
  ;; PDF/X forbids link annotations in the printable area; the press
  ;; edition keeps the words and page citations and drops the links.
  (let [linkless (assoc expand/default-style :links? false)]
    (testing "a childless xref keeps its label and page citation"
      (is (= [:fo/inline "Chapter 2" ", on page "
              [:fo/page-number-citation {:ref-id "ch-config"}]]
             (expand/expand [:xref {:to :ch-config :label "Chapter 2"
                                    :page true}]
                            linkless))))
    (testing "an xref with a body keeps the body"
      (is (= [:fo/inline "the config"]
             (expand/expand [:xref {:to :ch-config} "the config"] linkless))))
    (testing "a citation keeps its label"
      (is (= [:fo/inline "Smith 2020"]
             (expand/expand [:cite {:key :smith :label "Smith 2020"}]
                            linkless))))
    (testing "an external link keeps its text"
      (let [out (expand/expand [:a {:href "https://x.example"} "the site"]
                               linkless)]
        (is (= :fo/inline (first out)))
        (is (= "the site" (last out)))
        (is (nil? (:external-destination (second out))))))
    (testing "no basic-link is emitted anywhere"
      (doseq [node [[:xref {:to :x :label "L"}]
                    [:cite {:key :k}]
                    [:a {:href "https://x"} "t"]]]
        (is (empty? (filter #(and (vector? %) (= :fo/basic-link (first %)))
                            (tree-seq vector? seq (expand/expand node linkless)))))))))

(deftest code-block-preserves-pre-whitespace-through-serialization
  (let [xml (ser/serialize (ex [:pre "(defn f [x]\n  x)"])
                           {:xml-declaration? false})]
    (is (str/includes? xml "white-space=\"pre\""))
    (is (str/includes? xml "(defn f [x]\n  x)"))))

(deftest style-override-changes-properties
  (let [out (expand/expand [:p "x"] (assoc-in expand/default-style
                                              [:p :color] "red"))]
    (is (= "red" (:color (second out))))))

(deftest expansion-is-referentially-transparent
  (let [node [:chapter-ish [:p "x"]]]
    ;; unknown tag both times -> same structured failure, no state
    (is (= (catch-data #(ex node)) (catch-data #(ex node))))))

;; --- math -----------------------------------------------------------------------

(def ^:private math-svg
  [:svg {:height "20" :width "34" :xmlns "http://www.w3.org/2000/svg"}
   [:path {:d "M0 0"}]])

(deftest inline-math-becomes-an-instream-foreign-object
  (is (= [:fo/instream-foreign-object {:alignment-adjust "middle"} math-svg]
         (ex [:math {:notation "x^2" :svg math-svg}]))))

(deftest display-math-is-a-centered-block
  (let [out (ex [:math {:notation "x^2" :display true :id :sq :svg math-svg}])]
    (is (= :fo/block (first out)))
    (is (= "center" (:text-align (second out))))
    (is (= "sq" (:id (second out))))
    (is (= :fo/instream-foreign-object (first (nth out 2))))))

(deftest math-without-rendered-svg-names-the-alias
  (let [d (catch-data #(ex [:math {:notation "x^2"}]))]
    (is (= :smia.math/renderer-unavailable (:error/type d)))))

(deftest math-svg-serializes-verbatim-inside-the-fo
  (let [xml (ser/serialize (ex [:math {:notation "x" :svg math-svg}])
                           {:xml-declaration? false})]
    (is (str/includes? xml "<svg"))
    (is (str/includes? xml "xmlns=\"http://www.w3.org/2000/svg\""))))

;; --- diagrams -------------------------------------------------------------------

(deftest diagram-becomes-a-centered-instream-foreign-object
  (let [svg [:svg {:height "60" :width "120"} [:path {:d "M0 0"}]]
        out (ex [:diagram {:source "A -> B" :alt "x" :id :flow :svg svg}])]
    (is (= :fo/block (first out)))
    (is (= "center" (:text-align (second out))))
    (is (= "flow" (:id (second out))))
    (is (= [:fo/instream-foreign-object {} svg] (nth out 2)))))

(deftest diagram-without-rendered-svg-names-the-alias
  (let [d (catch-data #(ex [:diagram {:source "A -> B"}]))]
    (is (= :smia.diagram/renderer-unavailable (:error/type d)))))
