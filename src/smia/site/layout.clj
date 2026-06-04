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
   [smia.error :as error]
   [smia.html.assemble :as html-assemble]
   [clojure.string :as str]))

;; --- the :sidebar chrome -----------------------------------------------------

(defn- page-title [ctx title]
  (if (= title (:book-title ctx))
    title
    (str title " — " (:book-title ctx))))

(defn- head [ctx title]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title {} (page-title ctx title)]
   [:link {:rel "stylesheet" :href "styles.css"}]])

(defn- current?
  "True when a contents entry's `href` points at the page now rendering —
   the page file itself, or one of its in-page heading anchors."
  [href current-file]
  (boolean (and href
                (or (= href current-file)
                    (str/starts-with? href (str current-file "#"))))))

(defn- toc-entry [{:keys [level text href id]} current-file]
  [:li (cond-> {:class (str "toc-level-" level)}
         id (assoc :id id))
   (cond
     (nil? href)                  text
     (current? href current-file) [:a {:class "current" :href href} text]
     :else                        [:a {:href href} text])])

(defn- sidebar-toc
  "The sticky navigation rail: the book title linking home, then every
   contents entry, with the entry for the current page marked `current`."
  [ctx]
  (let [current-file (:file (:page ctx))]
    [:nav {:class "book-sidebar" :aria-label "Table of contents"}
     [:a {:class "book-sidebar-title" :href (:home-file ctx)}
      (:book-title ctx)]
     (into [:ol {:class "book-sidebar-list"}]
           (map #(toc-entry % current-file) (:contents ctx)))]))

(defn- sidebar-page-wrap [ctx title main]
  [:html
   (head ctx title)
   [:body {}
    [:div {:class "book-layout"}
     (sidebar-toc ctx)
     (into [:div {:class "book-content"}]
           (concat
             [(into [:main {}] main)]
             (when-let [nav (:nav-hiccup ctx)] [nav])))]]])

(def ^:private sidebar-chrome
  "Two-column docs chrome: the sidebar rail is the table of contents; the
   prev/next furniture (the plain chrome's `:nav`) sits beneath the
   reading column."
  {:page-wrap sidebar-page-wrap
   :nav       (:nav html-assemble/default-chrome)})

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
