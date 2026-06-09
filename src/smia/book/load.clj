(ns smia.book.load
  "Imperative shell: read a manuscript's chapter files and turn each into a
   Hiccup `[:chapter {…} …]` form. Two front-ends are dispatched on the
   file extension:

   - `.clj` — evaluated via `load-file` (the value of the last form is the
     chapter). TRUST BOUNDARY: this runs the author's own Clojure code, so
     chapters can `slurp` real code samples or generate content. Only build
     manuscripts you trust.
   - `.md`  — a prose-first Markdown front-end (smia.md.*) that reads
     the file as data, parses CommonMark, and compiles it to the same
     author Hiccup. `.md` chapters are read as data (no `load-file`), so a
     prose book is eval-free.

   The chapter `:id` is derived from the filename (a leading `NN-` ordering
   prefix is stripped) and the `:title` from the first H1; front-matter
   overrides both."
  (:require
   [smia.book.datatable :as datatable]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [smia.md.compile :as md-compile]
   [smia.md.frontmatter :as md-frontmatter]
   [smia.md.parse :as md-parse]
   [smia.md.schema :as md-schema]
   [smia.md.typography :as md-typography]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(declare chapter-id-from-path select-lines select-tagged marker-re include-pre? include-paths
         substitute-includes read-include resolve-includes check-chapter-shape
         load-markdown-chapter load-clojure-chapter duplicate-ids
         check-no-duplicate-ids load-references)

(defn load-chapter
  "Read one chapter file at `book-root`/`rel-path`, returning its Hiccup
   value. Dispatches on extension: `.md` via the Markdown front-end, every
   other extension via `load-file`. Throws a structured error if the file
   is missing or cannot be read/parsed.

   `opts` tunes the Markdown front-end: `:smart-punctuation false` turns
   the typographic punctuation pass off (it is on by default). `.clj`
   chapters are authored exactly and ignore `opts`."
  ([book-root rel-path] (load-chapter book-root rel-path {}))
  ([book-root rel-path opts]
   (let [f (io/file book-root rel-path)]
     (when-not (.exists f)
       (throw (error/ex :smia.book.load/missing-chapter
                        (str "Chapter file not found: " (.getPath f))
                        {:book-root book-root :path rel-path})))
     (if (str/ends-with? (str/lower-case rel-path) ".md")
       (load-markdown-chapter book-root rel-path f opts)
       (load-clojure-chapter book-root f)))))

(defn load-chapters
  "Load `rel-paths` (relative to `book-root`) in order, returning a vector
   of Hiccup chapter forms. A duplicate chapter `:id` is a hard error
   (assembly would otherwise silently collapse cross-reference targets)."
  ([book-root rel-paths] (load-chapters book-root rel-paths {}))
  ([book-root rel-paths opts]
   (let [chapters (mapv #(load-chapter book-root % opts) rel-paths)]
     (check-no-duplicate-ids chapters)
     chapters)))

(defn load-manuscript
  "Shell: normalize `config` into a typed document structure and load every
   file-backed section into its `[:chapter …]` Hiccup, returning the typed
   manuscript value:

     `{:title :author :numbering <policy> :sections [<spec+content> …]
       :chapters [<loaded-hiccup> …]}`

   `:sections` carries the structure (parts, matter, appendices) with each
   file-backed entry's loaded `:content`; `:chapters` is every loaded form in
   document order (used by the vocabulary and code-validation passes). A flat
   `:book/chapters` book yields a body of chapters with no parts. A duplicate
   chapter `:id` anywhere in the book is a hard error.

   `opts` is passed through to `load-chapter` (`:smart-punctuation`)."
  ([book-root config] (load-manuscript book-root config {}))
  ([book-root config opts]
   (let [{:keys [numbering sections]} (structure/normalize config)
         loaded   (mapv (fn [s]
                          (if-let [f (:file s)]
                            (assoc s :content (load-chapter book-root f opts))
                            s))
                        sections)
         chapters (vec (keep :content loaded))]
     (check-no-duplicate-ids chapters)
     {:title         (:book/title config)
      :subtitle      (:book/subtitle config)
      :author        (:book/author config)
      :language      (:book/language config)
      :numbering     numbering
      :running-heads (:book/running-heads config)
      :references    (load-references book-root config)
      :sections      loaded
      :chapters      chapters})))

;; --- private helpers -------------------------------------------------------

(defn- chapter-id-from-path
  "Derive a chapter `:id` keyword from a chapter file path: basename, minus
   extension and a leading `NN-`/`NN_` ordering prefix. e.g.
   `chapters/02-authoring.md` -> `:authoring`. Nil when nothing remains
   (a prefix-only name like `01-.md`)."
  [rel-path]
  (let [s (-> (io/file rel-path)
              (.getName)
              (str/replace #"\.[^.]*$" "")
              (str/replace #"^\d+[-_]" ""))]
    (when (seq s) (keyword s))))

(defn- select-lines
  "Return the inclusive 1-based `[from to]` line range of `text`."
  [text [from to]]
  (->> (str/split-lines text)
       (drop (max 0 (dec from)))
       (take (inc (- to from)))
       (str/join "\n")))

(defn- marker-re
  "A matcher for a `prefix::name` region marker anywhere in a line. The
   name is delimited: it must not be followed by another tag-name
   character, so the tag `x` never matches `tag::xy`. The name is quoted,
   so a tag with regex-special characters matches literally."
  [prefix tag]
  (re-pattern (str prefix "::" (java.util.regex.Pattern/quote tag) "(?![\\p{L}\\p{N}_-])")))

(defn- select-tagged
  "Return the lines of `text` between `tag::name` and `end::name` marker
   lines, the markers themselves excluded. The markers match anywhere in a
   line, so any comment syntax works, and the name is delimited so `x`
   does not match `tag::xy`. Multiple regions with the same tag
   concatenate in file order; an unclosed region runs to the end of the
   file. Returns nil when the tag opens nowhere."
  [text tag]
  (let [open  (marker-re "tag" tag)
        close (marker-re "end" tag)]
    (loop [lines (str/split-lines text), in? false, found? false, acc []]
      (if-let [line (first lines)]
        (cond
          (and in? (re-find close line))
          (recur (rest lines) false found? acc)

          (and (not in?) (re-find open line))
          (recur (rest lines) true true acc)

          :else
          (recur (rest lines) in? found? (cond-> acc in? (conj line))))
        (when found? (str/join "\n" acc))))))

(defn- include-pre?
  "True for a `[:pre {:include …} …]` node — a code block that pulls its
   source from a file."
  [node]
  (and (vector? node) (= :pre (first node))
       (map? (second node)) (:include (second node))))

(defn- include-paths
  "Pure: every `:include` source path referenced in a chapter tree, in
   document order and deduplicated."
  [node]
  (into [] (comp (filter include-pre?)
                 (map (comp :include second))
                 (distinct))
        (tree-seq vector? seq node)))

(defn- substitute-includes
  "Pure: replace every `[:pre {:include …}]` node with `[:pre <attrs without
   the include keys> <source>]`, taking the text from `sources`
   (path -> full source) and applying a `:lines [from to]` range or a
   `:tag \"name\"` marker region. The two selectors are exclusive; a tag
   that opens nowhere in its file is a hard error."
  [sources node]
  (cond
    (include-pre? node)
    (let [{:keys [include lines tag] :as attrs} (second node)
          text (get sources include)
          text (cond
                 (and tag lines)
                 (throw (error/ex :smia.book.load/conflicting-include-keys
                                  (str "An include selects with :tag or :lines, "
                                       "not both: " include)
                                  {:include include :tag tag :lines lines}))

                 tag
                 (or (select-tagged text tag)
                     (throw (error/ex :smia.book.load/missing-include-tag
                                      (str "No tag::" tag " marker in included "
                                           "file: " include)
                                      {:include include :tag tag})))

                 lines
                 (do
                   (when-not (and (vector? lines) (= 2 (count lines))
                                  (every? integer? lines)
                                  (<= 1 (first lines) (second lines)))
                     (throw (error/ex :smia.book.load/invalid-include-lines
                                      (str "An include's :lines must be a vector "
                                           "of two 1-based line numbers [from to] "
                                           "with from <= to, got: " (pr-str lines))
                                      {:include include :lines lines})))
                   (select-lines text lines))

                 :else text)]
      [:pre (dissoc attrs :include :lines :tag) text])

    (vector? node) (mapv #(substitute-includes sources %) node)
    :else          node))

(defn- escaping-path?
  "True for a content-referenced path that would read outside the book: an
   absolute path, or any `..` segment. Includes and data tables are pulled
   from the manuscript's own content (the less-trusted `.md` surface, which
   is otherwise eval-free), so — like image sources — they must stay within
   the book root and never name an arbitrary file on the build machine."
  [path]
  (or (str/starts-with? path "/")
      (some #{".."} (str/split path #"[/\\]"))))

(defn- escapes-canonically?
  "True when `book-root`/`path` resolves (symlinks followed) to a file
   outside the book root. The lexical check above catches `..` and absolute
   paths without touching the disk; this catches a symlink inside the book
   that points out of it."
  [book-root path]
  (let [root (str (.getCanonicalPath (io/file book-root))
                  java.io.File/separator)
        target (.getCanonicalPath (io/file book-root path))]
    (not (str/starts-with? target root))))

(defn- read-include
  "Shell: slurp the include source at `book-root`/`path`. A path that escapes
   the book root (lexically or through a symlink), or a missing file, is a
   hard error."
  [book-root path]
  (when (str/blank? path)
    (throw (error/ex :smia.book.load/missing-include
                     "An include's source path is blank."
                     {:book-root book-root :include path})))
  (when (or (escaping-path? path)
            (escapes-canonically? book-root path))
    (throw (error/ex :smia.book.load/unsafe-include
                     (str "Included source path " (pr-str path) " escapes the "
                          "book directory. Include paths must be relative and "
                          "stay within the book.")
                     {:book-root book-root :include path})))
  (let [f (io/file book-root path)]
    (when-not (.isFile f)
      (throw (error/ex :smia.book.load/missing-include
                       (str "Included source is not a readable file: " (.getPath f))
                       {:book-root book-root :include path})))
    (slurp f)))

(defn- resolve-includes
  "Shell: resolve every `[:pre {:include …}]` in a chapter tree. Collecting
   the source paths and substituting the text back are pure steps; only the
   read between them touches the filesystem."
  [book-root node]
  (let [sources (into {} (map (fn [p] [p (read-include book-root p)]))
                      (include-paths node))]
    (substitute-includes sources node)))

(defn- data-table?
  "True for a `[:table {:data …} …]` node — a table whose rows come from a
   data file rather than authored markup."
  [node]
  (and (vector? node) (= :table (first node))
       (map? (second node)) (:data (second node))))

(defn- data-table-paths
  "Pure: every `:data` source path referenced in a chapter tree, in
   document order and deduplicated."
  [node]
  (into [] (comp (filter data-table?)
                 (map (comp :data second))
                 (distinct))
        (tree-seq vector? seq node)))

(defn- data-format
  "The data-table format: the explicit `:format`, else inferred from the
   file extension (`.tsv`/`.edn`, defaulting to `:csv`)."
  [{:keys [data format]}]
  (or format
      (cond
        (str/ends-with? data ".tsv") :tsv
        (str/ends-with? data ".edn") :edn
        :else                        :csv)))

(defn- rows->table
  "Build a `[:table …]` from parsed `rows`. With `:header true` the first
   row becomes a `:thead` of `:th` cells and the rest a `:tbody`; otherwise
   every row is a `:tbody` of `:td` cells. The data-sourcing keys
   (`:data`, `:format`, `:header`) are dropped from the table's attrs so
   what reaches expansion is an ordinary table."
  [attrs rows]
  (when (empty? rows)
    (throw (error/ex :smia.book.load/empty-data
                     (str "Data-table source is empty: " (:data attrs))
                     {:data (:data attrs)})))
  (let [width (count (first rows))]
    (doseq [[i row] (map-indexed vector rows)]
      (when-not (= width (count row))
        (throw (error/ex :smia.book.load/ragged-data
                         (str "Data-table rows must all be the same width: "
                              (:data attrs) " row " (inc i) " has "
                              (count row) " cells, expected " width ".")
                         {:data (:data attrs) :row (inc i)
                          :cells (count row) :expected width})))))
  (let [tattrs   (dissoc attrs :data :format :header)
        row-of   (fn [tag cells] (into [:tr] (map (fn [c] [tag c]) cells)))]
    (if (:header attrs)
      [:table tattrs
       [:thead (row-of :th (first rows))]
       (into [:tbody] (map #(row-of :td %) (rest rows)))]
      (into [:table tattrs] [(into [:tbody] (map #(row-of :td %) rows))]))))

(defn- substitute-data-tables
  "Pure: replace every `[:table {:data …}]` node with a table built from
   the parsed `sources` (path -> file text), per the node's `:format` and
   `:header`."
  [sources node]
  (cond
    (data-table? node)
    (let [attrs (second node)]
      (rows->table attrs (datatable/rows (get sources (:data attrs))
                                         (data-format attrs))))
    (vector? node) (mapv #(substitute-data-tables sources %) node)
    :else          node))

(defn- read-data
  "Shell: slurp the data-table source at `book-root`/`path`. A path that
   escapes the book root, or a missing file, is a hard error."
  [book-root path]
  (when (str/blank? path)
    (throw (error/ex :smia.book.load/missing-data
                     "A data table's source path is blank."
                     {:book-root book-root :data path})))
  (when (or (escaping-path? path)
            (escapes-canonically? book-root path))
    (throw (error/ex :smia.book.load/unsafe-data
                     (str "Data-table source path " (pr-str path) " escapes the "
                          "book directory. Data paths must be relative and stay "
                          "within the book.")
                     {:book-root book-root :data path})))
  (let [f (io/file book-root path)]
    (when-not (.isFile f)
      (throw (error/ex :smia.book.load/missing-data
                       (str "Data-table source is not a readable file: " (.getPath f))
                       {:book-root book-root :data path})))
    (slurp f)))

(defn- resolve-data-tables
  "Shell: resolve every `[:table {:data …}]` in a chapter tree. Collecting
   the source paths and substituting the parsed rows back are pure steps;
   only the read between them touches the filesystem."
  [book-root node]
  (let [sources (into {} (map (fn [p] [p (read-data book-root p)]))
                      (data-table-paths node))]
    (substitute-data-tables sources node)))

(defn- check-chapter-shape
  "Validate that `form` is a well-formed `[:chapter {:id <keyword>
   :title <string>} …]` — the contract assembly relies on. Throws a
   structured error naming `path` otherwise; returns `form` when valid. The
   load boundary is where chapters are produced, so the shape is checked here
   rather than during assembly."
  [form path]
  (when-not (and (vector? form) (= :chapter (first form)))
    (throw (error/ex :smia.book.load/invalid-chapter
                     (str "A chapter must be a [:chapter {:id .. :title ..} ..] form: " path)
                     {:chapter form :path path})))
  (let [attrs (or (second form) {})]
    (when-not (keyword? (:id attrs))
      (throw (error/ex :smia.book.load/missing-chapter-id
                       (str "Each :chapter needs a keyword :id: " path)
                       {:chapter form :path path})))
    (when-not (string? (:title attrs))
      (throw (error/ex :smia.book.load/missing-chapter-title
                       (str "Each :chapter needs a string :title: " path)
                       {:chapter form :path path}))))
  form)

(defn- load-markdown-chapter
  "Compile a `.md` chapter file into a `[:chapter {…} …]` form. Smart
   punctuation is applied to the compiled prose (and the H1-derived title)
   unless `opts` carries `:smart-punctuation false`; front-matter values
   are EDN data and stay authored exactly."
  [book-root rel-path ^java.io.File f opts]
  (let [source   (slurp f)
        smarten? (not (false? (:smart-punctuation opts)))]
    (try
      (let [{:keys [attrs body]} (md-frontmatter/split source)
            ast                  (md-parse/parse body (.getPath f))
            [_ compiled-attrs & compiled-body] (md-compile/compile ast)
            compiled-body        (cond-> (vec compiled-body)
                                   smarten? (md-typography/smarten))
            compiled-body        (mapv #(->> %
                                              (resolve-includes book-root)
                                              (resolve-data-tables book-root))
                                        compiled-body)
            compiled-attrs       (cond-> compiled-attrs
                                   (and smarten? (:title compiled-attrs))
                                   (update :title md-typography/smarten-string))
            merged (merge (when-let [id (chapter-id-from-path rel-path)]
                            {:id id})
                          compiled-attrs
                          attrs)]
        (when-not (:id merged)
          (throw (error/ex :smia.book.load/underivable-id
                           (str "Cannot derive a chapter :id from the filename "
                                (pr-str rel-path) "; rename the file or add an "
                                ":id to the front-matter.")
                           {:path (.getPath f)})))
        (md-schema/check merged :smia.book.load/invalid-front-matter)
        (when-not (:title merged)
          (throw (error/ex :smia.book.load/missing-title
                           (str "Markdown chapter has no title: add a top-level "
                                "'# Heading' or a :title in front-matter: "
                                (.getPath f))
                           {:path (.getPath f)})))
        (check-chapter-shape (into [:chapter merged] compiled-body) (.getPath f)))
      (catch Exception e
        ;; Preserve structured front-matter/compile/schema errors (they
        ;; already carry context and positions); wrap anything else.
        (if (error/data e)
          (throw e)
          (throw (error/ex :smia.book.load/markdown-parse-error
                           (str "Failed to parse Markdown chapter "
                                (.getPath f) ": " (.getMessage e))
                           {:path (.getPath f) :cause (.getMessage e)})))))))

(defn- load-clojure-chapter
  "Evaluate a `.clj` chapter file; its last form's value is the chapter.
   Includes and data tables resolve afterwards, exactly as for Markdown —
   the author vocabulary is shared, so `[:pre {:include …}]` and
   `[:table {:data …}]` mean the same thing from either front end."
  [book-root ^java.io.File f]
  (let [form (try
               (load-file (.getPath f))
               (catch Exception e
                 (throw (error/ex :smia.book.load/chapter-eval-error
                                  (str "Failed to evaluate chapter " (.getPath f)
                                       ": " (.getMessage e))
                                  {:path (.getPath f) :cause (.getMessage e)}))))]
    (->> (check-chapter-shape form (.getPath f))
         (resolve-includes book-root)
         (resolve-data-tables book-root))))

(defn- duplicate-ids
  "Chapter `:id`s that occur more than once, sorted."
  [chapters]
  (->> chapters
       (keep (fn [c]
               (when (and (vector? c) (= :chapter (first c)) (map? (second c)))
                 (:id (second c)))))
       (structure/duplicates name)))

(defn- check-no-duplicate-ids [chapters]
  (let [dupes (duplicate-ids chapters)]
    (when (seq dupes)
      (throw (error/ex :smia.book.load/duplicate-chapter-id
                       (str "Duplicate chapter :id(s): "
                            (str/join ", " (map str dupes)))
                       {:duplicate-ids dupes})))))

(defn- load-references
  "Read the optional `:book/references` EDN file (key -> bibliography entry
   map), or nil when the book declares none. A declared-but-missing file is
   a hard error."
  [book-root config]
  (when-let [path (:book/references config)]
    (let [f (io/file book-root path)]
      (when-not (.exists f)
        (throw (error/ex :smia.book.load/missing-references
                         (str "References file not found: " (.getPath f))
                         {:book-root book-root :references path})))
      (with-open [reader (java.io.PushbackReader. (io/reader f))]
        (let [refs (edn/read {:eof ::eof} reader)
              more (try (edn/read {:eof ::eof} reader)
                        (catch Exception _ ::trailing))]
          (when-not (map? refs)
            (throw (error/ex :smia.book.load/invalid-references
                             "References file must be an EDN map of key -> entry."
                             {:path (.getPath f)})))
          (when-not (= ::eof more)
            (throw (error/ex :smia.book.load/invalid-references
                             (str "References file must hold exactly one EDN map; "
                                  "found content after it: " (.getPath f))
                             {:path (.getPath f)})))
          refs)))))
