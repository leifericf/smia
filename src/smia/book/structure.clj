(ns smia.book.structure
  "Pure core: normalize a validated `book.edn` config map into a canonical,
   ordered document structure — a numbering policy plus a flat vector of
   typed section specs (front matter, parts, body chapters, appendices,
   back matter).

   The flat `:book/chapters` list is the zero-config default and yields a
   body of chapters with no parts, so a book that declares no structure
   normalizes to exactly the sequence the assembler always produced. When
   `:book/parts` is present the body is grouped under part dividers.

   Section specs are plain maps keyed by `:kind`:

   - `{:kind :matter :matter :front|:back :role <kw> :file <str>?}` — a
     named matter section; a roleful section with no file (e.g.
     `:bibliography`, `:index`) carries `:generated true`.
   - `{:kind :part :title <str> :index <n>}` — a part divider.
   - `{:kind :chapter :file <str> :part <n|nil>}` — a body chapter.
   - `{:kind :appendix :file <str>}` — an appendix (lettered).

   No IO. File existence is checked in the shell (`smia.book.config`)."
  (:require
   [clojure.string :as str]))

(def default-numbering
  "Default numbering policy. Parts, chapters, and appendices are
   auto-numbered; sections are not. `:start-chapters-on :recto` inserts a
   blank page so a chapter opens on a right-hand page (print only)."
  {:parts            :roman
   :chapters         :arabic
   :appendices       :letter
   :sections         false
   :start-chapters-on :any})

(def ^:private role-titles
  "Explicit human titles for generated roles, so the multi-word lists read
   naturally (e.g. \"List of Figures\", not the title-cased \"List Of
   Figures\"). An author `:title` still overrides these downstream."
  {:bibliography      "Bibliography"
   :index             "Index"
   :list-of-figures   "List of Figures"
   :list-of-tables    "List of Tables"
   :list-of-listings  "List of Listings"})

(declare matter-spec front-sections back-sections part-sections
         body-sections appendix-sections)

(defn normalize
  "Normalize a `book.edn` config map into `{:numbering <policy>
   :sections [<spec> …]}`. Pure."
  [config]
  {:numbering (merge default-numbering (:book/numbering config))
   :sections  (vec (concat (front-sections config)
                           (body-sections config)
                           (appendix-sections config)
                           (back-sections config)))})

(defn file-list
  "Every source file referenced by `structure`, in document order. Part
   dividers and generated matter contribute nothing."
  [structure]
  (into [] (keep :file) (:sections structure)))

(defn duplicates
  "The distinct values occurring more than once in `coll`, sorted by
   `sort-key` (default `identity`). Returns a vector."
  ([coll] (duplicates identity coll))
  ([sort-key coll]
   (->> (frequencies coll)
        (filter (fn [[_ n]] (> n 1)))
        (map key)
        (sort-by sort-key)
        vec)))

(defn body?
  "True for sections numbered in the arabic body run (chapters and
   appendices)."
  [section]
  (contains? #{:chapter :appendix} (:kind section)))

(defn generated-role?
  "Back/front-matter roles whose content smia produces (no source
   file): the bibliography, the index, and the lists of figures, tables,
   and listings."
  [role]
  (contains? #{:bibliography :index
               :list-of-figures :list-of-tables :list-of-listings}
             role))

(defn role-title
  "A human title for a generated matter `role` (e.g. `:bibliography` ->
   \"Bibliography\"). Known generated roles have curated titles; any other
   role is title-cased from its kebab name."
  [role]
  (or (role-titles role)
      (->> (str/split (name role) #"-")
           (map str/capitalize)
           (str/join " "))))

;; --- private helpers -------------------------------------------------------

(defn- matter-spec [matter m]
  (cond-> {:kind :matter :matter matter :role (:role m)}
    (:file m)        (assoc :file (:file m))
    (:title m)       (assoc :title (:title m))
    (not (:file m))  (assoc :generated true)))

(defn- front-sections [config]
  (mapv #(matter-spec :front %) (:book/front-matter config)))

(defn- back-sections [config]
  (mapv #(matter-spec :back %) (:book/back-matter config)))

(defn- part-sections [parts]
  (vec (apply concat
              (map-indexed
               (fn [i {:part/keys [title chapters]}]
                 (cons {:kind :part :title title :index i}
                       (map (fn [f] {:kind :chapter :file f :part i}) chapters)))
               parts))))

(defn- body-sections [config]
  (if-let [parts (:book/parts config)]
    (part-sections parts)
    (mapv (fn [f] {:kind :chapter :file f :part nil})
          (:book/chapters config))))

(defn- appendix-sections [config]
  (mapv (fn [f] {:kind :appendix :file f}) (:book/appendices config)))
