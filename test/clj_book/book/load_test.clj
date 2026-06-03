(ns clj-book.book.load-test
  (:require
   [clj-book.book.load :as load]
   [clj-book.error :as error]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- tmp-book [tag]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "clj-book-load-" tag "-" (System/currentTimeMillis)))]
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
    (is (= :clj-book.book.load/missing-chapter (:error/type d)))))

(deftest broken-chapter-surfaces-eval-error
  (let [dir (tmp-book "broken")]
    (spit-chapter dir "chapters/01.clj" "(this is (not valid")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01.clj"))]
      (is (= :clj-book.book.load/chapter-eval-error (:error/type d))))))

(deftest malformed-chapter-is-a-hard-error
  (testing "the chapter shape is validated at the load boundary"
    (let [dir (tmp-book "malformed")]
      (spit-chapter dir "chapters/not-a-chapter.clj" "[:p \"not a chapter\"]")
      (is (= :clj-book.book.load/invalid-chapter
             (:error/type (catch-data
                            #(load/load-chapter (.getPath dir) "chapters/not-a-chapter.clj")))))
      (spit-chapter dir "chapters/no-id.clj" "[:chapter {:title \"T\"} \"x\"]")
      (is (= :clj-book.book.load/missing-chapter-id
             (:error/type (catch-data
                            #(load/load-chapter (.getPath dir) "chapters/no-id.clj")))))
      (spit-chapter dir "chapters/no-title.clj" "[:chapter {:id :x} \"x\"]")
      (is (= :clj-book.book.load/missing-chapter-title
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
      (is (= :clj-book.book.load/missing-title (:error/type d))))))

(deftest markdown-frontmatter-type-error-is-surfaced
  (let [dir (tmp-book "md-badfm")]
    (spit-chapter dir "chapters/01-x.md" "{:id \"not-a-keyword\"}\n# T\n\nHi\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :clj-book.book.load/invalid-front-matter (:error/type d))))))

(deftest duplicate-chapter-ids-are-a-hard-error
  (let [dir (tmp-book "dupe")]
    (spit-chapter dir "chapters/01.clj" "[:chapter {:id :same :title \"A\"}]")
    (spit-chapter dir "chapters/02.md" "{:id :same}\n# B\n\nhi\n")
    (let [d (catch-data #(load/load-chapters (.getPath dir)
                                             ["chapters/01.clj" "chapters/02.md"]))]
      (is (= :clj-book.book.load/duplicate-chapter-id (:error/type d)))
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

(deftest include-slurps-source-relative-to-book-root
  (let [dir (tmp-book "include")]
    (spit-chapter dir "src/sample.clj" "(ns sample)\n(defn add [a b] (+ a b))\n(add 1 2)\n")
    (spit-chapter dir "chapters/01-x.md"
                  "# Inc\n\n```clojure {:include \"src/sample.clj\" :lines [2 2]}\n```\n")
    (let [[_ _ pre] (load/load-chapter (.getPath dir) "chapters/01-x.md")]
      (is (= :pre (first pre)))
      (is (= {:lang :clojure} (second pre)) "include/lines keys are stripped")
      (is (= "(defn add [a b] (+ a b))" (nth pre 2)) "only the selected line range"))))

(deftest missing-include-is-a-hard-error
  (let [dir (tmp-book "noinc")]
    (spit-chapter dir "chapters/01-x.md"
                  "# X\n\n```clojure {:include \"src/nope.clj\"}\n```\n")
    (let [d (catch-data #(load/load-chapter (.getPath dir) "chapters/01-x.md"))]
      (is (= :clj-book.book.load/missing-include (:error/type d))))))
