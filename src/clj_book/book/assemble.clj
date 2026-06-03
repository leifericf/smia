(ns clj-book.book.assemble
  "Pure core: assemble ordered chapter Hiccup plus book metadata and a
   compiled theme into a single `:fo/root` FO-Hiccup tree.

   It emits the book machinery authors do not write by hand: the
   layout-master-set, a title page and table of contents (chapter links +
   dotted leaders + page-number citations), a PDF bookmark tree, and one
   `fo:page-sequence` per chapter with running heads (via
   `fo:marker`/`fo:retrieve-marker`) and page numbers. Chapter bodies are
   embedded as authored sugar; the expansion pass that runs afterwards
   turns that sugar into FO. Cross-references are resolved here: an
   `[:xref {:to id}]` to an unknown id is a hard error. No IO."
  (:require
   [clj-book.book.structure :as structure]
   [clj-book.error :as error]
   [clojure.string :as str]))

(defn- as-id [v] (if (keyword? v) (name v) (str v)))

;; --- chapter parsing ------------------------------------------------------

(defn- parse-chapter [form]
  (when-not (and (vector? form) (= :chapter (first form)))
    (throw (error/ex :clj-book.book.assemble/invalid-chapter
                     "A chapter must be a [:chapter {:id .. :title ..} ..] form."
                     {:chapter form})))
  (let [[_ attrs & body] form
        attrs (or attrs {})]
    (when-not (keyword? (:id attrs))
      (throw (error/ex :clj-book.book.assemble/missing-chapter-id
                       "Each :chapter needs a keyword :id."
                       {:chapter form})))
    (when-not (string? (:title attrs))
      (throw (error/ex :clj-book.book.assemble/missing-chapter-title
                       "Each :chapter needs a string :title."
                       {:chapter form})))
    ;; :number/:label are attached by the numbering pass (may be absent).
    {:id (:id attrs) :title (:title attrs) :body (vec body)
     :number (:number attrs) :label (:label attrs)}))

;; --- cross-reference resolution -------------------------------------------

(defn- collect-ids [node]
  (cond
    (vector? node)
    (let [[_ attrs] node]
      (concat (when (and (map? attrs) (:id attrs)) [(as-id (:id attrs))])
              (mapcat collect-ids node)))
    (seq? node) (mapcat collect-ids node)
    :else nil))

(defn- collect-xref-targets [node]
  (cond
    (vector? node)
    (let [[tag attrs] node]
      (concat (when (and (= :xref tag) (map? attrs) (:to attrs))
                [(as-id (:to attrs))])
              (mapcat collect-xref-targets node)))
    (seq? node) (mapcat collect-xref-targets node)
    :else nil))

(defn- resolve-xrefs!
  "Throw if any `[:xref {:to id}]` points at an id no chapter or element
   defines."
  [chapters]
  (let [defined (set (concat (map (comp name :id) chapters)
                             (mapcat #(collect-ids (:body %)) chapters)))
        used    (mapcat #(collect-xref-targets (:body %)) chapters)
        missing (vec (distinct (remove defined used)))]
    (when (seq missing)
      (throw (error/ex :clj-book.book.assemble/unresolved-xref
                       (str "Cross-reference(s) to unknown id(s): "
                            (str/join ", " missing))
                       {:missing missing :defined (vec (sort defined))})))))

;; --- sections -------------------------------------------------------------

(defn- generated-parsed
  "The synthetic `{:id :title :body}` for a generated matter section (the
   bibliography or index), which has no source file."
  [section]
  {:id    (:role section)
   :title (or (:title section) (structure/role-title (:role section)))
   :body  []})

(defn- section->parsed
  "The parsed `{:id :title :body}` a section contributes — its loaded
   chapter, or a generated placeholder for roleful matter."
  [section]
  (or (:chapter section) (generated-parsed section)))

(defn- prepare-sections
  "Attach a parsed `:chapter` to every file-backed section; leave part
   dividers and generated matter untouched."
  [sections]
  (mapv (fn [s] (if (:content s) (assoc s :chapter (parse-chapter (:content s))) s))
        sections))

(defn- book-sections [{:keys [sections]}]
  (prepare-sections sections))

(defn- heading-text [node]
  (apply str (filter string? (tree-seq vector? seq node))))

(defn- section-headings
  "Top-level `:h2` headings in a chapter body that carry an `:id` — the
   chapter's navigable sections, in order. Each is `{:id :text}`."
  [{:keys [body]}]
  (keep (fn [node]
          (when (and (vector? node) (= :h2 (first node))
                     (map? (second node)) (:id (second node)))
            {:id (name (:id (second node))) :text (heading-text node)}))
        body))

;; --- fragments ------------------------------------------------------------

(defn- bookmark
  "A `fo:bookmark` to `id` titled `title`, with optional nested children."
  [id title children]
  (into [:fo/bookmark {:internal-destination (name id)}
         [:fo/bookmark-title title]]
        children))

(defn- chapter-bookmark
  "A chapter's bookmark, nesting a child bookmark for each navigable
   section. A chapter with no id-bearing sections (the common case) yields
   exactly the flat bookmark of before."
  [parsed]
  (bookmark (:id parsed) (:title parsed)
            (map #(bookmark (:id %) (:text %) nil) (section-headings parsed))))

(defn- bookmark-tree
  "A nested PDF bookmark tree: parts contain their chapters, chapters
   contain their sections; every other section is a top-level bookmark. A
   flat book (no parts, no section ids) yields the same flat list as before."
  [prepared]
  (into [:fo/bookmark-tree]
        (loop [ss prepared, acc []]
          (if (empty? ss)
            acc
            (let [s (first ss)]
              (cond
                (= :part (:kind s))
                (let [idx (:index s)
                      [kids more] (split-with #(and (= :chapter (:kind %))
                                                    (= idx (:part %)))
                                              (rest ss))]
                  (recur more (conj acc (bookmark (str "part-" idx) (:title s)
                                                  (map #(chapter-bookmark (:chapter %)) kids)))))

                (:chapter s)
                (recur (rest ss) (conj acc (chapter-bookmark (:chapter s))))

                :else
                (let [{:keys [id title]} (section->parsed s)]
                  (recur (rest ss) (conj acc (bookmark id title nil))))))))))

(defn- numbered-text [number title]
  (if number (str number "  " title) title))

(defn- toc-entries
  "Flatten the prepared sections into table-of-contents entries
   `{:id :text :level :bold?}`: parts (bold, level 0) with their chapters
   (level 1) and each chapter's sections (level 2); flat chapters at level
   0; appendices and named matter at level 0."
  [prepared]
  (loop [ss prepared, acc []]
    (if (empty? ss)
      acc
      (let [s (first ss)]
        (case (:kind s)
          :part
          (recur (rest ss)
                 (conj acc {:id (str "part-" (:index s)) :level 0 :bold? true
                            :text (numbered-text (:label s) (:title s))}))

          :chapter
          (let [p     (:chapter s)
                level (if (:part s) 1 0)
                entry {:id (name (:id p)) :level level
                       :text (numbered-text (:number p) (:title p))}
                secs  (map (fn [h] {:id (:id h) :level (inc level) :text (:text h)})
                           (section-headings p))]
            (recur (rest ss) (into (conj acc entry) secs)))

          :appendix
          (let [p (:chapter s)]
            (recur (rest ss)
                   (conj acc {:id (name (:id p)) :level 0
                              :text (numbered-text (:number p) (:title p))})))

          :matter
          (let [{:keys [id title]} (section->parsed s)]
            (recur (rest ss) (conj acc {:id (name id) :level 0 :text title})))

          (recur (rest ss) acc))))))

(defn- toc-entry [{:keys [id text level bold?]} link-color]
  ;; text-align-last="justify" pushes the page number flush right; the
  ;; leader must be free to stretch (maximum 100%) so it absorbs all the
  ;; slack. A fixed-length leader would instead leave the line short and
  ;; spill the leftover space into the title's word spacing.
  [:fo/block (cond-> {:text-align-last "justify" :space-after "5pt"}
               (pos? level) (assoc :start-indent (str (* level 16) "pt"))
               bold?        (assoc :font-weight "bold"))
   [:fo/basic-link {:internal-destination id :color link-color} text]
   [:fo/leader {:leader-pattern         "dots"
                :leader-length.minimum  "12pt"
                :leader-length.optimum  "12pt"
                :leader-length.maximum  "100%"}]
   [:fo/page-number-citation {:ref-id id}]])

(defn- title-page [title author head-family muted-color]
  [:fo/block {:text-align "center" :space-before "108pt"
              :space-before.conditionality "retain"}
   [:fo/block {:font-family head-family :font-size "36pt" :font-weight "bold"
               :space-after "12pt"} title]
   (when author
     [:fo/block {:font-size "13pt" :color muted-color} author])])

;; --- running heads and footers --------------------------------------------

(def ^:private default-running-heads
  "Default running content per page parity: the chapter title on a verso
   (left) page, the current section on a recto (right) page, the page number
   in both footers. A book overrides any slot via `:book/running-heads`."
  {:verso  {:before :chapter :after :page}
   :recto  {:before :section :after :page}
   :screen {:before :chapter :after :page}})

(defn- parity-key [parity] (if (#{:recto :verso} parity) parity :screen))

(defn- slot->inline [content-slot book-title]
  (case content-slot
    :page       [:fo/page-number]
    :chapter    [:fo/retrieve-marker {:retrieve-class-name "chapter-title"}]
    :section    [:fo/retrieve-marker {:retrieve-class-name "section-title"}]
    :book-title book-title
    nil))

(defn- region-block [slot parity inline muted-color rule-color]
  (let [align (if (= slot :after)
                "center"
                (case parity :recto "right" :verso "left" "center"))]
    (into [:fo/block (cond-> {:text-align align :font-size "9pt" :color muted-color}
                       (= slot :before)
                       (assoc :border-bottom (str "0.25pt solid " rule-color)
                              :padding-bottom "3pt" :space-before "4pt"))]
          [inline])))

(defn- static-contents
  "The `fo:static-content` for every running region the theme declares,
   choosing each region's content from the running-heads config. With
   `headers?` false (front matter, part dividers) only footers are emitted."
  [{:keys [theme running-heads book-title headers?]}]
  (let [{:keys [running-regions muted-color rule-color]} theme]
    (keep (fn [{:keys [slot name parity]}]
            (let [cfg-slot (when (or headers? (= slot :after))
                             (get-in running-heads [(parity-key parity) slot]))
                  inline   (slot->inline cfg-slot book-title)]
              (when inline
                (into [:fo/static-content {:flow-name name}]
                      [(region-block slot parity inline muted-color rule-color)]))))
          running-regions)))

(defn- page-sequence
  "Build a `fo:page-sequence`: its page attrs, the running static content
   (headers + footers, or footers only), and the body flow."
  [page-attrs ctx headers? body-style flow-children]
  (into [:fo/page-sequence (merge {:master-reference (:master-ref ctx)} page-attrs)]
        (concat
          (static-contents (assoc ctx :headers? headers?))
          [(into [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)]
                 flow-children)])))

(defn- toc-furniture [title author prepared ctx]
  (let [{:keys [style link-color rule-color muted-color]} (:theme ctx)
        body-style  (:body style)
        head-family (get-in style [:h1 :font-family])]
    (page-sequence
      {:format "i"} ctx false body-style
      (concat
        [(title-page title author head-family muted-color)]
        [[:fo/block {:font-family head-family :font-size "18pt"
                     :font-weight "bold" :break-before "page"
                     :border-bottom (str "0.5pt solid " rule-color)
                     :padding-bottom "4pt" :space-after "12pt"} "Contents"]]
        (map #(toc-entry % link-color) (toc-entries prepared))))))

(defn- chapter-heading [{:keys [id title label]} style rule-color muted-color]
  ;; The running-head marker carries the bare title; the visible heading
  ;; shows the numbered label (e.g. "Chapter 1") above it when present.
  (into [:fo/block (merge (get style :h1)
                          {:id            (name id)
                           :break-before  "page"
                           :space-before  "36pt"
                           :space-before.conditionality "retain"
                           :space-after   "18pt"
                           :border-bottom (str "1pt solid " rule-color)
                           :padding-bottom "6pt"})
         [:fo/marker {:marker-class-name "chapter-title"} title]]
        (concat
          (when label
            [[:fo/block {:font-size "13pt" :font-weight "normal"
                         :color muted-color :space-after "2pt"} label]])
          [title])))

(defn- body-sequence
  "A page-sequence for one parsed chapter (or matter/appendix section):
   running heads, footers, the chapter heading, and the body. `page-attrs`
   carries the per-section page-numbering (roman front matter, the arabic
   reset on the first body section, recto parity)."
  [{:keys [body] :as parsed} ctx page-attrs]
  (let [{:keys [style rule-color muted-color]} (:theme ctx)
        body-style (:body style)]
    (page-sequence page-attrs ctx true body-style
                   (cons (chapter-heading parsed style rule-color muted-color) body))))

(defn- part-sequence
  "A part-divider page-sequence: the part title, centered and large, on its
   own page. Its `:id` (`part-N`) is the bookmark/cross-reference target."
  [section ctx page-attrs]
  (let [{:keys [style muted-color]} (:theme ctx)
        body-style  (:body style)
        head-family (get-in style [:h1 :font-family])]
    (page-sequence
      page-attrs ctx false body-style
      [(into [:fo/block {:id (str "part-" (:index section))
                         :font-family head-family :text-align "center"
                         :space-before "144pt"
                         :space-before.conditionality "retain"}]
             (concat
               (when-let [label (:label section)]
                 [[:fo/block {:font-size "16pt" :color muted-color
                              :space-after "8pt"} label]])
               [[:fo/block {:font-size "30pt" :font-weight "bold"}
                 (:title section)]]))])))

;; --- generated back matter (bibliography, index) --------------------------

(defn- bibliography-entry-text [{:keys [author title year publisher]}]
  (->> [(when author (str author "."))
        (when title (str title "."))
        (when publisher (str publisher ","))
        (when year (str year "."))]
       (remove nil?)
       (str/join " ")))

(defn- bibliography-blocks
  "A sorted, hanging-indent bibliography; each entry's `:id` is `ref-<key>`,
   the citation target."
  [references]
  (for [[key entry] (sort-by (fn [[k e]] [(or (:author e) (name k)) (str (:year e))])
                             references)]
    [:fo/block {:id (str "ref-" (name key)) :space-after "6pt"
                :start-indent "12pt" :text-indent "-12pt"}
     (bibliography-entry-text entry)]))

(defn- index-blocks
  "An alphabetical index; each term lists page citations to its marks."
  [index]
  (for [[term ids] (sort-by key index)]
    (into [:fo/block {:text-align-last "justify" :space-after "2pt"} term
           [:fo/leader {:leader-pattern "dots" :leader-length.minimum "12pt"
                        :leader-length.optimum "12pt" :leader-length.maximum "100%"}]]
          (interpose ", " (map (fn [id] [:fo/page-number-citation {:ref-id id}]) ids)))))

(defn- float-list-blocks
  "A list of figures/tables/listings: every numbered float of `kind`, in
   document order, linked to its anchor with a dotted leader and resolved
   page number (the TOC-entry pattern)."
  [floats want-kind link-color]
  (for [{:keys [kind id label title]} floats
        :when (= kind want-kind)]
    (let [text (if title (str label ". " title) label)]
      [:fo/block {:text-align-last "justify" :space-after "5pt"}
       [:fo/basic-link {:internal-destination id :color link-color} text]
       [:fo/leader {:leader-pattern        "dots"
                    :leader-length.minimum "12pt"
                    :leader-length.optimum "12pt"
                    :leader-length.maximum "100%"}]
       [:fo/page-number-citation {:ref-id id}]])))

(defn- matter-parsed
  "The parsed `{:id :title :body}` for a matter section: its loaded chapter,
   or a generated body for the bibliography, index, and float-list roles.
   An author `:title` on the section overrides the role's default title."
  [section ctx]
  (or (:chapter section)
      (let [role       (:role section)
            link-color (get-in ctx [:theme :link-color])]
        {:id    role
         :title (or (:title section) (structure/role-title role))
         :body  (case role
                  :bibliography     (vec (bibliography-blocks (:references ctx)))
                  :index            (vec (index-blocks (:index ctx)))
                  :list-of-figures  (vec (float-list-blocks (:floats ctx) :figure link-color))
                  :list-of-tables   (vec (float-list-blocks (:floats ctx) :table link-color))
                  :list-of-listings (vec (float-list-blocks (:floats ctx) :listing link-color))
                  [])})))

(defn- body-page-attrs
  "Page-numbering attrs for a body-run section: the first resets to arabic
   page 1; later ones start on a recto when parity is on (print)."
  [first-body? recto?]
  (cond
    first-body? {:initial-page-number "1" :format "1"}
    recto?      {:initial-page-number "auto-odd"}
    :else       {}))

(defn- section-sequences
  "Walk the prepared sections, emitting a page-sequence for each: roman
   front matter, part dividers and chapters/appendices (arabic, the first
   resetting the page count), and back matter."
  [prepared ctx]
  (let [recto? (:recto? ctx)]
    (loop [ss prepared, seen-body? false, acc []]
      (if (empty? ss)
        acc
        (let [s (first ss), k (:kind s)]
          (cond
            (and (= k :matter) (= :front (:matter s)))
            (recur (rest ss) seen-body?
                   (conj acc (body-sequence (matter-parsed s ctx) ctx {:format "i"})))

            (and (= k :matter) (= :back (:matter s)))
            (recur (rest ss) seen-body?
                   (conj acc (body-sequence (matter-parsed s ctx) ctx {})))

            (= k :part)
            (recur (rest ss) true
                   (conj acc (part-sequence s ctx (body-page-attrs (not seen-body?) recto?))))

            :else
            (recur (rest ss) true
                   (conj acc (body-sequence (:chapter s) ctx
                                            (body-page-attrs (not seen-body?) recto?))))))))))

;; --- assembly -------------------------------------------------------------

(defn assemble
  "Assemble a typed `manuscript` and a compiled `theme` (from
   `clj-book.book.theme/compile-theme`) into one `:fo/root` tree.

   The manuscript is the typed value from `book.load/load-manuscript`
   (`{:title :author :numbering :sections …}`). Chapter bodies remain
   authored sugar for the later expansion pass."
  [book theme]
  (let [{:keys [title author]} book
        {:keys [style master-reference masters profile]} theme
        numbering  (or (:numbering book) structure/default-numbering)
        prepared   (book-sections book)
        all-parsed (vec (keep :chapter prepared))
        _          (resolve-xrefs! all-parsed)
        recto?     (and (= profile :print)
                        (= :recto (:start-chapters-on numbering)))
        ctx        {:theme         theme
                    :master-ref    master-reference
                    :recto?        recto?
                    :book-title    title
                    :references    (:references book)
                    :index         (:index book)
                    :floats        (:floats book)
                    :running-heads (merge-with merge default-running-heads
                                               (:running-heads book))}
        body-style (get style :body)]
    (into [:fo/root {:font-family (:font-family body-style)
                     :font-size   (:font-size body-style)
                     :line-height (:line-height body-style)}]
          (concat
            [(into [:fo/layout-master-set] masters)]
            [(bookmark-tree prepared)]
            [(toc-furniture title author prepared ctx)]
            (section-sequences prepared ctx)))))
