(ns clj-book.api-test
  (:require
   [clj-book.api :as api]
   [clj-book.error :as error]
   [clojure.test :refer [deftest is]]))

(deftest public-api-surface
  (is (var? #'api/validate))
  (is (var? #'api/build)))

(deftest build-without-targets-is-hard-error
  (let [d (try (api/build {:book-root "test/fixtures/synthetic/valid-book"})
               nil
               (catch Exception e (error/data e)))]
    (is (= :clj-book.request/missing-targets (:error/type d)))))
