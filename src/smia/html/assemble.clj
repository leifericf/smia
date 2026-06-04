(ns smia.html.assemble
  "Pure core: assemble a numbered manuscript into a set of HTML pages.

   The HTML sibling of `book.assemble`: where the FO assembler emits page
   masters and page-sequences, this emits one HTML page per section plus
   a home page (title and table of contents), and renders the furniture
   paged output gets for free differently:

   - cross-references become real links (`smia.html.links` builds the
     `{id → file}` table from the assembled pages, so an `:xref` resolves
     to `\"file#anchor\"`);
   - footnotes have no page foot to sit on, so each chapter's notes are
     numbered, collected into an end-of-chapter block, and linked through
     `fnref-N`/`fn-N` noteref/backlink pairs;
   - generated back matter (bibliography, index, float lists) emits links
     where FO emits page-number citations.

   Page chrome (the `<html>` wrapper, navigation) is pluggable via
   `:chrome`, so the site and EPUB editions differ only in chrome,
   serialize mode, and packaging. Returns
   `{:pages [{:file :slug :kind :title :label :hiccup} …]
     :contents [{:level :text :href|:id} …]
     :resources [{:src} …]}`. No IO."
  (:require
   [smia.book.structure :as structure]
   [smia.error :as error]
   [smia.fo.hiccup :as hiccup]
   [smia.html.expand :as html-expand]
   [smia.html.links :as links]
   [clojure.string :as str]))

(declare default-chrome section-items page-items contents-entries
         links-table home-page build-page resources downloads-spec)

;; --- assembly ----------------------------------------------------------------

(defn assemble
  "Assemble a numbered `book` (the manuscript value out of
   `book.number/assign`) into the HTML page set. Options:
   `:chrome`     — `{:page-wrap (fn [ctx title main] page-hiccup)
                     :nav (fn [ctx] nav-hiccup-or-nil)}`, merged over the
                   defaults; `ctx` carries `:book-title :author :page
                   :prev :next :contents :resolve :home-file :nav-hiccup`.
   `:extension`  — page file extension (default \"html\"; EPUB content
                   documents pass \"xhtml\").
   `:highlight?` — enable syntax-highlight token spans.
   `:downloads`  — `{:base :assets}`; when present, synthesize a
                   site-only `downloads.html` page leading the section
                   pages (only the site edition passes this through)."
  ([book] (assemble book {}))
  ([book opts]
   (let [chrome    (merge default-chrome (:chrome opts))
         extension (or (:extension opts) "html")
         home-file (str "index." extension)
         dl-spec   (when-let [dl (:downloads opts)]
                     (downloads-spec dl extension))
         items     (section-items book extension)
         specs     (cond->> (page-items items)
                     dl-spec (cons dl-spec))
         _         (let [dupes (->> (cons home-file (map :file specs))
                                    frequencies
                                    (keep (fn [[f n]] (when (< 1 n) f))))]
                     (when (seq dupes)
                       (throw (error/ex :smia.html.assemble/duplicate-page
                                        (str "Two sections assemble to the same "
                                             "page: " (str/join ", " dupes))
                                        {:files (vec dupes)}))))
         contents  (cond->> (contents-entries items)
                     dl-spec (cons {:kind  :downloads
                                    :level 0
                                    :href  (:file dl-spec)
                                    :text  "Downloads"}))
         resolver  (links/resolver (links-table items specs home-file))
         base-ctx  {:book-title (:title book)
                    :author     (:author book)
                    :contents   contents
                    :home-file  home-file
                    :highlight? (boolean (:highlight? opts))}]
     {:pages     (into [(home-page book contents chrome base-ctx resolver)]
                       (map-indexed
                         (fn [i spec]
                           (build-page spec book resolver chrome base-ctx
                                       (get specs (dec i)) (get specs (inc i))))
                         specs))
      :contents  contents
      :resources (resources book)})))

;; --- section walking ----------------------------------------------------------

(defn- parse-chapter
  "Destructure a `[:chapter {…} …]` form into `{:id :title :body :number
   :label}` (the numbering pass stamped `:number`/`:label` when the
   section numbers)."
  [form]
  (let [[_ attrs & body] form
        attrs (or attrs {})]
    {:id     (name (:id attrs))
     :title  (:title attrs)
     :body   (vec body)
     :number (:number attrs)
     :label  (:label attrs)}))

(defn- chapter-slug [kind {:keys [number id]}]
  (case kind
    :chapter  (cond
                (and number (re-matches #"\d+" number))
                (format "chapter-%02d" (Long/parseLong number))
                number (str "chapter-" (str/lower-case number))
                :else  id)
    :appendix (if number (str "appendix-" (str/lower-case number)) id)))

(defn- matter-slug
  "A matter page's slug. The `:index` role would collide with the home
   page's `index.html`, so the book's index lives at `book-index.html`."
  [base]
  (if (= base "index") "book-index" base))

(defn- page-spec
  "The page a non-part section assembles to: its slug, display meta,
   anchor id, and either the authored `:body` or a `:generate-role` for
   the roleful back matter the assembler writes itself."
  [section book extension]
  (-> (case (:kind section)
        (:chapter :appendix)
        (let [parsed (parse-chapter (:content section))]
          {:kind   (:kind section)
           :part   (:part section)
           :slug   (chapter-slug (:kind section) parsed)
           :title  (:title parsed)
           :label  (:label parsed)
           :number (:number parsed)
           :id     (:id parsed)
           :body   (:body parsed)})

        :matter
        (if-let [content (:content section)]
          (let [parsed (parse-chapter content)]
            {:kind  :matter
             :slug  (matter-slug (:id parsed))
             :title (:title parsed)
             :id    (:id parsed)
             :body  (:body parsed)})
          (let [role (:role section)]
            {:kind          :matter
             :slug          (matter-slug (name role))
             :title         (or (:title section) (structure/role-title role))
             :id            (name role)
             :generate-role role
             :extra-ids     (when (= :bibliography role)
                              (mapv #(str "ref-" (name %))
                                    (keys (:references book))))})))
      (as-> spec (assoc spec :file (str (:slug spec) "." extension)))))

(defn- downloads-spec
  "The synthesized site-only Downloads page: the asset flagged
   `:default` (else the first) rendered as a prominent primary link,
   then the remaining assets as a list, each with its `:note`. Built
   from the `:html/*` hatch so the link classes and absolute release
   hrefs pass through `html.expand` untouched (the `:a` sugar would drop
   the class)."
  [{:keys [base assets]} extension]
  (let [href    (fn [a] (str base "/" (:file a)))
        default (or (first (filter :default assets)) (first assets))
        others  (remove #(identical? % default) assets)
        link    (fn [a klass]
                  [:html/a (cond-> {:href (href a)} klass (assoc :class klass))
                   (:label a)])
        items   (concat
                  [[:html/p {:class "lead"} (link default "default")]]
                  (when (:note default)
                    [[:html/p {:class "note"} (:note default)]])
                  (when (seq others)
                    [(into [:html/ul {}]
                           (map (fn [a]
                                  (into [:html/li {} (link a nil)]
                                        (when (:note a) [(str " — " (:note a))])))
                                others))]))]
    {:file  (str "downloads." extension)
     :slug  "downloads"
     :kind  :downloads
     :id    "downloads"
     :title "Downloads"
     :body  [(into [:html/div {:class "downloads"}] items)]}))

(defn- section-items
  "The ordered walk items: `{:type :part :section s}` for part dividers
   (no page of their own) and `{:type :page :spec …}` for everything
   else."
  [book extension]
  (mapv (fn [s]
          (if (= :part (:kind s))
            {:type :part :section s}
            {:type :page :spec (page-spec s book extension)}))
        (:sections book)))

(defn- page-items [items]
  (vec (keep :spec items)))

;; --- table of contents -----------------------------------------------------------

(defn- heading-text [node]
  (apply str (filter string? (tree-seq vector? seq node))))

(defn- numbered-text [number title]
  (if number (str number "  " title) title))

(defn- section-entries
  "Contents entries for a chapter's navigable `:h2` headings (those with
   an `:id`), one level beneath the chapter's own entry."
  [spec level]
  (keep (fn [node]
          (when (and (vector? node) (= :h2 (first node))
                     (map? (second node)) (:id (second node)))
            {:level level
             :href  (str (:file spec) "#" (name (:id (second node))))
             :text  (heading-text node)}))
        (:body spec)))

(defn- contents-entries
  "Flatten the walk items into ordered contents entries
   `{:level :text :href}` (a part entry carries an `:id` instead — its
   anchor lives on the home page itself)."
  [items]
  (vec (mapcat
         (fn [{:keys [type section spec]}]
           (if (= :part type)
             [{:kind  :part
               :level 0
               :id    (str "part-" (:index section))
               :text  (numbered-text (:label section) (:title section))}]
             (let [level (if (and (= :chapter (:kind spec)) (:part spec)) 1 0)]
               (cons {:kind  (:kind spec)
                      :level level
                      :href  (:file spec)
                      :text  (numbered-text (:number spec) (:title spec))}
                     (section-entries spec (inc level))))))
         items)))

;; --- link resolution ---------------------------------------------------------------

(defn- links-table
  "The `{anchor-id → file}` table over every assembled page: part anchors
   live on the home page; each section page contributes its authored
   anchors, its own id, and any generated anchors (`ref-*`)."
  [items specs home-file]
  (links/table
    (cons {:file home-file
           :ids  (keep #(when (= :part (:type %))
                          (str "part-" (:index (:section %))))
                       items)}
          (map (fn [spec]
                 {:file    (:file spec)
                  :content (when (:body spec) (into [:div {}] (:body spec)))
                  :ids     (cons (:id spec) (:extra-ids spec))})
               specs))))

;; --- footnote collection -------------------------------------------------------------

(defn- walk-footnotes
  "Walk `node` left-to-right, replacing each `[:footnote …]` with a
   numbered, childless `[:footnote {:n N}]` (the expander renders the
   noteref) and collecting `{:n :children}` into `acc`. Returns
   `[acc' node']`."
  [acc node]
  (cond
    (and (vector? node) (= :footnote (first node)))
    (let [[_ attrs kids] (hiccup/parse-node node)
          n (inc (count acc))]
      [(conj acc {:n n :children (vec kids)})
       [:footnote (assoc (or attrs {}) :n n)]])

    (vector? node)
    (reduce (fn [[acc out] x]
              (let [[acc' x'] (walk-footnotes acc x)]
                [acc' (conj out x')]))
            [acc []] node)

    (seq? node)
    (let [[acc' out] (reduce (fn [[acc out] x]
                               (let [[acc' x'] (walk-footnotes acc x)]
                                 [acc' (conj out x')]))
                             [acc []] node)]
      [acc' (seq out)])

    :else [acc node]))

(defn- collect-footnotes
  "Number and extract a chapter `body`'s footnotes. Returns
   `[body' notes]` where notes is `[{:n :children} …]`."
  [body]
  (let [[notes body'] (walk-footnotes [] (vec body))]
    [body' notes]))

(defn- footnotes-block
  "The end-of-chapter footnote block: each note body under its `fn-N`
   anchor with a backlink to its in-text `fnref-N` noteref."
  [notes ctx]
  [:section {:class "footnotes" :role "doc-endnotes"}
   (into [:ol {}]
         (map (fn [{:keys [n children]}]
                (-> (into [:li {:id (str "fn-" n) :role "doc-footnote"}]
                          (map #(html-expand/expand % ctx) children))
                    (conj " "
                          [:a {:class "footnote-backlink" :role "doc-backlink"
                               :href (str "#fnref-" n)} "↩"])))
              notes))])

;; --- generated back matter --------------------------------------------------------------

(defn- bibliography-entry-text [{:keys [author title year publisher]}]
  (->> [(when author (str author "."))
        (when title (str title "."))
        (when publisher (str publisher ","))
        (when year (str year "."))]
       (remove nil?)
       (str/join " ")))

(defn- bibliography-body
  "A sorted bibliography; each entry's `ref-<key>` id is the citation
   link target."
  [references]
  (vec (for [[key entry] (sort-by (fn [[k e]] [(or (:author e) (name k))
                                               (str (:year e))])
                                  references)]
         [:p {:id (str "ref-" (name key)) :class "bibliography-entry"}
          (bibliography-entry-text entry)])))

(defn- index-body
  "An alphabetical index; each term links its occurrences in order (the
   HTML rendering of FO's page-number citations)."
  [index resolve]
  (vec (for [[term ids] (sort-by key index)]
         (into [:p {:class "index-entry"} term " "]
               (interpose ", "
                          (map-indexed
                            (fn [i id] [:a {:href (resolve id)} (str (inc i))])
                            ids))))))

(defn- float-list-body
  "A list of figures/tables/listings: every numbered float of `kind`, in
   document order, linked to its anchor."
  [floats want-kind resolve]
  [(into [:ul {:class "float-list"}]
         (for [{:keys [kind id label title]} floats
               :when (= kind want-kind)]
           [:li {} [:a {:href (resolve id)}
                    (if title (str label ". " title) label)]]))])

(defn- generated-body [role book resolve]
  (case role
    :bibliography     (bibliography-body (:references book))
    :index            (index-body (:index book) resolve)
    :list-of-figures  (float-list-body (:floats book) :figure resolve)
    :list-of-tables   (float-list-body (:floats book) :table resolve)
    :list-of-listings (float-list-body (:floats book) :listing resolve)
    []))

;; --- pages -----------------------------------------------------------------------------

(defn- page-header
  "The heading furniture atop a section page: the numbered label (when
   the section numbers) above the anchored title."
  [{:keys [id title label]}]
  (into [:header {:class "chapter-header"}]
        (concat
          (when label [[:p {:class "chapter-label"} label]])
          [[:h1 {:id id} title]])))

(defn- book-header [{:keys [title author]}]
  (into [:header {:class "book-header"} [:h1 {} title]]
        (when author [[:p {:class "book-author"} author]])))

(defn- toc-nav [contents]
  [:nav {:class "toc" :aria-label "Table of contents"}
   [:h2 {} "Contents"]
   (into [:ol {:class "toc-list"}]
         (map (fn [{:keys [level text href id]}]
                [:li (cond-> {:class (str "toc-level-" level)}
                       id (assoc :id id))
                 (if href [:a {:href href} text] text)])
              contents))])

(defn- default-nav [ctx]
  (when-not (= :home (:kind (:page ctx)))
    (into [:nav {:class "page-nav"}]
          (concat
            [[:a {:href (:home-file ctx)} "Contents"]]
            (when-let [p (:prev ctx)]
              [[:a {:rel "prev" :href (:file p)} (:title p)]])
            (when-let [n (:next ctx)]
              [[:a {:rel "next" :href (:file n)} (:title n)]])))))

(defn- default-page-wrap [ctx title main]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title {} (if (= title (:book-title ctx))
                 title
                 (str title " — " (:book-title ctx)))]
    [:link {:rel "stylesheet" :href "styles.css"}]]
   (into [:body {}]
         (concat
           (when-let [nav (:nav-hiccup ctx)] [nav])
           [(into [:main {}] main)]
           (when-let [nav (:nav-hiccup ctx)] [nav])))])

(def default-chrome
  "Minimal standalone chrome: a stylesheet-linked page with contents and
   prev/next navigation. The site and EPUB editions override this."
  {:page-wrap default-page-wrap
   :nav       default-nav})

(defn- wrap-page
  "Run a page's `main` content through the chrome."
  [chrome ctx title main]
  (let [ctx (assoc ctx :nav-hiccup ((:nav chrome) ctx))]
    ((:page-wrap chrome) ctx title main)))

(defn- home-page [book contents chrome base-ctx resolver]
  (let [home-file (:home-file base-ctx)
        spec {:file home-file :slug "index" :kind :home
              :title (:title book)}
        ctx  (assoc base-ctx
                    :page spec
                    :resolve #(resolver % home-file))]
    (assoc spec :hiccup
           (wrap-page chrome ctx (:title book)
                      [(book-header book) (toc-nav contents)]))))

(defn- build-page
  "Assemble one section page: collect and number its footnotes, expand
   the authored body (or generate the roleful back matter), and wrap it
   in the chrome."
  [{:keys [file kind title label body generate-role] :as spec}
   book resolver chrome base-ctx prev next]
  (let [resolve      #(resolver % file)
        expand-ctx   {:resolve resolve :highlight? (:highlight? base-ctx)}
        [body notes] (when body (collect-footnotes body))
        main         (if generate-role
                       (into [(page-header spec)]
                             (generated-body generate-role book resolve))
                       (-> [(page-header spec)]
                           (into (map #(html-expand/expand % expand-ctx) body))
                           (cond-> (seq notes)
                             (conj (footnotes-block notes expand-ctx)))))
        ctx          (assoc base-ctx :page spec :prev prev :next next
                            :resolve resolve)]
    {:file file :slug (:slug spec) :kind kind :title title :label label
     :hiccup (wrap-page chrome ctx title main)}))

;; --- resources ------------------------------------------------------------------------------

(defn- remote-src?
  "True for an image source with a URI scheme (`https:`, `data:`, …) —
   the page links it as-is and the packaging shell copies nothing."
  [src]
  (boolean (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" src)))

(defn- unsafe-src?
  "True for a local source that would escape the output directory: an
   absolute path, or any `..` segment. Such a src is used verbatim as a
   filesystem path (site) and a zip entry name (epub), so an unchecked
   `..` writes outside the build — a path-traversal/zip-slip footgun."
  [src]
  (or (str/starts-with? src "/")
      (some #{".."} (str/split src #"[/\\]"))))

(defn- resources
  "Every local image the authored bodies reference, for the packaging
   shell to copy alongside the pages. Remote sources are left to the
   pages; an unsafe (parent-escaping or absolute) source is a structured
   error before anything is written."
  [book]
  (->> (:sections book)
       (keep :content)
       (mapcat #(tree-seq vector? seq %))
       (keep #(when (and (vector? %) (= :img (first %)) (map? (second %)))
                (:src (second %))))
       (remove remote-src?)
       (map (fn [src]
              (when (unsafe-src? src)
                (throw (error/ex :smia.html.assemble/unsafe-resource-path
                                 (str "Image source " (pr-str src) " escapes the "
                                      "output directory. Image paths must be "
                                      "relative and stay within the book.")
                                 {:src src})))
              src))
       distinct
       sort
       (mapv (fn [src] {:src src}))))
