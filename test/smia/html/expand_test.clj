(ns smia.html.expand-test
  (:require
   [smia.error :as error]
   [smia.html.expand :as html-expand]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [thunk]
  (try (thunk) nil (catch Exception e (error/data e))))

(def ^:private ctx
  {:resolve (fn [id] (str "resolved.html#" id))})

(defn- find-all
  "Every vector node in `tree` whose tag is `tag`."
  [tree tag]
  (filter #(and (vector? %) (= tag (first %)))
          (tree-seq vector? seq tree)))

;; --- HTML sugar passes through as clean HTML --------------------------------

(deftest paragraph-and-inlines-pass-through
  (is (= [:p {} "a " [:strong {} "b"] " " [:em {} "c"]]
         (html-expand/expand [:p "a " [:strong "b"] " " [:em "c"]] ctx))))

(deftest interface-vocabulary-inline-tags-render
  (testing "real HTML inline elements pass through"
    (is (= [:kbd {} "Enter"] (html-expand/expand [:kbd "Enter"] ctx)))
    (is (= [:mark {} "x"] (html-expand/expand [:mark "x"] ctx)))
    (is (= [:sub {} "2"] (html-expand/expand [:sub "2"] ctx)))
    (is (= [:sup {} "n"] (html-expand/expand [:sup "n"] ctx))))
  (testing ":menu renders a classed path with separator spans"
    (is (= [:span {:class "menu"}
            "File" [:span {:class "menu-sep"} " ▸ "] "Export"]
           (html-expand/expand [:menu "File" "Export"] ctx))))
  (testing ":button renders a classed span (not an interactive control)"
    (is (= [:span {:class "button"} "Save"]
           (html-expand/expand [:button "Save"] ctx)))))

(deftest richer-blocks-render
  (testing ":example is a classed div with an optional title"
    (is (= [:div {:class "example"} [:div {:class "example-title"} "Worked"]
            [:p {} "x"]]
           (html-expand/expand [:example {:title "Worked"} [:p "x"]] ctx))))
  (testing ":details is a closed disclosure with a summary"
    (is (= [:details {} [:summary {} "More"] [:p {} "x"]]
           (html-expand/expand [:details {:summary "More"} [:p "x"]] ctx))))
  (testing ":open is the same disclosure, rendered open"
    (is (= [:details {:open "open"} [:summary {} "Details"] [:p {} "x"]]
           (html-expand/expand [:open [:p "x"]] ctx)))))

(deftest heading-keeps-its-anchor-id
  (is (= [:h2 {:id "sec-a"} "1.2 " "Title"]
         (html-expand/expand [:h2 {:id :sec-a} "1.2 " "Title"] ctx))))

(deftest book-attrs-do-not-leak-into-html
  (testing "numbering-pass stamps like :number/:label stay out of attrs"
    (let [out (html-expand/expand
                [:table {:id "t1" :number "1" :label "Table 1" :cols [2 1]}
                 [:tr [:td "x"]]]
                ctx)]
      (is (= {:id "t1"} (second out))))))

(deftest lists-and-definition-lists-pass-through
  (is (= [:ul {} [:li {} "one"] [:li {} "two"]]
         (html-expand/expand [:ul [:li "one"] [:li "two"]] ctx)))
  (is (= [:dl {} [:dt {} "t"] [:dd {} "d"]]
         (html-expand/expand [:dl [:dt "t"] [:dd "d"]] ctx))))

(deftest external-link-keeps-href
  (is (= [:a {:href "https://x.example"} "x"]
         (html-expand/expand [:a {:href "https://x.example"} "x"] ctx))))

(deftest br-and-hr-are-void
  (is (= [:br {}] (html-expand/expand [:br] ctx)))
  (is (= [:hr {}] (html-expand/expand [:hr] ctx))))

(deftest seqs-among-children-are-flattened
  (is (= [:ul {} [:li {} "a"] [:li {} "b"]]
         (html-expand/expand [:ul (list [:li "a"] [:li "b"])] ctx))))

;; --- images require alt text ------------------------------------------------

(deftest img-with-alt-passes
  (is (= [:img {:src "cat.png" :alt "a cat"}]
         (html-expand/expand [:img {:src "cat.png" :alt "a cat"}] ctx))))

(deftest img-with-empty-alt-is-decorative-and-allowed
  (is (= [:img {:src "rule.png" :alt ""}]
         (html-expand/expand [:img {:src "rule.png" :alt ""}] ctx))))

(deftest img-without-alt-throws
  (let [d (catch-data #(html-expand/expand [:img {:src "cat.png"}] ctx))]
    (is (= :smia.html.expand/missing-alt-text (:error/type d)))
    (is (= "cat.png" (:src (:error/context d))))))

;; --- figures, tables, captions ----------------------------------------------

(deftest figure-emits-figcaption-with-label
  (let [out (html-expand/expand
              [:figure {:id "fig-1" :label "Figure 1" :caption "A cat"}
               [:img {:src "cat.png" :alt "a cat"}]]
              ctx)]
    (is (= :figure (first out)))
    (is (= {:id "fig-1"} (second out)))
    (is (= [[:figcaption {}
             [:span {:class "caption-label"} "Figure 1. "] "A cat"]]
           (find-all out :figcaption)))))

(deftest captioned-table-leads-with-caption-element
  (let [out (html-expand/expand
              [:table {:id "tbl-1" :label "Table 1" :caption "Sizes"}
               [:thead [:tr [:th "a"]]]
               [:tbody [:tr [:td "1"]]]]
              ctx)]
    (is (= :caption (first (nth out 2)))
        "the <caption> is the table's first child")
    (is (seq (find-all out :thead)))
    (is (seq (find-all out :tbody)))))

;; --- code listings ------------------------------------------------------------

(deftest plain-pre-wraps-code-with-language-class
  (is (= [:pre {} [:code {:class "language-clojure"} "(+ 1 2)"]]
         (html-expand/expand [:pre {:lang :clojure} "(+ 1 2)"] ctx))))

(deftest highlighting-emits-token-spans
  (let [out (html-expand/expand [:pre {:lang :clojure} "(def x \"s\")"]
                                (assoc ctx :highlight? true))
        spans (find-all out :span)]
    (is (some #(= {:class "tok-keyword"} (second %)) spans))
    (is (some #(= {:class "tok-string"} (second %)) spans))))

(deftest listing-with-file-bar-and-caption
  (let [out (html-expand/expand
              [:pre {:id "lst-1" :label "Listing 1" :caption "Adder"
                     :file "src/x.clj" :lang :clojure}
               "(+ 1 2)"]
              ctx)]
    (is (= :figure (first out)))
    (is (= {:class "listing" :id "lst-1"} (second out)))
    (is (= [[:div {:class "file-bar"} "src/x.clj"]] (find-all out :div)))
    (is (seq (find-all out :figcaption)))))

(deftest annotated-listing-emits-marks-and-notes
  (let [out (html-expand/expand
              [:pre {:annotations [{:line 1 :note "the sum"}]}
               "(+ 1 2)\n(- 3 4)"]
              ctx)
        sups (find-all out :sup)
        ols  (find-all out :ol)]
    (is (some #(= {:class "annotation-mark"} (second %)) sups))
    (is (= 1 (count ols)))
    (is (= "annotations" (:class (second (first ols)))))))

(deftest annotation-outside-line-range-throws
  (let [d (catch-data
            #(html-expand/expand
               [:pre {:annotations [{:line 9 :note "x"}]} "one line"]
               ctx))]
    (is (= :smia.html.expand/invalid-annotation (:error/type d)))))

(deftest line-numbers-emit-a-gutter
  (let [out (html-expand/expand
              [:pre {:line-numbers true} "a\nb"]
              ctx)]
    (is (= 2 (count (filter #(= {:class "line-no"} (second %))
                            (find-all out :span)))))))

;; --- book extensions ----------------------------------------------------------

(deftest admonition-is-an-aside-with-role-note
  (let [out (html-expand/expand
              [:admonition {:kind :warning} [:p "look out"]]
              ctx)]
    (is (= :aside (first out)))
    (is (= {:class "admonition warning" :role "note"} (second out)))
    (is (= [[:div {:class "admonition-title"} "Warning"]]
           (find-all out :div)))))

(deftest admonition-defaults-to-note
  (let [out (html-expand/expand [:admonition [:p "x"]] ctx)]
    (is (= "admonition note" (:class (second out))))))

(deftest sidebar-with-title-bar
  (let [out (html-expand/expand
              [:sidebar {:title "History"} [:p "x"]]
              ctx)]
    (is (= :aside (first out)))
    (is (= "sidebar" (:class (second out))))
    (is (= [[:div {:class "sidebar-title"} "History"]] (find-all out :div)))))

(deftest overview-has-a-default-label
  (let [out (html-expand/expand [:overview [:p "x"]] ctx)]
    (is (= "overview" (:class (second out))))
    (is (= [[:div {:class "overview-title"} "Overview"]] (find-all out :div))))
  (testing "an explicit nil title drops the label"
    (let [out (html-expand/expand [:overview {:title nil} [:p "x"]] ctx)]
      (is (empty? (find-all out :div))))))

(deftest epigraph-is-a-blockquote-with-attribution
  (let [out (html-expand/expand
              [:epigraph {:attribution "Rich"} [:p "Simple."]]
              ctx)]
    (is (= :blockquote (first out)))
    (is (= "epigraph" (:class (second out))))
    (is (= [[:footer {:class "attribution"} "— Rich"]]
           (find-all out :footer)))))

;; --- cross-references, citations, index, footnotes ----------------------------

(deftest childless-xref-composes-resolved-label
  (is (= [:a {:class "xref" :href "resolved.html#ch-two"} "Chapter 2"]
         (html-expand/expand
           [:xref {:to :ch-two :label "Chapter 2" :kind :chapter}] ctx))))

(deftest full-style-xref-includes-title
  (is (= [:a {:class "xref" :href "resolved.html#ch-two"} "Chapter 2: Setup"]
         (html-expand/expand
           [:xref {:to :ch-two :label "Chapter 2" :title "Setup" :style :full}]
           ctx))))

(deftest xref-with-children-keeps-them
  (is (= [:a {:class "xref" :href "resolved.html#ch-two"} "the setup chapter"]
         (html-expand/expand
           [:xref {:to :ch-two :label "Chapter 2"} "the setup chapter"] ctx))))

(deftest xref-without-target-throws
  (let [d (catch-data #(html-expand/expand [:xref {}] ctx))]
    (is (= :smia.html.expand/invalid-xref (:error/type d)))))

(deftest cite-links-to-bibliography-entry
  (is (= [:a {:class "cite" :href "resolved.html#ref-smith-2020"} "Smith 2020"]
         (html-expand/expand
           [:cite {:key :smith-2020 :label "Smith 2020"
                   :ref-id "ref-smith-2020"}]
           ctx))))

(deftest cite-without-key-throws
  (let [d (catch-data #(html-expand/expand [:cite {}] ctx))]
    (is (= :smia.html.expand/invalid-cite (:error/type d)))))

(deftest index-mark-is-an-empty-anchor-span
  (is (= [:span {:id "idx-3"}]
         (html-expand/expand [:index {:id "idx-3" :term "cats"}] ctx))))

(deftest numbered-footnote-becomes-a-noteref
  (is (= [:a {:class "noteref" :role "doc-noteref"
              :id "fnref-2" :href "#fn-2"}
          [:sup {} "2"]]
         (html-expand/expand [:footnote {:n 2} "ignored here"] ctx))))

(deftest unnumbered-footnote-throws
  (let [d (catch-data #(html-expand/expand [:footnote "x"] ctx))]
    (is (= :smia.html.expand/unnumbered-footnote (:error/type d)))))

;; --- page furniture -------------------------------------------------------------

(deftest page-break-and-keep-together-are-classed-divs
  (is (= [:div {:class "page-break"}]
         (html-expand/expand [:page-break] ctx)))
  (is (= [:div {:class "keep-together" :id "kt"} [:p {} "x"]]
         (html-expand/expand [:keep-together {:id "kt"} [:p "x"]] ctx))))

;; --- escape hatches ---------------------------------------------------------------

(deftest html-hatch-passes-through-with-namespace-stripped
  (is (= [:aside {:class "custom"} [:p {} "x"]]
         (html-expand/expand [:html/aside {:class "custom"} [:p "x"]] ctx))))

(deftest fo-hatch-in-html-edition-throws
  (let [d (catch-data #(html-expand/expand [:fo/block "x"] ctx))]
    (is (= :smia.html.expand/fo-tag-in-html (:error/type d)))
    (is (= :fo/block (:tag (:error/context d))))))

(deftest unknown-tag-throws
  (let [d (catch-data #(html-expand/expand [:marquee "x"] ctx))]
    (is (= :smia.html.expand/unknown-tag (:error/type d)))))

;; --- math -----------------------------------------------------------------------

(def ^:private math-svg
  [:svg {:height "20" :width "34" :xmlns "http://www.w3.org/2000/svg"}
   [:path {:d "M0 0"}]])

(deftest inline-math-emits-the-rendered-svg
  (is (= [:svg {:height "20" :width "34" :xmlns "http://www.w3.org/2000/svg"
                :role "img" :aria-label "x^2" :class "math"}
          [:path {:d "M0 0"}]]
         (html-expand/expand [:math {:notation "x^2" :svg math-svg}] ctx))))

(deftest display-math-is-a-centered-block
  (let [out (html-expand/expand
              [:math {:notation "x^2" :display true :id :sq :svg math-svg}] ctx)]
    (is (= :div (first out)))
    (is (= {:class "math-display" :id "sq"} (second out)))
    (is (= :svg (first (nth out 2))))))

(deftest math-without-rendered-svg-names-the-alias
  (let [d (catch-data #(html-expand/expand [:math {:notation "x^2"}] ctx))]
    (is (= :smia.math/renderer-unavailable (:error/type d)))))

;; --- diagrams -------------------------------------------------------------------

(def ^:private diagram-svg
  [:svg {:height "60" :width "120" :xmlns "http://www.w3.org/2000/svg"}
   [:path {:d "M0 0"}]])

(deftest diagram-emits-a-classed-block-with-the-rendered-svg
  (is (= [:div {:class "diagram" :id "flow"}
          [:svg {:height "60" :width "120" :xmlns "http://www.w3.org/2000/svg"
                 :role "img" :aria-label "A calls B" :class "diagram"}
           [:path {:d "M0 0"}]]]
         (html-expand/expand
           [:diagram {:source "A -> B" :alt "A calls B" :id :flow
                      :svg diagram-svg}] ctx))))

(deftest diagram-without-alt-text-throws
  (let [d (catch-data #(html-expand/expand
                         [:diagram {:source "A -> B" :svg diagram-svg}] ctx))]
    (is (= :smia.html.expand/missing-alt-text (:error/type d)))))

(deftest diagram-without-rendered-svg-names-the-alias
  (let [d (catch-data #(html-expand/expand
                         [:diagram {:source "A -> B" :alt "x"}] ctx))]
    (is (= :smia.diagram/renderer-unavailable (:error/type d)))))
