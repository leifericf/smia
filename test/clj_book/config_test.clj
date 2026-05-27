(ns clj-book.config-test
  (:require
   [clj-book.config :as config]
   [clj-book.error :as error]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")
(def invalid-root "test/fixtures/synthetic/invalid-book")
(def missing-chapter-root "test/fixtures/synthetic/missing-chapter-book")

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
            #(config/load-config {:book-root missing-chapter-root
                                  :config-path "book.edn"}))]
    (is (= :clj-book.config/missing-chapter (:error/type d)))
    (is (some #{"chapters/does-not-exist.clj"}
              (:missing (:error/context d))))))

(deftest duplicate-chapter-rejected
  (testing "Manuscript validation owns chapter uniqueness (pure)"
    (let [d (catch-data
              #(config/validate {:book/slug "x" :book/title "t"
                                 :book/chapters ["a.adoc" "a.adoc"]}
                                "book.edn"))]
      (is (= :clj-book.config/duplicate-chapter (:error/type d)))
      (is (= ["a.adoc"] (:duplicates (:error/context d)))))))

(deftest config-file-not-found
  (let [d (catch-data
            #(config/load-config {:book-root valid-root
                                  :config-path "nope.edn"}))]
    (is (= :clj-book.config/missing (:error/type d)))))
