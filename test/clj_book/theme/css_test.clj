(ns clj-book.theme.css-test
  (:require
   [clj-book.theme.css :as css]
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
