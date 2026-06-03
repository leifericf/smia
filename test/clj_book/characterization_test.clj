(ns clj-book.characterization-test
  "Real-manuscript regression: build the dogfood manual to PDF through the
   public API and assert its structure with PDFBox. This is the platform's
   end-to-end proof that the whole pipeline holds together."
  (:require
   [clj-book.api :as api]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (org.apache.pdfbox Loader)
   (org.apache.pdfbox.text PDFTextStripper)))

(def manual-root "manual")

(defn- out-root []
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-char-" (System/nanoTime)))

(defn- build! [profiles]
  (api/build {:book-root manual-root :profiles profiles :output-root (out-root)}))

(defn- artifact-path [man profile]
  (:path (first (filter #(= profile (:profile %)) (:artifacts man)))))

(deftest ^:integration manual-builds-for-both-profiles
  (let [man (build! [:screen :print])]
    (is (= "clj-book-manual" (:book/slug man)))
    (is (= [:screen :print] (:build/profiles man)))
    (is (= 2 (count (:artifacts man))))
    (doseq [art (:artifacts man)]
      (is (.exists (io/file (:path art))) "each profile's PDF exists"))
    (is (.exists (io/file (:manifest/path man))) "manifest is written")))

(deftest ^:integration manual-pdf-has-expected-structure
  (let [man  (build! [:print])
        path (artifact-path man :print)]
    (with-open [doc (Loader/loadPDF (io/file path))]
      (testing "the book paginates onto many pages"
        (is (> (.getNumberOfPages doc) 1)))
      (testing "a PDF outline (bookmarks) is present"
        (is (some? (.. doc getDocumentCatalog getDocumentOutline))))
      (testing "known content appears, including the TOC"
        (let [text (.getText (PDFTextStripper.) doc)]
          (is (str/includes? text "Contents"))
          (is (str/includes? text "Quickstart"))
          (is (str/includes? text "Error catalog"))))
      (testing "the structural apparatus is present"
        (let [text (.getText (PDFTextStripper.) doc)]
          (is (str/includes? text "Preface")     "named front matter")
          (is (str/includes? text "Part I")      "part dividers are numbered")
          (is (str/includes? text "Chapter 1")   "chapters are numbered")
          (is (str/includes? text "Figure 1")    "a numbered figure caption")
          (is (str/includes? text "Listing 1")   "a numbered code listing")
          (is (str/includes? text "Appendix A")  "a lettered appendix")
          (is (str/includes? text "Bibliography") "the generated bibliography")
          (is (str/includes? text "Index")       "the generated index"))))))

(deftest ^:integration manual-build-is-structurally-reproducible
  (testing "two builds of the same manuscript agree on pages and text"
    (let [a (artifact-path (build! [:screen]) :screen)
          b (artifact-path (build! [:screen]) :screen)]
      (with-open [da (Loader/loadPDF (io/file a))
                  db (Loader/loadPDF (io/file b))]
        (is (= (.getNumberOfPages da) (.getNumberOfPages db)))
        (is (= (.getText (PDFTextStripper.) da)
               (.getText (PDFTextStripper.) db)))))))
