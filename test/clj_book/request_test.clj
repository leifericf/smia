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

(deftest absent-book-root-defaults-to-current-dir
  (testing "A request with no :book-root builds the current directory"
    (is (= "." (:book-root (request/normalize {} :build))))))

(deftest blank-book-root-defaults-to-current-dir
  (testing "A blank or whitespace :book-root falls back to \".\""
    (is (= "." (:book-root (request/normalize {:book-root ""} :build))))
    (is (= "." (:book-root (request/normalize {:book-root "   "} :build))))))

(deftest non-string-book-root-is-hard-error
  (testing "A present non-string :book-root is rejected (no coercion on -X)"
    (let [d (catch-error #(request/normalize {:book-root 'docs/manual} :build))]
      (is (= :clj-book.request/invalid-value (:error/type d))))))

(deftest build-defaults-to-both-profiles
  (testing "A build with no :profiles renders both editions"
    (let [out (request/normalize valid-base :build)]
      (is (= [:screen :print] (:profiles out))))))

(deftest empty-profiles-defaults-to-both
  (let [out (request/normalize (assoc valid-base :profiles []) :build)]
    (is (= [:screen :print] (:profiles out)))))

(deftest unknown-profile-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :profiles [:screen :wat])
                                :build))]
    (is (= :clj-book.request/unknown-profile (:error/type d)))
    (is (= [:wat] (:unknown-profiles (:error/context d))))))

(deftest non-keyword-profile-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :profiles ["screen"])
                                :build))]
    (is (= :clj-book.request/invalid-profiles (:error/type d)))))

(deftest valid-single-profile-request
  (let [out (request/normalize (assoc valid-base :profiles [:print]) :build)]
    (is (= [:print] (:profiles out)))
    (is (= "test/fixtures/synthetic/valid-book" (:book-root out)))
    (is (= "build" (:output-root out)))
    (is (= "book.edn" (:config-path out)))))

(deftest valid-multi-profile-request
  (let [out (request/normalize
              (assoc valid-base :profiles [:screen :print]) :build)]
    (is (= [:screen :print] (:profiles out)))))

(deftest validate-command-leaves-profiles-nil
  (let [out (request/normalize valid-base :validate)]
    (is (nil? (:profiles out)))))

(deftest non-map-request-is-hard-error
  (let [d (catch-error #(request/normalize "oops" :build))]
    (is (= :clj-book.request/invalid-request (:error/type d)))))
