(ns clj-book.book.theme-test
  (:require
   [clj-book.book.theme :as theme]
   [clojure.test :refer [deftest is testing]]))

(def tokens
  {:color   {:text "#222222" :code-background "#eeeeee" :rule "#777777"}
   :type    {:body-family "Georgia" :heading-family "Helvetica"
             :mono-family "Menlo" :base-size "12pt" :h1-size "28pt"}
   :spacing {:paragraph "8pt"}
   :layout  {:page-size :letter :margin-inside "30mm" :margin-outside "18mm"}})

(deftest tokens-drive-the-style
  (let [{:keys [style]} (theme/compile-theme tokens :screen)]
    (testing "body typography comes from tokens"
      (is (= "Georgia" (-> style :body :font-family)))
      (is (= "12pt" (-> style :body :font-size)))
      (is (= "#222222" (-> style :body :color))))
    (testing "headings and code adopt token families/sizes"
      (is (= "Helvetica" (-> style :h1 :font-family)))
      (is (= "28pt" (-> style :h1 :font-size)))
      (is (= "Menlo" (-> style :code :font-family)))
      (is (= "#eeeeee" (-> style :pre :background-color))))
    (testing "paragraph spacing is token-driven"
      (is (= "8pt" (-> style :p :space-after))))))

(deftest screen-profile-has-one-symmetric-master
  (let [{:keys [masters master-reference]} (theme/compile-theme tokens :screen)
        spm (filter #(= :fo/simple-page-master (first %)) masters)]
    (is (= "book" master-reference))
    (is (= 1 (count spm)))
    (let [attrs (second (first spm))]
      (is (= "18mm" (:margin-left attrs)))
      (is (= "18mm" (:margin-right attrs)))
      (is (= "8.5in" (:page-width attrs)) "letter trim size from tokens"))))

(deftest print-profile-mirrors-recto-and-verso
  (let [{:keys [masters]} (theme/compile-theme tokens :print)
        by-name (into {} (for [m masters
                               :when (= :fo/simple-page-master (first m))]
                           [(:master-name (second m)) (second m)]))]
    (testing "recto binds on the left, verso on the right"
      (is (= "30mm" (:margin-left (by-name "book-recto"))))
      (is (= "18mm" (:margin-right (by-name "book-recto"))))
      (is (= "18mm" (:margin-left (by-name "book-verso"))))
      (is (= "30mm" (:margin-right (by-name "book-verso")))))
    (testing "a page-sequence-master selects them by odd/even"
      (is (some #(= :fo/page-sequence-master (first %)) masters)))))

(deftest defaults-apply-when-tokens-are-sparse
  (let [{:keys [style masters]} (theme/compile-theme
                                  {:color {} :type {} :spacing {} :layout {}}
                                  :screen)]
    (is (= "serif" (-> style :body :font-family)))
    (is (= "210mm" (:page-width (second (first masters))))
        "defaults to A4 when no page-size token is given")))
