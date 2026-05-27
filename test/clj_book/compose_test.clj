(ns clj-book.compose-test
  (:require
   [clj-book.compose :as compose]
   [clj-book.error :as error]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(defn- catch-data [f]
  (try (f) nil (catch Exception e (error/data e))))

(deftest master-adoc-respects-chapter-order
  (let [s (compose/master-adoc
            {:book-root valid-root
             :config {:book/slug "x"
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
          a (compose/master-adoc {:book-root valid-root :config cfg})
          b (compose/master-adoc {:book-root valid-root :config cfg})]
      (is (= a b)))))

(deftest duplicate-chapters-fail
  (let [d (catch-data
            #(compose/master-adoc
               {:book-root valid-root
                :config {:book/slug "x"
                         :book/title "X"
                         :book/chapters ["chapters/01-intro.adoc"
                                         "chapters/01-intro.adoc"]}}))]
    (is (= :clj-book.compose/duplicate-chapter (:error/type d)))))

(deftest missing-chapter-fails
  (let [d (catch-data
            #(compose/master-adoc
               {:book-root valid-root
                :config {:book/slug "x"
                         :book/title "X"
                         :book/chapters ["chapters/missing.adoc"]}}))]
    (is (= :clj-book.compose/missing-chapter (:error/type d)))))

(deftest master-includes-header-title
  (let [s (compose/master-adoc
            {:book-root valid-root
             :config {:book/slug "x"
                      :book/title "Great Book"
                      :book/chapters ["chapters/01-intro.adoc"]}})]
    (is (str/starts-with? s "= Great Book"))
    (is (str/includes? s "include::chapters/01-intro.adoc[]"))))

(deftest write-master-emits-file
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/clj-book-compose-test-" (System/currentTimeMillis))
        path (compose/write-master!
               {:book-root valid-root
                :intermediate-dir tmp
                :config {:book/slug "x"
                         :book/title "X"
                         :book/chapters ["chapters/01-intro.adoc"]}})]
    (is (.exists (io/file path)))
    (is (str/includes? (slurp path) "include::"))))
