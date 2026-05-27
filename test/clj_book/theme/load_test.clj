(ns clj-book.theme.load-test
  (:require
   [clj-book.error :as error]
   [clj-book.theme.load :as theme]
   [clojure.test :refer [deftest is]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def no-tokens-root "test/fixtures/synthetic/missing-tokens-book")

(defn- catch-data [f]
  (try (f) nil
       (catch Exception e (error/data e))))

(deftest tokens-loaded-from-fixture
  (let [{:keys [tokens path]} (theme/load-tokens {:book-root valid-root})]
    (is (map? tokens))
    (is (every? #(contains? tokens %) theme/required-groups))
    (is (.endsWith path "styles/tokens.edn"))))

(deftest missing-tokens-file-fails
  (let [d (catch-data #(theme/load-tokens {:book-root no-tokens-root}))]
    (is (= :clj-book.theme.load/missing (:error/type d)))
    (is (.endsWith (:path (:error/context d)) "tokens.edn"))))

(deftest missing-token-group-fails
  (let [d (catch-data
            #(with-redefs [theme/required-groups
                           #{:color :type :spacing :layout :motion}]
               (theme/load-tokens {:book-root valid-root})))]
    (is (= :clj-book.theme.load/missing-group (:error/type d)))
    (is (some #{:motion} (:missing (:error/context d))))))
