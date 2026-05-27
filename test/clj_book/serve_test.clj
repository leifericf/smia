(ns clj-book.serve-test
  (:require
   [clj-book.docbook :as docbook]
   [clj-book.error :as error]
   [clj-book.pipeline :as pipeline]
   [clj-book.serve :as serve]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")
(def invalid-root "test/fixtures/synthetic/invalid-book")
(def canned-xml-path "test/fixtures/synthetic/canned-docbook/book.xml")

(defn- tmp [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-serve-" tag "-" (System/currentTimeMillis)))

(defn- catch-data [f]
  (try (f) nil (catch Exception e (error/data e))))

(defn- stub-docbook!
  []
  (fn [{:keys [intermediate-dir]}]
    (let [out (io/file intermediate-dir "book.xml")]
      (io/make-parents out)
      (io/copy (io/file canned-xml-path) out)
      (.getPath out))))

(deftest preview-pages-built-from-context
  (with-redefs [docbook/generate-docbook! (stub-docbook!)]
    (let [ctx (pipeline/prepare {:book-root valid-root
                                 :config-path "book.edn"
                                 :output-root (tmp "ctx")
                                 :command :serve})
          pages (serve/build-preview-pages ctx)]
      (is (contains? pages "/index.html"))
      (is (some #(str/starts-with? % "/chapters/") (keys pages))))))

(deftest invalid-manuscript-surfaces-clear-error
  (testing "Serving an invalid book fails during prepare, not during request"
    (let [d (catch-data
              #(pipeline/prepare {:book-root invalid-root
                                  :config-path "book.edn"
                                  :output-root (tmp "invalid")
                                  :command :serve}))]
      (is (= :clj-book.config/missing-required-key (:error/type d))))))
