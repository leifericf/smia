(ns clj-book.md.compile-test
  (:require
   [clj-book.error :as error]
   [clj-book.fo.expand :as expand]
   [clj-book.fo.schema :as fo-schema]
   [clj-book.md.compile :as compile]
   [clj-book.md.parse :as parse]
   [clojure.test :refer [deftest is testing]]))

(defn- md->chapter [s] (compile/compile (parse/parse s "t.md")))
(defn- md->body [s] (let [[_ _ & body] (md->chapter s)] (vec body)))
(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest first-h1-becomes-title-and-leaves-body
  (let [[tag attrs & body] (md->chapter "# The Title\n\nProse.\n")]
    (is (= :chapter tag))
    (is (= "The Title" (:title attrs)))
    (is (= [[:p "Prose."]] (vec body)) "the H1 is not duplicated in the body")))

(deftest no-h1-yields-no-title
  (let [[_ attrs] (md->chapter "Just prose.\n")]
    (is (not (contains? attrs :title)))))

(deftest paragraphs-and-inline-markup
  (is (= [[:p "Plain " [:strong "bold"] " " [:em "italic"] " " [:code "x"] "."]]
         (md->body "Plain **bold** *italic* `x`.\n"))))

(deftest headings-map-to-h-tags
  (is (= [[:h2 "Two"] [:h3 "Three"]]
         (md->body "## Two\n\n### Three\n"))))

(deftest bullet-and-ordered-lists
  (is (= [[:ul [:li "a"] [:li "b"]]] (md->body "- a\n- b\n")))
  (is (= [[:ol [:li "one"] [:li "two"]]] (md->body "1. one\n2. two\n"))))

(deftest nested-lists-keep-block-structure
  (let [[ul] (md->body "- a\n    - b\n")]
    (is (= :ul (first ul)))
    ;; the outer item keeps its paragraph + the nested list as blocks
    (is (some #(and (vector? %) (= :ul (first %)))
              (tree-seq vector? seq ul)))))

(deftest blockquote-link-image-hr
  (is (= [[:blockquote [:p "quoted"]]] (md->body "> quoted\n")))
  (is (= [[:p [:a {:href "https://e.com"} "x"]]] (md->body "[x](https://e.com)\n")))
  (is (= [[:p [:img {:src "a.png" :alt "alt"}]]] (md->body "![alt](a.png)\n")))
  (is (= [[:hr]] (md->body "---\n"))))

(deftest soft-line-break-becomes-space
  (is (= [[:p "one" " " "two"]] (md->body "one\ntwo\n"))))

(deftest compiled-body-is-vocabulary-valid-and-expands
  (let [body (md->body "## H\n\nText **b** and `c`.\n\n- x\n- y\n\n> q\n\n---\n")]
    (is (every? fo-schema/valid? body))
    (is (vector? (mapv expand/expand body)))))

(deftest raw-html-is-a-structured-error-with-position
  (let [d (catch-data #(md->body "<div>nope</div>\n"))]
    (is (= :clj-book.md.compile/unsupported-node (:error/type d)))
    (is (= :html-block (get-in d [:error/context :node-type])))
    (is (map? (get-in d [:error/context :pos])))))

(deftest compile-requires-a-document-node
  (let [d (catch-data #(compile/compile {:type :paragraph}))]
    (is (= :clj-book.md.compile/not-a-document (:error/type d)))))
