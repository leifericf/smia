(ns clj-book.build.plan-test
  "The plan is a pure function: it is exercised here on in-memory data
   with no fixture files and no asciidoctor on PATH."
  (:require
   [clj-book.build.plan :as plan]
   [clj-book.schema :as schema]
   [clojure.test :refer [deftest is testing]]))

(def prepared
  {:request    {:book-root "books/x" :targets [:site :pdf]}
   :manuscript {:config      {:book/slug "x" :book/title "X"
                              :book/chapters ["a.adoc"]}
                :config-file "books/x/book.edn"
                :tokens      {:color {} :type {} :spacing {} :layout {}}
                :warnings    []}
   :paths      {:book-output-dir  "build/x"
                :intermediate-dir "build/x/intermediate"
                :site-output-dir  "build/x/site"
                :pdf-output-dir   "build/x/pdf"
                :tokens-dir       "build/x/intermediate/tokens"}})

(deftest plan-conforms-and-decides-targets
  (let [p (plan/plan prepared)]
    (is (schema/valid? schema/Plan p))
    (is (= [:site :pdf] (:targets p)))
    (testing "each target step resolves its output directory"
      (is (= "build/x/site" (-> p :target-steps first :output-dir)))
      (is (= "build/x/pdf" (-> p :target-steps second :output-dir))))
    (testing "manifest skeleton carries slug and target order"
      (is (= "x" (-> p :manifest-skeleton :book/slug)))
      (is (= [:site :pdf] (-> p :manifest-skeleton :build/targets))))))

(deftest plan-prereqs-are-deterministic-paths
  (let [p (plan/plan prepared)]
    (is (= [:master-adoc :pdf-theme]
           (map :kind (:prereqs p))))
    (is (= "build/x/intermediate/book.adoc"
           (:path (first (:prereqs p)))))))

(deftest plan-is-referentially-transparent
  (is (= (plan/plan prepared) (plan/plan prepared))))

(deftest site-only-plan-has-one-step
  (let [p (plan/plan (assoc-in prepared [:request :targets] [:site]))]
    (is (= [:site] (:targets p)))
    (is (= 1 (count (:target-steps p))))))
