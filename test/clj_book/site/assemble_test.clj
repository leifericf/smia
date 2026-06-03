(ns clj-book.site.assemble-test
  (:require
   [clj-book.book.number :as number]
   [clj-book.book.structure :as structure]
   [clj-book.site.assemble :as site]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private tokens
  {:color   {:link "#2a52be"}
   :type    {:body-family "serif" :highlight true}
   :code    {}
   :spacing {}
   :layout  {:page-size :a4}})

(def ^:private manuscript
  {:title     "The Book"
   :author    "An Author"
   :numbering structure/default-numbering
   :references {:smith-2020 {:author "Smith" :year 2020 :title "Things"}}
   :sections
   [{:kind :chapter
     :content [:chapter {:id :ch-one :title "One"}
               [:h2 {:id :sec-a} "Alpha"]
               [:p "see " [:xref {:to :sec-b}]]
               [:pre {:lang :clojure} "(def x 1)"]]}
    {:kind :chapter
     :content [:chapter {:id :ch-two :title "Two"}
               [:h2 {:id :sec-b} "Beta"]
               [:p "back to " [:xref {:to :sec-a}]]]}
    {:kind :matter :matter :back :role :bibliography :generated true}]})

(def ^:private book (:manuscript (number/assign manuscript)))
(def ^:private result (site/assemble book tokens))
(def ^:private pages (:pages result))

(deftest page-map-covers-every-page-and-the-stylesheet
  (is (= #{"index.html" "chapter-01.html" "chapter-02.html"
           "bibliography.html" "styles.css"}
         (set (keys pages)))))

(deftest html-pages-are-doctyped-html5-documents
  (doseq [[path content] pages
          :when (str/ends-with? path ".html")]
    (is (str/starts-with? content "<!DOCTYPE html>\n<html>")
        (str path " is a doctyped HTML5 document"))))

(deftest home-page-links-the-contents
  (let [home (get pages "index.html")]
    (is (str/includes? home "Contents"))
    (is (str/includes? home "href=\"chapter-01.html\""))))

(deftest cross-chapter-xrefs-resolve-in-the-serialized-output
  (is (str/includes? (get pages "chapter-01.html")
                     "href=\"chapter-02.html#sec-b\""))
  (is (str/includes? (get pages "chapter-02.html")
                     "href=\"chapter-01.html#sec-a\"")))

(deftest nav-chrome-chains-prev-and-next
  (testing "the middle of the chain points both ways"
    (is (str/includes? (get pages "chapter-02.html") "rel=\"prev\""))
    (is (str/includes? (get pages "chapter-02.html") "rel=\"next\"")))
  (testing "the edges do not run off the book"
    (is (not (str/includes? (get pages "chapter-01.html") "rel=\"prev\"")))
    (is (not (str/includes? (get pages "bibliography.html") "rel=\"next\"")))))

(deftest theme-highlighting-reaches-the-code
  (is (str/includes? (get pages "chapter-01.html") "tok-keyword")))

(deftest stylesheet-is-generated-from-the-tokens
  (let [css (get pages "styles.css")]
    (is (str/includes? css "body {"))
    (is (str/includes? css "#2a52be"))))

(deftest resources-pass-through
  (is (= [] (:resources result))))
