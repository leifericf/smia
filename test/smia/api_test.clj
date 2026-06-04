(ns smia.api-test
  (:require
   [smia.api :as api]
   [clojure.test :refer [deftest is]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(deftest public-api-surface
  (is (var? #'api/validate))
  (is (var? #'api/build)))

(deftest build-defaults-to-both-pdf-editions
  ;; A build with no :editions builds both PDF editions; exercised via
  ;; dry-run so it does not depend on the renderer.
  (let [p (api/build {:book-root valid-root :dry-run true})]
    (is (= [:screen :print] (:editions p)))))
