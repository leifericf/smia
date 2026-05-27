(ns clj-book.targets.site-test
  (:require
   [clj-book.docbook :as docbook]
   [clj-book.document :as document]
   [clj-book.targets.site :as site]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def canned-xml-path
  "test/fixtures/synthetic/canned-docbook/book.xml")

(defn- hiccup-tree []
  (let [tree (docbook/parse-docbook-file canned-xml-path)]
    (document/->html-model tree)))

(deftest docbook-transforms-to-article-hiccup
  (let [tree (hiccup-tree)]
    (is (= :article (first tree)) "Top-level book becomes <article>")
    (is (some #(and (vector? %) (= :section (first %))
                    (= "chapter" (:class (second %))))
              tree)
        "Chapters become <section class=\"chapter\">")))

(deftest page-map-emits-index-and-chapter-pages
  (let [tree (hiccup-tree)
        pages (site/page-map {:book/title "Tiny" :book/slug "tiny"
                              :css-href "assets/site.css"
                              :body-hiccup tree})]
    (is (contains? pages "/index.html"))
    (is (some #(str/starts-with? % "/chapters/") (keys pages)))))

(deftest emitted-html-has-no-script-tags
  (testing "Static export must contain no client-side JavaScript"
    (let [tree (hiccup-tree)
          pages (site/page-map {:book/title "Tiny" :book/slug "tiny"
                                :css-href "assets/site.css"
                                :body-hiccup tree})]
      (doseq [[path html] pages]
        (is (not (str/includes? html "<script"))
            (str path " contains a <script> tag"))
        (is (not (str/includes? (str/lower-case html) "javascript:"))
            (str path " contains a javascript: URL"))))))

(deftest emitted-html-references-stylesheet
  (let [tree (hiccup-tree)
        pages (site/page-map {:book/title "Tiny" :book/slug "tiny"
                              :css-href "assets/site.css"
                              :body-hiccup tree})]
    (doseq [[_ html] pages]
      (is (str/includes? html "rel=\"stylesheet\""))
      (is (str/includes? html "assets/site.css")))))
