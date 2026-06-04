(ns smia.theme.css-test
  (:require
   [smia.theme.css :as css]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private tokens
  {:color   {:text "#1c1c1c" :link "#2a52be" :muted "#666666"
             :rule "#bbbbbb" :code-background "#f4f4f4"}
   :type    {:body-family "serif" :heading-family "sans-serif"
             :mono-family "monospace" :base-size "11pt" :line-height "1.45"
             :h1-size "22pt" :h2-size "16pt" :h3-size "13pt"
             :highlight true}
   :code    {:keyword "#0033cc" :string "#008800"}
   :spacing {:paragraph "6pt" :block "8pt"}
   :layout  {:page-size :a4}})

(defn- rule
  "The prop map of the first rule whose selector is `selector`."
  [rules selector]
  (some (fn [[s props]] (when (= s selector) props)) rules))

(deftest body-rule-carries-the-type-and-color-tokens
  (let [body (rule (css/compile-css tokens) "body")]
    (is (= "serif" (:font-family body)))
    (is (= "11pt" (:font-size body)))
    (is (= "1.45" (:line-height body)))
    (is (= "#1c1c1c" (:color body)))))

(deftest headings-use-the-heading-family-and-sizes
  (let [rules (css/compile-css tokens)]
    (is (= "sans-serif" (:font-family (rule rules "h1, h2, h3, h4, h5, h6"))))
    (is (= "22pt" (:font-size (rule rules "h1"))))
    (is (= "16pt" (:font-size (rule rules "h2"))))))

(deftest links-use-the-link-color
  (is (= "#2a52be" (:color (rule (css/compile-css tokens) "a")))))

(deftest token-classes-merge-the-code-palette-over-defaults
  (let [rules (css/compile-css tokens)]
    (testing "overridden by the book's :code group"
      (is (= "#0033cc" (:color (rule rules ".tok-keyword"))))
      (is (= "#008800" (:color (rule rules ".tok-string")))))
    (testing "defaults fill the rest of the palette"
      (is (= "#888888" (:color (rule rules ".tok-comment")))))))

(deftest book-extension-idioms-are-styled
  (let [rules     (css/compile-css tokens)
        selectors (set (map first rules))]
    (doseq [s [".admonition" ".sidebar" ".overview" ".epigraph"
               ".file-bar" ".annotations" ".footnotes"
               "figure" "figcaption" "table" ".toc-list" ".page-nav"
               ".downloads" ".downloads .default"]]
      (is (contains? selectors s) (str s " has a rule")))))

(deftest sidebar-layout-selectors-are-styled
  (let [rules     (css/compile-css tokens)
        selectors (set (map first rules))]
    (doseq [s [".book-layout" ".book-sidebar" ".book-sidebar-title"
               ".book-sidebar-list" ".book-sidebar .current" ".book-content"]]
      (is (contains? selectors s) (str s " has a rule")))
    (testing "the rail uses flex-wrap so no @media query is emitted"
      (is (= "wrap" (:flex-wrap (rule rules ".book-layout"))))
      (is (not (str/includes? (css/css tokens) "@media"))))))

(deftest print-break-rules-cover-the-page-furniture
  (let [rules (css/compile-css tokens)]
    (is (= "page" (:break-before (rule rules ".page-break"))))
    (is (= "avoid" (:break-inside (rule rules ".keep-together"))))))

(deftest serialize-sorts-properties-for-determinism
  (testing "two prop maps with different insertion orders serialize equally"
    (is (= (css/serialize [["p" (array-map :color "#111" :margin "0")]])
           (css/serialize [["p" (array-map :margin "0" :color "#111")]])))))

(deftest serialize-formats-rules
  (is (= "p {\n  color: #111;\n  margin: 0;\n}\n"
         (css/serialize [["p" {:margin "0" :color "#111"}]]))))

(deftest css-is-deterministic-end-to-end
  (is (= (css/css tokens) (css/css tokens)))
  (is (str/includes? (css/css tokens) "body {")))

(deftest user-css-group-appends-after-the-generated-rules
  (let [themed (assoc tokens :css [["p.fancy" {:color "#bada55"}]
                                   [".hero" {:padding "2em"}]])
        rules  (css/compile-css themed)]
    (is (= [".hero" {:padding "2em"}] (last rules)))
    (is (= ["p.fancy" {:color "#bada55"}] (last (butlast rules))))
    (is (str/ends-with? (css/css themed) ".hero {\n  padding: 2em;\n}\n"))))

(deftest css-hatch-accepts-at-rule-wrappers
  (testing "a rule whose tail is more rules serializes as a wrapped block"
    (is (= (str "@media (max-width: 40em) {\n"
                "p {\n  margin: 0;\n}\n"
                "\n"
                ".hero {\n  padding: 1em;\n}\n"
                "}\n")
           (css/serialize [["@media (max-width: 40em)"
                            ["p" {:margin "0"}]
                            [".hero" {:padding "1em"}]]]))))
  (testing "wrapped rules pass through compile-css and stay deterministic"
    (let [themed (assoc tokens :css [["@media print" [".no-print" {:display "none"}]]])]
      (is (= ["@media print" [".no-print" {:display "none"}]]
             (last (css/compile-css themed))))
      (is (= (css/css themed) (css/css themed)))
      (is (str/includes? (css/css themed)
                         "@media print {\n.no-print {\n  display: none;\n}\n}\n")))))

(deftest dark-mode-is-opt-in-and-honors-the-os-setting
  (testing "a book without :site {:dark} emits no dark block"
    (is (not (str/includes? (css/css tokens) "prefers-color-scheme"))))
  (testing ":site {:dark true} emits a prefers-color-scheme media block"
    (let [themed (assoc tokens :site {:dark true})
          out    (css/css themed)]
      (is (str/includes? out "@media (prefers-color-scheme: dark)"))
      (is (str/includes? out "background-color: #1a1a1a")
          "the computed dark background is used by default")))
  (testing "a :dark token group overrides the computed palette"
    (let [themed (assoc tokens :site {:dark true} :dark {:background "#000000"})]
      (is (str/includes? (css/css themed) "background-color: #000000"))))
  (testing "dark CSS stays deterministic"
    (let [themed (assoc tokens :site {:dark true})]
      (is (= (css/css themed) (css/css themed))))))
