(ns clj-book.build.plan-test
  "The plan is a pure function: it is exercised here on in-memory data
   with no fixture files and no rendering."
  (:require
   [clj-book.build.plan :as plan]
   [clj-book.schema :as schema]
   [clojure.test :refer [deftest is testing]]))

(def prepared
  {:request    {:book-root "books/x" :editions [:screen :print]}
   :manuscript {:config      {:book/slug "x" :book/title "X"
                              :book/chapters ["a.clj"]}
                :config-file "books/x/book.edn"
                :tokens      {:color {} :type {} :spacing {} :layout {}}
                :warnings    []}
   :paths      {:book-output-dir  "build/x"
                :intermediate-dir "build/x/intermediate"
                :pdf-output-dir   "build/x/pdf"}})

(deftest plan-conforms-and-decides-editions
  (let [p (plan/plan prepared)]
    (is (schema/valid? schema/Plan p))
    (is (= [:screen :print] (:editions p)))
    (testing "each edition step resolves its FO and PDF paths"
      (is (= "build/x/intermediate/book-screen.fo"
             (-> p :edition-steps first :fo-path)))
      (is (= "build/x/pdf/x-screen.pdf"
             (-> p :edition-steps first :pdf-path)))
      (is (= "build/x/pdf/x-print.pdf"
             (-> p :edition-steps second :pdf-path))))
    (testing "manifest skeleton carries slug and edition order"
      (is (= "x" (-> p :manifest-skeleton :book/slug)))
      (is (= [:screen :print] (-> p :manifest-skeleton :build/editions))))))

(deftest plan-is-referentially-transparent
  (is (= (plan/plan prepared) (plan/plan prepared))))

(deftest single-edition-plan-has-one-step
  (let [p (plan/plan (assoc-in prepared [:request :editions] [:print]))]
    (is (= [:print] (:editions p)))
    (is (= 1 (count (:edition-steps p))))))
