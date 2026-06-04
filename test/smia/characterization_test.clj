(ns smia.characterization-test
  "Real-manuscript regression: build the dogfood manual to PDF through the
   public API and assert its structure with PDFBox. This is the platform's
   end-to-end proof that the whole pipeline holds together."
  (:require
   [smia.api :as api]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (org.apache.pdfbox Loader)
   (org.apache.pdfbox.text PDFTextStripper)))

(def manual-root "manual")

(defn- out-root []
  (str (System/getProperty "java.io.tmpdir")
       "/smia-char-" (System/nanoTime)))

(defn- build! [editions]
  (api/build {:book-root manual-root :editions editions :output-root (out-root)}))

(defn- artifact-path [man edition]
  (:path (first (filter #(= edition (:edition %)) (:artifacts man)))))

(deftest ^:integration manual-builds-for-both-pdf-editions
  (let [man (build! [:screen :print])]
    (is (= "smia-manual" (:book/slug man)))
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
          (is (str/includes? text "Error Catalog"))))
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
    (testing "pages nest in clean per-page directories"
      (is (.exists (io/file dir "part-1/quickstart/index.html")))
      (is (.exists (io/file dir "appendix-a/errors/index.html"))))
    (testing "the manual ships the sidebar layout, on the home and chapter pages"
      (is (str/includes? (slurp (io/file dir "index.html")) "class=\"book-sidebar\""))
      (is (str/includes? (slurp (io/file dir "part-1/quickstart/index.html"))
                         "class=\"book-sidebar\"")))
    (testing "the home page is a title card: the book title, but no duplicate TOC"
      (let [index (slurp (io/file dir "index.html"))]
        (is (str/includes? index "The Smia Manual"))
        (is (not (str/includes? index "class=\"toc\"")))))
    (testing "every internal link resolves to a real file and anchor"
      (let [base   (.toPath dir)
            htmls  (filter #(and (.isFile ^java.io.File %)
                                 (str/ends-with? (.getName ^java.io.File %) ".html"))
                           (file-seq dir))
            relof  (fn [^java.io.File f] (str (.relativize base (.toPath f))))
            dirof  (fn [rel] (if-let [i (str/last-index-of rel "/")]
                               (subs rel 0 (inc i)) ""))
            ids    (into {} (map (fn [^java.io.File f]
                                   [(relof f)
                                    (set (map second (re-seq #"id=\"([^\"]+)\""
                                                             (slurp f))))]))
                         htmls)
            resolve (fn [from-dir path]
                      (-> (java.nio.file.Paths/get from-dir (into-array String []))
                          (.resolve ^String path) (.normalize) str))]
        (doseq [^java.io.File f htmls
                :let [fr (relof f) from (dirof fr) html (slurp f)]
                [_ href] (re-seq #"href=\"([^\"]+)\"" html)
                :when (not (re-find #"^[a-z]+:" href))      ; skip http(s)/data/mailto
                :when (not (str/ends-with? href ".css"))]
          (let [[path frag] (str/split href #"#" 2)
                resolved    (when-not (str/blank? path) (resolve from path))
                target      (cond
                              (str/blank? path)                   fr
                              (str/ends-with? path "/")           (str resolved
                                                                       (when (seq resolved) "/")
                                                                       "index.html")
                              (str/includes? (str (last (str/split resolved #"/"))) ".") resolved
                              :else (str resolved "/index.html"))]
            (is (.exists (io/file dir target))
                (str href " in " fr " resolves to a real file"))
            (when (and frag (str/ends-with? target ".html"))
              (is (contains? (get ids target) frag)
                  (str href " in " fr " lands on a real anchor")))))))
    (testing "the stylesheet and referenced images are emitted"
      (is (.exists (io/file dir "styles.css")))
      (is (.exists (io/file dir "images/pipeline.svg"))))))

(deftest ^:integration manual-downloads-page-is-site-only
  (let [man      (build! [:site :epub])
        site-dir (:path (first (filter #(= :site (:edition %)) (:artifacts man))))
        epub     (artifact-path man :epub)]
    (testing "the site emits a downloads page linking every published asset"
      (let [dl (io/file site-dir "downloads/index.html")]
        (is (.exists dl))
        (let [html (slurp dl)]
          (doseq [asset ["smia-manual-screen.pdf"
                         "smia-manual-print.pdf"
                         "smia-manual-print-x.pdf"
                         "smia-manual.epub"]]
            (is (str/includes? html asset) (str asset " is linked"))))))
    (testing "the home contents links to the downloads page"
      (is (str/includes? (slurp (io/file site-dir "index.html"))
                         "href=\"downloads/\"")))
    (testing "the EPUB carries no downloads page"
      (with-open [zf (java.util.zip.ZipFile. (io/file epub))]
        (is (nil? (.getEntry zf "OEBPS/downloads.xhtml")))))))

(deftest ^:integration manual-builds-every-edition-in-one-run
  (let [man (build! [:screen :print :site :epub])]
    (is (= [:screen :print :site :epub] (:build/editions man)))
    (is (= [:screen :print :site :epub] (mapv :edition (:artifacts man))))
    (doseq [art (:artifacts man)]
      (is (.exists (io/file (:path art)))
          (str (name (:edition art)) " artifact exists")))))

(deftest ^:integration licensee-notice-stamps-pdf-but-not-site
  (let [out      (out-root)
        licensee "Ada Lovelace <ada@example.com>"
        man      (api/build {:book-root manual-root
                             :editions [:screen :site]
                             :licensee licensee
                             :output-root out})
        pdf      (artifact-path man :screen)
        site-dir (:path (first (filter #(= :site (:edition %)) (:artifacts man))))]
    (testing "every-page footer notice is present in the PDF text"
      (with-open [doc (Loader/loadPDF (io/file pdf))]
        (let [text (.getText (PDFTextStripper.) doc)]
          (is (str/includes? text (str "Licensed to " licensee))))))
    (testing "the HTML site does not carry the notice"
      (is (not (str/includes? (slurp (io/file site-dir "index.html"))
                              "Licensed to"))))))

(deftest ^:integration manual-epub-is-byte-reproducible
  (testing "two EPUB builds of the same manuscript are identical bytes"
    (let [a (artifact-path (build! [:epub]) :epub)
          b (artifact-path (build! [:epub]) :epub)]
      (is (java.util.Arrays/equals
            (java.nio.file.Files/readAllBytes (.toPath (io/file a)))
            (java.nio.file.Files/readAllBytes (.toPath (io/file b))))))))

(deftest ^:integration manual-builds-the-print-x-edition
  (let [man  (build! [:print-x])
        path (artifact-path man :print-x)]
    (is (str/ends-with? path "smia-manual-print-x.pdf"))
    (with-open [doc (Loader/loadPDF (io/file path))]
      (let [catalog (.getDocumentCatalog doc)]
        (testing "the document identifies as PDF/X-4"
          (let [xmp (slurp (.exportXMPMetadata (.getMetadata catalog)))]
            (is (str/includes? xmp "PDF/X-4"))))
        (testing "an output intent is embedded"
          (is (seq (.getOutputIntents catalog))))
        (testing "every font on every page is embedded"
          (doseq [page (.getPages doc)
                  :let [res (.getResources page)]
                  fname (.getFontNames res)]
            (let [font (.getFont res fname)]
              (is (.isEmbedded font)
                  (str (.getName font) " must be embedded for PDF/X")))))))))

(deftest ^:integration manual-build-is-structurally-reproducible
  (testing "two builds of the same manuscript agree on pages and text"
    (let [a (artifact-path (build! [:screen]) :screen)
          b (artifact-path (build! [:screen]) :screen)]
      (with-open [da (Loader/loadPDF (io/file a))
                  db (Loader/loadPDF (io/file b))]
        (is (= (.getNumberOfPages da) (.getNumberOfPages db)))
        (is (= (.getText (PDFTextStripper.) da)
               (.getText (PDFTextStripper.) db)))))))
