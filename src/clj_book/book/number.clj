(ns clj-book.book.number
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
      title), leaving the page reference to FO's `fo:page-number-citation`.

   It is pure and layout-free, so it runs in the pipeline before expansion
   and needs no rendering results. New numbered kinds (figures, tables,
   listings) register through the same counters in later phases. No IO."
  (:require
   [clj-book.book.structure :as structure]
   [clojure.string :as str]))

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

(def ^:private kind-words
  "The noun each numbered kind composes into a label, e.g. \"Chapter 3\"."
  {:part "Part" :chapter "Chapter" :appendix "Appendix"
   :figure "Figure" :table "Table" :listing "Listing" :section "Section"})

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

(defn- swap-count [counters k] (get (swap! counters update k (fnil inc 0)) k))

;; --- body sections (headings) ---------------------------------------------

(defn- numberable-kind
  "The numbered kind of a body block, or nil. Figures always number;
   tables and code listings number only when they carry a `:caption`."
  [node]
  (case (first node)
    :figure :figure
    :table  (when (:caption (attrs-of node)) :table)
    :pre    (when (:caption (attrs-of node)) :listing)
    nil))

(defn- number-body
  "Walk a chapter body: collect every `:id` heading into `registry` (an
   atom); number top-level (`:h2`) sections decimally within
   `chapter-number` when section numbering is on; and number figures,
   captioned tables, and captioned code listings book-wide via `counters`,
   annotating each with its `:number`/`:label` and registering any `:id`."
  [body {:keys [chapter-number policy registry counters]}]
  (let [sec (volatile! 0)]
    (letfn [(number-float [node kind]
              (let [a     (or (attrs-of node) {})
                    num   (str (swap-count counters kind))
                    label (str (kind-words kind) " " num)]
                (when (:id a)
                  (swap! registry assoc (name (:id a))
                         {:kind kind :number num :label label :title (:caption a)}))
                (into [(first node) (assoc a :number num :label label)]
                      (map walk (children-of node)))))
            (walk [node]
              (cond
                (heading? node)
                (let [a (attrs-of node)]
                  (if-let [id (:id a)]
                    (let [title (node-text node)]
                      (if (and (:sections policy) chapter-number (= :h2 (first node)))
                        (let [n (str chapter-number "." (vswap! sec inc))]
                          (swap! registry assoc (name id)
                                 {:kind :section :number n :title title})
                          (into [(first node) a (str n " ")] (children-of node)))
                        (do (swap! registry assoc (name id)
                                   {:kind :section :title title})
                            node)))
                    node))

                (and (vector? node) (numberable-kind node))
                (number-float node (numberable-kind node))

                (vector? node) (mapv walk node)
                :else          node))]
      (mapv walk body))))

;; --- structural sections --------------------------------------------------

(defn- number-chapter-like
  "Number a `:chapter` or `:appendix` section: increment its counter (unless
   the policy disables that kind), label it, annotate the chapter attrs, and
   number its body sections."
  [section policy registry counters]
  (let [k        (:kind section)
        fmt-key  (if (= k :appendix) (:appendices policy) (:chapters policy))
        numbered? (boolean fmt-key)
        n        (when numbered? (swap-count counters k))
        num      (when numbered? (fmt fmt-key n))
        label    (when num (str (kind-words k) " " num))
        [_ a & body] (:content section)
        body'    (number-body (vec body)
                              {:chapter-number num :policy policy
                               :registry registry :counters counters})
        a'       (cond-> a num (assoc :number num :label label :kind k))]
    (swap! registry assoc (name (:id a))
           (cond-> {:kind k :title (:title a)}
             num (assoc :number num :label label)))
    (assoc section :content (into [:chapter a'] body') :number num :label label)))

(defn- number-part [section policy registry counters]
  (let [n     (swap-count counters :part)
        num   (fmt (:parts policy) n)
        label (str (kind-words :part) " " num)]
    (swap! registry assoc (str "part-" (:index section))
           {:kind :part :number num :label label :title (:title section)})
    (assoc section :number num :label label)))

(defn- number-matter [section policy registry counters]
  (if-let [content (:content section)]
    (let [[_ a & body] content
          body' (number-body (vec body)
                             {:chapter-number nil :policy policy
                              :registry registry :counters counters})]
      (swap! registry assoc (name (:id a)) {:kind :matter :title (:title a)})
      (assoc section :content (into [:chapter a] body')))
    (do (swap! registry assoc (name (:role section))
               {:kind :matter :title (structure/role-title (:role section))})
        section)))

(defn- number-sections [sections policy]
  (let [registry (atom {})
        counters (atom {})
        out (mapv (fn [s]
                    (case (:kind s)
                      :part                (number-part s policy registry counters)
                      (:chapter :appendix) (number-chapter-like s policy registry counters)
                      :matter              (number-matter s policy registry counters)
                      s))
                  sections)]
    {:sections out :registry @registry}))

;; --- cross-reference rewriting --------------------------------------------

(defn- xref-attrs
  "Merge the registry entry's label, title, and kind onto a childless
   xref's attrs so the expander can compose its final text."
  [a entry]
  (cond-> a
    (:label entry) (assoc :label (:label entry))
    (:title entry) (assoc :title (:title entry))
    (:kind entry)  (assoc :kind (:kind entry))))

(defn- rewrite-xrefs [node registry]
  (cond
    (and (vector? node) (= :xref (first node)) (map? (second node)))
    (let [a    (second node)
          kids (children-of node)]
      (if (seq kids)
        (into [:xref a] (map #(rewrite-xrefs % registry) kids))
        (if-let [entry (get registry (name (:to a)))]
          [:xref (xref-attrs a entry)]
          node)))
    (vector? node) (mapv #(rewrite-xrefs % registry) node)
    :else          node))

(defn- rewrite-section [section registry]
  (if (:content section)
    (assoc section :content (rewrite-xrefs (:content section) registry))
    section))

;; --- public transform -----------------------------------------------------

(defn assign
  "Number `manuscript`'s targets and resolve cross-references. Returns
   `{:manuscript <annotated manuscript> :registry <id → entry>}`."
  [manuscript]
  (let [policy (:numbering manuscript)
        {:keys [sections registry]} (number-sections (:sections manuscript) policy)
        sections (mapv #(rewrite-section % registry) sections)]
    {:manuscript (assoc manuscript :sections sections)
     :registry   registry}))

(defn counts
  "Per-kind counts of numbered targets, for the dry-run plan."
  [{:keys [registry]}]
  (let [by (frequencies (map :kind (vals registry)))]
    (cond-> {}
      (:part by)      (assoc :parts (:part by))
      (:chapter by)   (assoc :chapters (:chapter by))
      (:appendix by)  (assoc :appendices (:appendix by)))))
