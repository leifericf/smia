(ns smia.fo.render-test
  "FOP integration: render real PDFs and assert their structure with
   PDFBox. Uses only base-14 fonts and no external resources, so there is
   no font or network dependence."
  (:require
   [smia.error :as error]
   [smia.fo.render :as render]
   [smia.fo.serialize :as ser]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io ByteArrayOutputStream)
   (org.apache.pdfbox Loader)
   (org.apache.pdfbox.text PDFTextStripper)))

(defn- render-bytes [fo-xml]
  (let [out (ByteArrayOutputStream.)]
    (render/render-pdf! fo-xml out {})
    (.toByteArray out)))

(defn- minimal-fo [& body-blocks]
  (ser/serialize
    [:fo/root
     [:fo/layout-master-set
      [:fo/simple-page-master {:master-name  "page"
                               :page-height  "297mm"
                               :page-width   "210mm"
                               :margin       "20mm"}
       [:fo/region-body]]]
     [:fo/page-sequence {:master-reference "page"}
      (into [:fo/flow {:flow-name "xsl-region-body"}] body-blocks)]]))

(defn- pdf-pages [^bytes b]
  (with-open [doc (Loader/loadPDF b)]
    (.getNumberOfPages doc)))

(defn- pdf-text [^bytes b]
  (with-open [doc (Loader/loadPDF b)]
    (.getText (PDFTextStripper.) doc)))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest ^:integration renders-valid-fo-to-pdf
  (let [bytes (render-bytes (minimal-fo [:fo/block "Hello from FOP"]))]
    (testing "output is a PDF"
      (is (str/starts-with? (String. bytes 0 5) "%PDF-")))
    (testing "the page renders and contains the text"
      (is (= 1 (pdf-pages bytes)))
      (is (str/includes? (pdf-text bytes) "Hello from FOP")))))

(deftest ^:integration rendering-does-not-reenter-the-xml-parser
  ;; Regression: finishing a page makes FOP lazily load its event model
  ;; through a second XML parse, from inside the outer FO parse's
  ;; endElement. Driving FOP with an identity Transformer reused the
  ;; in-progress parser, so that nested parse aborted with
  ;; "FWK005 parse may not be called while parsing" and no page ever
  ;; rendered. Rendering must drive FOP with its own reader.
  (let [out   (ByteArrayOutputStream.)
        res   (render/render-pdf! (minimal-fo [:fo/block "page one"]) out {})
        bytes (.toByteArray out)]
    (is (str/starts-with? (String. bytes 0 5) "%PDF-"))
    (is (= 1 (pdf-pages bytes)) "the page finished without a nested-parse failure")
    (is (vector? (:warnings res)))))

(deftest ^:integration multi-page-content-paginates
  (let [blocks (for [i (range 80)]
                 [:fo/block {:space-after "6pt"} (str "Paragraph number " i)])
        bytes  (render-bytes (apply minimal-fo blocks))]
    (is (> (pdf-pages bytes) 1) "enough content spills onto a second page")))

(deftest ^:integration render-is-structurally-reproducible
  (let [fo (minimal-fo [:fo/block "Reproducible"]
                       [:fo/block "Across runs"])
        a  (render-bytes fo)
        b  (render-bytes fo)]
    (testing "identical FO yields the same page count and text"
      (is (= (pdf-pages a) (pdf-pages b)))
      (is (= (pdf-text a) (pdf-text b))))))

(deftest ^:integration invalid-fo-surfaces-structured-error
  (testing "a page-sequence referencing a missing master fails clearly"
    (let [bad (ser/serialize
                [:fo/root
                 [:fo/page-sequence {:master-reference "does-not-exist"}
                  [:fo/flow {:flow-name "xsl-region-body"}
                   [:fo/block "orphan"]]]])
          d   (catch-data #(render-bytes bad))]
      (is (contains? #{:smia.fo.render/fo-error
                       :smia.fo.render/render-failed}
                     (:error/type d))))))

(deftest ^:integration hyphenation-patterns-ride-the-classpath
  ;; The hyphenation-pattern jar is a core dependency; a narrow justified
  ;; column in a language-tagged document must hyphenate without FOP
  ;; emitting a missing-pattern event.
  (let [fo  (ser/serialize
              [:fo/root {:language "en"}
               [:fo/layout-master-set
                [:fo/simple-page-master {:master-name "page"
                                         :page-height "297mm"
                                         :page-width  "60mm"
                                         :margin      "5mm"}
                 [:fo/region-body]]]
               [:fo/page-sequence {:master-reference "page"}
                [:fo/flow {:flow-name "xsl-region-body"}
                 [:fo/block {:text-align "justify" :hyphenate "true"}
                  "Internationalization and characterization considerations
                   demand uncompromisingly sophisticated hyphenation."]]]])
        out (ByteArrayOutputStream.)
        res (render/render-pdf! fo out {})]
    (is (str/starts-with? (String. (.toByteArray out) 0 5) "%PDF-"))
    (is (not-any? #(str/includes? (str/lower-case (str %)) "hyphen")
                  (:warnings res))
        "no missing-hyphenation-pattern warning")))

(deftest ^:integration warnings-are-returned-not-thrown
  ;; A valid document should render without errors; warnings (if any) come
  ;; back in the result rather than aborting the build.
  (let [out (ByteArrayOutputStream.)
        res (render/render-pdf! (minimal-fo [:fo/block "ok"]) out {})]
    (is (vector? (:warnings res)))))
