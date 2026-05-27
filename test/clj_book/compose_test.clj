(ns clj-book.compose-test
  (:require
   [clj-book.compose :as compose]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest master-adoc-respects-chapter-order
  (let [s (compose/master-adoc
            {:config {:book/slug "x"
                      :book/title "X"
                      :book/chapters ["chapters/02-body.adoc"
                                      "chapters/01-intro.adoc"]}})
        i2 (.indexOf s "02-body")
        i1 (.indexOf s "01-intro")]
    (is (pos? i2))
    (is (pos? i1))
    (is (< i2 i1) "02-body include must precede 01-intro")))

(deftest master-adoc-is-deterministic
  (testing "Same input yields byte-identical output"
    (let [cfg {:book/slug "x"
               :book/title "X"
               :book/chapters ["chapters/01-intro.adoc"
                               "chapters/02-body.adoc"]}
          a (compose/master-adoc {:config cfg})
          b (compose/master-adoc {:config cfg})]
      (is (= a b)))))

(deftest master-includes-header-title
  (let [s (compose/master-adoc
            {:config {:book/slug "x"
                      :book/title "Great Book"
                      :book/chapters ["chapters/01-intro.adoc"]}})]
    (is (str/starts-with? s "= Great Book"))
    (is (str/includes? s "include::chapters/01-intro.adoc[]"))))

(deftest write-master-emits-file
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/clj-book-compose-test-" (System/currentTimeMillis))
        path (compose/write-master!
               {:intermediate-dir tmp
                :config {:book/slug "x"
                         :book/title "X"
                         :book/chapters ["chapters/01-intro.adoc"]}})]
    (is (.exists (io/file path)))
    (is (str/includes? (slurp path) "include::"))))
