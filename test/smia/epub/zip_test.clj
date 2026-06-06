(ns smia.epub.zip-test
  (:require
   [smia.epub.zip :as zip]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.util Arrays)
   (java.util.zip ZipEntry ZipInputStream)))

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/smia-epub-zip-" tag "-" (System/nanoTime)))

(def ^:private entries
  [{:path "mimetype" :content "application/epub+zip" :method :stored}
   {:path "META-INF/container.xml" :content "<container/>"}
   {:path "OEBPS/content.opf" :content "<package/>"}
   {:path "OEBPS/chapter-01.xhtml" :content "<html/>"}])

(defn- zip-entries
  "`[name method]` pairs of every entry in the archive, in order."
  [path]
  (with-open [zis (ZipInputStream. (io/input-stream path))]
    (loop [out []]
      (if-let [e (.getNextEntry zis)]
        (recur (conj out [(.getName e) (.getMethod e)]))
        out))))

(deftest mimetype-is-the-first-entry-and-stored
  (let [out (str (tmp-dir "order") "/book.epub")]
    (zip/write! {:entries entries :epub-path out :book-root (tmp-dir "nores")})
    (let [[[name method] & _] (zip-entries out)]
      (is (= "mimetype" name))
      (is (= ZipEntry/STORED method)))))

(deftest archive-preserves-entry-order-and-content
  (let [out (str (tmp-dir "content") "/book.epub")]
    (zip/write! {:entries entries :epub-path out :book-root (tmp-dir "nores")})
    (is (= ["mimetype" "META-INF/container.xml" "OEBPS/content.opf"
            "OEBPS/chapter-01.xhtml"]
           (mapv first (zip-entries out))))))

(deftest two-writes-are-byte-identical
  (let [a (str (tmp-dir "det-a") "/book.epub")
        b (str (tmp-dir "det-b") "/book.epub")]
    (zip/write! {:entries entries :epub-path a :book-root (tmp-dir "nores")})
    (zip/write! {:entries entries :epub-path b :book-root (tmp-dir "nores")})
    (is (Arrays/equals (java.nio.file.Files/readAllBytes
                         (.toPath (io/file a)))
                       (java.nio.file.Files/readAllBytes
                         (.toPath (io/file b))))
        "the same entries always zip to the same bytes")))

(deftest resource-entries-read-from-the-book-root
  (let [root (tmp-dir "root")
        out  (str (tmp-dir "res") "/book.epub")]
    (io/make-parents (io/file root "images/x.png"))
    (spit (io/file root "images/x.png") "png-bytes")
    (let [result (zip/write!
                   {:entries   (conj entries
                                     {:path "OEBPS/images/x.png"
                                      :resource "images/x.png"})
                    :epub-path out
                    :book-root root})]
      (is (= [] (:warnings result)))
      (is (some #(= "OEBPS/images/x.png" (first %)) (zip-entries out))))))

(deftest missing-resource-is-a-warning-not-a-failure
  (let [out (str (tmp-dir "miss") "/book.epub")
        result (zip/write!
                 {:entries   (conj entries
                                   {:path "OEBPS/images/nope.png"
                                    :resource "images/nope.png"})
                  :epub-path out
                  :book-root (tmp-dir "miss-root")})]
    (testing "the package is still written, without the resource"
      (is (.exists (io/file out)))
      (is (not-any? #(= "OEBPS/images/nope.png" (first %)) (zip-entries out)))
      (is (= 1 (count (:warnings result))))
      (is (= :smia.epub.zip/missing-resource
             (:warning/type (first (:warnings result))))))))

(deftest symlinked-resource-cannot-leave-the-book-root
  (let [root   (tmp-dir "symlink")
        secret (io/file (str root "-secret.png"))
        out    (str (tmp-dir "symlink-out") "/book.epub")]
    (spit secret "outside-bytes")
    (.mkdirs (io/file root "images"))
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file root "images/link.png"))
     (.toPath secret)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (let [d (try (zip/write! {:entries   (conj entries
                                               {:path "OEBPS/images/link.png"
                                                :resource "images/link.png"})
                              :epub-path out
                              :book-root root})
                 nil
                 (catch Exception e (smia.error/data e)))]
      (is (= :smia.epub.zip/unsafe-resource (:error/type d))))))
