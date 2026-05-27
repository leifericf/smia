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
