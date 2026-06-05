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
   [smia.book.dictionary :as dictionary]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [smia.fo.hiccup :as hiccup]
   [smia.html.expand :as html-expand]
   [smia.html.links :as links]
   [smia.site.search-index :as search-index]
   [clojure.string :as str]))

(declare default-chrome section-items page-items contents-entries
         links-table home-page build-page resources downloads-spec
         search-fallback-spec numbered-text
         flat-location nested-location chapter-slug matter-slug)

;; --- assembly ----------------------------------------------------------------

(defn assemble
  "Assemble a numbered `book` (the manuscript value out of
   `book.number/assign`) into the HTML page set. Options:
   `:chrome`     — `{:page-wrap (fn [ctx title main] page-hiccup)
                     :nav (fn [ctx] nav-hiccup-or-nil)
                     :home-toc? bool}`, merged over the defaults; `ctx`
                   carries `:book-title :author :page :prev :next
                   :contents :resolve :home-file :nav-hiccup`. `:home-toc?`
                   (default true) renders the contents on the home page;
                   a chrome whose own framing carries the contents sets it
                   false to leave the landing a title card.
   `:extension`  — page file extension (default \"html\"; EPUB content
                   documents pass \"xhtml\").
   `:highlight?` — enable syntax-highlight token spans.
   `:downloads`  — `{:base :assets}`; when present, synthesize a
                   site-only `downloads.html` page leading the section
                   pages (only the site edition passes this through).
   `:search`     — when truthy, synthesize the static search fallback
                   page (trailing the section pages) and render the
                   search form and script tag in the chrome (only the
                   site edition passes this through)."
  ([book] (assemble book {}))
  ([book opts]
   (let [chrome    (merge default-chrome (:chrome opts))
         language  (:language book)
         extension (or (:extension opts) "html")
         locate    (or (:location opts) flat-location)
         home-loc  (locate {:kind :home} extension)
         dl-spec   (when-let [dl (:downloads opts)]
                     (downloads-spec dl extension locate language))
         items     (section-items book extension locate)
         specs     (vec (cond->> (page-items items)
                          dl-spec (cons dl-spec)))
         search-sp (when (:search opts)
                     (search-fallback-spec specs extension locate language))
         specs     (cond-> specs search-sp (conj search-sp))
         _         (let [dupes (->> (cons (:file home-loc) (map :file specs))
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
                                    :href  (:url dl-spec)
                                    :text  (dictionary/localize language :downloads "Downloads")}))
         contents  (vec (cond-> contents
                          search-sp (concat [{:kind  :search
                                              :level 0
                                              :href  (:url search-sp)
                                              :text  (dictionary/localize language :search "Search")}])))
         table     (links-table items specs (:url home-loc))
         resolver  (links/resolver table)
         base-ctx  {:book-title (:title book)
                    :author     (:author book)
                    :contents   contents
                    :home-loc   home-loc
                    :home-url   (:url home-loc)
                    :search?    (boolean search-sp)
                    :search-url (:url search-sp)
                    :language    language
                    :edit-url    (:edit-url opts)
                    :mermaid     (boolean (:mermaid opts))
                    :mermaid-src (:mermaid-src opts)
                    :dark-toggle (boolean (:dark-toggle opts))
                    :reader      (boolean (:reader opts))
                    :reader-theme (boolean (:reader-theme opts))
                    :keyboard    (boolean (:keyboard opts))
                    :highlight?  (boolean (:highlight? opts))}]
     {:pages     (into [(home-page book contents chrome base-ctx resolver)]
                       (map-indexed
                         (fn [i spec]
                           (build-page spec book resolver chrome base-ctx
                                       (get specs (dec i)) (get specs (inc i))))
                         specs))
      :contents  contents
      :links     table
      :specs     specs
      :resources (resources book)})))

;; --- page location strategies ------------------------------------------------

(defn- slugify
  "A URL slug from a string or keyword: lowercase, runs of non-alphanumeric
   collapsed to a single hyphen, trimmed. `:book-production` -> `\"book-production\"`."
  [x]
  (-> (name x)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn flat-location
  "The default location: one flat file per page at the site root. `spec`
   carries `:kind` and the numbered `:id`/`:number` the slug derives from.
   Returns `{:file <name> :dir \"\" :url <name>}` — `:dir` is the root and
   `:url` equals `:file`, so links stay same-directory."
  [spec extension]
  (let [slug (case (:kind spec)
               (:chapter :appendix) (chapter-slug (:kind spec) spec)
               :matter              (matter-slug (:id spec))
               :downloads           "downloads"
               :search              "search"
               :home                "index")
        file (str slug "." extension)]
    {:file file :dir "" :url file}))

(defn- section-dir
  "The directory url for a page under the nested location, ending in `/`:
   body chapters nest under their part (`part-1/quickstart/`), appendices
   under their letter (`appendix-a/error-catalog/`), matter and part-less
   chapters sit at the root (`preface/`)."
  [spec]
  (case (:kind spec)
    :chapter   (if-let [p (:part spec)]
                 (str "part-" (inc p) "/" (slugify (:id spec)) "/")
                 (str (slugify (:id spec)) "/"))
    :appendix  (str "appendix-" (str/lower-case (:number spec)) "/"
                    (slugify (:id spec)) "/")
    :matter    (str (matter-slug (:id spec)) "/")
    :downloads "downloads/"
    :search    "search/"
    :home      ""))

(defn nested-location
  "A clean, SEO-friendly location: each page is the `index.<ext>` of its
   own directory, so the served url is the extensionless directory path
   (`part-1/quickstart/`). The home page stays at the site root."
  [spec extension]
  (let [dir (section-dir spec)]
    {:file (str dir "index." extension) :dir dir :url dir}))

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
  [section book extension locate]
  (-> (case (:kind section)
        (:chapter :appendix)
        (let [parsed (parse-chapter (:content section))]
          {:kind        (:kind section)
           :part        (:part section)
           :slug        (chapter-slug (:kind section) parsed)
           :title       (:title parsed)
           :label       (:label parsed)
           :number      (:number parsed)
           :id          (:id parsed)
           :source-file (:file section)
           :body        (:body parsed)})

        :matter
        (if-let [content (:content section)]
          (let [parsed (parse-chapter content)]
            {:kind        :matter
             :slug        (matter-slug (:id parsed))
             :title       (:title parsed)
             :id          (:id parsed)
             :source-file (:file section)
             :body        (:body parsed)})
          (let [role (:role section)]
            {:kind          :matter
             :slug          (matter-slug (name role))
             :title         (or (:title section) (structure/role-title role))
             :id            (name role)
             :generate-role role
             :extra-ids     (when (= :bibliography role)
                              (mapv #(str "ref-" (name %))
                                    (keys (:references book))))})))
      (as-> spec (merge spec (locate spec extension)))))

(defn- downloads-spec
  "The synthesized site-only Downloads page: the asset flagged
   `:default` (else the first) rendered as a prominent primary link,
   then the remaining assets as a list, each with its `:note`. Built
   from the `:html/*` hatch so the link classes and absolute release
   hrefs pass through `html.expand` untouched (the `:a` sugar would drop
   the class)."
  [{:keys [base assets]} extension locate language]
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
    (merge {:slug  "downloads"
            :kind  :downloads
            :id    "downloads"
            :title (dictionary/localize language :downloads "Downloads")
            :body  [(into [:html/div {:class "downloads"}] items)]}
           (locate {:kind :downloads} extension))))

(defn- search-fallback-spec
  "The synthesized static search page: what a reader without JavaScript
   lands on when the chrome's search form submits. A static site cannot
   answer the query server-side, so the page lists the book by category
   with plain links — every page reachable, nothing required. Built from
   the `:html/*` hatch like the Downloads page."
  [specs extension locate language]
  (let [loc      (locate {:kind :search} extension)
        rel      #(links/relativize (:url loc) %)
        groups   (group-by :kind (filter :id specs))
        sections (keep
                   (fn [kind]
                     (when-let [ss (seq (get groups kind))]
                       (into [:html/section {:class "search-category"}
                              [:html/h2 {} (search-index/kind-label kind language)]]
                             [(into [:html/ul {}]
                                    (map (fn [s]
                                           [:html/li {}
                                            [:html/a {:href (rel (:url s))}
                                             (numbered-text (:number s)
                                                            (:title s))]])
                                         ss))])))
                   [:chapter :appendix :matter :downloads])]
    (merge {:slug  "search"
            :kind  :search
            :id    "search"
            :title (dictionary/localize language :search "Search")
            :body  (vec (cons [:html/p {:class "search-fallback-note"}
                               (dictionary/localize
                                 language :search-fallback-note
                                 (str "With JavaScript enabled, the search box "
                                      "suggests matches as you type. Without it, "
                                      "the book is listed here by category."))]
                              sections))}
           loc)))

(defn search-form
  "The search form the chromes render when the search island is on: a
   plain GET form targeting the static fallback page, so it works with
   no JavaScript at all. The island mounts on the `data-island` marker
   and reads the page-relative index url and site root from the data
   attributes."
  [ctx]
  (when (:search? ctx)
    (let [href-to (:href-to ctx)]
      [:form {:class          "search"
              :role           "search"
              :method         "get"
              :action         (href-to (:search-url ctx))
              :data-island    "smia-search"
              :data-index-url (href-to "search-index.json")
              :data-root      (href-to "")}
       [:input {:type "search" :name "q"
                :placeholder (dictionary/localize (:language ctx) :search-placeholder "Search…")
                :aria-label  (dictionary/localize (:language ctx) :search-aria "Search this book")
                :autocomplete "off"}]])))

(defn search-script
  "The deferred script tag for the search island, when it is on."
  [ctx]
  (when (:search? ctx)
    [:script {:defer "defer" :src ((:href-to ctx) "search.js")}]))

(defn mermaid-scripts
  "The deferred script tags for the mermaid island, when it is on. The
   optional `:mermaid-src` (a UMD mermaid build that sets `window.mermaid`)
   is loaded first; then the committed island bundle (compiled from
   `cljs/smia/site/mermaid_client.cljs`) runs it over the `<pre class=
   \"mermaid\">` blocks. With no `:mermaid-src`, the bundle expects the
   library to be present some other way; with no JavaScript, the diagram
   source shows. Returns a (possibly empty) seq of script tags."
  [ctx]
  (when (:mermaid ctx)
    (cond-> []
      (string? (:mermaid-src ctx))
      (conj [:script {:defer "defer" :src (:mermaid-src ctx)}])
      :always
      (conj [:script {:defer "defer" :src ((:href-to ctx) "mermaid.js")}]))))

(defn theme-script
  "The dark-mode toggle island's script tag, when the toggle is on. Unlike
   the search and mermaid scripts it is NOT deferred: it runs in `<head>`
   before first paint so a stored light/dark choice applies with no flash.
   With JavaScript off the script never runs and the OS setting governs."
  [ctx]
  (when (:dark-toggle ctx)
    [:script {:src ((:href-to ctx) "theme.js")}]))

(defn theme-toggle
  "The dark-mode toggle button, when the toggle is on. It ships `hidden`
   and inert; the island unhides and wires it, so a no-JavaScript reader
   never sees a dead control while the OS scheme still applies. Positioned
   out of flow by `.theme-toggle`, so its place in the markup is just tab
   order."
  [ctx]
  (when (:dark-toggle ctx)
    [:button {:type "button"
              :class "theme-toggle"
              :data-theme-toggle "data-theme-toggle"
              :hidden "hidden"
              :aria-pressed "false"
              :aria-label (dictionary/localize (:language ctx)
                                               :toggle-color-scheme "Toggle dark mode")}
     (dictionary/localize (:language ctx) :toggle-color-scheme "Toggle dark mode")]))

(defn reader-script
  "The reader-preferences island's script tag, when the controls are on.
   Like the dark-toggle script it is NOT deferred: it runs in `<head>`
   before first paint so stored width/scale/contrast/scheme choices apply
   with no flash. With JavaScript off the script never runs, the controls
   stay hidden, and the stylesheet defaults govern."
  [ctx]
  (when (:reader ctx)
    [:script {:src ((:href-to ctx) "reader.js")}]))

(defn reader-controls
  "The reader-preferences control cluster, when the controls are on. It
   ships `hidden` and inert; the island unhides and wires it, so a
   no-JavaScript reader never sees a dead control. A compact button reveals
   a small panel of width, text-size, contrast, and — when dark mode is on
   — color-scheme controls. The theme control reuses the dark toggle's
   `data-theme-toggle` marker, so the cluster supersedes the standalone
   button."
  [ctx]
  (when (:reader ctx)
    (let [lang   (:language ctx)
          loc    #(dictionary/localize lang %1 %2)
          label  (fn [term default] [:span {:class "reader-label"} (loc term default)])
          step   (fn [attr val term default glyph]
                   [:button {:type "button" attr val
                             :aria-label (loc term default)} glyph])
          pair   (fn [& bs] (into [:div {:class "reader-pair"}] bs))
          ;; an on/off control with no text label — the switch shows its state,
          ;; and the row label beside it names what it toggles.
          switch (fn [attr term default]
                   [:button (assoc {:type "button" :class "reader-switch"
                                    :aria-pressed "false"
                                    :aria-label (loc term default)}
                                   attr "")])]
      (into [:div {:class "reader-controls" :data-reader-controls "" :hidden "hidden"}
             [:button {:type "button" :class "reader-button"
                       :data-reader-toggle "" :aria-expanded "false"
                       :aria-label (loc :reader-settings "Reader settings")}
              "Aa"]]
            [(into [:div {:class "reader-panel" :data-reader-panel "" :hidden "hidden"}]
                   (concat
                     [(label :reading-width "Width")
                      (pair (step :data-reader-width "-" :narrower "Narrower" "–")
                            (step :data-reader-width "+" :wider "Wider" "+"))
                      (label :text-size "Text size")
                      (pair (step :data-reader-scale "-" :smaller "Smaller text" "A–")
                            (step :data-reader-scale "+" :larger "Larger text" "A+"))
                      (label :contrast "Contrast")
                      (switch :data-reader-contrast :high-contrast "High contrast")
                      (label :focus-mode "Focus mode")
                      (switch :data-focus-toggle :focus-mode "Focus mode")]
                     (when (:reader-theme ctx)
                       [(label :color-scheme "Theme")
                        (switch :data-theme-toggle :toggle-color-scheme "Toggle dark mode")])
                     [[:button {:type "button" :class "reader-reset" :data-reader-reset ""}
                       (loc :reset-defaults "Reset to defaults")]]))]))))

(defn keys-script
  "The keyboard-shortcuts island's deferred script tag, when the shortcuts
   are on. It only binds handlers, so it need not run before paint."
  [ctx]
  (when (:keyboard ctx)
    [:script {:defer "defer" :src ((:href-to ctx) "keys.js")}]))

(defn keyboard-help
  "The keyboard-shortcuts help dialog, when the shortcuts are on. It ships
   `hidden`; the island reveals it on `?` and dismisses it on Escape, a
   click outside, or the close button. Every shortcut it lists also has a
   visible control, so the dialog is a reference, not a requirement."
  [ctx]
  (when (:keyboard ctx)
    (let [lang (:language ctx)
          loc  #(dictionary/localize lang %1 %2)
          row  (fn [keys label] (list [:dt {} keys] [:dd {} label]))]
      [:div {:class "kbd-help" :data-kbd-help "" :hidden "hidden"
             :role "dialog" :aria-modal "true"
             :aria-label (loc :keyboard-shortcuts "Keyboard shortcuts")}
       [:div {:class "kbd-help-panel"}
        [:h2 {} (loc :keyboard-shortcuts "Keyboard shortcuts")]
        (into [:dl {}]
              (concat
                (row [:span {} [:kbd {} "←"] [:kbd {} "→"]
                      [:kbd {} "h"] [:kbd {} "l"]]
                     (loc :previous-next-page "Previous / next page"))
                (row [:kbd {} "/"] (loc :search "Search"))
                (row [:kbd {} "d"] (loc :dark-mode "Dark mode"))
                (row [:kbd {} "f"] (loc :focus-mode "Focus mode"))
                (row [:kbd {} "g"] (loc :contents "Contents"))
                (row [:kbd {} "?"] (loc :show-this-help "Show this help"))))
        [:button {:type "button" :class "kbd-help-close" :data-kbd-close ""}
         (loc :close "Close")]]])))

(defn reading-progress
  "A thin reading-progress bar pinned to the top of the viewport, when the
   reader controls are on. It ships `hidden` and empty; the island reveals
   it and drives its width from the scroll position. With no JavaScript it
   stays hidden, so a reader never sees a bar that cannot move. Decorative,
   so `aria-hidden`."
  [ctx]
  (when (:reader ctx)
    [:div {:class "reading-progress" :data-reading-progress ""
           :hidden "hidden" :aria-hidden "true"}
     [:div {:class "reading-progress-bar"}]]))

(defn breadcrumb
  "A `Part › Chapter` orientation trail atop the reading column, when the
   reader controls are on and the page sits in the contents (not the home
   page). Pure HTML built from the already-assembled `:contents`: the
   page's own entry, preceded by the nearest part divider above it. Most
   valuable in focus mode, where the sidebar is hidden, but a quiet aid in
   any layout. Nil when the page has no contents entry."
  [ctx]
  (when (and (:reader ctx) (not= :home (:kind (:page ctx))))
    (let [page-url (:url (:page ctx))
          contents (vec (:contents ctx))
          here     (first (keep-indexed
                            (fn [i e]
                              (when (and (:href e)
                                         (not= :part (:kind e))
                                         (= (first (str/split (:href e) #"#")) page-url))
                                i))
                            contents))]
      (when here
        (let [chap (nth contents here)
              part (some (fn [e] (when (= :part (:kind e)) e))
                         (reverse (subvec contents 0 here)))]
          (into [:nav {:class "breadcrumb"
                       :aria-label (dictionary/localize (:language ctx) :breadcrumb "Breadcrumb")}]
                (concat
                  (when part
                    [[:span {:class "breadcrumb-part"} (:text part)]
                     [:span {:class "breadcrumb-sep" :aria-hidden "true"} "›"]])
                  [[:span {:class "breadcrumb-page"} (:text chap)]])))))))

(defn edit-link
  "An \"Edit this page\" link, when the book set `:book/edit-url` and the
   current page has a known source file. The href is the base URL joined to
   the page's source path (e.g. a repository's blob/edit URL)."
  [ctx]
  (when-let [base (:edit-url ctx)]
    (when-let [src (:source-file (:page ctx))]
      [:a {:class "edit-page"
           :href  (str (str/replace base #"/*$" "/") src)}
       (dictionary/localize (:language ctx) :edit-this-page "Edit this page")])))

(defn edge-nav
  "Icon-only previous/next chevrons pinned to the page margins. Plain
   links (no JavaScript): each carries `rel` and a descriptive
   `aria-label` (\"Previous page: <title>\"), so the visual glyph stays
   bare while assistive tech gets the destination. Nil on the home page or
   when the page chains nowhere; the bottom `page-nav` carries the labeled,
   in-flow navigation that this supplements."
  [ctx]
  (when-not (= :home (:kind (:page ctx)))
    (let [href-to (:href-to ctx)
          lang    (:language ctx)
          prev    (:prev ctx)
          next    (:next ctx)
          link    (fn [page klass rel term default glyph]
                    [:a {:class           (str "edge-link " klass)
                         :rel             rel
                         :href            (href-to (:url page))
                         :aria-label      (str (dictionary/localize lang term default)
                                               ": " (:title page))}
                     [:span {:aria-hidden "true"} glyph]])]
      (when (or prev next)
        (into [:nav {:class      "edge-nav"
                     :aria-label (dictionary/localize lang :pagination "Pagination")}]
              (concat
                (when prev [(link prev "edge-prev" "prev" :previous-page "Previous page" "‹")])
                (when next [(link next "edge-next" "next" :next-page "Next page" "›")])))))))

(defn- section-items
  "The ordered walk items: `{:type :part :section s}` for part dividers
   (no page of their own) and `{:type :page :spec …}` for everything
   else."
  [book extension locate]
  (mapv (fn [s]
          (if (= :part (:kind s))
            {:type :part :section s}
            {:type :page :spec (page-spec s book extension locate)}))
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
             :href  (str (:url spec) "#" (name (:id (second node))))
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
                      :href  (:url spec)
                      :text  (numbered-text (:number spec) (:title spec))}
                     (section-entries spec (inc level))))))
         items)))

;; --- link resolution ---------------------------------------------------------------

(defn- links-table
  "The `{anchor-id → page-url}` table over every assembled page: part
   anchors live on the home page; each section page contributes its
   authored anchors, its own id, and any generated anchors (`ref-*`). The
   value is the page's link url (a flat file or a directory), which the
   resolver relativizes per referring page."
  [items specs home-url]
  (links/table
    (cons {:file home-url
           :ids  (keep #(when (= :part (:type %))
                          (str "part-" (:index (:section %))))
                       items)}
          (map (fn [spec]
                 {:file    (:url spec)
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

(defn- toc-nav
  "The table-of-contents nav. `href-to` relativizes each entry's
   absolute-from-root url against the page being rendered."
  [contents href-to language]
  [:nav {:class "toc" :aria-label (dictionary/localize language :table-of-contents "Table of contents")}
   [:h2 {} (dictionary/localize language :contents "Contents")]
   (into [:ol {:class "toc-list"}]
         (map (fn [{:keys [level text href id]}]
                [:li (cond-> {:class (str "toc-level-" level)}
                       id (assoc :id id))
                 (if href [:a {:href (href-to href)} text] text)])
              contents))])

(defn- default-nav [ctx]
  (when-not (= :home (:kind (:page ctx)))
    (let [href-to (:href-to ctx)]
      (into [:nav {:class "page-nav"}]
            (concat
              [[:a {:href (href-to (:home-url ctx))}
                (dictionary/localize (:language ctx) :contents "Contents")]]
              (when-let [p (:prev ctx)]
                [[:a {:rel "prev" :href (href-to (:url p))} (:title p)]])
              (when-let [n (:next ctx)]
                [[:a {:rel "next" :href (href-to (:url n))} (:title n)]]))))))

(defn- default-page-wrap [ctx title main]
  [:html
   (into [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title {} (if (= title (:book-title ctx))
                       title
                       (str title " — " (:book-title ctx)))]
          [:link {:rel "stylesheet" :href ((:href-to ctx) "styles.css")}]]
         (concat (when-let [t (theme-script ctx)] [t])
                 (when-let [r (reader-script ctx)] [r])
                 (when-let [s (search-script ctx)] [s])
                 (when-let [k (keys-script ctx)] [k])
                 (mermaid-scripts ctx)))
   (into [:body {}]
         (concat
           (when-let [p (reading-progress ctx)] [p])
           (when-let [b (theme-toggle ctx)] [b])
           (when-let [c (reader-controls ctx)] [c])
           (when-let [f (search-form ctx)] [f])
           (when-let [nav (:nav-hiccup ctx)] [nav])
           [(into [:main {}] (concat (when-let [e (edge-nav ctx)] [e])
                                     (when-let [b (breadcrumb ctx)] [b])
                                     main))]
           (when-let [e (edit-link ctx)] [[:footer {:class "page-footer"} e]])
           (when-let [nav (:nav-hiccup ctx)] [nav])
           (when-let [h (keyboard-help ctx)] [h])))])

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

(defn- home-page
  "The home page (`index`). Its main content is the book header, followed
   by the table of contents unless the chrome opts out with
   `:home-toc? false` — a layout whose own chrome already carries the
   contents (the sidebar) leaves the landing a plain title card."
  [book contents chrome base-ctx resolver]
  (let [spec    (merge {:slug "index" :kind :home :title (:title book)}
                       (:home-loc base-ctx))
        href-to #(links/relativize (:url spec) %)
        ctx     (assoc base-ctx
                       :page spec
                       :href-to href-to
                       :resolve #(resolver % (:url spec)))
        main    (cond-> [(book-header book)]
                  (get chrome :home-toc? true)
                  (conj (toc-nav contents href-to (:language base-ctx))))]
    (assoc spec :hiccup
           (wrap-page chrome ctx (:title book) main))))

(defn- build-page
  "Assemble one section page: collect and number its footnotes, expand
   the authored body (or generate the roleful back matter), and wrap it
   in the chrome."
  [{:keys [file url kind title label body generate-role] :as spec}
   book resolver chrome base-ctx prev next]
  (let [href-to      #(links/relativize url %)
        resolve      #(resolver % url)
        expand-ctx   {:resolve    resolve
                      :highlight? (:highlight? base-ctx)
                      :language   (:language base-ctx)
                      :mermaid    (:mermaid base-ctx)
                      :asset-base (href-to "")}
        [body notes] (when body (collect-footnotes body))
        main         (if generate-role
                       (into [(page-header spec)]
                             (generated-body generate-role book resolve))
                       (-> [(page-header spec)]
                           (into (map #(html-expand/expand % expand-ctx) body))
                           (cond-> (seq notes)
                             (conj (footnotes-block notes expand-ctx)))))
        ctx          (assoc base-ctx :page spec :prev prev :next next
                            :resolve resolve :href-to href-to)]
    {:file file :url url :id (:id spec) :slug (:slug spec) :kind kind
     :title title :label label
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
