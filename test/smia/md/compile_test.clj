(ns smia.md.compile-test
  (:require
   [smia.error :as error]
   [smia.fo.expand :as expand]
   [smia.fo.schema :as fo-schema]
   [smia.md.compile :as compile]
   [smia.md.parse :as parse]
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

(deftest figure-directive-wraps-an-image
  (is (= [[:figure {:id :diagram :caption "A widget"} [:img {:src "w.png" :alt "alt"}]]]
         (md->body ":::figure {:id :diagram :caption \"A widget\"}\n![alt](w.png)\n:::\n"))))

(deftest captioned-table-via-the-bare-edn-line
  (is (= [[:table {:id :grid :caption "A grid"}
           [:thead [:tr [:th "H"]]] [:tbody [:tr [:td "x"]]]]]
         (md->body "{:id :grid :caption \"A grid\"}\n\n| H |\n|---|\n| x |\n"))))

(deftest code-listing-fence-carries-file-and-caption
  (is (= [[:pre {:lang :clojure :id :ex :file "core.clj" :caption "Core"} "(+ 1 2)"]]
         (md->body "```clojure {:id :ex :file \"core.clj\" :caption \"Core\"}\n(+ 1 2)\n```\n"))))

(deftest annotations-ride-on-the-fenced-code-info-string
  (is (= [[:pre {:lang :clojure :id :ex
                 :annotations [{:line 1 :note "Defines the accumulator"}
                               {:line 2 :note "Folds the sequence"}]}
           "(def xs [1 2 3])\n(reduce + xs)"]]
         (md->body
          (str "```clojure {:id :ex :annotations "
               "[{:line 1 :note \"Defines the accumulator\"} "
               "{:line 2 :note \"Folds the sequence\"}]}\n"
               "(def xs [1 2 3])\n(reduce + xs)\n```\n")))))

(deftest inline-cite-and-index-escapes-compile
  (is (= [[:p "See " [:cite {:key :smith2020}] "."]]
         (md->body "See `smith2020`{=cite}.\n")))
  (is (= [[:p "Determinism" [:index {:term "Determinism"}] " matters."]]
         (md->body "Determinism`Determinism`{=index} matters.\n"))))

(deftest inline-attr-escape-compiles
  (is (= [[:p "Smia " [:attr :version] " ships today."]]
         (md->body "Smia `version`{=attr} ships today.\n"))))

(deftest inline-math-escape-compiles
  (is (= [[:p "Euler: " [:math {:notation "e^{i\\pi} = -1"}] "."]]
         (md->body "Euler: `e^{i\\pi} = -1`{=math}.\n"))))

(deftest math-fence-compiles-to-display-math
  (is (= [[:math {:notation "\\frac{a}{b}" :display true}]]
         (md->body "```math\n\\frac{a}{b}\n```\n"))))

(deftest math-fence-keeps-its-attrs
  (is (= [[:math {:notation "x^2" :display true :id :square}]]
         (md->body "```math {:id :square}\nx^2\n```\n"))))

(deftest plantuml-fence-compiles-to-a-diagram
  (is (= [[:diagram {:source "A -> B" :id :flow :alt "A calls B"}]]
         (md->body "```plantuml {:id :flow :alt \"A calls B\"}\nA -> B\n```\n"))))

(deftest mermaid-fence-compiles-to-a-client-diagram
  (is (= [[:diagram {:engine :mermaid :source "graph TD; A-->B"}]]
         (md->body "```mermaid\ngraph TD; A-->B\n```\n")))
  (testing "a caption still doubles as alt text"
    (is (= [[:diagram {:engine :mermaid :source "graph TD; A-->B"
                       :caption "Flow" :alt "Flow"}]]
           (md->body "```mermaid {:caption \"Flow\"}\ngraph TD; A-->B\n```\n")))))

(deftest diagram-alt-defaults-to-its-caption
  (is (= [[:diagram {:source "A -> B" :caption "Flow" :alt "Flow"}]]
         (md->body "```plantuml {:caption \"Flow\"}\nA -> B\n```\n"))))

(deftest figure-caption-reaches-a-bare-diagram-as-alt
  (is (= [[:figure {:id :arch :caption "The pipeline"}
           [:diagram {:source "A -> B" :alt "The pipeline"}]]]
         (md->body ":::figure {:id :arch :caption \"The pipeline\"}\n```plantuml\nA -> B\n```\n:::\n"))))

(deftest sidebar-and-epigraph-directives-compile
  (is (= [[:sidebar {:title "Aside"} [:p "Body."]]]
         (md->body ":::sidebar {:title \"Aside\"}\nBody.\n:::\n")))
  (is (= [[:epigraph {:attribution "A. Hacker"} [:p "Make it work."]]]
         (md->body ":::epigraph {:attribution \"A. Hacker\"}\nMake it work.\n:::\n"))))

(deftest overview-directive-compiles
  (is (= [[:overview {} [:ul [:li "a"] [:li "b"]]]]
         (md->body ":::overview\n- a\n- b\n:::\n")))
  (testing ":title is carried through"
    (is (= [[:overview {:title "In brief"} [:p "Summary."]]]
           (md->body ":::overview {:title \"In brief\"}\nSummary.\n:::\n")))))

(deftest overview-title-must-be-a-string
  (let [d (catch-data #(md->body ":::overview {:title 5}\nhi\n:::\n"))]
    (is (= :smia.md.compile/invalid-overview (:error/type d)))))

(deftest deflist-directive-pairs-terms-and-definitions
  (testing "a paragraph that is a lone strong span is a term; the next block its definition"
    (is (= [[:dl {}
             [:dt "Manuscript"] [:dd "The normalized document structure."]
             [:dt "Profile"]    [:dd "A layout variant."]]]
           (md->body (str ":::deflist\n"
                          "**Manuscript**\n\n"
                          "The normalized document structure.\n\n"
                          "**Profile**\n\n"
                          "A layout variant.\n"
                          ":::\n")))))
  (testing "definitions keep their inline markup"
    (is (= [[:dl {}
             [:dt "reduce"] [:dd "Folds with " [:code "reduce"] "."]]]
           (md->body (str ":::deflist\n"
                          "**reduce**\n\n"
                          "Folds with `reduce`.\n"
                          ":::\n"))))))

(deftest interface-markers-compile-to-ui-tags
  (testing "a key chord folds into kbd nodes"
    (is (= [[:p "Press " [:span [:kbd "Ctrl"] "+" [:kbd "S"]] " to save."]]
           (md->body "Press `[\"Ctrl\" \"S\"]`{=kbd} to save.\n"))))
  (testing "a menu path folds into a menu node"
    (is (= [[:p "Open " [:menu "File" "Export"] "."]]
           (md->body "Open `[\"File\" \"Export\"]`{=menu}.\n"))))
  (testing "button, mark, sub, and sup wrap a label"
    (is (= [[:p "Click " [:button "Save"] "."]]
           (md->body "Click `Save`{=button}.\n")))
    (is (= [[:p "H" [:sub "2"] "O and x" [:sup "2"] "."]]
           (md->body "H`2`{=sub}O and x`2`{=sup}.\n")))
    (is (= [[:p "A " [:mark "key"] " point."]]
           (md->body "A `key`{=mark} point.\n")))))

(deftest when-directive-compiles-to-a-conditional
  (is (= [[:when {:equals [:edition :epub]} [:p "EPUB only."]]]
         (md->body ":::when {:equals [:edition :epub]}\nEPUB only.\n:::\n")))
  (testing "the condition map is the directive's attrs"
    (is (= [[:when {:defined :draft} [:p "Draft."]]]
           (md->body ":::when {:defined :draft}\nDraft.\n:::\n")))))

(deftest richer-block-directives-compile
  (testing ":::example carries its title and body"
    (is (= [[:example {:title "A worked case"} [:p "Body."]]]
           (md->body ":::example {:title \"A worked case\"}\nBody.\n:::\n"))))
  (testing ":::details and :::open compile to disclosures"
    (is (= [[:details {:summary "Show more"} [:p "Hidden."]]]
           (md->body ":::details {:summary \"Show more\"}\nHidden.\n:::\n")))
    (is (= [[:open {} [:p "Visible."]]]
           (md->body ":::open\nVisible.\n:::\n")))))

(deftest page-mechanics-directives-compile
  (testing ":::page-break compiles to the page-break element"
    (is (= [[:page-break]] (md->body ":::page-break\n:::\n"))))
  (testing ":::keep-together wraps its block content"
    (is (= [[:keep-together {} [:p "a"] [:p "b"]]]
           (md->body ":::keep-together\na\n\nb\n:::\n")))))

(deftest body-less-directives-reject-content
  (testing ":::table rows come from :data; body content cannot render"
    (let [d (catch-data
              #(md->body ":::table {:data \"x.csv\"}\nStray content.\n:::\n"))]
      (is (= :smia.md.compile/unexpected-directive-body (:error/type d)))
      (is (= "table" (:name (:error/context d))))))
  (testing ":::page-break carries no content"
    (let [d (catch-data #(md->body ":::page-break\nStray content.\n:::\n"))]
      (is (= :smia.md.compile/unexpected-directive-body (:error/type d)))))
  (testing "an empty body still compiles"
    (is (= [[:table {:data "x.csv"}]]
           (md->body ":::table {:data \"x.csv\"}\n:::\n")))))

(deftest heading-trailing-edn-map-becomes-attributes
  (testing "a trailing bare EDN map on a heading line is pulled out as attrs"
    (is (= [[:h2 {:id :setup} "Setup"]]
           (md->body "## Setup {:id :setup}\n")))
    (is (= [[:h3 {:id :keys} "The Keys"]]
           (md->body "### The Keys {:id :keys}\n"))))
  (testing "a heading with no trailing map is plain CommonMark"
    (is (= [[:h2 "Plain heading"]] (md->body "## Plain heading\n"))))
  (testing "the H1 title strips its attribute map too"
    (is (= "Quickstart" (:title (second (md->chapter "# Quickstart {:id :qs}\n"))))))
  (testing "only the trailing map is attrs; earlier braces stay content"
    (is (= "Title {:a 1} more"
           (:title (second (md->chapter "# Title `{:a 1}` more {:id :foo}\n")))))
    (is (= [[:h2 {:id :x} "A {b} c"]]
           (md->body "## A {b} c {:id :x}\n"))))
  (testing "a trailing map may itself nest maps"
    (is (= [[:h2 {:style {:b 1}} "S"]]
           (md->body "## S {:style {:b 1}}\n"))))
  (testing "a trailing empty map carries no attributes and stays text"
    (is (= [[:h2 "Using {}"]] (md->body "## Using {}\n")))
    (is (= "Using {}" (:title (second (md->chapter "# Using {}\n")))))))

(deftest bullet-and-ordered-lists
  (is (= [[:ul [:li "a"] [:li "b"]]] (md->body "- a\n- b\n")))
  (is (= [[:ol [:li "one"] [:li "two"]]] (md->body "1. one\n2. two\n"))))

(deftest ordered-list-preserves-a-non-default-start
  (testing "a list starting above 1 carries its start ordinal"
    (is (= [[:ol {:start 3} [:li "three"] [:li "four"]]]
           (md->body "3. three\n4. four\n"))))
  (testing "a list starting at 1 carries no start attribute"
    (is (= [[:ol [:li "one"] [:li "two"]]]
           (md->body "1. one\n2. two\n")))))

(deftest tight-lists-inline-their-items
  ;; No blank lines between items -> a CommonMark "tight" list -> items
  ;; render inline, with no inner paragraph block.
  (is (= [[:ul [:li "a"] [:li "b"]]] (md->body "- a\n- b\n"))))

(deftest loose-lists-wrap-items-in-paragraphs
  ;; Blank lines between items -> a "loose" list -> each item keeps its
  ;; paragraph, matching hand-written [:li [:p …]] Hiccup.
  (is (= [[:ul [:li [:p "a"]] [:li [:p "b"]]]] (md->body "- a\n\n- b\n")))
  (is (= [[:ol [:li [:p "one"]] [:li [:p "two"]]]] (md->body "1. one\n\n2. two\n"))))

(deftest fenced-code-strips-the-fences-trailing-newline
  ;; The newline before the closing fence is not part of the sample, so it
  ;; is dropped (no spurious trailing blank line in the rendered block).
  (is (= [[:pre {:lang :clojure} "(+ 1 2)"]] (md->body "```clojure\n(+ 1 2)\n```\n")))
  (is (= [[:pre {} "a\n\nb"]] (md->body "```\na\n\nb\n```\n"))
      "internal blank lines are preserved; only the final fence newline is dropped"))

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
    (is (= :smia.md.compile/unsupported-node (:error/type d)))
    (is (= :html-block (get-in d [:error/context :node-type])))
    (is (map? (get-in d [:error/context :pos])))))

(deftest compile-requires-a-document-node
  (let [d (catch-data #(compile/compile {:type :paragraph}))]
    (is (= :smia.md.compile/not-a-document (:error/type d)))))

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

(deftest directives-nest-with-innermost-fence-attribution
  (testing "each ::: closes the innermost open directive, not the outer"
    (is (= [[:example {:title "t"}
             [:admonition {:kind :note} [:p "body"]]
             [:p "after"]]]
           (md->body (str ":::example {:title \"t\"}\n"
                          ":::admonition {:kind :note}\nbody\n:::\n"
                          "after\n:::\n"))))))

(deftest closing-fence-inside-a-code-fence-is-content
  (testing "a ::: line in an embedded code fence never closes the directive"
    (is (= [[:admonition {:kind :note}
             [:pre {} ":::\nstill code"]
             [:p "after"]]]
           (md->body (str ":::admonition {:kind :note}\n"
                          "```\n:::\nstill code\n```\n"
                          "after\n:::\n"))))))

(deftest unknown-directive-is-an-error
  (let [d (catch-data #(md->body ":::flummox {:x 1}\nhi\n:::\n"))]
    (is (= :smia.md.compile/unknown-directive (:error/type d)))))

(deftest admonition-kind-must-be-a-keyword
  (let [d (catch-data #(md->body ":::admonition {:kind \"tip\"}\nhi\n:::\n"))]
    (is (= :smia.md.compile/invalid-admonition (:error/type d)))))

(deftest gfm-table
  (is (= [[:table {}
           [:thead [:tr [:th "A"] [:th "B"]]]
           [:tbody [:tr [:td "1"] [:td "2"]]]]]
         (md->body "| A | B |\n|---|---|\n| 1 | 2 |\n"))))

(deftest gfm-table-column-alignment-becomes-per-cell-align
  (testing "the separator row's colons set each column's :align"
    (is (= [[:table {}
             [:thead [:tr [:th {:align "left"} "A"] [:th {:align "center"} "B"]
                      [:th {:align "right"} "C"]]]
             [:tbody [:tr [:td {:align "left"} "1"] [:td {:align "center"} "2"]
                      [:td {:align "right"} "3"]]]]]
           (md->body (str "| A | B | C |\n|:--|:-:|--:|\n| 1 | 2 | 3 |\n")))))
  (testing "an unaligned column keeps a bare cell"
    (is (= [[:table {}
             [:thead [:tr [:th "A"]]]
             [:tbody [:tr [:td "1"]]]]]
           (md->body "| A |\n|---|\n| 1 |\n")))))

(deftest table-cols-from-preceding-edn-line
  (is (= [[:table {:cols [3 1]}
           [:thead [:tr [:th "A"] [:th "B"]]]
           [:tbody [:tr [:td "1"] [:td "2"]]]]]
         (md->body "{:cols [3 1]}\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"))))

(deftest table-cols-auto-from-preceding-edn-line
  (is (= [[:table {:cols :auto}
           [:thead [:tr [:th "A"] [:th "B"]]]
           [:tbody [:tr [:td "1"] [:td "2"]]]]]
         (md->body "{:cols :auto}\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"))))

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

(deftest circular-footnote-definitions-are-an-error
  (let [data (catch-data #(md->body "Text[^a].\n\n[^a]: see [^a] again\n"))]
    (is (= :smia.md.compile/circular-footnote (:error/type data)))
    (is (= "a" (-> data :error/context :label))))
  (let [data (catch-data #(md->body "Text[^a].\n\n[^a]: see [^b]\n\n[^b]: see [^a]\n"))]
    (is (= :smia.md.compile/circular-footnote (:error/type data)))))

(deftest footnote-definitions-may-chain-without-cycles
  (is (= [[:p "T" [:footnote "a " [:footnote "b done"]] "."]]
         (md->body "T[^a].\n\n[^a]: a [^b]\n\n[^b]: b done\n"))))

(deftest reference-style-links-and-images-compile
  ;; A link/image reference definition is metadata: the reference resolves
  ;; and the definition itself produces no output.
  (is (= [[:p "See " [:a {:href "https://example.com"} "the docs"] "."]]
         (md->body "See [the docs][d].\n\n[d]: https://example.com\n")))
  (is (= [[:p [:img {:src "img.png" :alt "logo"}]]]
         (md->body "![logo][l]\n\n[l]: img.png\n")))
  (is (= [[:p "x"]]
         (md->body "x\n\n[unused]: https://example.com\n"))
      "an unused reference definition is dropped, not an error"))

(deftest link-to-empty-anchor-is-an-error
  (let [d (catch-data #(md->body "See [here](#).\n"))]
    (is (= :smia.md.compile/empty-xref (:error/type d)))))

(deftest link-to-anchor-becomes-an-xref
  (is (= [[:p "See " [:xref {:to :theming} "the theming chapter"] "."]]
         (md->body "See [the theming chapter](#theming).\n")))
  (is (= [[:p [:a {:href "https://e.com"} "ext"]]]
         (md->body "[ext](https://e.com)\n"))))

(deftest fenced-code-info-splits-into-lang-and-edn-attrs
  (is (= [[:pre {:lang :clojure} "(+ 1 2)"]]
         (md->body "```clojure\n(+ 1 2)\n```\n")))
  (is (= [[:pre {:test true :lang :clojure} "(+ 1 2)"]]
         (md->body "```clojure {:test true}\n(+ 1 2)\n```\n"))))

(deftest fenced-code-lang-is-only-the-first-info-word
  (testing "extra words after the language are ignored, not folded into the keyword"
    (is (= [[:pre {:lang :clojure} "code"]]
           (md->body "```clojure ignored words\ncode\n```\n")))
    (is (= [[:pre {:test true :lang :clojure} "code"]]
           (md->body "```clojure extra {:test true}\ncode\n```\n")))))

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
    (is (= :smia.md.compile/invalid-raw-escape (:error/type d)))))

(deftest raw-escape-block-must-hold-exactly-one-form
  (let [d (catch-data #(md->body "```{=hiccup}\n[:p \"kept\"]\n[:p \"second\"]\n```\n"))]
    (is (= :smia.md.compile/invalid-raw-escape (:error/type d)))))

(deftest inline-raw-escape-must-hold-exactly-one-form
  (let [d (catch-data #(md->body "use `[:em \"a\"] junk`{=hiccup} here\n"))]
    (is (= :smia.md.compile/invalid-raw-escape (:error/type d)))))

(deftest directive-attrs-reject-trailing-content
  (let [d (catch-data #(md->body ":::admonition {:kind :note} {:kind :tip}\nBody.\n:::\n"))]
    (is (= :smia.md.compile/invalid-directive-attrs (:error/type d)))))

(deftest fence-attrs-reject-trailing-content
  (let [d (catch-data #(md->body "```clojure {:test true} {:more 1}\n(+ 1 2)\n```\n"))]
    (is (= :smia.md.compile/invalid-fence-info (:error/type d)))))

(deftest phase2-grammar-is-vocabulary-valid-and-expands
  (let [body (md->body (str ":::admonition {:kind :tip}\nBe **careful**.\n:::\n\n"
                            "{:cols [3 1]}\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n"
                            "```clojure {:test true}\n(+ 1 2)\n```\n\n"
                            "```{=fo}\n[:fo/block \"raw\"]\n```\n"))]
    (is (every? fo-schema/valid? body))
    (is (vector? (mapv expand/expand body)))))
