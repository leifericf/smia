(ns smia.site.assemble-test
  (:require
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [smia.site.assemble :as site]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(def ^:private tokens
  {:color   {:link "#2a52be"}
   :type    {:body-family "serif" :highlight true}
   :code    {}
   :spacing {}
   :layout  {:page-size :a4}})

(def ^:private manuscript
  {:title     "The Book"
   :author    "An Author"
   :numbering structure/default-numbering
   :references {:smith-2020 {:author "Smith" :year 2020 :title "Things"}}
   :sections
   [{:kind :chapter
     :content [:chapter {:id :ch-one :title "One"}
               [:h2 {:id :sec-a} "Alpha"]
               [:p "see " [:xref {:to :sec-b}]]
               [:pre {:lang :clojure} "(def x 1)"]]}
    {:kind :chapter
     :content [:chapter {:id :ch-two :title "Two"}
               [:h2 {:id :sec-b} "Beta"]
               [:p "back to " [:xref {:to :sec-a}]]]}
    {:kind :matter :matter :back :role :bibliography :generated true}]})

(def ^:private book (:manuscript (number/assign manuscript)))
(def ^:private result (site/assemble book tokens))
(def ^:private pages (:pages result))

(deftest page-map-covers-every-page-and-the-stylesheet
  (is (= #{"index.html" "ch-one/index.html" "ch-two/index.html"
           "bibliography/index.html" "styles.css"}
         (set (keys pages)))))

(deftest html-pages-are-doctyped-html5-documents
  (doseq [[path content] pages
          :when (str/ends-with? path ".html")]
    (is (str/starts-with? content "<!DOCTYPE html>\n<html>")
        (str path " is a doctyped HTML5 document"))))

(deftest home-page-links-the-contents
  (let [home (get pages "index.html")]
    (is (str/includes? home "Contents"))
    (is (str/includes? home "href=\"ch-one/\""))))

(deftest cross-chapter-xrefs-resolve-as-relative-directory-urls
  (is (str/includes? (get pages "ch-one/index.html")
                     "href=\"../ch-two/#sec-b\""))
  (is (str/includes? (get pages "ch-two/index.html")
                     "href=\"../ch-one/#sec-a\"")))

(deftest assets-resolve-relative-to-the-nested-page
  (testing "the stylesheet link climbs back to the site root"
    (is (str/includes? (get pages "ch-one/index.html")
                       "href=\"../styles.css\""))))

(deftest nav-chrome-chains-prev-and-next
  (testing "the middle of the chain points both ways"
    (is (str/includes? (get pages "ch-two/index.html") "rel=\"prev\""))
    (is (str/includes? (get pages "ch-two/index.html") "rel=\"next\"")))
  (testing "the edges do not run off the book"
    (is (not (str/includes? (get pages "ch-one/index.html") "rel=\"prev\"")))
    (is (not (str/includes? (get pages "bibliography/index.html") "rel=\"next\"")))))

(deftest theme-highlighting-reaches-the-code
  (is (str/includes? (get pages "ch-one/index.html") "tok-keyword")))

(deftest edge-chevrons-supplement-the-bottom-nav
  (testing "a middle page gets both chevrons, icon-only with descriptive labels"
    (let [page (get pages "ch-two/index.html")]
      (is (str/includes? page "class=\"edge-nav\""))
      (is (str/includes? page "edge-prev"))
      (is (str/includes? page "edge-next"))
      (is (str/includes? page "aria-label=\"Previous page: One\""))
      (is (str/includes? page "aria-label=\"Next page: ")))
    (testing "the glyphs themselves are hidden from assistive tech"
      (is (str/includes? (get pages "ch-two/index.html") "aria-hidden=\"true\""))))
  (testing "the first page has no previous chevron, the last no next"
    (is (not (str/includes? (get pages "ch-one/index.html") "edge-prev")))
    (is (not (str/includes? (get pages "bibliography/index.html") "edge-next"))))
  (testing "the home page carries no edge chevrons"
    (is (not (str/includes? (get pages "index.html") "edge-nav")))))

(deftest stylesheet-is-generated-from-the-tokens
  (let [css (get pages "styles.css")]
    (is (str/includes? css "body {"))
    (is (str/includes? css "#2a52be"))))

(deftest resources-pass-through
  (is (= [] (:resources result))))

(def ^:private downloads
  {:base   "https://example.com/dl"
   :assets [{:label "Screen PDF" :file "b-screen.pdf"
             :note "For screen." :default true}
            {:label "EPUB" :file "b.epub" :note "For e-readers."}]})

(deftest downloads-config-adds-a-downloads-page
  (let [pages (:pages (site/assemble book tokens {:downloads downloads}))]
    (is (contains? pages "downloads/index.html"))
    (testing "the home contents links to the downloads page"
      (is (str/includes? (get pages "index.html") "href=\"downloads/\"")))
    (testing "the page links each asset by its full release URL"
      (is (str/includes? (get pages "downloads/index.html")
                         "href=\"https://example.com/dl/b-screen.pdf\""))
      (is (str/includes? (get pages "downloads/index.html")
                         "href=\"https://example.com/dl/b.epub\"")))))

(deftest two-arity-assemble-has-no-downloads-page
  (is (not (contains? (set (keys pages)) "downloads/index.html"))))

;; --- redirects ----------------------------------------------------------------

(deftest redirects-synthesize-stub-pages
  (let [pages (:pages (site/assemble book tokens
                                     {:redirects {"old/one/"  :ch-one
                                                  "moved.html" :sec-b}}))]
    (is (contains? pages "old/one/index.html"))
    (is (contains? pages "moved.html"))
    (let [stub (get pages "old/one/index.html")]
      (is (str/includes? stub "http-equiv=\"refresh\""))
      (is (str/includes? stub "url=../../ch-one/") "the refresh target is relative")
      (is (str/includes? stub "rel=\"canonical\""))
      (is (str/includes? stub "<a href=\"../../ch-one/\"") "a plain link remains"))
    (testing "an anchor target redirects to its page plus fragment"
      (is (str/includes? (get pages "moved.html") "url=ch-two/#sec-b")))))

(deftest redirect-canonical-is-absolute-when-the-site-url-is-known
  (let [pages (:pages (site/assemble book tokens
                                     {:redirects {"old/" :ch-one}
                                      :site-url  "https://example.com/book"}))]
    (is (str/includes? (get pages "old/index.html")
                       "href=\"https://example.com/book/ch-one/\" rel=\"canonical\""))))

(deftest unknown-redirect-target-is-a-hard-error
  (let [d (catch-data #(site/assemble book tokens {:redirects {"old/" :nope}}))]
    (is (= :smia.site.redirects/unknown-target (:error/type d)))
    (is (= :nope (get-in d [:error/context :target])))
    (is (= "old/" (get-in d [:error/context :redirect])))))

(deftest redirect-may-not-overwrite-a-page
  (let [d (catch-data #(site/assemble book tokens {:redirects {"ch-one/" :ch-two}}))]
    (is (= :smia.site.redirects/redirect-overwrites-page (:error/type d)))))

;; --- sitemap and robots ---------------------------------------------------------

(deftest site-url-adds-sitemap-and-robots
  (let [pages (:pages (site/assemble book tokens
                                     {:site-url "https://example.com/book"}))]
    (is (contains? pages "sitemap.xml"))
    (is (contains? pages "robots.txt"))
    (let [sm (get pages "sitemap.xml")]
      (is (str/includes? sm "<loc>https://example.com/book/</loc>"))
      (is (str/includes? sm "<loc>https://example.com/book/ch-one/</loc>"))
      (is (str/includes? sm "<loc>https://example.com/book/bibliography/</loc>")))
    (is (str/includes? (get pages "robots.txt")
                       "Sitemap: https://example.com/book/sitemap.xml"))))

(deftest sitemap-lists-pages-in-sorted-order-and-skips-stubs
  (let [pages (:pages (site/assemble book tokens
                                     {:site-url  "https://example.com/book"
                                      :redirects {"old/" :ch-one}}))
        sm    (get pages "sitemap.xml")]
    (is (not (str/includes? sm "old/")) "redirect stubs are not canonical pages")
    (let [locs (mapv second (re-seq #"<loc>([^<]*)</loc>" sm))]
      (is (= locs (vec (sort locs)))))))

(deftest without-a-site-url-no-sitemap-or-robots
  (is (not (contains? pages "sitemap.xml")))
  (is (not (contains? pages "robots.txt"))))

;; --- the search island ----------------------------------------------------------

(def ^:private search-tokens (assoc tokens :site {:search true}))
(def ^:private search-result (site/assemble book search-tokens))
(def ^:private search-pages (:pages search-result))

(deftest search-token-adds-the-index-and-fallback-page
  (is (contains? search-pages "search-index.json"))
  (is (contains? search-pages "search/index.html"))
  (testing "the fallback page lists the book by category"
    (let [fallback (get search-pages "search/index.html")]
      (is (str/includes? fallback "Chapters"))
      (is (str/includes? fallback "href=\"../ch-one/\""))))
  (testing "the index carries kinds and entries"
    (let [json (get search-pages "search-index.json")]
      (is (str/includes? json "{\"kind\":\"chapter\",\"label\":\"Chapters\"}"))
      (is (str/includes? json "\"url\":\"ch-one/\""))))
  (testing "the search page itself is not in the index"
    (is (not (str/includes? (get search-pages "search-index.json")
                            "\"kind\":\"search\"")))))

(deftest search-chrome-is-progressive-enhancement
  (let [page (get search-pages "ch-one/index.html")]
    (testing "a plain GET form targets the fallback page"
      (is (str/includes? page "role=\"search\""))
      (is (str/includes? page "action=\"../search/\""))
      (is (str/includes? page "data-island=\"smia-search\"")))
    (testing "the data attributes carry page-relative paths"
      (is (str/includes? page "data-index-url=\"../search-index.json\""))
      (is (str/includes? page "data-root=\"../\"")))
    (testing "the deferred script tag is page-relative"
      (is (str/includes? page "<script defer=\"defer\" src=\"../search.js\">")))))

(deftest search-bundle-is-named-for-the-emit-shell
  (is (= [{:resource "smia/site/search.js" :path "search.js"}]
         (:bundled search-result))))

(deftest search-index-json-is-deterministic
  (is (= (get search-pages "search-index.json")
         (get (:pages (site/assemble book search-tokens)) "search-index.json"))))

(deftest without-the-token-no-search-anywhere
  (is (not (contains? pages "search-index.json")))
  (is (not (contains? pages "search/index.html")))
  (is (= [] (:bundled result)))
  (is (not (str/includes? (get pages "ch-one/index.html") "search"))))

(deftest shipped-search-bundle-is-on-the-classpath
  (let [r (clojure.java.io/resource "smia/site/search.js")]
    (is (some? r))
    (is (pos? (count (slurp r))))))

(deftest shipped-reader-bundle-is-on-the-classpath
  (let [r (clojure.java.io/resource "smia/site/reader.js")]
    (is (some? r))
    (is (pos? (count (slurp r))))))

(deftest shipped-keys-bundle-is-on-the-classpath
  (let [r (clojure.java.io/resource "smia/site/keys.js")]
    (is (some? r))
    (is (pos? (count (slurp r))))))

;; --- the mermaid island -----------------------------------------------------

(def ^:private mermaid-manuscript
  (:manuscript
    (number/assign
      {:title "M" :author "A" :numbering structure/default-numbering
       :sections [{:kind :chapter
                   :content [:chapter {:id :ch :title "Ch"}
                             [:diagram {:engine :mermaid :source "graph TD; A-->B"}]]}]})))

(deftest mermaid-island-emits-the-pre-and-bundle-when-on
  (let [r    (site/assemble mermaid-manuscript (assoc tokens :site {:mermaid true}))
        page (get (:pages r) "ch/index.html")]
    (testing "the diagram is a <pre class=mermaid> the script transforms"
      (is (str/includes? page "<pre class=\"mermaid\">graph TD; A--&gt;B</pre>")))
    (testing "the deferred island bundle is linked (page-relative)"
      (is (str/includes? page "src=\"../mermaid.js\"")))
    (testing "the bundle is named for the emit shell"
      (is (some #(= {:resource "smia/site/mermaid.js" :path "mermaid.js"} %)
                (:bundled r))))))

(deftest mermaid-src-adds-a-vendor-loader-before-the-bundle
  (let [r    (site/assemble mermaid-manuscript
                            (assoc tokens :site {:mermaid {:src "https://cdn.example/mermaid.js"}}))
        page (get (:pages r) "ch/index.html")]
    (is (str/includes? page "src=\"https://cdn.example/mermaid.js\""))
    (is (< (.indexOf page "cdn.example") (.indexOf page "src=\"../mermaid.js\""))
        "the vendor loader comes before the island bundle")))

(deftest without-the-token-mermaid-falls-back-to-source
  (let [r    (site/assemble mermaid-manuscript tokens)
        page (get (:pages r) "ch/index.html")]
    (is (not (str/includes? page "class=\"mermaid\"")))
    (is (str/includes? page "graph TD") "the source still shows as a listing")
    (is (not (some #(= "mermaid.js" (:path %)) (:bundled r))))))

(deftest shipped-mermaid-bundle-is-on-the-classpath
  (let [res (clojure.java.io/resource "smia/site/mermaid.js")]
    (is (some? res))
    (is (pos? (count (slurp res))))))

;; --- the dark-mode toggle island --------------------------------------------

(def ^:private toggle-result
  (site/assemble book (assoc tokens :site {:dark {:toggle true}})))

(deftest toggle-island-adds-the-explicit-data-theme-blocks
  (let [css (get (:pages toggle-result) "styles.css")]
    (testing "an explicit choice overrides the media query, both ways"
      (is (str/includes? css "html[data-theme=\"dark\"]"))
      (is (str/includes? css "html[data-theme=\"light\"]")))
    (testing "the toggle button is styled"
      (is (str/includes? css ".theme-toggle")))))

(deftest toggle-island-emits-a-pre-paint-script-and-a-button
  (let [page (get (:pages toggle-result) "ch-one/index.html")]
    (testing "the island script is loaded, not deferred, so it runs before paint"
      (is (str/includes? page "<script src=\"../theme.js\">"))
      (is (not (str/includes? page "defer=\"defer\" src=\"../theme.js\""))))
    (testing "a toggle button is rendered, inert until the script wires it"
      (is (str/includes? page "data-theme-toggle"))
      (is (str/includes? page "<button")))
    (testing "the bundle is named for the emit shell"
      (is (some #(= {:resource "smia/site/theme.js" :path "theme.js"} %)
                (:bundled toggle-result))))))

;; --- the reader-preferences island ------------------------------------------

(deftest reader-island-emits-the-cluster-script-and-bundle-when-on
  (let [r    (site/assemble book (assoc tokens :site {:reader true}))
        page (get (:pages r) "ch-one/index.html")
        css  (get (:pages r) "styles.css")]
    (testing "the island script is loaded, not deferred, so it runs before paint"
      (is (str/includes? page "<script src=\"../reader.js\">"))
      (is (not (str/includes? page "defer=\"defer\" src=\"../reader.js\""))))
    (testing "the control cluster is rendered, inert until the script wires it"
      (is (str/includes? page "data-reader-controls"))
      (is (str/includes? page "data-reader-toggle"))
      (is (str/includes? page "data-reader-width=\"+\""))
      (is (str/includes? page "data-reader-scale=\"-\""))
      (is (str/includes? page "data-reader-contrast"))
      (is (str/includes? page "data-focus-toggle")))
    (testing "with dark mode off the cluster carries no color-scheme control"
      (is (not (str/includes? page "data-theme-toggle"))))
    (testing "a hidden reading-progress bar ships for the island to drive"
      (is (str/includes? page "data-reading-progress")))
    (testing "the stylesheet carries the reading variables and cluster styling"
      (is (str/includes? css "--reading-width"))
      (is (str/includes? css ".reader-controls"))
      (is (str/includes? css ".reading-progress")))
    (testing "the bundle is named for the emit shell"
      (is (some #(= {:resource "smia/site/reader.js" :path "reader.js"} %)
                (:bundled r))))))

(deftest reader-with-dark-folds-the-theme-control-into-the-cluster
  (let [r    (site/assemble book (assoc tokens :site {:reader true :dark true}))
        page (get (:pages r) "ch-one/index.html")
        css  (get (:pages r) "styles.css")]
    (testing "the cluster gains a color-scheme control"
      (is (str/includes? page "data-theme-toggle")))
    (testing "the explicit data-theme blocks back that control"
      (is (str/includes? css "html[data-theme=\"dark\"]")))
    (testing "the standalone toggle bundle is not shipped; the reader bundle is"
      (is (not (some #(= "theme.js" (:path %)) (:bundled r))))
      (is (some #(= "reader.js" (:path %)) (:bundled r))))))

(deftest without-the-token-no-reader-island
  (let [r    (site/assemble book tokens)
        page (get (:pages r) "ch-one/index.html")]
    (is (not (str/includes? page "reader.js")))
    (is (not (str/includes? page "data-reader-controls")))
    (is (not (str/includes? page "class=\"breadcrumb\"")))
    (is (not (str/includes? (get (:pages r) "styles.css") "--reading-width")))
    (is (not (some #(= "reader.js" (:path %)) (:bundled r))))))

(deftest reader-renders-an-orientation-breadcrumb
  (let [r    (site/assemble book (assoc tokens :site {:reader true}))
        page (get (:pages r) "ch-one/index.html")
        home (get (:pages r) "index.html")]
    (testing "a content page gets a breadcrumb"
      (is (str/includes? page "class=\"breadcrumb\"")))
    (testing "the home page does not"
      (is (not (str/includes? home "class=\"breadcrumb\""))))))

;; --- the keyboard-shortcuts island ------------------------------------------

(deftest keyboard-island-emits-the-help-and-bundle-when-on
  (let [r    (site/assemble book (assoc tokens :site {:keyboard true}))
        page (get (:pages r) "ch-one/index.html")
        css  (get (:pages r) "styles.css")]
    (testing "the island script is deferred (it only binds handlers)"
      (is (str/includes? page "defer=\"defer\" src=\"../keys.js\"")))
    (testing "a hidden help dialog ships for the island to reveal"
      (is (str/includes? page "data-kbd-help"))
      (is (str/includes? page "data-kbd-close"))
      (is (str/includes? page "Keyboard shortcuts")))
    (testing "the help dialog is styled"
      (is (str/includes? css ".kbd-help-panel")))
    (testing "the bundle is named for the emit shell"
      (is (some #(= {:resource "smia/site/keys.js" :path "keys.js"} %)
                (:bundled r))))))

(deftest without-the-token-no-keyboard-island
  (let [r    (site/assemble book tokens)
        page (get (:pages r) "ch-one/index.html")]
    (is (not (str/includes? page "keys.js")))
    (is (not (str/includes? page "data-kbd-help")))
    (is (not (str/includes? (get (:pages r) "styles.css") ".kbd-help")))
    (is (not (some #(= "keys.js" (:path %)) (:bundled r))))))

(deftest dark-without-toggle-stays-os-driven-only
  (let [r    (site/assemble book (assoc tokens :site {:dark true}))
        css  (get (:pages r) "styles.css")
        page (get (:pages r) "ch-one/index.html")]
    (is (str/includes? css "@media (prefers-color-scheme: dark)"))
    (is (not (str/includes? css "html[data-theme")))
    (is (not (str/includes? page "theme.js")))
    (is (not (str/includes? page "data-theme-toggle")))
    (is (not (some #(= "theme.js" (:path %)) (:bundled r))))))

(deftest shipped-theme-bundle-is-on-the-classpath
  (let [res (clojure.java.io/resource "smia/site/theme.js")]
    (is (some? res))
    (is (pos? (count (slurp res))))))
