(ns clj-book.targets.site
  "Site target (Render): assemble the Document HTML model into static
   HTML pages via Stasis and emit the compiled CSS. The DocBook -> HTML
   transform lives in clj-book.document; this namespace owns page
   assembly and output."
  (:require
   [clj-book.docbook.parse :as docbook-parse]
   [clj-book.document :as document]
   [clj-book.theme.css :as theme-css]
   [clj-book.theme.load :as theme]
   [clojure.java.io :as io]
   [hiccup2.core :as h]
   [stasis.core :as stasis]))

(defn- collect-chapters [book-hiccup]
  (->> book-hiccup
       (filter (fn [n] (and (vector? n)
                            (= :section (first n))
                            (= "chapter" (:class (second n))))))))

(defn- toc [chapters]
  (into [:nav {:class "toc"} [:h2 "Contents"]]
        [(into [:ol]
               (for [ch chapters
                     :let [a (second ch)
                           id (:id a)
                           title (last (nth ch 2 nil))]]
                 [:li [:a {:href (str "chapters/" id ".html")} title]]))]))

(defn- page [{:keys [title css-href]} body]
  (str "<!DOCTYPE html>"
       (h/html
         [:html {:lang "en"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:title title]
           [:link {:rel "stylesheet" :href css-href}]]
          [:body body]])))

(defn- chapter-id [ch idx]
  (or (:id (second ch))
      (str "ch-" (inc idx))))

(defn- chapter-title [ch]
  (let [h (nth ch 2 nil)]
    (cond
      (and (vector? h) (= :h2 (first h))) (last h)
      :else "Chapter")))

(defn page-map
  "Build the Stasis-shaped page map from the in-memory site model."
  [{:keys [book/title book/slug css-href body-hiccup]}]
  (let [chapters (collect-chapters body-hiccup)
        toc-h    (toc (map-indexed
                        (fn [i ch]
                          [:section {:id (chapter-id ch i)
                                     :class "chapter"}
                           [:h2 (chapter-title ch)]])
                        chapters))
        opts     {:title (or title slug) :css-href css-href}
        index    (page opts
                       [:main
                        [:header [:h1 (or title slug)]]
                        toc-h])
        ch-pages (into {}
                       (map-indexed
                         (fn [i ch]
                           (let [id (chapter-id ch i)]
                             [(str "/chapters/" id ".html")
                              (page opts [:main ch])]))
                         chapters))]
    (merge {"/index.html" index} ch-pages)))

(defn render-pages
  "Render the Stasis page map from the generated DocBook. This is the
   single in-memory rendering path: the full build exports the result to
   disk, the preview server serves it directly, and both agree on one
   HTML model. Reads the generated `book.xml`; does not write."
  [{:keys [intermediate-dir config css-href]}]
  (let [docbook (docbook-parse/parse-docbook-file
                  (str intermediate-dir "/book.xml"))
        body    (document/->html-model docbook)]
    (page-map {:book/title  (:book/title config)
               :book/slug   (:book/slug config)
               :css-href    css-href
               :body-hiccup body})))

(defn build!
  "Build the site target: render the page map, export it to disk, and
   emit the compiled CSS. Returns a map of produced artifact paths."
  [{:keys [intermediate-dir output-dir book-root config tokens]}]
  (let [pages   (render-pages {:intermediate-dir intermediate-dir
                               :config           config
                               :css-href         "assets/site.css"})
        extras  (theme/load-site-extras book-root)
        css     (theme-css/compile-css {:tokens tokens :extras extras})
        out     (io/file output-dir)
        css-out (io/file out "assets/site.css")]
    (io/make-parents (io/file out "marker"))
    (stasis/empty-directory! out)
    (stasis/export-pages pages (.getPath out))
    (io/make-parents css-out)
    (spit css-out css)
    {:html (.getPath (io/file out "index.html"))
     :css  (.getPath css-out)
     :dir  (.getPath out)}))
