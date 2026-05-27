(ns clj-book.book.load
  "Imperative shell: read a manuscript's chapter `.clj` files and
   evaluate each to a Hiccup chapter form.

   TRUST BOUNDARY: building a book runs the author's own Clojure code via
   `load-file`, so chapters can `slurp` real code samples or generate
   content programmatically (the same trust model as Pollen, and as the
   prior `styles/site.clj` seam). Only build manuscripts you trust. The
   value of a chapter file is the value of its last form — normally a
   `[:chapter {:id .. :title ..} ..]` literal."
  (:require
   [clj-book.error :as error]
   [clojure.java.io :as io]))

(defn load-chapter
  "Read and evaluate one chapter file at `book-root`/`rel-path`, returning
   its Hiccup value. Throws a structured error if the file is missing or
   evaluation fails."
  [book-root rel-path]
  (let [f (io/file book-root rel-path)]
    (when-not (.exists f)
      (throw (error/ex :clj-book.book.load/missing-chapter
                       (str "Chapter file not found: " (.getPath f))
                       {:book-root book-root :path rel-path})))
    (try
      (load-file (.getPath f))
      (catch Exception e
        (throw (error/ex :clj-book.book.load/chapter-eval-error
                         (str "Failed to evaluate chapter " (.getPath f)
                              ": " (.getMessage e))
                         {:path (.getPath f) :cause (.getMessage e)}))))))

(defn load-chapters
  "Load `rel-paths` (relative to `book-root`) in order, returning a vector
   of Hiccup chapter forms."
  [book-root rel-paths]
  (mapv #(load-chapter book-root %) rel-paths))
