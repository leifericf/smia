(ns clj-book.theme.load-test
  (:require
   [clj-book.error :as error]
   [clj-book.theme.load :as theme]
   [clojure.test :refer [deftest is]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def no-theme-root "test/fixtures/synthetic/missing-tokens-book")

(defn- catch-data [f]
  (try (f) nil
       (catch Exception e (error/data e))))

(deftest theme-loaded-from-book-root
  (let [{:keys [tokens path]} (theme/load-tokens {:book-root valid-root})]
    (is (map? tokens))
    (is (every? #(contains? tokens %) theme/required-groups))
    (is (.endsWith path "theme.edn"))
    (is (not (.contains path "styles"))
        "theme.edn lives at the book root, beside book.edn")))

(deftest missing-theme-file-fails
  (let [d (catch-data #(theme/load-tokens {:book-root no-theme-root}))]
    (is (= :clj-book.theme.load/missing (:error/type d)))
    (is (.endsWith (:path (:error/context d)) "theme.edn"))))

(deftest missing-token-group-fails
  (let [d (catch-data
            #(theme/validate {:color {} :type {} :spacing {}} "theme.edn"))]
    (is (= :clj-book.theme.load/invalid-tokens (:error/type d)))
    (is (contains? (:errors (:error/context d)) :layout))))
