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

;; --- Phase 2: book extensions ---------------------------------------------

(deftest admonition-directive
  (is (= [[:admonition {:kind :tip} [:p "Be " [:strong "careful"] "."]]]
         (md->body ":::admonition {:kind :tip}\nBe **careful**.\n:::\n"))))

(deftest admonition-without-attrs-defaults-to-empty-map
  (is (= [[:admonition {} [:p "Plain note."]]]
         (md->body ":::admonition\nPlain note.\n:::\n"))))

(deftest admonition-survives-a-nested-code-fence
  (let [[adm] (md->body ":::admonition {:kind :note}\nText.\n\n```clojure\n(+ 1 2)\n```\n:::\n")]
    (is (= :admonition (first adm)))
    (is (some #(and (vector? %) (= :pre (first %))) (tree-seq vector? seq adm)))))

(deftest unknown-directive-is-an-error
  (let [d (catch-data #(md->body ":::sidebar {:x 1}\nhi\n:::\n"))]
    (is (= :clj-book.md.compile/unknown-directive (:error/type d)))))

(deftest admonition-kind-must-be-a-keyword
  (let [d (catch-data #(md->body ":::admonition {:kind \"tip\"}\nhi\n:::\n"))]
    (is (= :clj-book.md.compile/invalid-admonition (:error/type d)))))

(deftest gfm-table
  (is (= [[:table {}
           [:thead [:tr [:th "A"] [:th "B"]]]
           [:tbody [:tr [:td "1"] [:td "2"]]]]]
         (md->body "| A | B |\n|---|---|\n| 1 | 2 |\n"))))

(deftest table-cols-from-preceding-edn-line
  (is (= [[:table {:cols [3 1]}
           [:thead [:tr [:th "A"] [:th "B"]]]
           [:tbody [:tr [:td "1"] [:td "2"]]]]]
         (md->body "{:cols [3 1]}\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"))))

(deftest bare-edn-line-not-before-a-table-stays-prose
  ;; A bare map that is not immediately above a table is ordinary content.
  (let [body (md->body "{:cols [3 1]}\n\nJust prose.\n")]
    (is (= 2 (count body)))
    (is (= :p (ffirst body)))))

(deftest footnote-reference-inlines-its-definition
  (is (= [[:p "Text." [:footnote "A note."]]]
         (md->body "Text.[^1]\n\n[^1]: A note.\n"))))

(deftest inline-footnote
  (is (= [[:p "Text" [:footnote "aside"] "."]]
         (md->body "Text^[aside].\n"))))

(deftest undefined-footnote-reference-stays-literal
  ;; CommonMark only emits a footnote reference when a matching definition
  ;; exists; an undefined "[^99]" is left as ordinary text.
  (is (= [[:p "Text.[^99]"]] (md->body "Text.[^99]\n"))))

(deftest link-to-anchor-becomes-an-xref
  (is (= [[:p "See " [:xref {:to :theming} "the theming chapter"] "."]]
         (md->body "See [the theming chapter](#theming).\n")))
  (is (= [[:p [:a {:href "https://e.com"} "ext"]]]
         (md->body "[ext](https://e.com)\n"))))

(deftest fenced-code-info-splits-into-lang-and-edn-attrs
  (is (= [[:pre {:lang :clojure} "(+ 1 2)\n"]]
         (md->body "```clojure\n(+ 1 2)\n```\n")))
  (is (= [[:pre {:test true :lang :clojure} "(+ 1 2)\n"]]
         (md->body "```clojure {:test true}\n(+ 1 2)\n```\n"))))

(deftest include-fence-is-body-less
  (is (= [[:pre {:include "src/x.clj" :lines [1 3] :lang :clojure}]]
         (md->body "```clojure {:include \"src/x.clj\" :lines [1 3]}\n```\n"))))

(deftest hiccup-block-escape-splices-author-hiccup
  (is (= [[:admonition {:kind :note} [:p "hi"]]]
         (md->body "```{=hiccup}\n[:admonition {:kind :note} [:p \"hi\"]]\n```\n"))))

(deftest fo-block-escape-splices-verbatim
  (is (= [[:fo/block {:space-before "12pt"} "raw"]]
         (md->body "```{=fo}\n[:fo/block {:space-before \"12pt\"} \"raw\"]\n```\n"))))

(deftest inline-hiccup-escape
  (is (= [[:p "use " [:strong "x"] " here"]]
         (md->body "use `[:strong \"x\"]`{=hiccup} here\n"))))

(deftest malformed-raw-escape-is-an-error
  (let [d (catch-data #(md->body "```{=hiccup}\n[:p \"unterminated\n```\n"))]
    (is (= :clj-book.md.compile/invalid-raw-escape (:error/type d)))))

(deftest phase2-grammar-is-vocabulary-valid-and-expands
  (let [body (md->body (str ":::admonition {:kind :tip}\nBe **careful**.\n:::\n\n"
                            "{:cols [3 1]}\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n"
                            "```clojure {:test true}\n(+ 1 2)\n```\n\n"
                            "```{=fo}\n[:fo/block \"raw\"]\n```\n"))]
    (is (every? fo-schema/valid? body))
    (is (vector? (mapv expand/expand body)))))
