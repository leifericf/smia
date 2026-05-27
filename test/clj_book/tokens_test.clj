(ns clj-book.tokens-test
  (:require
   [clj-book.error :as error]
   [clj-book.tokens :as tokens]
   [clojure.test :refer [deftest is]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def no-tokens-root "test/fixtures/synthetic/missing-tokens-book")

(defn- catch-data [f]
  (try (f) nil
       (catch Exception e (error/data e))))

(deftest tokens-loaded-from-fixture
  (let [{:keys [tokens path]} (tokens/load-tokens {:book-root valid-root})]
    (is (map? tokens))
    (is (every? #(contains? tokens %) tokens/required-groups))
    (is (.endsWith path "styles/tokens.edn"))))

(deftest missing-tokens-file-fails
  (let [d (catch-data #(tokens/load-tokens {:book-root no-tokens-root}))]
    (is (= :clj-book.tokens/missing (:error/type d)))
    (is (.endsWith (:path (:error/context d)) "tokens.edn"))))

(deftest missing-token-group-fails
  (let [d (catch-data
            #(with-redefs [tokens/required-groups
                           #{:color :type :spacing :layout :motion}]
               (tokens/load-tokens {:book-root valid-root})))]
    (is (= :clj-book.tokens/missing-group (:error/type d)))
    (is (some #{:motion} (:missing (:error/context d))))))
