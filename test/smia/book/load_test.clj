(ns smia.book.load-test
  (:require
   [smia.book.load :as load]
   [smia.error :as error]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- tmp-book [tag]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "smia-load-" tag "-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    dir))

(defn- spit-chapter [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest loads-a-data-literal-chapter
  (let [dir (tmp-book "lit")]
    (spit-chapter dir "chapters/01.clj"
                  "[:chapter {:id :intro :title \"Intro\"} [:p \"hi\"]]")
    (is (= [:chapter {:id :intro :title "Intro"} [:p "hi"]]
           (load/load-chapter (.getPath dir) "chapters/01.clj")))))

(deftest evaluates-author-code-to-build-hiccup
  (testing "a chapter is a program: its last form's value is used"
    (let [dir (tmp-book "prog")]
      (spit-chapter dir "chapters/01.clj"
                    "(let [items [\"a\" \"b\" \"c\"]]
                       (into [:chapter {:id :gen :title \"Generated\"}]
                             (for [i items] [:p i])))")
      (is (= [:chapter {:id :gen :title "Generated"}
              [:p "a"] [:p "b"] [:p "c"]]
             (load/load-chapter (.getPath dir) "chapters/01.clj"))))))

(deftest loads-chapters-in-order
  (let [dir (tmp-book "order")]
    (spit-chapter dir "chapters/01.clj" "[:chapter {:id :a :title \"A\"}]")
    (spit-chapter dir "chapters/02.clj" "[:chapter {:id :b :title \"B\"}]")
    (is (= [:a :b]
           (map #(get-in % [1 :id])
                (load/load-chapters (.getPath dir)
                                    ["chapters/01.clj" "chapters/02.clj"]))))))

(deftest missing-chapter-is-a-hard-error
  (let [dir (tmp-book "missing")
        d   (catch-data #(load/load-chapter (.getPath dir) "chapters/nope.clj"))]
    (is (= :smia.book.load/missing-chapter (:error/type d)))))

(deftest broken-chapter-surfaces-eval-error
  (let [dir (tmp-book "broken")]
    (spit-chapter dir "chapters/01.clj" "(this is (not valid")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01.clj"))]
      (is (= :smia.book.load/chapter-eval-error (:error/type d))))))

(deftest malformed-chapter-is-a-hard-error
  (testing "the chapter shape is validated at the load boundary"
    (let [dir (tmp-book "malformed")]
      (spit-chapter dir "chapters/not-a-chapter.clj" "[:p \"not a chapter\"]")
      (is (= :smia.book.load/invalid-chapter
             (:error/type (catch-data
                            #(load/load-chapter (.getPath dir) "chapters/not-a-chapter.clj")))))
      (spit-chapter dir "chapters/no-id.clj" "[:chapter {:title \"T\"} \"x\"]")
      (is (= :smia.book.load/missing-chapter-id
             (:error/type (catch-data
                            #(load/load-chapter (.getPath dir) "chapters/no-id.clj")))))
      (spit-chapter dir "chapters/no-title.clj" "[:chapter {:id :x} \"x\"]")
      (is (= :smia.book.load/missing-chapter-title
             (:error/type (catch-data
                            #(load/load-chapter (.getPath dir) "chapters/no-title.clj"))))))))

;; --- Markdown front-end ---------------------------------------------------

(deftest loads-a-markdown-chapter
  (testing "id from filename (NN- prefix stripped), title from first H1"
    (let [dir (tmp-book "md")]
      (spit-chapter dir "chapters/02-authoring.md"
                    "# Authoring\n\nWrite **prose**.\n")
      (is (= [:chapter {:id :authoring :title "Authoring"}
              [:p "Write " [:strong "prose"] "."]]
             (load/load-chapter (.getPath dir) "chapters/02-authoring.md"))))))

(deftest frontmatter-overrides-id-and-title
  (let [dir (tmp-book "md-fm")]
    (spit-chapter dir "chapters/01-x.md"
                  "{:id :custom :title \"Custom\" :draft true}\n# Ignored\n\nHi\n")
    (let [[_ attrs] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= :custom (:id attrs)))
      (is (= "Custom" (:title attrs)))
      (is (true? (:draft attrs)) "extra front-matter keys are preserved"))))

(deftest markdown-without-a-title-is-an-error
  (let [dir (tmp-book "md-notitle")]
    (spit-chapter dir "chapters/01-x.md" "Just prose, no heading.\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/missing-title (:error/type d))))))

(deftest markdown-frontmatter-type-error-is-surfaced
  (let [dir (tmp-book "md-badfm")]
    (spit-chapter dir "chapters/01-x.md" "{:id \"not-a-keyword\"}\n# T\n\nHi\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/invalid-front-matter (:error/type d))))))

(deftest duplicate-chapter-ids-are-a-hard-error
  (let [dir (tmp-book "dupe")]
    (spit-chapter dir "chapters/01.clj" "[:chapter {:id :same :title \"A\"}]")
    (spit-chapter dir "chapters/02.md" "{:id :same}\n# B\n\nhi\n")
    (let [d (catch-data #(load/load-chapters (.getPath dir)
                                             ["chapters/01.clj" "chapters/02.md"]))]
      (is (= :smia.book.load/duplicate-chapter-id (:error/type d)))
      (is (= [:same] (get-in d [:error/context :duplicate-ids]))))))

(deftest clj-and-md-chapters-mix
  (let [dir (tmp-book "mixed")]
    (spit-chapter dir "chapters/01-intro.clj"
                  "[:chapter {:id :intro :title \"Intro\"} [:p \"a\"]]")
    (spit-chapter dir "chapters/02-body.md" "# Body\n\nb\n")
    (is (= [:intro :body]
           (map #(get-in % [1 :id])
                (load/load-chapters (.getPath dir)
                                    ["chapters/01-intro.clj" "chapters/02-body.md"]))))))

(deftest markdown-prose-gets-smart-punctuation
  (testing "quotes, dashes, and ellipses are smartened by default"
    (let [dir (tmp-book "md-smart")]
      (spit-chapter dir "chapters/01-x.md"
                    "# It's \"Smart\"\n\nDon't quote \"this\" -- or...\n")
      (let [[_ attrs p] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
        (is (= "It’s “Smart”" (:title attrs)) "the H1 title is smartened")
        (is (= [:p "Don’t quote “this” – or…"] p))))))

(deftest smart-punctuation-can-be-disabled
  (let [dir (tmp-book "md-exact")]
    (spit-chapter dir "chapters/01-x.md" "# T\n\nDon't \"quote\"\n")
    (let [[_ _ p] (load/load-chapter (.getPath dir) "chapters/01-x.md"
                                     {:smart-punctuation false})]
      (is (= [:p "Don't \"quote\""] p)))))

(deftest front-matter-title-is-authored-exactly
  (testing "only Markdown prose is smartened; an EDN :title is data"
    (let [dir (tmp-book "md-fm-title")]
      (spit-chapter dir "chapters/01-x.md"
                    "{:title \"Don't \\\"Smarten\\\"\"}\n# Ignored\n\nHi\n")
      (let [[_ attrs] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
        (is (= "Don't \"Smarten\"" (:title attrs)))))))

(deftest clojure-chapters-are-never-smartened
  (let [dir (tmp-book "clj-exact")]
    (spit-chapter dir "chapters/01.clj"
                  "[:chapter {:id :x :title \"X\"} [:p \"Don't \\\"quote\\\"\"]]")
    (is (= [:chapter {:id :x :title "X"} [:p "Don't \"quote\""]]
           (load/load-chapter (.getPath dir) "chapters/01.clj")))))

(deftest include-slurps-source-relative-to-book-root
  (let [dir (tmp-book "include")]
    (spit-chapter dir "src/sample.clj" "(ns sample)\n(defn add [a b] (+ a b))\n(add 1 2)\n")
    (spit-chapter dir "chapters/01-x.md"
                  "# Inc\n\n```clojure {:include \"src/sample.clj\" :lines [2 2]}\n```\n")
    (let [[_ _ pre] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= :pre (first pre)))
      (is (= {:lang :clojure} (second pre)) "include/lines keys are stripped")
      (is (= "(defn add [a b] (+ a b))" (nth pre 2)) "only the selected line range"))))

(deftest include-selects-a-tagged-region
  (let [dir (tmp-book "inc-tag")]
    (spit-chapter dir "src/sample.clj"
                  (str "(ns sample)\n"
                       ";; tag::core\n"
                       "(defn add [a b] (+ a b))\n"
                       ";; end::core\n"
                       "(add 1 2)\n"))
    (spit-chapter dir "chapters/01-x.md"
                  "# Inc\n\n```clojure {:include \"src/sample.clj\" :tag \"core\"}\n```\n")
    (let [[_ _ pre] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= {:lang :clojure} (second pre)) "include/tag keys are stripped")
      (is (= "(defn add [a b] (+ a b))" (nth pre 2))
          "only the region between the markers, markers excluded"))))

(deftest tagged-regions-with-the-same-tag-concatenate
  (let [sources {"a.clj" (str "before\n"
                              "# tag::x\n"
                              "one\n"
                              "# end::x\n"
                              "between\n"
                              "// tag::x\n"
                              "two\n"
                              "// end::x\n")}
        tree    [:pre {:lang :clojure :include "a.clj" :tag "x"}]]
    (is (= [:pre {:lang :clojure} "one\ntwo"]
           (#'load/substitute-includes sources tree))
        "regions concatenate in file order, any comment syntax")))

(deftest a-tag-does-not-match-a-longer-tag-name
  (testing "tag x must not open on tag::xy (marker names are delimited)"
    (let [sources {"a.clj" (str "tag::xy\nFROM-XY\nend::xy\n"
                                "tag::x\nFROM-X\nend::x\n")}
          tree    [:pre {:include "a.clj" :tag "x"}]]
      (is (= [:pre {} "FROM-X"]
             (#'load/substitute-includes sources tree))
          "only the x region, not the xy region"))))

(deftest unclosed-tagged-region-runs-to-the-end-of-file
  (let [sources {"a.clj" "before\n;; tag::x\none\ntwo\n"}
        tree    [:pre {:include "a.clj" :tag "x"}]]
    (is (= [:pre {} "one\ntwo"]
           (#'load/substitute-includes sources tree)))))

(deftest missing-include-tag-is-a-hard-error
  (let [dir (tmp-book "inc-notag")]
    (spit-chapter dir "src/sample.clj" "(ns sample)\n")
    (spit-chapter dir "chapters/01-x.md"
                  "# X\n\n```clojure {:include \"src/sample.clj\" :tag \"nope\"}\n```\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/missing-include-tag (:error/type d)))
      (is (= "nope" (get-in d [:error/context :tag])))
      (is (= "src/sample.clj" (get-in d [:error/context :include]))))))

(deftest conflicting-include-keys-are-a-hard-error
  (let [dir (tmp-book "inc-conflict")]
    (spit-chapter dir "src/sample.clj" "(ns sample)\n")
    (spit-chapter dir "chapters/01-x.md"
                  (str "# X\n\n```clojure {:include \"src/sample.clj\""
                       " :tag \"core\" :lines [1 2]}\n```\n"))
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/conflicting-include-keys (:error/type d))))))

(deftest missing-include-is-a-hard-error
  (let [dir (tmp-book "noinc")]
    (spit-chapter dir "chapters/01-x.md"
                  "# X\n\n```clojure {:include \"src/nope.clj\"}\n```\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/missing-include (:error/type d))))))

(deftest include-that-escapes-the-book-root-is-a-hard-error
  (testing "a parent-escaping include never reaches the filesystem"
    (let [dir    (tmp-book "inc-escape")
          secret (io/file (.getParentFile dir) "secret.txt")]
      (spit secret "TOP SECRET")
      (spit-chapter dir "chapters/01-x.md"
                    "# X\n\n```clojure {:include \"../secret.txt\"}\n```\n")
      (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
        (is (= :smia.book.load/unsafe-include (:error/type d)))
        (is (= "../secret.txt" (get-in d [:error/context :include]))))))
  (testing "an absolute include path is rejected too"
    (let [dir (tmp-book "inc-abs")]
      (spit-chapter dir "chapters/01-x.md"
                    "# X\n\n```clojure {:include \"/etc/hosts\"}\n```\n")
      (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
        (is (= :smia.book.load/unsafe-include (:error/type d)))))))

(deftest data-table-that-escapes-the-book-root-is-a-hard-error
  (let [dir    (tmp-book "data-escape")
        secret (io/file (.getParentFile dir) "secret.csv")]
    (spit secret "a,b\n1,2\n")
    (spit-chapter dir "chapters/01-x.md"
                  "# D\n\n:::table {:data \"../secret.csv\"}\n:::\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/unsafe-data (:error/type d)))
      (is (= "../secret.csv" (get-in d [:error/context :data]))))))

;; The collect and substitute halves of include resolution are pure, so they
;; can be exercised directly — no disk, no slurp.

(deftest include-paths-are-collected-in-order-and-deduplicated
  (let [tree [:chapter {:id :x :title "X"}
              [:pre {:lang :clojure :include "a.clj" :lines [1 2]}]
              [:p "prose"]
              [:pre {:lang :clojure :include "b.clj"}]
              [:pre {:lang :clojure :include "a.clj" :lines [5 6]}]]]
    (is (= ["a.clj" "b.clj"] (#'load/include-paths tree))
        "each source path appears once, in document order")))

(deftest substitute-includes-injects-source-and-strips-include-keys
  (let [sources {"a.clj" "line-1\nline-2\nline-3\nline-4"}
        tree    [:p [:pre {:lang :clojure :include "a.clj" :lines [2 3]}]]]
    (is (= [:p [:pre {:lang :clojure} "line-2\nline-3"]]
           (#'load/substitute-includes sources tree))
        "the :lines range is applied and :include/:lines are dropped")))

;; --- data-sourced tables ----------------------------------------------------

(deftest data-table-directive-reads-csv-with-a-header
  (let [dir (tmp-book "data-csv")]
    (spit-chapter dir "data/grid.csv" "A,B\n1,2\n3,4\n")
    (spit-chapter dir "chapters/01-x.md"
                  (str "# D\n\n:::table {:data \"data/grid.csv\" :header true"
                       " :id :grid :caption \"A grid\"}\n:::\n"))
    (let [[_ _ table] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= [:table {:id :grid :caption "A grid"}
              [:thead [:tr [:th "A"] [:th "B"]]]
              [:tbody [:tr [:td "1"] [:td "2"]] [:tr [:td "3"] [:td "4"]]]]
             table)
          "the data keys are dropped and the rows are built from the file"))))

(deftest data-table-without-a-header-is-all-body
  (let [dir (tmp-book "data-nohdr")]
    (spit-chapter dir "data/g.csv" "1,2\n3,4\n")
    (spit-chapter dir "chapters/01-x.md"
                  "# D\n\n:::table {:data \"data/g.csv\"}\n:::\n")
    (let [[_ _ table] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= [:table {} [:tbody [:tr [:td "1"] [:td "2"]]
                         [:tr [:td "3"] [:td "4"]]]]
             table)))))

(deftest data-table-infers-format-from-the-extension
  (let [dir (tmp-book "data-tsv")]
    (spit-chapter dir "data/g.tsv" "A\tB\n1\t2\n")
    (spit-chapter dir "chapters/01-x.md"
                  "# D\n\n:::table {:data \"data/g.tsv\" :header true}\n:::\n")
    (let [[_ _ table] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= [:thead [:tr [:th "A"] [:th "B"]]] (nth table 2))))))

(deftest data-table-reads-edn-rows
  (let [dir (tmp-book "data-edn")]
    (spit-chapter dir "data/g.edn" "[[\"A\" \"B\"] [1 2]]")
    (spit-chapter dir "chapters/01-x.md"
                  "# D\n\n:::table {:data \"data/g.edn\" :header true}\n:::\n")
    (let [[_ _ table] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= [:tbody [:tr [:td "1"] [:td "2"]]] (nth table 3))))))

(deftest missing-data-file-is-a-hard-error
  (let [dir (tmp-book "data-missing")]
    (spit-chapter dir "chapters/01-x.md"
                  "# D\n\n:::table {:data \"data/nope.csv\"}\n:::\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :smia.book.load/missing-data (:error/type d)))
      (is (= "data/nope.csv" (get-in d [:error/context :data]))))))

(deftest data-table-paths-are-collected-in-order-and-deduplicated
  (let [tree [:chapter {:id :x :title "X"}
              [:table {:data "a.csv"}]
              [:p "prose"]
              [:table {:data "b.csv"}]
              [:table {:data "a.csv" :header true}]]]
    (is (= ["a.csv" "b.csv"] (#'load/data-table-paths tree)))))
