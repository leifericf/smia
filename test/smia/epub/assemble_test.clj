(ns smia.epub.assemble-test
  (:require
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.epub.assemble :as epub]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private tokens
  {:color {} :type {} :code {} :spacing {} :layout {}})

(def ^:private manuscript
  {:title     "The Book"
   :author    "An Author"
   :numbering structure/default-numbering
   :references {}
   :sections
   [{:kind :chapter
     :content [:chapter {:id :ch-one :title "One"}
               [:h2 {:id :sec-a} "Alpha"]
               [:p "see " [:xref {:to :sec-b}]]
               [:figure {:id "fig-cat" :caption "A cat"}
                [:img {:src "images/cat.png" :alt "a cat"}]]]}
    {:kind :chapter
     :content [:chapter {:id :ch-two :title "Two"}
               [:h2 {:id :sec-b} "Beta"]]}]})

(def ^:private book (:manuscript (number/assign manuscript)))
(def ^:private result
  (epub/assemble book tokens {:identifier "urn:smia:the-book"}))
(def ^:private entries (:entries result))

(defn- entry [path]
  (first (filter #(= path (:path %)) entries)))

(deftest mimetype-is-first-stored-and-exact
  (let [m (first entries)]
    (is (= "mimetype" (:path m)))
    (is (= "application/epub+zip" (:content m)))
    (is (= :stored (:method m)))))

(deftest container-points-at-the-package-document
  (let [c (entry "META-INF/container.xml")]
    (is (some? c))
    (is (str/includes? (:content c) "full-path=\"OEBPS/content.opf\""))
    (is (str/includes? (:content c)
                       "media-type=\"application/oebps-package+xml\""))))

(deftest package-document-carries-dublin-core
  (let [opf (:content (entry "OEBPS/content.opf"))]
    (is (str/includes? opf "<dc:title>The Book</dc:title>"))
    (is (str/includes? opf "<dc:creator>An Author</dc:creator>"))
    (is (str/includes? opf "<dc:identifier id=\"book-id\">urn:smia:the-book</dc:identifier>"))
    (is (str/includes? opf "<dc:language>en</dc:language>"))
    (testing "the modification stamp is pinned for determinism"
      (is (str/includes? opf
                         "<meta property=\"dcterms:modified\">1970-01-01T00:00:00Z</meta>")))))

(deftest package-manifest-covers-every-content-entry
  (let [opf (:content (entry "OEBPS/content.opf"))]
    (doseq [{:keys [path]} entries
            :when (str/starts-with? path "OEBPS/")
            :when (not= path "OEBPS/content.opf")]
      (is (str/includes? opf (str "href=\"" (subs path 6) "\""))
          (str path " is in the manifest")))
    (testing "media types are declared"
      (is (str/includes? opf "media-type=\"application/xhtml+xml\""))
      (is (str/includes? opf "media-type=\"text/css\""))
      (is (str/includes? opf "media-type=\"image/png\"")))
    (testing "the nav document is marked as such"
      (is (str/includes? opf "properties=\"nav\"")))))

(deftest spine-lists-pages-in-reading-order
  (let [opf   (:content (entry "OEBPS/content.opf"))
        spine (second (re-find #"(?s)<spine>(.*)</spine>" opf))
        refs  (mapv second (re-seq #"idref=\"([^\"]+)\"" spine))]
    (is (= (first refs) "index"))
    (is (< (.indexOf ^java.util.List refs "chapter-01")
           (.indexOf ^java.util.List refs "chapter-02")))))

(deftest accessibility-metadata-is-present-by-construction
  (let [opf (:content (entry "OEBPS/content.opf"))]
    (is (str/includes? opf "<meta property=\"schema:accessMode\">textual</meta>"))
    (testing "visual mode is declared because the book has images"
      (is (str/includes? opf "<meta property=\"schema:accessMode\">visual</meta>")))
    (is (str/includes? opf
                       "<meta property=\"schema:accessModeSufficient\">textual</meta>"))
    (is (str/includes? opf
                       "<meta property=\"schema:accessibilityFeature\">tableOfContents</meta>"))
    (is (str/includes? opf
                       "<meta property=\"schema:accessibilityHazard\">none</meta>"))))

(deftest accessibility-config-overrides-the-defaults
  (let [r   (epub/assemble book tokens
                           {:identifier "x"
                            :accessibility {:summary "Fully navigable."
                                            :hazards ["flashing"]}})
        opf (:content (first (filter #(= "OEBPS/content.opf" (:path %))
                                     (:entries r))))]
    (is (str/includes? opf
                       "<meta property=\"schema:accessibilitySummary\">Fully navigable.</meta>"))
    (is (str/includes? opf
                       "<meta property=\"schema:accessibilityHazard\">flashing</meta>"))))

(deftest text-only-book-has-no-visual-access-mode
  (let [text-book (:manuscript
                    (number/assign
                      (assoc manuscript :sections
                             [(first (:sections manuscript))]
                             :sections
                             [{:kind :chapter
                               :content [:chapter {:id :ch :title "T"}
                                         [:p "words only"]]}])))
        r   (epub/assemble text-book tokens {:identifier "x"})
        opf (:content (first (filter #(= "OEBPS/content.opf" (:path %))
                                     (:entries r))))]
    (is (not (str/includes? opf
                            "<meta property=\"schema:accessMode\">visual</meta>")))))

(deftest nav-document-has-toc-and-landmarks
  (let [nav (:content (entry "OEBPS/nav.xhtml"))]
    (is (str/includes? nav "epub:type=\"toc\""))
    (is (str/includes? nav "epub:type=\"landmarks\""))
    (is (str/includes? nav "href=\"chapter-01.xhtml\""))))

(deftest pages-are-xhtml-documents
  (let [page (:content (entry "OEBPS/chapter-01.xhtml"))]
    (is (str/starts-with? page "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    (is (str/includes? page "xmlns=\"http://www.w3.org/1999/xhtml\""))
    (testing "cross-references use the xhtml extension"
      (is (str/includes? page "href=\"chapter-02.xhtml#sec-b\"")))))

(deftest images-become-resource-entries
  (let [e (entry "OEBPS/images/cat.png")]
    (is (= "images/cat.png" (:resource e)))))

(deftest svg-referencing-page-declares-the-svg-property
  (let [svg-book (:manuscript
                   (number/assign
                     (assoc manuscript :sections
                            [{:kind :chapter
                              :content [:chapter {:id :ch :title "T"}
                                        [:p [:img {:src "images/d.svg"
                                                   :alt "a diagram"}]]]}])))
        r   (epub/assemble svg-book tokens {:identifier "x"})
        opf (:content (first (filter #(= "OEBPS/content.opf" (:path %))
                                     (:entries r))))]
    (is (str/includes? opf "href=\"chapter-01.xhtml\" id=\"chapter-01\" media-type=\"application/xhtml+xml\" properties=\"svg\""))
    (is (str/includes? opf "media-type=\"image/svg+xml\""))))

(deftest assembly-is-deterministic
  (is (= entries
         (:entries (epub/assemble book tokens
                                  {:identifier "urn:smia:the-book"})))))

(deftest epub-never-has-a-downloads-page
  (testing "the downloads page is site-only by construction"
    (is (nil? (entry "OEBPS/downloads.xhtml")))
    (is (not-any? #(str/includes? (str (:path %)) "downloads") entries))))
