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
  {:id (:role section) :title (structure/role-title (:role section)) :body []})

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

(defn- legacy-sections
  "Wrap a flat `:chapters` list as body chapter sections, so a manuscript
   that predates the typed model assembles through the same walk and yields
   byte-identical output."
  [chapters]
  (mapv (fn [c] {:kind :chapter :part nil :content c}) chapters))

(defn- book-sections [{:keys [sections chapters]}]
  (prepare-sections (or sections (legacy-sections chapters))))

(defn- body-chapters
  "Parsed body chapters (kind `:chapter`), in order — the TOC's entries."
  [prepared]
  (->> prepared (filter #(= :chapter (:kind %))) (map :chapter)))

;; --- fragments ------------------------------------------------------------

(defn- bookmark
  "A `fo:bookmark` to `id` titled `title`, with optional nested children."
  [id title children]
  (into [:fo/bookmark {:internal-destination (name id)}
         [:fo/bookmark-title title]]
        children))

(defn- bookmark-tree
  "A nested PDF bookmark tree: parts contain their chapters; every other
   section is a top-level bookmark. A flat book (no parts) yields the same
   flat list as before."
  [prepared]
  (into [:fo/bookmark-tree]
        (loop [ss prepared, acc []]
          (if (empty? ss)
            acc
            (let [s (first ss)]
              (if (= :part (:kind s))
                (let [idx (:index s)
                      [kids more] (split-with #(and (= :chapter (:kind %))
                                                    (= idx (:part %)))
                                              (rest ss))
                      child-bms (map #(bookmark (:id (:chapter %))
                                                (:title (:chapter %)) nil)
                                     kids)]
                  (recur more (conj acc (bookmark (str "part-" idx)
                                                  (:title s) child-bms))))
                (let [{:keys [id title]} (section->parsed s)]
                  (recur (rest ss) (conj acc (bookmark id title nil))))))))))

(defn- toc-entry [{:keys [id title]} link-color]
  ;; text-align-last="justify" pushes the page number flush right; the
  ;; leader must be free to stretch (maximum 100%) so it absorbs all the
  ;; slack. A fixed-length leader would instead leave the line short and
  ;; spill the leftover space into the title's word spacing.
  [:fo/block {:text-align-last "justify" :space-after "5pt"}
   [:fo/basic-link {:internal-destination (name id) :color link-color} title]
   [:fo/leader {:leader-pattern         "dots"
                :leader-length.minimum  "12pt"
                :leader-length.optimum  "12pt"
                :leader-length.maximum  "100%"}]
   [:fo/page-number-citation {:ref-id (name id)}]])

(defn- title-page [title author head-family muted-color]
  [:fo/block {:text-align "center" :space-before "108pt"
              :space-before.conditionality "retain"}
   [:fo/block {:font-family head-family :font-size "36pt" :font-weight "bold"
               :space-after "12pt"} title]
   (when author
     [:fo/block {:font-size "13pt" :color muted-color} author])])

(defn- toc-furniture [title author chapters master-ref theme]
  (let [{:keys [style link-color rule-color muted-color]} theme
        body-style  (:body style)
        head-family (get-in style [:h1 :font-family])]
    [:fo/page-sequence {:master-reference master-ref :format "i"}
     [:fo/static-content {:flow-name "xsl-region-after"}
      [:fo/block {:text-align "center" :font-size "9pt" :color muted-color}
       [:fo/page-number]]]
     (into [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)]
           (concat
             [(title-page title author head-family muted-color)]
             [[:fo/block {:font-family head-family :font-size "18pt"
                          :font-weight "bold" :break-before "page"
                          :border-bottom (str "0.5pt solid " rule-color)
                          :padding-bottom "4pt" :space-after "12pt"} "Contents"]]
             (map #(toc-entry % link-color) chapters)))]))

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
   running head, page number, the chapter heading, and the body. `page-attrs`
   carries the per-section page-numbering (roman front matter, the arabic
   reset on the first body section, recto parity)."
  [{:keys [body] :as parsed} master-ref theme page-attrs]
  (let [{:keys [style rule-color muted-color]} theme
        body-style (:body style)]
    [:fo/page-sequence (merge {:master-reference master-ref} page-attrs)
     [:fo/static-content {:flow-name "xsl-region-before"}
      [:fo/block {:text-align "center" :font-size "9pt" :color muted-color
                  :border-bottom (str "0.25pt solid " rule-color)
                  :padding-bottom "3pt" :space-before "4pt"}
       [:fo/retrieve-marker {:retrieve-class-name "chapter-title"}]]]
     [:fo/static-content {:flow-name "xsl-region-after"}
      [:fo/block {:text-align "center" :font-size "9pt" :color muted-color}
       [:fo/page-number]]]
     (into [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)]
           (cons (chapter-heading parsed style rule-color muted-color) body))]))

(defn- part-sequence
  "A part-divider page-sequence: the part title, centered and large, on its
   own page. Its `:id` (`part-N`) is the bookmark/cross-reference target."
  [section master-ref theme page-attrs]
  (let [{:keys [style muted-color]} theme
        body-style  (:body style)
        head-family (get-in style [:h1 :font-family])]
    [:fo/page-sequence (merge {:master-reference master-ref} page-attrs)
     [:fo/static-content {:flow-name "xsl-region-after"}
      [:fo/block {:text-align "center" :font-size "9pt" :color muted-color}
       [:fo/page-number]]]
     [:fo/flow (merge {:flow-name "xsl-region-body"} body-style)
      (into [:fo/block {:id (str "part-" (:index section))
                        :font-family head-family :text-align "center"
                        :space-before "144pt"
                        :space-before.conditionality "retain"}]
            (concat
              (when-let [label (:label section)]
                [[:fo/block {:font-size "16pt" :color muted-color
                             :space-after "8pt"} label]])
              [[:fo/block {:font-size "30pt" :font-weight "bold"}
                (:title section)]]))]]))

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
  [prepared master-ref theme recto?]
  (loop [ss prepared, seen-body? false, acc []]
    (if (empty? ss)
      acc
      (let [s (first ss), k (:kind s)]
        (cond
          (and (= k :matter) (= :front (:matter s)))
          (recur (rest ss) seen-body?
                 (conj acc (body-sequence (section->parsed s) master-ref theme
                                          {:format "i"})))

          (and (= k :matter) (= :back (:matter s)))
          (recur (rest ss) seen-body?
                 (conj acc (body-sequence (section->parsed s) master-ref theme {})))

          (= k :part)
          (recur (rest ss) true
                 (conj acc (part-sequence s master-ref theme
                                          (body-page-attrs (not seen-body?) recto?))))

          :else
          (recur (rest ss) true
                 (conj acc (body-sequence (:chapter s) master-ref theme
                                          (body-page-attrs (not seen-body?) recto?)))))))))

;; --- assembly -------------------------------------------------------------

(defn assemble
  "Assemble a typed `manuscript` and a compiled `theme` (from
   `clj-book.book.theme/compile-theme`) into one `:fo/root` tree.

   The manuscript is either the typed value from `book.load/load-manuscript`
   (`{:title :author :numbering :sections …}`) or the legacy flat shape
   (`{:title :author :chapters …}`), which is treated as a body of chapters
   with no parts and assembles to byte-identical output. Chapter bodies
   remain authored sugar for the later expansion pass."
  [book theme]
  (let [{:keys [title author]} book
        {:keys [style master-reference masters profile]} theme
        numbering  (or (:numbering book) structure/default-numbering)
        prepared   (book-sections book)
        all-parsed (vec (keep :chapter prepared))
        _          (resolve-xrefs! all-parsed)
        body-chs   (body-chapters prepared)
        recto?     (and (= profile :print)
                        (= :recto (:start-chapters-on numbering)))
        body-style (get style :body)]
    (into [:fo/root {:font-family (:font-family body-style)
                     :font-size   (:font-size body-style)
                     :line-height (:line-height body-style)}]
          (concat
            [(into [:fo/layout-master-set] masters)]
            [(bookmark-tree prepared)]
            [(toc-furniture title author body-chs master-reference theme)]
            (section-sequences prepared master-reference theme recto?)))))
