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
