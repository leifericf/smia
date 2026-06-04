(ns clj-book.build.request-test
  (:require
   [clj-book.error :as error]
   [clj-book.build.request :as request]
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
    (let [d (catch-error #(request/normalize {:book-root 'manual} :build))]
      (is (= :clj-book.build.request/invalid-value (:error/type d))))))

(deftest build-defaults-to-both-pdf-editions
  (testing "A build with no :editions builds screen and print"
    (let [out (request/normalize valid-base :build)]
      (is (= [:screen :print] (:editions out))))))

(deftest empty-editions-defaults-to-both
  (let [out (request/normalize (assoc valid-base :editions []) :build)]
    (is (= [:screen :print] (:editions out)))))

(deftest unknown-edition-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :editions [:screen :wat])
                                :build))]
    (is (= :clj-book.build.request/unknown-edition (:error/type d)))
    (is (= [:wat] (:unknown-editions (:error/context d))))))

(deftest non-keyword-edition-is-hard-error
  (let [d (catch-error
            #(request/normalize (assoc valid-base :editions ["screen"])
                                :build))]
    (is (= :clj-book.build.request/invalid-editions (:error/type d)))))

(deftest valid-single-edition-request
  (let [out (request/normalize (assoc valid-base :editions [:print]) :build)]
    (is (= [:print] (:editions out)))
    (is (= "test/fixtures/synthetic/valid-book" (:book-root out)))
    (is (= "build" (:output-root out)))
    (is (= "book.edn" (:config-path out)))))

(deftest valid-multi-edition-request
  (let [out (request/normalize
              (assoc valid-base :editions [:screen :print]) :build)]
    (is (= [:screen :print] (:editions out)))))

(deftest validate-command-leaves-editions-nil
  (let [out (request/normalize valid-base :validate)]
    (is (nil? (:editions out)))))

(deftest every-edition-has-a-descriptor
  (testing "the internal descriptor names a format for every edition"
    (doseq [e request/supported-editions]
      (is (keyword? (:format (request/edition-descriptors e)))))))

(deftest site-edition-is-supported
  (let [out (request/normalize (assoc valid-base :editions [:site]) :build)]
    (is (= [:site] (:editions out)))
    (is (= :html (:format (request/edition-descriptors :site))))))

(deftest epub-edition-is-supported
  (let [out (request/normalize (assoc valid-base :editions [:epub]) :build)]
    (is (= [:epub] (:editions out)))
    (is (= :epub (:format (request/edition-descriptors :epub))))))

(deftest print-x-edition-is-a-pdf-with-print-layout-and-conformance
  (let [out (request/normalize (assoc valid-base :editions [:print-x]) :build)]
    (is (= [:print-x] (:editions out)))
    (is (= {:format :pdf :layout :print :pdf-x true}
           (request/edition-descriptors :print-x)))))

(deftest clean-flag-defaults-false-and-normalizes
  (is (false? (:clean (request/normalize valid-base :build))))
  (is (true? (:clean (request/normalize (assoc valid-base :clean true) :build)))))

(deftest non-map-request-is-hard-error
  (let [d (catch-error #(request/normalize "oops" :build))]
    (is (= :clj-book.build.request/invalid-request (:error/type d)))))
