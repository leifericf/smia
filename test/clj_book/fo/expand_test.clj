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

(deftest xref-without-target-is-structured-error
  (let [d (catch-data #(ex [:xref {} "x"]))]
    (is (= :clj-book.fo.expand/invalid-xref (:error/type d)))))

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
