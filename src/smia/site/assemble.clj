(ns smia.site.assemble
  "Pure core: assemble a numbered manuscript into a static site.

   The thinnest possible layer over the shared HTML core: pages come from
   `html.assemble`, the stylesheet from `theme.css`, and this namespace
   serializes both into one page map `{path → content-string}` (the
   Stasis convention, without the dependency). On top of that sit the
   site-only affordances: a Downloads page, redirect stub pages for URLs
   that moved, and — when the book declares its public URL — a sitemap
   and a robots.txt. The shell that writes the map to disk is
   `smia.site.emit`. No IO."
  (:require
   [smia.error :as error]
   [smia.html.assemble :as html-assemble]
   [smia.html.links :as links]
   [smia.html.serialize :as html-serialize]
   [smia.site.layout :as layout]
   [smia.site.search-index :as search-index]
   [smia.theme.css :as css]
   [clojure.string :as str]))

;; --- redirects ----------------------------------------------------------------

(defn- stub-file
  "The file a redirect stub is written to: a directory path gets its
   `index.html`, a `.html` path is used as-is, anything else is treated
   as a directory."
  [path]
  (cond
    (str/ends-with? path "/")     (str path "index.html")
    (str/ends-with? path ".html") path
    :else                         (str path "/index.html")))

(defn- stub-url
  "The url the stub page lives at, for relativizing the target href."
  [path]
  (cond
    (str/ends-with? path "/")     path
    (str/ends-with? path ".html") path
    :else                         (str path "/")))

(defn- redirect-stub
  "A minimal page that sends readers (and crawlers) on: an instant meta
   refresh, a canonical link, and a plain link for anyone the refresh
   does not move."
  [old-path target-url site-url]
  (let [href      (links/relativize (stub-url old-path) target-url)
        canonical (if site-url (str site-url target-url) href)]
    (html-serialize/serialize
      [:html {}
       [:head {}
        [:meta {:charset "utf-8"}]
        [:meta {:http-equiv "refresh" :content (str "0; url=" href)}]
        [:link {:rel "canonical" :href canonical}]
        [:title {} "Moved"]]
       [:body {}
        [:p {} "This page has moved to " [:a {:href href} canonical] "."]]]
      {:doctype? true})))

(defn- redirect-stubs
  "Synthesize `{stub-file → content}` for `redirects`
   (`{old-path → target-id}`). A target that is a page lands on the page;
   any other anchor lands on its page plus the `#fragment`. A target
   outside the link `table` or a stub that would overwrite a real page is
   a hard error."
  [redirects table page-ids page-map site-url]
  (into {}
        (map (fn [[old-path target]]
               (let [id         (name target)
                     target-url (some-> (get table id)
                                        (cond-> (not (contains? page-ids id))
                                          (str "#" id)))
                     file       (stub-file old-path)]
                 (when-not target-url
                   (throw (error/ex :smia.site.redirects/unknown-target
                                    (str "Redirect " (pr-str old-path) " points at "
                                         (pr-str target) ", which no page declares.")
                                    {:redirect old-path :target target})))
                 (when (contains? page-map file)
                   (throw (error/ex :smia.site.redirects/redirect-overwrites-page
                                    (str "Redirect " (pr-str old-path) " would "
                                         "overwrite the page " file ".")
                                    {:redirect old-path :file file})))
                 [file (redirect-stub old-path target-url site-url)])))
        (sort-by key redirects)))

;; --- sitemap and robots ---------------------------------------------------------

(defn- escape-xml [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- page-url
  "The clean url a page file serves at: a nested page's directory, a flat
   file as itself."
  [file]
  (if (str/ends-with? file "index.html")
    (subs file 0 (- (count file) (count "index.html")))
    file))

(defn- sitemap
  "A deterministic sitemap over the canonical page `files`: absolute
   locations under `site-url`, sorted."
  [site-url files]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n"
       (apply str (map #(str "<url><loc>" (escape-xml (str site-url %)) "</loc></url>\n")
                       (sort (map page-url files))))
       "</urlset>\n"))

(defn- robots [site-url]
  (str "User-agent: *\n"
       "Disallow:\n"
       "\n"
       "Sitemap: " site-url "sitemap.xml\n"))

;; --- assembly ----------------------------------------------------------------

(defn assemble
  "Assemble a numbered `book` and theme `tokens` into
   `{:pages {path → content-string} :resources [{:src} …]}`. The page map
   holds every HTML page (doctyped HTML5) plus `styles.css`; `:resources`
   names the image files the pages reference, for the emit shell to copy.

   `opts` carries the site-only affordances out of `book.edn`:
   `:downloads` (`:book/downloads`) adds a Downloads page; `:redirects`
   (`:book/redirects`, `{old-path → target-id}`) synthesizes stub pages
   that send moved URLs on to their targets; `:site-url`
   (`:book/site-url`) makes redirect canonicals absolute and adds a
   sorted `sitemap.xml` (canonical pages only, stubs excluded) and a
   `robots.txt` pointing at it. Without `:site-url` neither file is
   written — a site addressed only relatively cannot name itself.

   The theme's `:site {:search true}` token turns on the search island:
   the page map gains a deterministic `search-index.json` and a static
   `search/` fallback page, the chrome gains the form and script tag,
   and `:bundled` names the shipped `search.js` for the emit shell to
   copy from the classpath. The default stays zero JavaScript."
  ([book tokens] (assemble book tokens {}))
  ([book tokens {:keys [downloads redirects site-url edit-url]}]
   (let [search?     (boolean (get-in tokens [:site :search]))
         mermaid     (get-in tokens [:site :mermaid])
         mermaid-src (when (map? mermaid) (:src mermaid))
         dark        (get-in tokens [:site :dark])
         dark?       (boolean dark)
         toggle?     (boolean (and (map? dark) (:toggle dark)))
         {:keys [pages resources] :as assembled}
         (html-assemble/assemble
           book (cond-> {:highlight? (get-in tokens [:type :highlight] false)
                         :chrome     (layout/chrome-for tokens)
                         :location   html-assemble/nested-location}
                  search?   (assoc :search true)
                  mermaid   (assoc :mermaid true :mermaid-src mermaid-src)
                  toggle?   (assoc :dark-toggle true)
                  edit-url  (assoc :edit-url edit-url)
                  downloads (assoc :downloads downloads)))
         site-url (some-> site-url (str/replace #"/*$" "/"))
         page-map (into {"styles.css" (css/css tokens {:dark? dark? :toggle? toggle?})}
                        (map (fn [{:keys [file hiccup]}]
                               [file (html-serialize/serialize
                                       hiccup {:doctype? true})])
                             pages))
         stubs    (when (seq redirects)
                    (redirect-stubs redirects (:links assembled)
                                    (set (keep :id pages)) page-map site-url))
         page-map (merge page-map stubs)
         page-map (cond-> page-map
                    search?
                    (assoc "search-index.json"
                           (search-index/index-json
                             (search-index/index (:specs assembled) (:language book))))
                    site-url
                    (assoc "sitemap.xml" (sitemap site-url (map :file pages))
                           "robots.txt"  (robots site-url)))]
     {:pages     page-map
      :resources resources
      :bundled   (cond-> []
                   search? (conj {:resource "smia/site/search.js" :path "search.js"})
                   mermaid (conj {:resource "smia/site/mermaid.js" :path "mermaid.js"})
                   toggle? (conj {:resource "smia/site/theme.js" :path "theme.js"}))})))
