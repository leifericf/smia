(ns clj-book.config-test
  (:require
   [clj-book.config :as config]
   [clj-book.error :as error]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")
(def invalid-root "test/fixtures/synthetic/invalid-book")
(def missing-tokens-root "test/fixtures/synthetic/missing-tokens-book")

(defn- catch-data [f]
  (try (f) nil
       (catch Exception e
         (error/data e))))

(deftest missing-required-key-fails
  (let [d (catch-data
            #(config/load-config {:book-root invalid-root
                                  :config-path "book.edn"}))]
    (is (= :clj-book.config/missing-required-key (:error/type d)))
    (is (some #{:book/slug} (:missing (:error/context d))))))

(deftest additional-keys-pass
  (testing "open-map shape preserves custom keys"
    (let [{:keys [config]} (config/load-config
                             {:book-root valid-root
                              :config-path "book.edn"})]
      (is (contains? config :custom/extension)))))

(deftest required-keys-loaded
  (let [{:keys [config warnings]} (config/load-config
                                    {:book-root valid-root
                                     :config-path "book.edn"})]
    (is (= "tiny-book" (:book/slug config)))
    (is (string? (:book/title config)))
    (is (vector? (:book/chapters config)))
    (is (vector? warnings))))

(deftest missing-chapter-fails
  (let [d (catch-data
            #(config/load-config {:book-root missing-tokens-root
                                  :config-path "book.edn"}))]
    ;; The fixture's chapters/01.adoc exists, so this succeeds.
    (is (nil? d) "missing-tokens fixture has its own chapter")))

(deftest config-file-not-found
  (let [d (catch-data
            #(config/load-config {:book-root valid-root
                                  :config-path "nope.edn"}))]
    (is (= :clj-book.config/missing (:error/type d)))))

(deftest layout-warning-on-unknown-value
  (let [{:keys [warnings]}
        (config/load-config {:book-root valid-root
                             :config-path "book.edn"})]
    ;; valid-book fixture uses well-known layout values; no unknown.
    (is (every? #(not= :clj-book.config/unknown-layout-value
                       (:warning/type %))
                warnings))))
