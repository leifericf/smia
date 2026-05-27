(ns clj-book.characterization-test
  "Pin externally-visible behavior at the public api boundary before the
   internals are refactored. Builds the synthetic `valid-book` (site only,
   so no asciidoctor-pdf CLI is needed) with the DocBook step stubbed from
   canned XML, then asserts the manifest, the on-disk `artifacts.edn`, and
   the rendered HTML structure. If a later phase changes any of these, the
   refactor has altered observable behavior."
  (:require
   [clj-book.api :as api]
   [clj-book.docbook :as docbook]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")
(def canned-xml-path "test/fixtures/synthetic/canned-docbook/book.xml")

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-char-" tag "-" (System/currentTimeMillis)))

(defn- stub-docbook!
  "Stand in for `generate-docbook!` by copying canned DocBook XML into
   place, so the characterization runs without the asciidoctor CLI."
  []
  (fn [{:keys [intermediate-dir]}]
    (let [out (io/file intermediate-dir "book.xml")]
      (io/make-parents out)
      (io/copy (io/file canned-xml-path) out)
      (.getPath out))))

(deftest api-build-site-pins-manifest-and-html
  (with-redefs [docbook/generate-docbook! (stub-docbook!)]
    (let [output-root (tmp-dir "build")
          man (api/build {:book-root   valid-root
                          :targets     [:site]
                          :output-root output-root})]
      (testing "manifest returned from api/build"
        (is (= "tiny-book" (:book/slug man)))
        (is (= [:site] (:build/targets man)))
        (is (= "clj-book" (-> man :build/metadata :tool)))
        (let [site-art (some #(when (= :site (:target %)) %) (:artifacts man))]
          (is (some? site-art) "a :site artifact is recorded")
          (is (str/ends-with? (:path site-art) "index.html"))))
      (testing "artifacts.edn is written to disk with the expected shape"
        (let [edn-path (.getPath (io/file output-root "tiny-book" "artifacts.edn"))
              parsed   (edn/read-string (slurp edn-path))]
          (is (= "tiny-book" (:book/slug parsed)))
          (is (= [:site] (:build/targets parsed)))
          (is (vector? (:artifacts parsed)))))
      (testing "rendered HTML preserves the section.chapter structure"
        (let [index-html (slurp (io/file output-root "tiny-book" "site" "index.html"))
              ch-html    (slurp (io/file output-root "tiny-book" "site"
                                         "chapters" "ch-intro.html"))]
          (is (str/includes? index-html "<!DOCTYPE html>"))
          (is (not (str/includes? index-html "<script")))
          (is (str/includes? index-html "chapters/ch-intro.html")
              "index links to the first chapter page")
          (is (str/includes? ch-html "<section")
              "chapter page renders a section element")
          (is (str/includes? ch-html "class=\"chapter\"")
              "chapter section carries the chapter class"))))))
