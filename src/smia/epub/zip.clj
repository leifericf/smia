(ns smia.epub.zip
  "EPUB context (shell): write assembled package entries as a
   byte-reproducible EPUB archive.

   The OCF rules and the determinism contract live here: `mimetype` must
   be the archive's first entry, stored uncompressed; every entry gets a
   pinned timestamp (set time-zone-independently) so the same entries
   always zip to the same bytes — the EPUB analogue of the FO renderer's
   pinned creation date. `:resource` entries are read from the book root;
   a missing resource is a warning, not a failure."
  (:require
   [smia.error :as error]
   [clojure.java.io :as io])
  (:import
   (java.time LocalDateTime ZoneId)
   (java.util.zip CRC32 ZipEntry ZipOutputStream)))

(def ^:private pinned-time-millis
  "Fixed per-entry timestamp. DOS zip time cannot represent the epoch (it
   starts in 1980), so the earliest representable instant is pinned
   instead. The millis are derived from the *local* 1980-01-01 so the DOS
   field encodes the same bytes in every time zone, and `setTime` (unlike
   `setTimeLocal`) adds no extended-timestamp extra field — OCF forbids
   extra fields on the mimetype entry."
  (-> (LocalDateTime/of 1980 1 1 0 0 0)
      (.atZone (ZoneId/systemDefault))
      (.toInstant)
      (.toEpochMilli)))

(defn- entry-bytes
  "The bytes for one entry: inline `:content`, or the `:resource` file
   under `book-root` (nil when missing). A resource that resolves —
   symlinks followed — outside the book root is a hard error: the
   assembler's lexical check cannot see a symlink pointing out."
  [{:keys [content resource]} book-root]
  (cond
    content  (.getBytes ^String content "UTF-8")
    resource (let [f (io/file book-root resource)]
               (when (.exists f)
                 (let [root (str (.getCanonicalPath (io/file book-root))
                                 java.io.File/separator)]
                   (when-not (.startsWith (.getCanonicalPath f) root)
                     (throw (error/ex :smia.epub.zip/unsafe-resource
                                      (str "Resource " (pr-str resource)
                                           " resolves outside the book "
                                           "directory and is not packaged.")
                                      {:resource resource}))))
                 (java.nio.file.Files/readAllBytes (.toPath f))))))

(defn- put-entry! [^ZipOutputStream zos {:keys [path method]} ^bytes bytes]
  (let [entry (doto (ZipEntry. ^String path)
                (.setTime pinned-time-millis))]
    (when (= :stored method)
      (.setMethod entry ZipEntry/STORED)
      (.setSize entry (alength bytes))
      (.setCrc entry (.getValue (doto (CRC32.) (.update bytes)))))
    (.putNextEntry zos entry)
    (.write zos bytes)
    (.closeEntry zos)))

(defn write!
  "Write assembled `entries` (see `epub.assemble`) as an EPUB archive at
   `epub-path`, reading `:resource` entries from `book-root`. Returns
   `{:warnings [{:warning/type :path} …]}`."
  [{:keys [entries epub-path book-root]}]
  (when-not (= "mimetype" (:path (first entries)))
    (throw (error/ex :smia.epub.zip/mimetype-not-first
                     "The mimetype entry must come first in an EPUB archive."
                     {:first (:path (first entries))})))
  (io/make-parents (io/file epub-path))
  (let [warnings (volatile! [])]
    (with-open [zos (ZipOutputStream. (io/output-stream epub-path))]
      (doseq [entry entries]
        (if-let [bytes (entry-bytes entry book-root)]
          (put-entry! zos entry bytes)
          (vswap! warnings conj
                  {:warning/type :smia.epub.zip/missing-resource
                   :path         (:resource entry)}))))
    {:warnings @warnings}))
