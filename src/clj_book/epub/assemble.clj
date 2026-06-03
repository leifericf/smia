(ns clj-book.epub.assemble
  "Pure core: assemble a numbered manuscript into EPUB3 package contents.

   EPUB is packaging over the shared HTML core: the same pages
   `html.assemble` builds for the site, serialized as XHTML, wrapped in
   reader-friendly chrome, plus the package apparatus — `mimetype` (first
   entry, stored uncompressed), `META-INF/container.xml`, the OPF package
   document (Dublin Core metadata, a manifest of every file, the reading
   spine, schema.org accessibility metadata), and the `nav.xhtml` EPUB
   navigation document.

   Accessible by construction: access modes, sufficient modes, features,
   and hazards are emitted with computed defaults (`visual` is declared
   exactly when the book has images), overridable via `book.edn`
   `:book/accessibility`. The `dcterms:modified` stamp is pinned to the
   epoch, mirroring the PDF renderer's pinned creation date, so package
   contents are deterministic.

   Returns ordered entries `[{:path :content|:resource :method?} …]`;
   the shell (`epub.zip`) turns them into a byte-reproducible archive.
   No IO."
  (:require
   [clj-book.fo.serialize :as fo-serialize]
   [clj-book.html.assemble :as html-assemble]
   [clj-book.html.serialize :as html-serialize]
   [clj-book.theme.css :as css]
   [clojure.string :as str]))

(def pinned-modified
  "Fixed dcterms:modified stamp (the Unix epoch) so the package does not
   vary run to run."
  "1970-01-01T00:00:00Z")

(declare svg-referenced? package-doc nav-doc)

(def ^:private container-xml
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<container version=\"1.0\" "
       "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
       "<rootfiles>"
       "<rootfile full-path=\"OEBPS/content.opf\" "
       "media-type=\"application/oebps-package+xml\"/>"
       "</rootfiles>"
       "</container>"))

(defn- page-wrap
  "Reader chrome: EPUB readers supply navigation, so a page is just its
   titled, styled content."
  [_ctx title main]
  [:html
   [:head
    [:title {} title]
    [:link {:rel "stylesheet" :type "text/css" :href "styles.css"}]]
   [:body {} (into [:main {}] main)]])

(def ^:private chrome
  {:page-wrap page-wrap
   :nav       (fn [_] nil)})

;; --- assembly ------------------------------------------------------------------

(defn assemble
  "Assemble a numbered `book` and theme `tokens` into
   `{:entries [{:path :content|:resource :method?} …]}` in package order.
   Options: `:identifier` (required — the dc:identifier value),
   `:language` (default \"en\"), `:accessibility` (the `book.edn`
   `:book/accessibility` map, merged over computed defaults)."
  [book tokens {:keys [identifier language accessibility]}]
  (let [{:keys [pages contents resources]}
        (html-assemble/assemble
          book {:chrome    chrome
                :extension "xhtml"
                :highlight? (get-in tokens [:type :highlight] false)})
        page-entries  (mapv (fn [{:keys [file hiccup]}]
                              (cond-> {:path    (str "OEBPS/" file)
                                       :id      (str/replace file #"\.xhtml$" "")
                                       :content (html-serialize/serialize
                                                  hiccup {:mode :xhtml :doctype? true})}
                                (svg-referenced? hiccup) (assoc :properties "svg")))
                            pages)
        image-entries (mapv (fn [{:keys [src]}]
                              {:path     (str "OEBPS/" src)
                               :id       (str "res-" (str/replace src #"[^A-Za-z0-9]" "-"))
                               :resource src})
                            resources)
        nav-entry     {:path    "OEBPS/nav.xhtml"
                       :id      "nav"
                       :content (nav-doc contents page-entries)}
        css-entry     {:path    "OEBPS/styles.css"
                       :id      "css"
                       :content (css/css tokens)}
        manifest      (concat [nav-entry css-entry] page-entries image-entries)
        opf           {:path    "OEBPS/content.opf"
                       :content (package-doc book
                                             {:identifier    identifier
                                              :language      language
                                              :accessibility accessibility
                                              :images?       (boolean (seq resources))}
                                             manifest
                                             page-entries)}]
    {:entries (vec (concat
                     [{:path    "mimetype"
                       :content "application/epub+zip"
                       :method  :stored}
                      {:path "META-INF/container.xml" :content container-xml}
                      opf]
                     (map #(dissoc % :id :properties) manifest)))}))

;; --- the package document --------------------------------------------------------

(def ^:private media-types
  {"xhtml" "application/xhtml+xml"
   "css"   "text/css"
   "png"   "image/png"
   "jpg"   "image/jpeg"
   "jpeg"  "image/jpeg"
   "gif"   "image/gif"
   "svg"   "image/svg+xml"
   "webp"  "image/webp"})

(defn- media-type [path]
  (get media-types (str/lower-case (last (str/split path #"\.")))
       "application/octet-stream"))

(defn- svg-referenced?
  "True when a page references an SVG image — its manifest item must
   then declare the `svg` property."
  [hiccup]
  (boolean
    (some #(and (vector? %) (= :img (first %)) (map? (second %))
                (str/ends-with? (str/lower-case (str (:src (second %))))
                                ".svg"))
          (tree-seq vector? seq hiccup))))

(defn- a11y-metas
  "schema.org accessibility metadata with computed defaults: textual
   always, visual exactly when the book carries images. The
   `:book/accessibility` map overrides any slot."
  [accessibility images?]
  (let [modes      (or (:access-modes accessibility)
                       (cond-> ["textual"] images? (conj "visual")))
        sufficient (or (:access-mode-sufficient accessibility) ["textual"])
        features   (or (:features accessibility)
                       ["structuralNavigation" "tableOfContents"])
        hazards    (or (:hazards accessibility) ["none"])
        summary    (:summary accessibility)]
    (concat
      (map (fn [m] [:meta {:property "schema:accessMode"} m]) modes)
      (map (fn [m] [:meta {:property "schema:accessModeSufficient"} m]) sufficient)
      (map (fn [m] [:meta {:property "schema:accessibilityFeature"} m]) features)
      (map (fn [m] [:meta {:property "schema:accessibilityHazard"} m]) hazards)
      (when summary
        [[:meta {:property "schema:accessibilitySummary"} summary]]))))

(defn- package-doc
  "The OPF package document: Dublin Core metadata, the manifest of every
   packaged file, and the reading-order spine."
  [book {:keys [identifier language accessibility images?]} manifest pages]
  (fo-serialize/serialize
    [:package {:xmlns "http://www.idpf.org/2007/opf"
               :version "3.0"
               :unique-identifier "book-id"}
     (into [:metadata {:xmlns/dc "http://purl.org/dc/elements/1.1/"}]
           (concat
             [[:dc/identifier {:id "book-id"} identifier]
              [:dc/title {} (:title book)]]
             (when (:author book) [[:dc/creator {} (:author book)]])
             [[:dc/language {} (or language "en")]
              [:meta {:property "dcterms:modified"} pinned-modified]]
             (a11y-metas accessibility images?)))
     (into [:manifest]
           (map (fn [{:keys [path id properties]}]
                  [:item (cond-> {:id         id
                                  :href       (subs path (count "OEBPS/"))
                                  :media-type (media-type path)}
                           (= id "nav") (assoc :properties "nav")
                           properties   (assoc :properties properties))])
                manifest))
     (into [:spine]
           (map (fn [{:keys [id]}] [:itemref {:idref id}]) pages))]))

;; --- the navigation document --------------------------------------------------------

(defn- nav-ol
  "Nest the flat contents entries (by `:level`) into the `ol` tree the
   EPUB navigation document requires. A part entry has no page of its
   own, so it becomes an unlinked `span` heading — the toc nav's links
   must follow spine order, which a link back to the home page's part
   anchor would break."
  [entries]
  (loop [es entries, lis []]
    (if (empty? es)
      (into [:ol {}] lis)
      (let [e           (first es)
            [kids more] (split-with #(> (:level %) (:level e)) (rest es))
            label       (if (:href e)
                          [:a {:href (:href e)} (:text e)]
                          [:span {} (:text e)])
            li          (if (seq kids)
                          [:li {} label (nav-ol kids)]
                          [:li {} label])]
        (recur more (conj lis li))))))

(defn- nav-doc
  "The EPUB navigation document: the table of contents and the landmarks."
  [contents pages]
  (let [home-file  "index.xhtml"
        body-start (or (some (fn [{:keys [path id]}]
                               (when (and id (str/starts-with? id "chapter-"))
                                 (subs path (count "OEBPS/"))))
                             pages)
                       (subs (:path (first pages)) (count "OEBPS/")))]
    (html-serialize/serialize
      [:html {:xmlns/epub "http://www.idpf.org/2007/ops"}
       [:head [:title {} "Contents"]]
       [:body {}
        [:nav {:epub/type "toc" :role "doc-toc"}
         [:h1 {} "Contents"]
         (nav-ol contents)]
        [:nav {:epub/type "landmarks" :hidden "hidden"}
         [:h2 {} "Landmarks"]
         [:ol {}
          [:li {} [:a {:epub/type "toc" :href home-file}
                   "Table of contents"]]
          [:li {} [:a {:epub/type "bodymatter" :href body-start}
                   "Start of content"]]]]]]
      {:mode :xhtml :doctype? true})))
