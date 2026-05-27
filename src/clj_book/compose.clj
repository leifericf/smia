(ns clj-book.compose
  "Compose a deterministic master `book.adoc` with `include::` directives
   in configured chapter order."
  (:require
   [clj-book.error :as error]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- duplicate-chapters [chapters]
  (->> (frequencies chapters)
       (filter (fn [[_ n]] (> n 1)))
       (map key)
       sort
       vec))

(defn- header-lines [config]
  (let [{:book/keys [title authors revision]} config]
    (cond-> ["= " title]
      authors  (conj "\n" (str/join "; " authors))
      revision (conj "\n" revision))))

(defn- chapter-include [book-root chapter]
  (when-not (.exists (io/file book-root chapter))
    (throw (error/ex :clj-book.compose/missing-chapter
                     (str "Referenced chapter not found: " chapter)
                     {:book-root book-root :chapter chapter})))
  (str "include::" chapter "[]"))

(defn master-adoc
  "Return the deterministic master AsciiDoc string for the given config.
   Lines and chapter order follow the configuration map exactly."
  [{:keys [book-root config]}]
  (let [chapters (:book/chapters config)
        dupes    (duplicate-chapters chapters)]
    (when (seq dupes)
      (throw (error/ex :clj-book.compose/duplicate-chapter
                       (str "Duplicate chapter reference(s): "
                            (str/join ", " dupes))
                       {:duplicates dupes :chapters chapters})))
    (let [header (apply str (header-lines config))
          body   (->> chapters
                      (map #(chapter-include book-root %))
                      (str/join "\n"))]
      (str header "\n\n" body "\n"))))

(defn write-master!
  "Write the master adoc string to `<intermediate-dir>/book.adoc`.
   Returns the written file path."
  [{:keys [intermediate-dir] :as ctx}]
  (let [content (master-adoc ctx)
        out (io/file intermediate-dir "book.adoc")]
    (io/make-parents out)
    (spit out content)
    (.getPath out)))
