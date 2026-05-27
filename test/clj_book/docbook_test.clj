(ns clj-book.docbook-test
  (:require
   [clj-book.docbook :as docbook]
   [clojure.test :refer [deftest is testing]]))

(def canned-xml-path
  "test/fixtures/synthetic/canned-docbook/book.xml")

(deftest parses-canned-docbook
  (let [tree (docbook/parse-docbook-file canned-xml-path)]
    (is (= :book (first tree)))
    (testing "preserves attributes as a keyword-keyed map at position 1"
      (is (map? (second tree))))))

(deftest preserves-chapters-and-titles
  (let [tree     (docbook/parse-docbook-file canned-xml-path)
        children (drop 2 tree)
        chapters (filter #(and (vector? %) (= :chapter (first %)))
                         children)]
    (is (= 2 (count chapters)))
    (let [first-ch (first chapters)
          attrs    (second first-ch)
          title    (some #(when (and (vector? %) (= :title (first %))) %)
                         first-ch)]
      (is (= "ch-intro" (:xml:id attrs)))
      (is (some? title)))))
