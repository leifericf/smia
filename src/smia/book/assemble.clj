(ns smia.book.assemble
  "Pure core: assemble ordered chapter Hiccup plus book metadata and a
   compiled theme into a single `:fo/root` FO-Hiccup tree.

   It emits the book machinery authors do not write by hand: the
   layout-master-set, a title page and table of contents (chapter links +
   dotted leaders + page-number citations), a PDF bookmark tree, and one
   `fo:page-sequence` per chapter with running heads (via
   `fo:marker`/`fo:retrieve-marker`) and page numbers. Chapter bodies are
   embedded as authored sugar; the expansion pass that runs afterwards
   turns that sugar into FO. Cross-references and citations are resolved
   earlier, by the numbering pass (`book.number`), so assembly may assume
   every `:xref`/`:cite` is already labelled. No IO."
  (:require
   [smia.book.structure :as structure]
   [clojure.string :as str]))

(declare book-sections bookmark-tree toc-furniture section-sequences
         default-running-heads)

;; --- language -------------------------------------------------------------

(defn bcp47->fo
  "Split a BCP-47 tag (the book's `:book/language`) into the 2-letter
   `language`/`country` attributes FO wants on the root, normalizing case.
   The country is the first 2-letter subtag after the language, so a script
   subtag (`zh-Hans`) is not mistaken for one. No language, no attributes —
   the FO output stays byte-identical for a language-less book."
  [tag]
  (if (or (nil? tag) (str/blank? tag))
    {}
    (let [[lang & subtags] (str/split tag #"-")
          region (some #(when (re-matches #"[A-Za-z]{2}" %) %) subtags)]
      (if (str/blank? lang)
        {}
        (cond-> {:language (str/lower-case lang)}
          region (assoc :country (str/upper-case region)))))))

;; --- assembly -------------------------------------------------------------

(defn assemble
  "Assemble a typed `manuscript` and a compiled `theme` (from
   `smia.theme.compile/compile-theme`) into one `:fo/root` tree.

   The manuscript is the typed value from `book.load/load-manuscript`
   (`{:title :author :numbering :sections …}`). Chapter bodies remain
   authored sugar for the later expansion pass."
  [book theme]
  (let [{:keys [title subtitle author]} book
        {:keys [style master-reference masters layout]} theme
        numbering  (or (:numbering book) structure/default-numbering)
        prepared   (book-sections book)
        recto?     (and (= layout :print)
                        (= :recto (:start-chapters-on numbering)))
        ctx        {:theme         theme
                    :master-ref    master-reference
                    :recto?        recto?
                    :book-title    title
                    :references    (:references book)
                    :index         (:index book)
                    :floats        (:floats book)
                    :running-heads (merge-with merge default-running-heads
                                               (:running-heads book))
                    :licensee      (:licensee book)}
        body-style (get style :body)]
    (into [:fo/root (merge {:font-family (:font-family body-style)
                            :font-size   (:font-size body-style)
                            :line-height (:line-height body-style)}
                           (bcp47->fo (:language book)))]
          (concat
            [(into [:fo/layout-master-set] masters)]
            [(bookmark-tree prepared)]
            [(toc-furniture title subtitle author prepared ctx)]
            (section-sequences prepared ctx)))))

;; --- chapter parsing ------------------------------------------------------

(defn- parse-chapter
  "Destructure a `[:chapter {…} …]` form into `{:id :title :body :number
   :label}`. The chapter's shape is validated at the load boundary
   (`book.load/check-chapter-shape`), so this is a pure non-throwing
   destructure. `:number`/`:label` are attached by the numbering pass and may
   be absent."
  [form]
  (let [[_ attrs & body] form
        attrs (or attrs {})]
    {:id (:id attrs) :title (:title attrs) :body (vec body)
     :number (:number attrs) :label (:label attrs)}))

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
  "Attach a parsed `:chapter` to every file-backed section, and resolve the
   localized title of every generated (file-less) matter section so the
   outline and the page builder render the same localized heading. Part
   dividers and author-titled sections are untouched."
  [sections language]
  (mapv (fn [s]
          (cond-> s
            (:content s) (assoc :chapter (parse-chapter (:content s)))
            (and (:role s) (not (:content s)) (not (:title s)))
            (assoc :title (structure/role-title (:role s) language))))
        sections))

(defn- book-sections [{:keys [sections language]}]
  (prepare-sections sections language))

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

;; --- book outline ---------------------------------------------------------

;; The bookmark tree, table of contents, and page-sequence run all walk the
;; prepared sections with the same parts -> chapters -> sections dispatch.
;; `outline` performs that walk once, as a pure value the three builders
;; consume, so the dispatch and part-grouping live in one place.

(defn- nav-children
  "The navigable in-chapter headings of `parsed`, as child outline nodes the
   bookmark and TOC builders nest beneath it."
  [parsed]
  (mapv (fn [{:keys [id text]}] {:kind :section :id id :title text})
        (section-headings parsed)))

(defn- section-node
  "An outline node for a non-part section: its destination id, bare title,
   computed number/label, the original section, its parsed chapter, and child
   nodes for its navigable headings. Works for file-backed chapters,
   appendices, and matter as well as generated (file-less) matter."
  [section]
  (let [parsed (section->parsed section)]
    {:kind     (:kind section)
     :id       (name (:id parsed))
     :title    (:title parsed)
     :number   (:number parsed)
     :label    (:label parsed)
     :section  section
     :parsed   parsed
     :children (nav-children parsed)}))

(defn- part-node
  "An outline node for a part divider, nesting its grouped chapters."
  [section kids]
  {:kind     :part
   :id       (str "part-" (:index section))
   :title    (:title section)
   :label    (:label section)
   :section  section
   :children (mapv section-node kids)})

(defn- outline
  "The book's structure as one ordered tree, computed once and shared by the
   bookmark, table-of-contents, and page-sequence builders. Top-level nodes
   are in document order; a part nests its chapters; every chapter-like node
   carries its navigable headings as children."
  [prepared]
  (loop [ss prepared, acc []]
    (if (empty? ss)
      acc
      (let [s (first ss)]
        (if (= :part (:kind s))
          (let [idx (:index s)
                [kids more] (split-with #(and (= :chapter (:kind %))
                                              (= idx (:part %)))
                                        (rest ss))]
            (recur more (conj acc (part-node s kids))))
          (recur (rest ss) (conj acc (section-node s))))))))

(defn- outline-seq
  "The outline flattened back to document order — each part followed by its
   chapters — for the page-sequence walk, which numbers pages in reading
   order rather than by nesting."
  [prepared]
  (mapcat (fn [n] (if (= :part (:kind n)) (cons n (:children n)) [n]))
          (outline prepared)))

;; --- fragments ------------------------------------------------------------

(defn- bookmark
  "A `fo:bookmark` to `id` titled `title`, with optional nested children."
  [id title children]
  (into [:fo/bookmark {:internal-destination (name id)}
         [:fo/bookmark-title title]]
        children))

(defn- node->bookmark
  "An outline node's `fo:bookmark`, recursively nesting its children (a
   part's chapters, or a chapter's navigable sections)."
  [node]
  (bookmark (:id node) (:title node) (map node->bookmark (:children node))))

(defn- bookmark-tree
  "A nested PDF bookmark tree: parts contain their chapters, chapters
   contain their sections; every other section is a top-level bookmark. A
   flat book (no parts, no section ids) yields the same flat list as before."
  [prepared]
  (into [:fo/bookmark-tree] (map node->bookmark (outline prepared))))

(defn- numbered-text [number title]
  (if number (str number "  " title) title))

(defn- chapter-toc-entries
  "A chapter node's TOC entry at `level`, followed by its navigable sections
   at `level + 1`."
  [node level]
  (cons {:id (:id node) :level level
         :text (numbered-text (:number node) (:title node))}
        (map (fn [c] {:id (:id c) :level (inc level) :text (:title c)})
             (:children node))))

(defn- node->toc-entries
  "An outline node's table-of-contents entries `{:id :text :level :bold?}`:
   a part (bold, level 0) with its chapters (level 1) and their sections
   (level 2); a flat chapter at level 0 with its sections at level 1;
   appendices and named matter at level 0 with no sub-entries."
  [node]
  (case (:kind node)
    :part     (cons {:id (:id node) :level 0 :bold? true
                     :text (numbered-text (:label node) (:title node))}
                    (mapcat #(chapter-toc-entries % 1) (:children node)))
    :chapter  (chapter-toc-entries node 0)
    :appendix [{:id (:id node) :level 0
                :text (numbered-text (:number node) (:title node))}]
    :matter   [{:id (:id node) :level 0 :text (:title node)}]
    nil))

(defn- toc-entries
  "Flatten the book outline into table-of-contents entries in document
   order."
  [prepared]
  (mapcat node->toc-entries (outline prepared)))

(defn- maybe-link
  "A live internal link, or — when the theme says `:links? false` (the
   print-x edition; PDF/X forbids link annotations) — the bare text. The
   page citation beside it does the locating either way."
  [{:keys [link-color links?]} id text]
  (if (false? links?)
    [:fo/inline text]
    [:fo/basic-link {:internal-destination id :color link-color} text]))

(defn- toc-entry [{:keys [id text level bold?]} theme]
  ;; text-align-last="justify" pushes the page number flush right; the
  ;; leader must be free to stretch (maximum 100%) so it absorbs all the
  ;; slack. A fixed-length leader would instead leave the line short and
  ;; spill the leftover space into the title's word spacing.
  [:fo/block (cond-> {:text-align-last "justify" :space-after "5pt"}
               (pos? level) (assoc :start-indent (str (* level 16) "pt"))
               bold?        (assoc :font-weight "bold"))
   (maybe-link theme id text)
   [:fo/leader {:leader-pattern         "dots"
                :leader-length.minimum  "12pt"
                :leader-length.optimum  "12pt"
                :leader-length.maximum  "100%"}]
   [:fo/page-number-citation {:ref-id id}]])

(defn- title-page [title subtitle author head-family muted-color]
  [:fo/block {:text-align "center" :space-before "108pt"
              :space-before.conditionality "retain"}
   [:fo/block {:font-family head-family :font-size "36pt" :font-weight "bold"
               :space-after "12pt"} title]
   (when subtitle
     [:fo/block {:font-family head-family :font-size "16pt" :font-style "italic"
                 :color muted-color :space-after "18pt"} subtitle])
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

(defn- region-block
  "The block inside a running region. A header (`:before`) takes the
   theme's running-head style — letterspaced capitals by default — over
   the base furniture; the footer (the folio) stays plain."
  [slot parity inline {:keys [muted-color rule-color running-head]}]
  (let [align (if (= slot :after)
                "center"
                (case parity :recto "right" :verso "left" "center"))]
    (into [:fo/block (cond-> {:text-align align :font-size "9pt" :color muted-color}
                       (= slot :before)
                       (-> (merge running-head)
                           (assoc :border-bottom (str "0.25pt solid " rule-color)
                                  :padding-bottom "3pt" :space-before "4pt")))]
          [inline])))

(defn- licensee-block
  "The per-recipient footer notice (\"Licensed to …\"): a small, muted,
   centered line beneath the page number. Present only when the build
   supplies a `licensee`, so it personalizes a PDF without altering the
   manuscript."
  [licensee muted-color]
  [:fo/block {:text-align "center" :font-size "7.5pt" :color muted-color
              :space-before "2pt"}
   (str "Licensed to " licensee)])

(defn- static-contents
  "The `fo:static-content` for every running region the theme declares,
   choosing each region's content from the running-heads config. With
   `headers?` false (front matter, part dividers) only footers are emitted.
   When a `licensee` is set, each footer also carries the licensee notice."
  [{:keys [theme running-heads book-title headers? licensee]}]
  (let [{:keys [running-regions muted-color]} theme]
    (keep (fn [{:keys [slot name parity]}]
            (let [cfg-slot (when (or headers? (= slot :after))
                             (get-in running-heads [(parity-key parity) slot]))
                  inline   (slot->inline cfg-slot book-title)
                  blocks   (cond-> []
                             inline
                             (conj (region-block slot parity inline theme))
                             (and (= slot :after) licensee)
                             (conj (licensee-block licensee muted-color)))]
              (when (seq blocks)
                (into [:fo/static-content {:flow-name name}] blocks))))
          running-regions)))

(defn- footnote-separator
  "The rule between a page's text and its footnotes: a short thin lead in
   the theme's rule color. FOP shows it only on pages that carry a note."
  [rule-color]
  [:fo/static-content {:flow-name "xsl-footnote-separator"}
   [:fo/block {:space-after "4pt"}
    [:fo/leader {:leader-pattern "rule" :leader-length "25%"
                 :rule-thickness "0.5pt" :color rule-color}]]])

(defn- page-sequence
  "Build a `fo:page-sequence`: its page attrs, the running static content
   (headers + footers, or footers only), optional extra static content
   (the footnote separator), and the body flow."
  ([page-attrs ctx headers? body-style flow-children]
   (page-sequence page-attrs ctx headers? body-style flow-children nil))
  ([page-attrs ctx headers? body-style flow-children extra-statics]
   (into [:fo/page-sequence (merge {:master-reference (:master-ref ctx)} page-attrs)]
         (concat
           (static-contents (assoc ctx :headers? headers?))
           extra-statics
           [(into [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)]
                  flow-children)]))))

(defn- toc-furniture [title subtitle author prepared ctx]
  (let [{:keys [style rule-color muted-color]} (:theme ctx)
        body-style  (:body style)
        head-family (get-in style [:h1 :font-family])]
    (page-sequence
      {:format "i"} ctx false body-style
      (concat
        [(title-page title subtitle author head-family muted-color)]
        [[:fo/block {:font-family head-family :font-size "18pt"
                     :font-weight "bold" :break-before "page"
                     :border-bottom (str "0.5pt solid " rule-color)
                     :padding-bottom "4pt" :space-after "12pt"} "Contents"]]
        (map #(toc-entry % (:theme ctx)) (toc-entries prepared))))))

(defn- chapter-heading [{:keys [id title label]} {:keys [style rule-color muted-color
                                                         chapter-drop]}]
  ;; The running-head marker carries the bare title; the visible heading
  ;; shows the numbered label (e.g. "Chapter 1") above it when present.
  ;; The themed chapter drop sets the heading below the trim — the
  ;; classical opening-page cue.
  (into [:fo/block (merge (get style :h1)
                          {:id            (name id)
                           :break-before  "page"
                           :space-before  (or chapter-drop "36pt")
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
  (let [{:keys [style rule-color]} (:theme ctx)
        body-style (:body style)]
    (page-sequence page-attrs ctx true body-style
                   (cons (chapter-heading parsed (:theme ctx)) body)
                   [(footnote-separator rule-color)])))

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

(defn- collation-key
  "Case- and accent-insensitive sort key for reader-facing apparatus.
   Locale-independent (reproducible output); the original string breaks
   ties so terms that fold alike still order deterministically."
  [^String s]
  [(-> (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD)
       (str/replace #"\p{M}" "")
       (.toLowerCase java.util.Locale/ROOT))
   s])

(defn- bibliography-blocks
  "A sorted, hanging-indent bibliography; each entry's `:id` is `ref-<key>`,
   the citation target."
  [references]
  (for [[key entry] (sort-by (fn [[k e]] [(collation-key (or (:author e) (name k)))
                                          (str (:year e))])
                             references)]
    [:fo/block {:id (str "ref-" (name key)) :space-after "6pt"
                :start-indent "12pt" :text-indent "-12pt"}
     (bibliography-entry-text entry)]))

(defn- index-blocks
  "An alphabetical index; each term lists page citations to its marks."
  [index]
  (for [[term ids] (sort-by (comp collation-key key) index)]
    (into [:fo/block {:text-align-last "justify" :space-after "2pt"} term
           [:fo/leader {:leader-pattern "dots" :leader-length.minimum "12pt"
                        :leader-length.optimum "12pt" :leader-length.maximum "100%"}]]
          (interpose ", " (map (fn [id] [:fo/page-number-citation {:ref-id id}]) ids)))))

(defn- float-list-blocks
  "A list of figures/tables/listings: every numbered float of `kind`, in
   document order, linked to its anchor with a dotted leader and resolved
   page number (the TOC-entry pattern)."
  [floats want-kind theme]
  (for [{:keys [kind id label title]} floats
        :when (= kind want-kind)]
    (let [text (if title (str label ". " title) label)]
      [:fo/block {:text-align-last "justify" :space-after "5pt"}
       (maybe-link theme id text)
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
      (let [role  (:role section)
            theme (:theme ctx)]
        {:id    role
         :title (or (:title section) (structure/role-title role))
         :body  (case role
                  :bibliography     (vec (bibliography-blocks (:references ctx)))
                  :index            (vec (index-blocks (:index ctx)))
                  :list-of-figures  (vec (float-list-blocks (:floats ctx) :figure theme))
                  :list-of-tables   (vec (float-list-blocks (:floats ctx) :table theme))
                  :list-of-listings (vec (float-list-blocks (:floats ctx) :listing theme))
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
  "Walk the book outline in document order, emitting a page-sequence for
   each node: roman front matter, part dividers and chapters/appendices
   (arabic, the first resetting the page count), and back matter."
  [prepared ctx]
  (let [recto? (:recto? ctx)]
    (loop [ns (outline-seq prepared), seen-body? false, acc []]
      (if (empty? ns)
        acc
        (let [{:keys [kind section parsed]} (first ns)]
          (cond
            (and (= kind :matter) (= :front (:matter section)))
            (recur (rest ns) seen-body?
                   (conj acc (body-sequence (matter-parsed section ctx) ctx {:format "i"})))

            (and (= kind :matter) (= :back (:matter section)))
            (recur (rest ns) seen-body?
                   (conj acc (body-sequence (matter-parsed section ctx) ctx {})))

            (= kind :part)
            (recur (rest ns) true
                   (conj acc (part-sequence section ctx (body-page-attrs (not seen-body?) recto?))))

            :else
            (recur (rest ns) true
                   (conj acc (body-sequence parsed ctx
                                            (body-page-attrs (not seen-body?) recto?))))))))))
