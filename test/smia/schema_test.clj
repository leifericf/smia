(ns smia.schema-test
  (:require
   [smia.book.config :as config]
   [smia.error :as error]
   [smia.build.request :as request]
   [smia.schema :as schema]
   [smia.theme.load :as theme]
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

(def ^:private minimal-manuscript
  {:book/slug "s" :book/title "t" :book/chapters ["a.md"]})

(deftest manuscript-schema-states-the-whole-contract
  (testing "the dogfood manual's book.edn conforms"
    (let [{:keys [config]} (config/load-config {:book-root "manual"
                                                :config-path "book.edn"})]
      (is (nil? (schema/explain schema/Manuscript config)))))
  (testing "every interpreted optional key is typed, not merely tolerated"
    (is (not (schema/valid? schema/Manuscript
                            (assoc minimal-manuscript :book/author 42))))
    (is (not (schema/valid? schema/Manuscript
                            (assoc minimal-manuscript :book/language :en)))
        "language is an IETF tag string")
    (is (not (schema/valid? schema/Manuscript
                            (assoc minimal-manuscript :book/site-url 42))))
    (is (not (schema/valid? schema/Manuscript
                            (assoc minimal-manuscript :book/redirects
                                   {:old "new"})))
        "redirects map old path strings to id keywords")
    (is (not (schema/valid? schema/Manuscript
                            (assoc minimal-manuscript :book/downloads
                                   {:base "u"})))
        "downloads need their assets")
    (is (not (schema/valid? schema/Manuscript
                            (assoc minimal-manuscript :book/attributes
                                   {:k :keyword-value})))
        "attribute values are strings, numbers, or author Hiccup"))
  (testing "unrecognized keys still pass (the open map is the contract)"
    (is (schema/valid? schema/Manuscript
                       (assoc minimal-manuscript :custom/extension true)))))

(deftest check-throws-structured-error
  (let [d (catch-data
            #(schema/check schema/Manuscript {:book/title "t"}
                           :smia.schema-test/bad))]
    (is (= :smia.schema-test/bad (:error/type d)))
    (is (some? (:errors (:error/context d)))))
  (testing "check returns the value unchanged when it conforms"
    (let [m {:book/slug "s" :book/title "t" :book/chapters ["a"]}]
      (is (= m (schema/check schema/Manuscript m
                             :smia.schema-test/bad))))))

(deftest normalized-requests-conform-to-request-schema
  (testing "the Request schema documents the actual normalize output"
    (let [build-req (request/normalize
                      {:book-root "b" :editions [:screen :print]} :build)
          val-req   (request/normalize {:book-root "b"} :validate)]
      (is (schema/valid? schema/Request build-req))
      (is (schema/valid? schema/Request val-req)
          "validate requests carry nil :editions and still conform"))))

(deftest paths-schema-matches-resolved-paths
  (is (schema/valid? schema/Paths
                     {:book-output-dir  "build/x"
                      :intermediate-dir "build/x/intermediate"
                      :pdf-output-dir   "build/x/pdf"})))
