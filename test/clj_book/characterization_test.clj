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

(defn- build! [editions]
  (api/build {:book-root manual-root :editions editions :output-root (out-root)}))

(defn- artifact-path [man edition]
  (:path (first (filter #(= edition (:edition %)) (:artifacts man)))))

(deftest ^:integration manual-builds-for-both-pdf-editions
  (let [man (build! [:screen :print])]
    (is (= "clj-book-manual" (:book/slug man)))
    (is (= [:screen :print] (:build/editions man)))
    (is (= 2 (count (:artifacts man))))
    (doseq [art (:artifacts man)]
      (is (.exists (io/file (:path art))) "each edition's PDF exists"))
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

(deftest ^:integration manual-builds-the-site-edition
  (let [man (build! [:site])
        art (first (:artifacts man))
        dir (io/file (:path art))]
    (is (= [:site] (:build/editions man)))
    (testing "the home page exists and carries the contents"
      (let [index (slurp (io/file dir "index.html"))]
        (is (str/includes? index "Contents"))
        (is (str/includes? index "The clj-book Manual"))))
    (testing "every page the contents links to exists on disk"
      (let [index (slurp (io/file dir "index.html"))
            hrefs (map second (re-seq #"href=\"([^\"#]+\.html)" index))]
        (is (seq hrefs))
        (doseq [h hrefs]
          (is (.exists (io/file dir h)) (str h " is linked from the TOC")))))
    (testing "every internal link lands on a real file and anchor"
      (let [pages  (filter #(str/ends-with? (.getName ^java.io.File %) ".html")
                           (.listFiles dir))
            ids    (into {}
                         (map (fn [^java.io.File f]
                                [(.getName f)
                                 (set (map second (re-seq #"id=\"([^\"]+)\""
                                                          (slurp f))))]))
                         pages)]
        (doseq [^java.io.File f pages
                [_ href] (re-seq #"href=\"([^\"]+)\"" (slurp f))
                :when (not (re-find #"^[a-z]+:" href))
                :when (not (str/ends-with? href ".css"))]
          (let [[file frag] (str/split href #"#" 2)
                target      (if (str/blank? file) (.getName f) file)]
            (is (contains? ids target)
                (str href " in " (.getName f) " names a real page"))
            (when frag
              (is (contains? (get ids target) frag)
                  (str href " in " (.getName f) " lands on a real anchor")))))))
    (testing "the stylesheet and referenced images are emitted"
      (is (.exists (io/file dir "styles.css")))
      (is (.exists (io/file dir "images/pipeline.svg"))))))

(deftest ^:integration manual-builds-every-edition-in-one-run
  (let [man (build! [:screen :print :site :epub])]
    (is (= [:screen :print :site :epub] (:build/editions man)))
    (is (= [:screen :print :site :epub] (mapv :edition (:artifacts man))))
    (doseq [art (:artifacts man)]
      (is (.exists (io/file (:path art)))
          (str (name (:edition art)) " artifact exists")))))

(deftest ^:integration manual-epub-is-byte-reproducible
  (testing "two EPUB builds of the same manuscript are identical bytes"
    (let [a (artifact-path (build! [:epub]) :epub)
          b (artifact-path (build! [:epub]) :epub)]
      (is (java.util.Arrays/equals
            (java.nio.file.Files/readAllBytes (.toPath (io/file a)))
            (java.nio.file.Files/readAllBytes (.toPath (io/file b))))))))

(deftest ^:integration manual-build-is-structurally-reproducible
  (testing "two builds of the same manuscript agree on pages and text"
    (let [a (artifact-path (build! [:screen]) :screen)
          b (artifact-path (build! [:screen]) :screen)]
      (with-open [da (Loader/loadPDF (io/file a))
                  db (Loader/loadPDF (io/file b))]
        (is (= (.getNumberOfPages da) (.getNumberOfPages db)))
        (is (= (.getText (PDFTextStripper.) da)
               (.getText (PDFTextStripper.) db)))))))
