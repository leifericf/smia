(ns clj-book.schema-test
  (:require
   [clj-book.book.config :as config]
   [clj-book.error :as error]
   [clj-book.build.request :as request]
   [clj-book.schema :as schema]
   [clj-book.theme.load :as theme]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(defn- catch-data [f]
  (try (f) nil (catch Exception e (error/data e))))

(deftest real-fixtures-conform
  (testing "the synthetic fixture's parsed values satisfy the schemas"
    (let [{:keys [config]} (config/load-config {:book-root valid-root
                                                :config-path "book.edn"})
          {:keys [tokens]} (theme/load-tokens {:book-root valid-root})]
      (is (schema/valid? schema/Manuscript config))
      (is (schema/valid? schema/Tokens tokens))
      (is (nil? (schema/explain schema/Manuscript config)))
      (is (nil? (schema/explain schema/Tokens tokens))))))

(deftest manuscript-rejects-bad-shapes
  (is (not (schema/valid? schema/Manuscript {:book/slug "s" :book/title "t"
                                             :book/chapters []}))
      "empty chapters is invalid")
  (is (not (schema/valid? schema/Manuscript {:book/slug 1 :book/title "t"
                                             :book/chapters ["a"]}))
      "non-string slug is invalid")
  (is (some? (schema/explain schema/Manuscript {:book/title "t"}))
      "humanized explanation is returned for a bad value"))

(deftest check-throws-structured-error
  (let [d (catch-data
            #(schema/check schema/Manuscript {:book/title "t"}
                           :clj-book.schema-test/bad))]
    (is (= :clj-book.schema-test/bad (:error/type d)))
    (is (some? (:errors (:error/context d)))))
  (testing "check returns the value unchanged when it conforms"
    (let [m {:book/slug "s" :book/title "t" :book/chapters ["a"]}]
      (is (= m (schema/check schema/Manuscript m
                             :clj-book.schema-test/bad))))))

(deftest normalized-requests-conform-to-request-schema
  (testing "the Request schema documents the actual normalize output"
    (let [build-req (request/normalize
                      {:book-root "b" :profiles [:screen :print]} :build)
          val-req   (request/normalize {:book-root "b"} :validate)]
      (is (schema/valid? schema/Request build-req))
      (is (schema/valid? schema/Request val-req)
          "validate requests carry nil :profiles and still conform"))))

(deftest paths-schema-matches-resolved-paths
  (is (schema/valid? schema/Paths
                     {:book-output-dir  "build/x"
                      :intermediate-dir "build/x/intermediate"
                      :pdf-output-dir   "build/x/pdf"})))
