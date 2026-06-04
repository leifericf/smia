(ns smia.epub.epubcheck-test
  "Conformance: the manual's EPUB passes epubcheck with zero errors. This
   is the edition's external proof, the EPUB analogue of asserting PDF
   structure with PDFBox."
  (:require
   [smia.api :as api]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]])
  (:import
   (com.adobe.epubcheck.api EpubCheck)
   (java.io PrintWriter StringWriter)))

(defn- out-root []
  (str (System/getProperty "java.io.tmpdir")
       "/smia-epubcheck-" (System/nanoTime)))

(deftest ^:integration manual-epub-passes-epubcheck
  (let [man  (api/build {:book-root "manual" :editions [:epub]
                         :output-root (out-root)})
        path (:path (first (:artifacts man)))
        sw   (StringWriter.)
        ;; doValidate returns the problem count (older releases a boolean)
        result (.doValidate (EpubCheck. (io/file path) (PrintWriter. sw)))]
    (is (.exists (io/file path)))
    (is (or (true? result) (= 0 result))
        (str "epubcheck found problems:\n" sw))))
