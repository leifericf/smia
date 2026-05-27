(ns clj-book.fo.attrs-test
  (:require
   [clj-book.fo.attrs :as attrs]
   [clojure.test :refer [deftest is testing]]))

(deftest attr-name-handles-plain-dotted-and-namespaced
  (is (= "space-before" (attrs/attr-name :space-before)))
  (testing "compound dotted property names pass through verbatim"
    (is (= "keep-together.within-page"
           (attrs/attr-name :keep-together.within-page))))
  (testing "namespaced keyword renders as ns:name"
    (is (= "xml:lang" (attrs/attr-name :xml/lang)))))

(deftest attr-value-stringifies-by-type
  (is (= "12pt" (attrs/attr-value "12pt")))
  (is (= "always" (attrs/attr-value :always)))
  (is (= "3" (attrs/attr-value 3)))
  (is (= "0.5" (attrs/attr-value 1/2))))

(deftest pairs-are-sorted-and-drop-nils
  (is (= [["font-size" "11pt"] ["space-before" "6pt"]]
         (attrs/pairs {:space-before "6pt" :font-size "11pt"})))
  (testing "nil-valued attributes are dropped"
    (is (= [["a" "1"]] (attrs/pairs {:a 1 :b nil}))))
  (testing "empty attribute map yields no pairs"
    (is (empty? (attrs/pairs {})))
    (is (empty? (attrs/pairs nil)))))
