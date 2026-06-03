(ns clj-book.fo.expand-test
  (:require
   [clj-book.error :as error]
   [clj-book.fo.expand :as expand]
   [clj-book.fo.serialize :as ser]
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

(deftest pre-validation-attrs-are-inert-to-expansion
  ;; The Markdown front-end tags code blocks with :lang/:test/:include for
  ;; the opt-in validation pass; those attrs must not affect rendering.
  (is (= (ex [:pre "code"])
         (ex [:pre {:lang :clojure :test true} "code"])
         (ex [:pre {:lang :clojure :include "x.clj"} "code"]))))

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
    (is (= :clj-book.fo.expand/empty-table (:error/type d)))))

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
  (is (= :clj-book.fo.expand/invalid-cols
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
    (is (= :clj-book.fo.expand/invalid-xref (:error/type d)))))

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

(deftest page-break-forces-a-break-before
  (is (= [:fo/block {:break-before "page"}] (ex [:page-break]))))

(deftest keep-together-wraps-its-body
  (let [out (ex [:keep-together [:p "a"] [:p "b"]])]
    (is (= :fo/block (first out)))
    (is (= "always" (:keep-together.within-page (second out))))
    (is (= 2 (count (filter #(and (vector? %) (= :fo/block (first %))) out))))))

(deftest unknown-tag-is-structured-error
  (let [d (catch-data #(ex [:marquee "no"]))]
    (is (= :clj-book.fo.expand/unknown-tag (:error/type d)))
    (is (= :marquee (:tag (:error/context d))))))

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
