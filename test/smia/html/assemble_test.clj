(ns smia.html.assemble-test
  (:require
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.error]
   [smia.html.assemble :as html-assemble]
   [smia.html.chrome :as chrome]
   [clojure.test :refer [deftest is testing]]))

(def ^:private manuscript
  {:title     "The Book"
   :author    "An Author"
   :numbering structure/default-numbering
   :references {:smith-2020 {:author "Smith" :year 2020 :title "Things"}}
   :sections
   [{:kind :matter :matter :front :role :preface
     :content [:chapter {:id :preface :title "Preface"} [:p "hello"]]}
    {:kind :chapter
     :content [:chapter {:id :ch-one :title "One"}
               [:h2 {:id :sec-a} "Alpha"]
               [:p "see " [:xref {:to :sec-b}]]
               [:p "noted" [:footnote "the note"]]
               [:figure {:id "fig-cat" :caption "A cat"}
                [:img {:src "images/cat.png" :alt "a cat"}]]
               [:p "cats " [:index {:term "cats"}]]
               [:p "as " [:cite {:key :smith-2020}] " says"]]}
    {:kind :chapter
     :content [:chapter {:id :ch-two :title "Two"}
               [:h2 {:id :sec-b} "Beta"]
               [:p "back to " [:xref {:to :sec-a}]]]}
    {:kind :matter :matter :back :role :bibliography :generated true}
    {:kind :matter :matter :back :role :index :generated true}
    {:kind :matter :matter :back :role :list-of-figures :generated true}]})

(def ^:private book (:manuscript (number/assign manuscript)))
(def ^:private result (html-assemble/assemble book {}))

(defn- page-in [result file]
  (first (filter #(= file (:file %)) (:pages result))))

(defn- page [file]
  (page-in result file))

(defn- nodes
  "Every vector node in `tree` for which `pred` is true."
  [tree pred]
  (filter #(and (vector? %) (pred %)) (tree-seq vector? seq tree)))

(defn- hrefs [tree]
  (set (keep #(when (and (vector? %) (= :a (first %)) (map? (second %)))
                (:href (second %)))
             (tree-seq vector? seq tree))))

(defn- by-class [tree cls]
  (nodes tree #(= cls (:class (second %)))))

;; --- beta-review draft marking -----------------------------------------------

(def ^:private draft-book
  (assoc book :draft {:label "Beta"
                      :notice "Confidential review copy. Not for distribution."
                      :watermark "BETA"}))

(deftest draft-watermark-rides-every-page
  (let [res (html-assemble/assemble draft-book {})]
    (testing "every page carries the click-through watermark element"
      (doseq [p (:pages res)]
        (let [wm (by-class (:hiccup p) "draft-watermark")]
          (is (= 1 (count wm)) (str (:file p) " has one watermark"))
          (is (.contains (apply str (filter string? (tree-seq vector? seq (first wm))))
                         "BETA")))))))

(deftest draft-banner-leads-the-cover
  (let [res  (html-assemble/assemble draft-book {})
        home (:hiccup (page-in res "index.html"))]
    (testing "the home/cover page shows the clear notice banner"
      (let [banner (by-class home "draft-banner")]
        (is (= 1 (count banner)))
        (is (.contains (apply str (filter string? (tree-seq vector? seq (first banner))))
                       "Confidential review copy. Not for distribution."))))))

(deftest no-draft-leaves-no-marks
  (testing "a final book carries neither watermark nor banner on any page"
    (doseq [p (:pages result)]
      (is (empty? (by-class (:hiccup p) "draft-watermark")))
      (is (empty? (by-class (:hiccup p) "draft-banner"))))))

(deftest page-set-has-home-chapters-and-back-matter-in-order
  (is (= ["index.html" "preface.html" "chapter-01.html" "chapter-02.html"
          "bibliography.html" "book-index.html" "list-of-figures.html"]
         (mapv :file (:pages result)))))

(deftest every-page-is-a-full-html-document
  (doseq [p (:pages result)]
    (is (= :html (first (:hiccup p))) (str (:file p) " wraps a <html> page"))))

(deftest home-page-carries-the-table-of-contents
  (let [home (:hiccup (page "index.html"))]
    (is (some #(= [:h2 {} "Contents"] %) (tree-seq vector? seq home)))
    (is (contains? (hrefs home) "chapter-01.html"))
    (is (contains? (hrefs home) "bibliography.html"))))

(deftest chapter-page-has-labelled-heading-with-anchor
  (let [ch (:hiccup (page "chapter-01.html"))]
    (is (seq (nodes ch #(and (= :h1 (first %)) (= "ch-one" (:id (second %)))))))
    (is (some #(= [:p {:class "chapter-label"} "Chapter 1"] %)
              (tree-seq vector? seq ch)))))

(deftest cross-chapter-xref-resolves-to-file-and-fragment
  (is (contains? (hrefs (:hiccup (page "chapter-01.html")))
                 "chapter-02.html#sec-b"))
  (is (contains? (hrefs (:hiccup (page "chapter-02.html")))
                 "chapter-01.html#sec-a")))

(deftest footnotes-collect-at-the-chapter-end-with-backlinks
  (let [ch    (:hiccup (page "chapter-01.html"))
        notes (nodes ch #(= "footnotes" (:class (second %))))]
    (is (= 1 (count notes)))
    (is (seq (nodes ch #(= "fn-1" (:id (second %)))))
        "the note body gets the fn-1 anchor")
    (is (contains? (hrefs ch) "#fn-1") "the noteref points at the note")
    (is (contains? (hrefs ch) "#fnref-1") "the backlink returns to the text")
    (testing "each note item is a plain li"
      ;; doc-footnote is not a valid li role and doc-endnote is deprecated;
      ;; epubcheck flags both. The section's doc-endnotes role suffices.
      (is (nil? (:role (second (first (nodes ch #(= "fn-1" (:id (second %))))))))))))

(deftest bibliography-page-anchors-every-entry
  (let [bib (:hiccup (page "bibliography.html"))]
    (is (seq (nodes bib #(= "ref-smith-2020" (:id (second %))))))
    (is (contains? (hrefs (:hiccup (page "chapter-01.html")))
                   "bibliography.html#ref-smith-2020"))))

(deftest index-page-links-terms-to-their-marks
  (let [idx (:hiccup (page "book-index.html"))]
    (is (some #(= "cats" %) (tree-seq vector? seq idx)))
    (is (contains? (hrefs idx) "chapter-01.html#idx-1"))))

(deftest float-list-links-to-the-figure
  (let [lof (:hiccup (page "list-of-figures.html"))]
    (is (contains? (hrefs lof) "chapter-01.html#fig-cat"))
    (is (some #(and (string? %) (.contains ^String % "Figure 1"))
              (tree-seq vector? seq lof)))))

(deftest resources-list-referenced-images
  (is (= [{:src "images/cat.png"}] (:resources result))))

(defn- catch-data [thunk]
  (try (thunk) nil (catch Exception e (smia.error/data e))))

(defn- one-img-book [src]
  (:manuscript
    (number/assign
      {:title "B" :author nil :numbering structure/default-numbering
       :references {}
       :sections [{:kind :chapter
                   :content [:chapter {:id :c :title "C"}
                             [:p [:img {:src src :alt "x"}]]]}]})))

(deftest remote-image-sources-are-not-collected-as-resources
  (testing "a remote URL stays an href and is not copied as a local file"
    (doseq [src ["https://example.com/cat.png"
                 "http://example.com/cat.png"
                 "data:image/png;base64,AAAA"]]
      (let [r (html-assemble/assemble (one-img-book src) {})]
        (is (= [] (:resources r)) (str src " is not a local resource"))))))

(deftest unsafe-image-paths-are-rejected
  (testing "a parent-escaping path is a structured error before any write"
    (let [d (catch-data #(html-assemble/assemble
                           (one-img-book "../secret.png") {}))]
      (is (= :smia.html.assemble/unsafe-resource-path (:error/type d)))
      (is (= "../secret.png" (:src (:error/context d))))))
  (testing "a nested parent-escaping segment is caught too"
    (let [d (catch-data #(html-assemble/assemble
                           (one-img-book "images/../../secret.png") {}))]
      (is (= :smia.html.assemble/unsafe-resource-path (:error/type d)))))
  (testing "an absolute path is rejected"
    (let [d (catch-data #(html-assemble/assemble
                           (one-img-book "/etc/passwd") {}))]
      (is (= :smia.html.assemble/unsafe-resource-path (:error/type d))))))

(deftest safe-relative-image-paths-are-collected
  (is (= [{:src "images/sub/cat.png"}]
         (:resources (html-assemble/assemble
                       (one-img-book "images/sub/cat.png") {})))))

(deftest contents-entries-cover-chapters-and-sections
  (let [entries (:contents result)]
    (is (some #(and (= "chapter-01.html" (:href %)) (= 0 (:level %))) entries))
    (is (some #(and (= "chapter-01.html#sec-a" (:href %)) (= 1 (:level %)))
              entries))))

(deftest nested-location-nests-pages-and-relativizes-links
  (let [r     (html-assemble/assemble book {:location html-assemble/nested-location})
        files (set (map :file (:pages r)))]
    (testing "each page is the index of its own directory"
      (is (contains? files "index.html"))
      (is (contains? files "ch-one/index.html"))
      (is (contains? files "bibliography/index.html")))
    (testing "cross-page links are relative directory urls"
      (is (contains? (hrefs (:hiccup (page-in r "ch-one/index.html")))
                     "../ch-two/#sec-b")))
    (testing "the home contents links to the page directories"
      (is (contains? (hrefs (:hiccup (page-in r "index.html"))) "ch-one/")))
    (testing "contents entries carry directory urls"
      (is (some #(= "ch-one/" (:href %)) (:contents r))))))

(deftest custom-chrome-wraps-every-page
  (let [r (html-assemble/assemble
            book
            {:chrome {:page-wrap (fn [_ctx title main]
                                   [:html [:body [:h1 {} title]
                                           (into [:main {}] main)]])}})]
    (doseq [p (:pages r)]
      (is (= :html (first (:hiccup p)))))
    (is (= [:h1 {} "One"]
           (first (nodes (:hiccup (second (rest (:pages r))))
                         #(= :h1 (first %))))))))

(deftest extension-option-renames-pages-and-links
  (let [r (html-assemble/assemble book {:extension "xhtml"})]
    (is (= "index.xhtml" (:file (first (:pages r)))))
    (is (some #(= "chapter-01.xhtml" (:file %)) (:pages r)))
    (testing "cross-file hrefs follow the extension"
      (is (contains? (hrefs (:hiccup (nth (:pages r) 2)))
                     "chapter-02.xhtml#sec-b")))))

(def ^:private downloads
  {:base   "https://example.com/releases/latest/download"
   :assets [{:label "Screen PDF" :file "book-screen.pdf"
             :note "For reading on screen." :default true}
            {:label "Print PDF" :file "book-print.pdf" :note "For printing."}
            {:label "EPUB" :file "book.epub" :note "For e-readers."}]})

(deftest downloads-option-synthesizes-a-site-only-page
  (let [r  (html-assemble/assemble book {:downloads downloads})
        dl (page-in r "downloads.html")]
    (testing "the page leads the section pages, just after the home page"
      (is (= "downloads.html" (:file (second (:pages r))))))
    (testing "every asset is linked by its full release URL"
      (let [hs (hrefs (:hiccup dl))]
        (is (contains? hs "https://example.com/releases/latest/download/book-screen.pdf"))
        (is (contains? hs "https://example.com/releases/latest/download/book-print.pdf"))
        (is (contains? hs "https://example.com/releases/latest/download/book.epub"))))
    (testing "the default asset renders as the prominent primary link"
      (is (seq (nodes (:hiccup dl)
                      #(and (= :a (first %)) (= "default" (:class (second %))))))))
    (testing "the home table of contents links to the downloads page"
      (is (contains? (hrefs (:hiccup (first (:pages r)))) "downloads.html")))))

(deftest without-downloads-there-is-no-downloads-page
  (is (nil? (page-in result "downloads.html")))
  (is (not (contains? (hrefs (:hiccup (first (:pages result)))) "downloads.html"))))

(deftest parts-anchor-on-the-home-page
  (let [parted {:title "P" :author nil
                :numbering structure/default-numbering
                :references {}
                :sections
                [{:kind :part :index 1 :title "Part One"}
                 {:kind :chapter :part 1
                  :content [:chapter {:id :ch-a :title "A"}
                            [:p "of " [:xref {:to "part-1"}]]]}]}
        r      (html-assemble/assemble
                 (:manuscript (number/assign parted)) {})
        home   (:hiccup (first (:pages r)))]
    (is (seq (filter #(and (vector? %) (map? (second %))
                           (= "part-1" (:id (second %))))
                     (tree-seq vector? seq home)))
        "the part's anchor lives on the home page")
    (is (contains? (hrefs (:hiccup (second (:pages r)))) "index.html#part-1")
        "an xref to the part resolves to the home page anchor")))

(deftest edit-link-builds-from-edit-url-and-source-file
  (testing "an edit link joins the base url to the page's source file"
    (let [link (chrome/edit-link
                 {:edit-url "https://github.com/me/book/edit/main"
                  :page     {:source-file "chapters/01-intro.md"}})]
      (is (= :a (first link)))
      (is (= "https://github.com/me/book/edit/main/chapters/01-intro.md"
             (:href (second link))))
      (is (= "Edit this page" (last link)))))
  (testing "no edit-url means no link"
    (is (nil? (chrome/edit-link {:page {:source-file "x.md"}}))))
  (testing "a page with no source file (generated matter) gets no link"
    (is (nil? (chrome/edit-link {:edit-url "https://e.com" :page {}})))))

(deftest index-and-bibliography-collate-case-insensitively
  (let [idx   (#'html-assemble/index-body
                {"banana" ["i1"] "Apple" ["i2"] "Zebra" ["i3"] "árbol" ["i4"]}
                (fn [id] (str "#" id)))
        terms (mapv #(nth % 2) idx)
        bib   (#'html-assemble/bibliography-body
                {:s {:author "Smith" :year 2020}
                 :a {:author "Alpha" :year 2019}
                 :b {:author "brown" :year 2021}})
        texts (mapv #(nth % 2) bib)]
    (is (= ["Apple" "árbol" "banana" "Zebra"] terms))
    (is (= ["Alpha. 2019." "brown. 2021." "Smith. 2020."] texts))))
