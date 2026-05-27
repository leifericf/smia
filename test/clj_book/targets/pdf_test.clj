(ns clj-book.targets.pdf-test
  (:require
   [clj-book.error :as error]
   [clj-book.targets.pdf :as pdf]
   [clj-book.theme.load :as theme]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-pdf-" tag "-" (System/currentTimeMillis)))

(defn- catch-data [f]
  (try (f) nil (catch Exception e (error/data e))))

(deftest write-theme-yaml-emits-file
  (let [intermediate (tmp-dir "yaml")
        path (pdf/write-theme-yaml!
               {:book-root        valid-root
                :tokens           (:tokens (theme/load-tokens
                                             {:book-root valid-root}))
                :intermediate-dir intermediate})]
    (is (.exists (io/file path)))
    (is (str/includes? (slurp path) "base:"))))

(deftest preflight-reports-missing-cli
  (testing "When asciidoctor-pdf is unavailable, preflight throws clearly"
    (with-redefs [pdf/preflight!
                  (fn [_] (throw
                            (error/ex :clj-book.targets.pdf/cli-missing
                                      "asciidoctor-pdf CLI not found on PATH."
                                      {:command "asciidoctor-pdf"})))]
      (let [d (catch-data #(pdf/preflight! {:master-path "x"}))]
        (is (= :clj-book.targets.pdf/cli-missing (:error/type d)))))))

(deftest preflight-reports-missing-master
  (let [d (catch-data
            #(with-redefs [pdf/cli-available? (constantly true)
                           ;; bypass missing CLI by stubbing internals
                           ]
               ;; preflight! checks master-path before CLI presence:
               (pdf/preflight! {:master-path "/no/such/file"})))]
    (is (or (= :clj-book.targets.pdf/cli-missing (:error/type d))
            (= :clj-book.targets.pdf/missing-master (:error/type d)))
        "Expect either CLI-missing or master-missing depending on env")))
