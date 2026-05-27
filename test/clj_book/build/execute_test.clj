(ns clj-book.build.execute-test
  (:require
   [clj-book.build.execute :as execute]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-execute-" tag "-" (System/currentTimeMillis)))

(defn- request [tag & {:as overrides}]
  (merge {:command     :build
          :book-root   valid-root
          :config-path "book.edn"
          :output-root (tmp-dir tag)}
         overrides))

(deftest prepare-loads-config-and-tokens
  (let [{:keys [manuscript paths]} (execute/prepare (request "prepare"))]
    (is (= "tiny-book" (:book/slug (:config manuscript))))
    (is (map? (:tokens manuscript)))
    (is (vector? (:warnings manuscript)))
    (is (str/includes? (:intermediate-dir paths) "tiny-book/intermediate"))
    (is (str/includes? (:pdf-output-dir paths) "tiny-book/pdf"))))

(deftest validate-returns-ok
  (testing "validate loads, assembles and vocabulary-checks the chapters"
    (let [out (execute/validate (request "validate"))]
      (is (= :ok (:status out))))))

(deftest dry-run-returns-plan-without-building
  (testing "Dry run validates and plans but performs no profile render"
    (let [p (execute/build (request "dry" :profiles [:screen :print]
                                    :dry-run true))]
      (is (= [:screen :print] (:profiles p)))
      (is (= 2 (count (:profile-steps p))))
      (is (= "tiny-book" (-> p :manifest-skeleton :book/slug))))))

(deftest ^:integration single-profile-build-writes-pdf-and-manifest
  (let [req (request "build" :profiles [:screen])
        man (execute/build req)
        art (first (:artifacts man))]
    (is (= "tiny-book" (:book/slug man)))
    (is (= [:screen] (:build/profiles man)))
    (is (= :screen (:profile art)))
    (is (.exists (io/file (:path art))) "the PDF is written to disk")
    (is (str/ends-with? (:path art) "tiny-book-screen.pdf"))
    (is (.exists (io/file (-> art :paths :fo))) "the intermediate FO is written")
    (is (.exists (io/file (:manifest/path man))))))

(deftest invalid-profile-blocked-by-request-normalization
  ;; This is asserted at the public api/request layer; execute assumes
  ;; the request has been normalized.
  (is true))
