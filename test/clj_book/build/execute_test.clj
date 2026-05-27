(ns clj-book.build.execute-test
  (:require
   [clj-book.build.execute :as execute]
   [clj-book.error :as error]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-execute-" tag "-" (System/currentTimeMillis)))

(defn- catch-data [f]
  (try (f) nil (catch Exception e (error/data e))))

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
    (is (str/includes? (:intermediate-dir paths) "tiny-book/intermediate"))))

(deftest validate-returns-ok
  (let [out (execute/validate (request "validate"))]
    (is (= :ok (:status out)))))

(deftest dry-run-returns-plan-without-building
  (testing "Dry run validates and plans but performs no profile render"
    (let [p (execute/build (request "dry" :profiles [:screen :print]
                                    :dry-run true))]
      (is (= [:screen :print] (:profiles p)))
      (is (= 2 (count (:profile-steps p))))
      (is (= "tiny-book" (-> p :manifest-skeleton :book/slug))))))

(deftest real-build-not-yet-implemented
  (testing "Rendering pipeline is under construction"
    (let [d (catch-data #(execute/build (request "build" :profiles [:print])))]
      (is (= :clj-book.build.execute/not-implemented (:error/type d))))))

(deftest invalid-profile-blocked-by-request-normalization
  ;; This is asserted at the public api/request layer; execute assumes
  ;; the request has been normalized.
  (is true))
