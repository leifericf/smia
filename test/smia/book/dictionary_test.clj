(ns smia.book.dictionary-test
  (:require
   [smia.book.dictionary :as dict]
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.fo.expand :as fo-expand]
   [smia.html.expand :as html-expand]
   [smia.site.search-index :as search-index]
   [m1p.core :as m1p]
   [clojure.test :refer [deftest is testing]]))

;; --- the fallback chain -----------------------------------------------------

(deftest localize-resolves-english-by-default
  (testing "nil and :en both yield the shipped English"
    (is (= "Figure" (dict/localize nil :figure)))
    (is (= "Figure" (dict/localize :en :figure)))
    (is (= "List of Figures" (dict/localize nil :list-of-figures))))
  (testing "a language tag is normalized to its primary subtag"
    (is (= "Figure" (dict/localize "en-US" :figure)))
    (is (= "Chapter" (dict/localize "EN" :chapter))))
  (testing "an unknown language falls back to English"
    (is (= "Figure" (dict/localize "fr" :figure))))
  (testing "an unknown key uses the explicit fallback, then the title-cased key"
    (is (= "Fallback" (dict/localize nil :no-such-term "Fallback")))
    (is (= "No Such Term" (dict/localize nil :no-such-term)))))

;; --- a synthetic locale proves the override + fallback mechanism -------------

(def ^:private synthetic
  (m1p/prepare-dictionary {:figure "Figur" :note "Notat" :chapter "Kapittel"}))

(defmacro with-locale [& body]
  `(with-redefs [dict/dictionaries (assoc dict/dictionaries :zz synthetic)]
     ~@body))

(deftest a-locale-overrides-known-terms-and-falls-back-for-the-rest
  (with-locale
    (is (= "Figur" (dict/localize "zz" :figure)) "an overridden term is localized")
    (is (= "Kapittel" (dict/localize "zz" :chapter)))
    (is (= "Table" (dict/localize "zz" :table)) "an unset term falls back to English")
    (is (= "Made Up" (dict/localize "zz" :made-up)) "an unknown key title-cases")))

;; --- the mechanism reaches the apparatus passes -----------------------------

(deftest numbering-labels-are-localized
  (with-locale
    (let [out (number/assign
                {:numbering structure/default-numbering
                 :language  "zz"
                 :sections  [{:kind :chapter
                              :content [:chapter {:id :a :title "A"}
                                        [:figure {:id :f :caption "C"}
                                         [:img {:src "x.png"}]]]}]})]
      (is (= "Figur 1" (:label (get (:registry out) "f"))))
      (is (= "Kapittel 1" (:label (get (:registry out) "a")))))))

(deftest admonition-labels-are-localized-in-both-formats
  (with-locale
    (testing "HTML reads the label from the ctx language"
      (let [out (html-expand/expand [:admonition {:kind :note} [:p "x"]]
                                    {:language "zz"})]
        (is (some #(= "Notat" %) (tree-seq vector? seq out)))))
    (testing "FO reads the label from the style language"
      (let [out (fo-expand/expand [:admonition {:kind :note} [:p "x"]]
                                  {:language "zz"})]
        (is (some #(= "Notat" %) (tree-seq vector? seq out)))))))

(deftest generated-furniture-strings-are-localized
  (with-redefs [dict/dictionaries
                (assoc dict/dictionaries :zz
                       (m1p/prepare-dictionary {:on-page  "på side"
                                                :details  "Detaljer"
                                                :overview "Oversikt"}))]
    (testing "the xref page phrase"
      (let [out (fo-expand/expand [:xref {:to :ch :label "Kapittel 2" :page true}]
                                  {:language "zz"})]
        (is (some #(and (string? %) (clojure.string/includes? % "på side"))
                  (tree-seq vector? seq out)))))
    (testing "the disclosure default summary"
      (doseq [out [(fo-expand/expand [:details {} [:p "x"]] {:language "zz"})
                   (html-expand/expand [:details {} [:p "x"]] {:language "zz"})]]
        (is (some #(= "Detaljer" %) (tree-seq vector? seq out)))))
    (testing "the overview default title"
      (doseq [out [(fo-expand/expand [:overview {} [:p "x"]] {:language "zz"})
                   (html-expand/expand [:overview {} [:p "x"]] {:language "zz"})]]
        (is (some #(= "Oversikt" %) (tree-seq vector? seq out)))))))

(deftest search-category-labels-are-localized
  (with-redefs [dict/dictionaries
                (assoc dict/dictionaries :zz
                       (m1p/prepare-dictionary {:cat/figure "Figurer"}))]
    (is (= "Figurer" (search-index/kind-label :figure "zz")))
    (is (= "Tables" (search-index/kind-label :table "zz")) "unset falls back to English")))
