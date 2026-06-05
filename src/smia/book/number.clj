(ns smia.book.number
  "Pure core: the numbering and cross-reference engine.

   Given a typed manuscript (`{:numbering <policy> :sections […]}`, the value
   `book.load/load-manuscript` produces before assembly), it:

   1. numbers the numbered targets per the policy — parts (roman), chapters
      (arabic), appendices (letters), and, when `:sections` is enabled,
      sections (decimal within their chapter);
   2. builds a registry `{id → {:kind :number :label :title}}` covering every
      labelled target (structural sections plus in-body headings);
   3. annotates each target with its computed `:number`/`:label`, so assembly
      can render \"Chapter 1\" headings and a numbered table of contents;
   4. rewrites every childless `[:xref {:to id}]` to its composed label
      (\"Chapter 2\", \"Appendix A\", or — for an unnumbered target — its
      title), leaving the page reference to FO's `fo:page-number-citation`;
      an `:xref` to an id no target defines is a hard error.

   It is pure and layout-free, so it runs in the pipeline before expansion
   and needs no rendering results. New numbered kinds (figures, tables,
   listings) register through the same counters in later phases. No IO."
  (:require
   [smia.book.dictionary :as dictionary]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [clojure.string :as str]))

(declare number-sections rewrite-section)

;; --- public transform -----------------------------------------------------

(defn assign
  "Number `manuscript`'s targets, resolve cross-references and citations, and
   collect index marks. Returns `{:manuscript <annotated manuscript with
   :index term->ids> :registry <id → entry>}`. Bibliography lookups use the
   manuscript's `:references` map."
  [manuscript]
  (let [policy     (:numbering manuscript)
        references (:references manuscript)
        {:keys [sections registry index floats]}
        (number-sections (:sections manuscript) policy (:language manuscript))
        sections   (mapv #(rewrite-section % registry references) sections)]
    {:manuscript (assoc manuscript :sections sections :index index :floats floats)
     :registry   registry}))

(defn counts
  "Per-kind counts of numbered targets, for the dry-run plan."
  [{:keys [registry]}]
  (let [by (frequencies (map :kind (vals registry)))]
    (cond-> {}
      (:part by)      (assoc :parts (:part by))
      (:chapter by)   (assoc :chapters (:chapter by))
      (:appendix by)  (assoc :appendices (:appendix by)))))

;; --- number formats -------------------------------------------------------

(defn- ->roman [n]
  (let [pairs [[1000 "M"] [900 "CM"] [500 "D"] [400 "CD"] [100 "C"] [90 "XC"]
               [50 "L"] [40 "XL"] [10 "X"] [9 "IX"] [5 "V"] [4 "IV"] [1 "I"]]]
    (loop [n n, [[v s] & more :as ps] pairs, acc ""]
      (cond
        (or (zero? n) (empty? ps)) acc
        (>= n v)                   (recur (- n v) ps (str acc s))
        :else                      (recur n more acc)))))

(defn- ->letter [n] (str (char (+ (int \A) (dec n)))))

(def ^:private formatters
  {:arabic str :roman ->roman :letter ->letter})

(defn- fmt [format n] ((get formatters format str) n))

(defn- kind-word
  "The localized noun for a numbered `kind` in `language` (the manuscript's
   `:book/language`, threaded through the accumulator). The dictionary's
   float and structure entries carry the `:en` baseline."
  [language kind]
  (dictionary/localize language kind))

(def ^:private float-id-prefixes
  "The synthesized anchor-id prefix for each float kind, used when the
   author gave no `:id` (mirrors the `idx-N` scheme for index marks)."
  {:figure "fig" :table "tbl" :listing "lst"})

;; --- node helpers ---------------------------------------------------------

(defn- attrs-of [node] (when (map? (second node)) (second node)))
(defn- children-of [node] (if (map? (second node)) (drop 2 node) (rest node)))

(defn- heading? [node]
  (and (vector? node) (#{:h1 :h2 :h3 :h4 :h5 :h6} (first node))))

(defn- node-text [node]
  (cond
    (string? node) node
    (vector? node) (apply str (map node-text (children-of node)))
    :else          ""))

;; --- counters --------------------------------------------------------------

;; The numbering walk threads a single accumulator value — a plain map
;;
;;     {:registry {} :counters {} :index {} :idx-counter 0 :floats []}
;;
;; — left-to-right through the tree. Each walker takes the accumulator and a
;; node and returns `[acc' node']`: no atoms, no escaping mutable state. A
;; transient `:sec` key holds the per-chapter section counter while a body is
;; walked and is dropped again on the way out.

(defn- bump
  "Increment the `k` counter in `acc`, returning `[acc' n]` with the new
   count."
  [acc k]
  (let [acc (update-in acc [:counters k] (fnil inc 0))]
    [acc (get-in acc [:counters k])]))

(defn- register
  "Add registry `entry` under `id`, or throw if `id` is already taken.
   Every author-supplied `:id` — on a heading, chapter, appendix, part,
   matter section, or captioned float — must be unique across the whole
   book: ids become anchor targets, and a duplicate would otherwise
   surface only as a late, format-specific render error."
  [acc id entry]
  (when (contains? (:registry acc) id)
    (throw (error/ex :smia.book.number/duplicate-id
                     (str "Duplicate id " (pr-str id) ": an :id must be unique "
                          "across the whole book.")
                     {:id id})))
  (update acc :registry assoc id entry))

;; --- body sections (headings) ---------------------------------------------

(declare walk walk-seq number-float mark-index)

(defn- numberable-kind
  "The numbered kind of a body block, or nil. Figures always number;
   tables and code listings number only when they carry a `:caption`."
  [node]
  (case (first node)
    :figure :figure
    :table  (when (:caption (attrs-of node)) :table)
    :pre    (when (:caption (attrs-of node)) :listing)
    nil))

(defn- walk-seq
  "Walk `nodes` left-to-right, threading `acc`. Returns `[acc' nodes']`."
  [ctx acc nodes]
  (reduce (fn [[acc out] node]
            (let [[acc node'] (walk ctx acc node)]
              [acc (conj out node')]))
          [acc []]
          nodes))

(defn- number-float
  "Number a figure/table/listing `node` of `kind`: bump the book-wide
   counter, register it, record it in `:floats`, and stamp its attrs."
  [ctx acc node kind]
  (let [a         (or (attrs-of node) {})
        [acc n]   (bump acc kind)
        num       (str n)
        label     (str (kind-word (:language acc) kind) " " num)
        author-id (:id a)
        id        (if author-id (name author-id)
                      (str (float-id-prefixes kind) "-" num))
        entry     {:kind kind :id id :number num :label label
                   :title (:caption a)}
        acc       (-> acc
                      (register id (dissoc entry :id))
                      (update :floats conj entry))
        [acc kids] (walk-seq ctx acc (children-of node))]
    [acc (into [(first node)
                (cond-> (assoc a :number num :label label)
                  (not author-id) (assoc :id id))]
               kids)]))

(defn- mark-index
  "Stamp an `:index` mark with a unique anchor id, collecting `term -> [ids]`
   in `acc`."
  [ctx acc node]
  (let [a   (or (attrs-of node) {})
        n   (inc (:idx-counter acc))
        id  (str "idx-" n)
        acc (cond-> (assoc acc :idx-counter n)
              (:term a) (update :index update (:term a) (fnil conj []) id))
        [acc kids] (walk-seq ctx acc (children-of node))]
    [acc (into [:index (assoc a :id id)] kids)]))

(defn- walk
  "Walk one body `node` with the read-only `ctx` (`:policy`,
   `:chapter-number`), threading `acc`. Returns `[acc' node']`: collects
   `:id` headings into the registry, numbers `:h2` sections decimally within
   the chapter when enabled, numbers floats, and marks index entries."
  [ctx acc node]
  (let [{:keys [policy chapter-number]} ctx]
    (cond
      (heading? node)
      (let [a (attrs-of node)]
        (if-let [id (:id a)]
          (let [title (node-text node)]
            (if (and (:sections policy) chapter-number (= :h2 (first node)))
              (let [sec (inc (:sec acc))
                    n   (str chapter-number "." sec)
                    acc (-> acc
                            (assoc :sec sec)
                            (register (name id)
                                      {:kind :section :number n :title title}))]
                [acc (into [(first node) a (str n " ")] (children-of node))])
              [(register acc (name id) {:kind :section :title title})
               node]))
          [acc node]))

      (and (vector? node) (= :index (first node)))
      (mark-index ctx acc node)

      (and (vector? node) (numberable-kind node))
      (number-float ctx acc node (numberable-kind node))

      (vector? node) (walk-seq ctx acc node)
      :else          [acc node])))

(defn- number-body
  "Walk a chapter `body` threading `acc`, numbering its sections within
   `chapter-number` (nil to disable) under `policy`. Returns `[acc' body']`.
   The per-chapter section counter lives in a transient `:sec` key that does
   not escape this call."
  [acc body policy chapter-number]
  (let [ctx          {:policy policy :chapter-number chapter-number}
        [acc body']  (walk-seq ctx (assoc acc :sec 0) body)]
    [(dissoc acc :sec) body']))

;; --- structural sections --------------------------------------------------

(defn- number-chapter-like
  "Number a `:chapter` or `:appendix` section: increment its counter (unless
   the policy disables that kind), label it, annotate the chapter attrs, and
   number its body sections. Returns `[acc' section']`."
  [acc section policy]
  (let [k         (:kind section)
        fmt-key   (if (= k :appendix) (:appendices policy) (:chapters policy))
        numbered? (boolean fmt-key)
        [acc n]   (if numbered? (bump acc k) [acc nil])
        num       (when numbered? (fmt fmt-key n))
        label     (when num (str (kind-word (:language acc) k) " " num))
        [_ a & body] (:content section)
        [acc body'] (number-body acc (vec body) policy num)
        a'        (cond-> a num (assoc :number num :label label :kind k))
        acc       (register acc (name (:id a))
                            (cond-> {:kind k :title (:title a)}
                              num (assoc :number num :label label)))]
    [acc (assoc section :content (into [:chapter a'] body')
                :number num :label label)]))

(defn- number-part
  "Number a `:part` section. Returns `[acc' section']`."
  [acc section policy]
  (let [[acc n] (bump acc :part)
        num     (fmt (:parts policy) n)
        label   (str (kind-word (:language acc) :part) " " num)
        acc     (register acc (str "part-" (:index section))
                          {:kind :part :number num :label label
                           :title (:title section)})]
    [acc (assoc section :number num :label label)]))

(defn- number-matter
  "Register a matter section (front/back matter) and number any body it
   carries. Returns `[acc' section']`."
  [acc section policy]
  (if-let [content (:content section)]
    (let [[_ a & body] content
          [acc body'] (number-body acc (vec body) policy nil)
          acc (register acc (name (:id a))
                        {:kind :matter :title (:title a)})]
      [acc (assoc section :content (into [:chapter a] body'))])
    [(register acc (name (:role section))
               {:kind :matter
                :title (or (:title section)
                           (structure/role-title (:role section) (:language acc)))})
     section]))

(defn- number-sections
  "Number every top-level `section` under `policy`, threading the numbering
   accumulator left-to-right. `language` (the book's `:book/language`) drives
   the localized labels. Returns `{:sections :registry :index :floats}`."
  [sections policy language]
  (let [init {:registry {} :counters {} :index {} :idx-counter 0 :floats []
              :language language}
        [acc out]
        (reduce (fn [[acc out] s]
                  (let [[acc s'] (case (:kind s)
                                   :part                (number-part acc s policy)
                                   (:chapter :appendix) (number-chapter-like acc s policy)
                                   :matter              (number-matter acc s policy)
                                   [acc s])]
                    [acc (conj out s')]))
                [init []]
                sections)]
    {:sections out :registry (:registry acc) :index (:index acc)
     :floats (:floats acc)}))

;; --- cross-reference rewriting --------------------------------------------

(defn- xref-attrs
  "Merge the registry entry's label, title, and kind onto a childless
   xref's attrs so the expander can compose its final text."
  [a entry]
  (cond-> a
    (:label entry) (assoc :label (:label entry))
    (:title entry) (assoc :title (:title entry))
    (:kind entry)  (assoc :kind (:kind entry))))

(defn- cite-label
  "Compose a citation's visible label from a bibliography `entry`: \"Author
   Year\" when both are present, else the title, else the bare key."
  [entry key]
  (cond
    (and (:author entry) (:year entry)) (str (:author entry) " " (:year entry))
    (:title entry)                      (:title entry)
    :else                               (name key)))

(defn- rewrite-refs
  "Resolve cross-references and citations: a childless `:xref` gains the
   target's label/title/kind; a `:cite` gains its bibliography label and
   `ref-id`. An `:xref` to an id no target defines is a hard error, as is an
   unknown citation key."
  [node registry references]
  (cond
    (and (vector? node) (= :xref (first node)) (map? (second node)))
    (let [a    (second node)
          kids (children-of node)
          entry (get registry (name (:to a)))]
      (when-not entry
        (throw (error/ex :smia.book.number/unresolved-xref
                         (str "Cross-reference to unknown id: " (:to a))
                         {:to (:to a)})))
      (if (seq kids)
        (into [:xref a] (map #(rewrite-refs % registry references) kids))
        [:xref (xref-attrs a entry)]))

    (and (vector? node) (= :cite (first node)) (map? (second node)))
    (let [a   (second node)
          key (:key a)
          entry (get references key)]
      (when-not entry
        (throw (error/ex :smia.book.number/unknown-citation
                         (str "No bibliography entry for citation: " key)
                         {:key key})))
      [:cite (assoc a :label (cite-label entry key)
                    :ref-id (str "ref-" (name key)))])

    (vector? node) (mapv #(rewrite-refs % registry references) node)
    :else          node))

(defn- rewrite-section [section registry references]
  (if (:content section)
    (assoc section :content (rewrite-refs (:content section) registry references))
    section))
