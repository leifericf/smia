(ns smia.site.search-index
  "Pure core: the site's search index.

   From the assembled page specs, build the data the search island works
   over: one entry per page (with its full text), plus anchored entries
   for the apparatus — sections, figures, tables, listings, and index
   terms. The index also carries the category labels and display order
   (`:kinds`), so everything book-specific lives in the data and the
   shipped search bundle stays generic.

   The index serializes through a small hand-written JSON emitter:
   object keys sorted, strings escaped, no dependency — the same
   determinism discipline as every other artifact. No IO."
  (:require
   [smia.book.dictionary :as dictionary]
   [clojure.string :as str]))

;; --- categories ---------------------------------------------------------------

(def ^:private kind-order
  [:chapter :appendix :matter :downloads :section :figure :table :listing :term])

(def ^:private kind-labels
  "The English category labels — the `:en` baseline and the fallback for
   `kind-label` (the localized lookup keys these under `:cat/<kind>`)."
  {:chapter  "Chapters"
   :appendix "Appendices"
   :matter   "Pages"
   :downloads "Downloads"
   :section  "Sections"
   :figure   "Figures"
   :table    "Tables"
   :listing  "Listings"
   :term     "Index terms"})

(defn kind-label
  "The reader-facing label for an entry kind (also used by the static
   fallback page), localized to `language` (the book's `:book/language`),
   English as fallback."
  ([kind] (kind-label kind nil))
  ([kind language]
   (dictionary/localize language (keyword "cat" (name kind))
                        (get kind-labels kind (str/capitalize (name kind))))))

;; --- text extraction -------------------------------------------------------------

(def ^:private block-tags
  "Tags whose text ends a run — two paragraphs must not glue together.
   Compared by name, so the `:html/*` hatch counts too."
  #{"p" "div" "section" "article" "ul" "ol" "li" "dl" "dt" "dd"
    "blockquote" "table" "thead" "tbody" "tr" "td" "th"
    "h1" "h2" "h3" "h4" "h5" "h6" "figure" "figcaption" "pre"})

(defn- text-of [node]
  (cond
    (string? node) node
    (vector? node)
    (let [[tag & kids] node
          s (apply str (map text-of (remove map? kids)))]
      (if (and (keyword? tag) (contains? block-tags (name tag)))
        (str s " ")
        s))
    :else ""))

(defn- plain-text
  "The visible text of one Hiccup tree: strings concatenated in order
   (inline runs carry their own spacing, block boundaries become one
   space), whitespace collapsed."
  [tree]
  (-> (text-of tree)
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- heading-block? [b]
  (and (vector? b) (contains? #{:h1 :h2 :h3 :h4 :h5 :h6} (first b))))

(defn- body-text
  "The visible text of a page body: each block's text, blocks separated
   by a space. Headings are left out — they index as their own section
   entries."
  [body]
  (->> body
       (remove heading-block?)
       (map plain-text)
       (remove str/blank?)
       (str/join " ")))

(defn- numbered-title [number title]
  (if number (str number "  " title) title))

;; --- apparatus entries -------------------------------------------------------------

(def ^:private float-kinds {:figure :figure :table :table :pre :listing})

(defn- anchored-entries
  "Walk a page `body` for the apparatus that anchors inside the page:
   `:h2` headings with ids, captioned floats, and index terms."
  [url body]
  (->> (tree-seq vector? seq (vec body))
       (keep
         (fn [node]
           (when (and (vector? node) (map? (second node)))
             (let [[tag a] node]
               (cond
                 (and (= :h2 tag) (:id a))
                 {:kind "section"
                  :title (plain-text node)
                  :url   (str url "#" (name (:id a)))}

                 (and (float-kinds tag) (:label a) (:caption a) (:id a))
                 {:kind  (name (float-kinds tag))
                  :title (str (:label a) " — " (:caption a))
                  :url   (str url "#" (name (:id a)))}

                 (and (= :index tag) (:term a) (:id a))
                 {:kind  "term"
                  :title (:term a)
                  :url   (str url "#" (name (:id a)))}

                 :else nil)))))
       vec))

;; --- the index ----------------------------------------------------------------

(defn index
  "Build `{:kinds [{:kind :label} …] :entries [{:kind :title :url
   :text?} …]}` from the assembled page `specs`, in document order.
   `:kinds` lists only the categories present, in display order.
   `language` localizes the category labels."
  ([specs] (index specs nil))
  ([specs language]
  (let [page-entries
        (mapcat (fn [{:keys [kind id title number url body]}]
                  (when (and id title (not= :search kind))
                    (cons {:kind  (name kind)
                           :title (numbered-title number title)
                           :url   url
                           :text  (body-text body)}
                          (anchored-entries url body))))
                specs)
        entries (vec page-entries)
        present (set (map :kind entries))]
    {:kinds   (vec (keep (fn [k]
                           (when (present (name k))
                             {:kind (name k) :label (kind-label k language)}))
                         kind-order))
     :entries entries})))

;; --- JSON ----------------------------------------------------------------------

(defn- escape-json [^String s]
  (let [sb (StringBuilder.)]
    (doseq [c s]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return  (.append sb "\\r")
        \tab     (.append sb "\\t")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (str sb)))

(defn- json-value [v]
  (cond
    (string? v)  (str "\"" (escape-json v) "\"")
    (number? v)  (str v)
    (boolean? v) (str v)
    (nil? v)     "null"
    (map? v)     (str "{"
                      (->> (sort-by key v)
                           (map (fn [[k val]]
                                  (str "\"" (escape-json (name k)) "\":"
                                       (json-value val))))
                           (str/join ","))
                      "}")
    (sequential? v) (str "[" (str/join "," (map json-value v)) "]")
    :else (str "\"" (escape-json (str v)) "\"")))

(defn index-json
  "Serialize an `index` to deterministic JSON: object keys sorted,
   entry order preserved."
  [index]
  (json-value (into (sorted-map) index)))
