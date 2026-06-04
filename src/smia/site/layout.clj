(ns smia.site.layout
  "Pure core: selectable page chrome for the site edition.

   The shared HTML core (`html.assemble`) wraps every page in pluggable
   chrome — `{:page-wrap (fn [ctx title main]) :nav (fn [ctx])}`. This
   namespace is a small registry of named site layouts mapped to chrome,
   selected by the theme's `:site {:layout …}` token:

   - `:plain` (the default) reuses `html.assemble/default-chrome`: one
     reading column with a contents link and prev/next navigation. The
     site behaves exactly as before this registry existed.
   - `:sidebar` is a two-column \"docs\" layout — a sticky table-of-contents
     rail beside the reading column — built from pure HTML and CSS, with
     no JavaScript.

   Adding a layout is one registry entry; an unknown layout is a
   structured error. No IO."
  (:require
   [smia.book.dictionary :as dictionary]
   [smia.error :as error]
   [smia.html.assemble :as html-assemble]
   [clojure.string :as str]))

;; --- the :sidebar chrome -----------------------------------------------------

(defn- page-title [ctx title]
  (if (= title (:book-title ctx))
    title
    (str title " — " (:book-title ctx))))

(defn- head [ctx title]
  (into [:head
         [:meta {:charset "utf-8"}]
         [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
         [:title {} (page-title ctx title)]
         [:link {:rel "stylesheet" :href ((:href-to ctx) "styles.css")}]]
        (when-let [s (html-assemble/search-script ctx)] [s])))

(defn- current?
  "True when a contents entry's `href` (an absolute-from-root page url,
   possibly carrying a `#fragment`) points at the page now rendering —
   the page itself, or one of its in-page heading anchors."
  [href current-url]
  (boolean (and href
                (= (first (str/split href #"#")) current-url))))

(defn- toc-entry [{:keys [level text href id]} current-url href-to]
  [:li (cond-> {:class (str "toc-level-" level)}
         id (assoc :id id))
   (cond
     (nil? href)                 text
     (current? href current-url) [:a {:class "current" :href (href-to href)} text]
     :else                       [:a {:href (href-to href)} text])])

(defn- sidebar-toc
  "The navigation rail: the book title linking home, then every contents
   entry, with the entry for the current page marked `current`. The outer
   `nav` stretches to the layout's full height (it carries the rail's
   background); the inner wrapper is the sticky, scrolling part. Hrefs
   are relativized against the page being rendered."
  [ctx]
  (let [current-url (:url (:page ctx))
        href-to     (:href-to ctx)]
    [:nav {:class "book-sidebar"
           :aria-label (dictionary/localize (:language ctx) :table-of-contents "Table of contents")}
     (into [:div {:class "book-sidebar-inner"}
            [:a {:class "book-sidebar-title" :href (href-to (:home-url ctx))}
             (:book-title ctx)]]
           (concat
             (when-let [f (html-assemble/search-form ctx)] [f])
             [(into [:ol {:class "book-sidebar-list"}]
                    (map #(toc-entry % current-url href-to) (:contents ctx)))]))]))

(defn- sidebar-page-wrap [ctx title main]
  [:html
   (head ctx title)
   [:body {}
    [:div {:class "book-layout"}
     (sidebar-toc ctx)
     (into [:div {:class "book-content"}]
           (concat
             [(into [:main {}] main)]
             (when-let [e (html-assemble/edit-link ctx)]
               [[:footer {:class "page-footer"} e]])
             (when-let [nav (:nav-hiccup ctx)] [nav])))]]])

(def ^:private sidebar-chrome
  "Two-column docs chrome: the sidebar rail is the table of contents; the
   prev/next furniture (the plain chrome's `:nav`) sits beneath the
   reading column. `:home-toc? false` keeps the landing a plain title
   card — the sidebar already lists every page, so a contents list on the
   home page would only duplicate the rail."
  {:page-wrap  sidebar-page-wrap
   :nav        (:nav html-assemble/default-chrome)
   :home-toc?  false})

;; --- the registry ------------------------------------------------------------

(def ^:private layouts
  "Named site layouts mapped to page chrome. `:plain` is the default and
   reuses the shared minimal chrome unchanged."
  {:plain   html-assemble/default-chrome
   :sidebar sidebar-chrome})

(defn chrome-for
  "The page chrome for the site `tokens`, selected by
   `(get-in tokens [:site :layout])` (default `:plain`). Throws
   `:smia.site.layout/unknown-layout` for a layout outside the registry."
  [tokens]
  (let [layout (get-in tokens [:site :layout] :plain)]
    (or (get layouts layout)
        (throw (error/ex :smia.site.layout/unknown-layout
                         (str "Unknown site layout " (pr-str layout)
                              ". Known layouts: "
                              (str/join ", " (map name (sort (keys layouts))))
                              ".")
                         {:layout layout
                          :known  (vec (sort (keys layouts)))})))))
