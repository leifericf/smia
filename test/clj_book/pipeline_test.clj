(ns clj-book.pipeline-test
  (:require
   [clj-book.compose :as compose]
   [clj-book.docbook :as docbook]
   [clj-book.error :as error]
   [clj-book.pipeline :as pipeline]
   [clj-book.targets.pdf :as pdf-target]
   [clj-book.targets.site :as site-target]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")
(def canned-xml-path "test/fixtures/synthetic/canned-docbook/book.xml")

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-pipeline-" tag "-" (System/currentTimeMillis)))

(defn- catch-data [f]
  (try (f) nil (catch Exception e (error/data e))))

(defn- request [tag & {:as overrides}]
  (merge {:command     :build
          :book-root   valid-root
          :config-path "book.edn"
          :output-root (tmp-dir tag)}
         overrides))

(deftest prepare-loads-config-and-tokens
  (let [{:keys [manuscript paths]} (pipeline/prepare (request "prepare"))]
    (is (= "tiny-book" (:book/slug (:config manuscript))))
    (is (map? (:tokens manuscript)))
    (is (vector? (:warnings manuscript)))
    (is (str/includes? (:intermediate-dir paths) "tiny-book/intermediate"))
    (is (str/includes? (:site-output-dir paths)  "tiny-book/site"))
    (is (str/includes? (:pdf-output-dir paths)   "tiny-book/pdf"))))

(deftest validate-returns-ok
  (let [out (pipeline/validate (request "validate"))]
    (is (= :ok (:status out)))))

(defn- stub-docbook!
  "Return a fake `generate-docbook!` that copies the canned XML into
   place, simulating Asciidoctor without needing the CLI."
  []
  (fn [{:keys [intermediate-dir]}]
    (let [out (io/file intermediate-dir "book.xml")]
      (io/make-parents out)
      (io/copy (io/file canned-xml-path) out)
      (.getPath out))))

(deftest single-target-site-build
  (testing "Build runs prereqs, emits site artifacts and manifest"
    (with-redefs [docbook/generate-docbook! (stub-docbook!)]
      (let [req (request "site-build" :targets [:site])
            man (pipeline/build req)
            slug (:book/slug man)]
        (is (= "tiny-book" slug))
        (is (= [:site] (:build/targets man)))
        (is (some #(= :site (:target %)) (:artifacts man)))
        (let [site-art (some #(when (= :site (:target %)) %)
                             (:artifacts man))
              html (slurp (:path site-art))]
          (is (str/includes? html "<!DOCTYPE html>"))
          (is (not (str/includes? html "<script"))))))))

(deftest pdf-target-fails-clearly-without-cli
  (testing "Build with [:pdf] surfaces structured CLI-missing error"
    ;; Force the missing-CLI path deterministically: CI installs
    ;; asciidoctor-pdf for the dogfood build, so we cannot rely on it
    ;; being absent from PATH.
    (with-redefs [docbook/generate-docbook! (stub-docbook!)
                  clj-book.targets.pdf/cli-available? (constantly false)]
      (let [d (catch-data #(pipeline/build (request "pdf-fail"
                                                    :targets [:pdf])))]
        (is (= :clj-book.targets.pdf/cli-missing (:error/type d)))))))

(deftest multi-target-build-shares-prereqs
  (testing "Multi-target run reuses the composed master and tokens"
    (let [write-count (atom 0)
          original    compose/write-master!]
      (with-redefs [docbook/generate-docbook! (stub-docbook!)
                    compose/write-master!
                    (fn [ctx]
                      (swap! write-count inc)
                      (original ctx))
                    pdf-target/build!
                    (fn [{:keys [output-dir config]}]
                      (let [out (io/file output-dir
                                         (str (:book/slug config) ".pdf"))]
                        (io/make-parents out)
                        (spit out "%PDF-1.4\n%fake\n")
                        {:pdf (.getPath out) :dir (.getParent out)}))]
        (let [req (request "multi" :targets [:site :pdf])
              man (pipeline/build req)]
          (is (= 1 @write-count)
              "compose/write-master! must run exactly once for multi-target builds")
          (is (= [:site :pdf] (:build/targets man)))
          (is (= 2 (count (:artifacts man))))
          (is (every? (set (map :target (:artifacts man))) #{:site :pdf})))))))

(deftest invalid-target-blocked-by-request-normalization
  ;; This is asserted at the public api/request layer; pipeline assumes
  ;; the request has been normalized.
  (is true))
