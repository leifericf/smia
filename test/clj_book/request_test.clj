(ns clj-book.request-test
  (:require
   [clj-book.error :as error]
   [clj-book.request :as request]
   [clojure.test :refer [deftest is testing]]))

(def valid-base
  {:book-root "test/fixtures/synthetic/valid-book"})

(defn- catch-error [f]
  (try (f) nil
       (catch Exception e
         (error/data e))))

(deftest missing-book-root-is-hard-error
  (let [d (catch-error #(request/normalize {} :build))]
    (is (= :clj-book.request/missing-book-root (:error/type d)))))

(deftest missing-targets-is-hard-error
  (testing "Build command requires :targets"
    (let [d (catch-error #(request/normalize valid-base :build))]
      (is (= :clj-book.request/missing-targets (:error/type d))))))

(deftest empty-targets-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :targets []) :build))]
    (is (= :clj-book.request/missing-targets (:error/type d)))))

(deftest unknown-target-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :targets [:site :wat])
                                :build))]
    (is (= :clj-book.request/unknown-target (:error/type d)))
    (is (= [:wat] (:unknown-targets (:error/context d))))))

(deftest non-keyword-target-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :targets ["site"])
                                :build))]
    (is (= :clj-book.request/invalid-targets (:error/type d)))))

(deftest valid-single-target-request
  (let [out (request/normalize (assoc valid-base :targets [:site]) :build)]
    (is (= [:site] (:targets out)))
    (is (= "test/fixtures/synthetic/valid-book" (:book-root out)))
    (is (= "build" (:output-root out)))
    (is (= "book.edn" (:config-path out)))))

(deftest valid-multi-target-request
  (let [out (request/normalize
              (assoc valid-base :targets [:site :pdf]) :build)]
    (is (= [:site :pdf] (:targets out)))))

(deftest validate-command-tolerates-missing-targets
  (let [out (request/normalize valid-base :validate)]
    (is (nil? (:targets out)))))

(deftest non-map-request-is-hard-error
  (let [d (catch-error #(request/normalize "oops" :build))]
    (is (= :clj-book.request/invalid-request (:error/type d)))))
