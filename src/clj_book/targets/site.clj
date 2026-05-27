(ns clj-book.targets.site
  "Site target: transform DocBook 5 -> Hiccup -> static HTML via Stasis."
  (:require
   [clj-book.docbook :as docbook]
   [clj-book.tokens.css :as tokens-css]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [stasis.core :as stasis]))

(declare ->hiccup)

(defn- tag-of [node]
  (when (vector? node) (first node)))

(defn- attrs-of [node]
  (let [a (and (vector? node) (second node))]
    (if (map? a) a {})))

(defn- content-of [node]
  (if (vector? node)
    (let [tail (drop 2 node)]
      (if (map? (second node)) tail (rest node)))
    nil))

(defn- inline->hiccup [c]
  (cond
    (string? c) c
    (vector? c)
    (case (tag-of c)
      :emphasis      (into [:em] (map inline->hiccup (content-of c)))
      :literal       (into [:code] (map inline->hiccup (content-of c)))
      :code          (into [:code] (map inline->hiccup (content-of c)))
      :link          (let [a (attrs-of c)]
                       (into [:a {:href (or (:xlink:href a) (:href a) "#")}]
                             (map inline->hiccup (content-of c))))
      :xref          (let [a (attrs-of c)]
                       [:a {:href (str "#" (or (:linkend a) ""))}
                        (or (:linkend a) "")])
      :phrase        (into [:span] (map inline->hiccup (content-of c)))
      (->hiccup c))
    :else (str c)))

(defn- title-of [node]
  (some (fn [c]
          (when (and (vector? c) (= :title (tag-of c)))
            (->> (content-of c)
                 (map inline->hiccup)
                 (apply str)
                 str/trim)))
        (content-of node)))

(defn- heading [level node]
  (let [t (title-of node)
        a (attrs-of node)
        id (:xml:id a)]
    (when t
      [(keyword (str "h" level))
       (cond-> {} id (assoc :id id))
       t])))

(defn- non-title-children [node]
  (->> (content-of node)
       (remove (fn [c]
                 (and (vector? c) (= :title (tag-of c)))))))

(defn- ->hiccup [node]
  (cond
    (string? node) node
    (vector? node)
    (case (tag-of node)
      :book
      (into [:article {:class "book"}]
            (concat (when-let [h (heading 1 node)] [h])
                    (map ->hiccup (non-title-children node))))
      :info
      (into [:header {:class "book-info"}]
            (map inline->hiccup (content-of node)))
      :chapter
      (let [a (attrs-of node)
            id (:xml:id a)]
        (into [:section (cond-> {:class "chapter"} id (assoc :id id))]
              (concat (when-let [h (heading 2 node)] [h])
                      (map ->hiccup (non-title-children node)))))
      :section
      (let [a (attrs-of node)
            id (:xml:id a)]
        (into [:section (cond-> {:class "section"} id (assoc :id id))]
              (concat (when-let [h (heading 3 node)] [h])
                      (map ->hiccup (non-title-children node)))))
      :simpara    (into [:p] (map inline->hiccup (content-of node)))
      :para       (into [:p] (map inline->hiccup (content-of node)))
      :literallayout (into [:pre] (map inline->hiccup (content-of node)))
      :programlisting (into [:pre [:code]] (map inline->hiccup (content-of node)))
      :itemizedlist (into [:ul]
                          (for [c (content-of node)
                                :when (and (vector? c) (= :listitem (tag-of c)))]
                            (into [:li] (map ->hiccup (content-of c)))))
      :orderedlist (into [:ol]
                         (for [c (content-of node)
                               :when (and (vector? c) (= :listitem (tag-of c)))]
                           (into [:li] (map ->hiccup (content-of c)))))
      :title nil
      (into [:div {:class (str "db-" (name (tag-of node)))}]
            (map inline->hiccup (content-of node))))
    :else (str node)))

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

(defn build!
  "Build the site target: render Hiccup, write the page map, and emit
   the compiled CSS. Returns a map of produced artifact paths."
  [{:keys [intermediate-dir output-dir book-root config tokens] :as ctx}]
  (let [docbook (docbook/parse-docbook-file
                  (str intermediate-dir "/book.xml"))
        body    (->hiccup docbook)
        css     (tokens-css/compile-css {:book-root book-root :tokens tokens})
        css-href "assets/site.css"
        slug    (:book/slug config)
        title   (:book/title config)
        pages   (page-map {:book/title  title
                           :book/slug   slug
                           :css-href    css-href
                           :body-hiccup body})
        out     (io/file output-dir)
        css-out (io/file out "assets/site.css")]
    (io/make-parents css-out)
    (spit css-out css)
    (stasis/empty-directory! out {:dirs-to-keep ["assets"]})
    (stasis/export-pages pages (.getPath out))
    {:html (.getPath (io/file out "index.html"))
     :css  (.getPath css-out)
     :dir  (.getPath out)}))
