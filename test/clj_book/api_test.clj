(ns clj-book.api-test
  (:require
   [clj-book.api :as api]
   [clojure.test :refer [deftest is]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(deftest public-api-surface
  (is (var? #'api/validate))
  (is (var? #'api/build)))

(deftest build-defaults-to-both-profiles
  ;; A build with no :profiles renders both editions; exercised via
  ;; dry-run so it does not depend on the renderer.
  (let [p (api/build {:book-root valid-root :dry-run true})]
    (is (= [:screen :print] (:profiles p)))))
