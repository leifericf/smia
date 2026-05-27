(ns clj-book.document-test
  (:require
   [clj-book.docbook :as docbook]
   [clj-book.document :as document]
   [clojure.test :refer [deftest is testing]]))

(def canned-xml-path "test/fixtures/synthetic/canned-docbook/book.xml")

(defn- model []
  (document/->html-model (docbook/parse-docbook-file canned-xml-path)))

(deftest book-becomes-article
  (let [m (model)]
    (is (= :article (first m)) "Top-level book becomes <article>")
    (is (= "book" (:class (second m))))))

(deftest chapters-become-sections-with-ids
  (let [m (model)
        chapters (filter #(and (vector? %)
                               (= :section (first %))
                               (= "chapter" (:class (second %))))
                         m)]
    (is (= 2 (count chapters)) "both chapters are transformed")
    (is (= "ch-intro" (:id (second (first chapters))))
        "xml:id carries through to the section id")
    (testing "chapter heading is an <h2> carrying the title text"
      (let [h2 (some #(when (and (vector? %) (= :h2 (first %))) %)
                     (first chapters))]
        (is (= "Introduction" (last h2)))))))

(deftest itemized-list-becomes-ul
  (let [m (model)
        ul (some (fn find-ul [node]
                   (cond
                     (and (vector? node) (= :ul (first node))) node
                     (vector? node) (some find-ul node)
                     :else nil))
                 m)]
    (is (some? ul) "itemizedlist becomes a <ul>")
    (is (= 2 (count (filter #(and (vector? %) (= :li (first %))) ul))))))

(deftest pure-transform-is-referentially-transparent
  (is (= (model) (model)) "same DocBook input yields the same HTML model"))
