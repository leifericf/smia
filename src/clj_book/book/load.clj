(ns clj-book.book.load
  "Imperative shell: read a manuscript's chapter files and turn each into a
   Hiccup `[:chapter {…} …]` form. Two front-ends are dispatched on the
   file extension:

   - `.clj` — evaluated via `load-file` (the value of the last form is the
     chapter). TRUST BOUNDARY: this runs the author's own Clojure code, so
     chapters can `slurp` real code samples or generate content. Only build
     manuscripts you trust.
   - `.md`  — a prose-first Markdown front-end (clj-book.md.*) that reads
     the file as data, parses CommonMark, and compiles it to the same
     author Hiccup. `.md` chapters are read as data (no `load-file`), so a
     prose book is eval-free.

   The chapter `:id` is derived from the filename (a leading `NN-` ordering
   prefix is stripped) and the `:title` from the first H1; front-matter
   overrides both."
  (:require
   [clj-book.error :as error]
   [clj-book.md.compile :as md-compile]
   [clj-book.md.frontmatter :as md-frontmatter]
   [clj-book.md.parse :as md-parse]
   [clj-book.md.schema :as md-schema]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- chapter-id-from-path
  "Derive a chapter `:id` keyword from a chapter file path: basename, minus
   extension and a leading `NN-`/`NN_` ordering prefix. e.g.
   `chapters/02-authoring.md` -> `:authoring`."
  [rel-path]
  (-> (io/file rel-path)
      (.getName)
      (str/replace #"\.[^.]*$" "")
      (str/replace #"^\d+[-_]" "")
      (keyword)))

(defn- load-markdown-chapter
  "Compile a `.md` chapter file into a `[:chapter {…} …]` form."
  [rel-path ^java.io.File f]
  (let [source (slurp f)]
    (try
      (let [{:keys [attrs body]} (md-frontmatter/split source)
            ast                  (md-parse/parse body (.getPath f))
            [_ compiled-attrs & compiled-body] (md-compile/compile ast)
            merged (merge {:id (chapter-id-from-path rel-path)}
                          compiled-attrs
                          attrs)]
        (md-schema/check merged :clj-book.book.load/invalid-front-matter)
        (when-not (:title merged)
          (throw (error/ex :clj-book.book.load/missing-title
                           (str "Markdown chapter has no title: add a top-level "
                                "'# Heading' or a :title in front-matter: "
                                (.getPath f))
                           {:path (.getPath f)})))
        (into [:chapter merged] compiled-body))
      (catch Exception e
        ;; Preserve structured front-matter/compile/schema errors (they
        ;; already carry context and positions); wrap anything else.
        (if (error/data e)
          (throw e)
          (throw (error/ex :clj-book.book.load/markdown-parse-error
                           (str "Failed to parse Markdown chapter "
                                (.getPath f) ": " (.getMessage e))
                           {:path (.getPath f) :cause (.getMessage e)})))))))

(defn- load-clojure-chapter
  "Evaluate a `.clj` chapter file; its last form's value is the chapter."
  [^java.io.File f]
  (try
    (load-file (.getPath f))
    (catch Exception e
      (throw (error/ex :clj-book.book.load/chapter-eval-error
                       (str "Failed to evaluate chapter " (.getPath f)
                            ": " (.getMessage e))
                       {:path (.getPath f) :cause (.getMessage e)})))))

(defn load-chapter
  "Read one chapter file at `book-root`/`rel-path`, returning its Hiccup
   value. Dispatches on extension: `.md` via the Markdown front-end, every
   other extension via `load-file`. Throws a structured error if the file
   is missing or cannot be read/parsed."
  [book-root rel-path]
  (let [f (io/file book-root rel-path)]
    (when-not (.exists f)
      (throw (error/ex :clj-book.book.load/missing-chapter
                       (str "Chapter file not found: " (.getPath f))
                       {:book-root book-root :path rel-path})))
    (if (str/ends-with? (str/lower-case rel-path) ".md")
      (load-markdown-chapter rel-path f)
      (load-clojure-chapter f))))

(defn- duplicate-ids
  "Chapter `:id`s that occur more than once, sorted."
  [chapters]
  (->> chapters
       (keep (fn [c]
               (when (and (vector? c) (= :chapter (first c)) (map? (second c)))
                 (:id (second c)))))
       frequencies
       (filter (fn [[_ n]] (> n 1)))
       (map key)
       (sort-by name)
       vec))

(defn load-chapters
  "Load `rel-paths` (relative to `book-root`) in order, returning a vector
   of Hiccup chapter forms. A duplicate chapter `:id` is a hard error
   (assembly would otherwise silently collapse cross-reference targets)."
  [book-root rel-paths]
  (let [chapters (mapv #(load-chapter book-root %) rel-paths)
        dupes    (duplicate-ids chapters)]
    (when (seq dupes)
      (throw (error/ex :clj-book.book.load/duplicate-chapter-id
                       (str "Duplicate chapter :id(s): "
                            (str/join ", " (map str dupes)))
                       {:duplicate-ids dupes})))
    chapters))
