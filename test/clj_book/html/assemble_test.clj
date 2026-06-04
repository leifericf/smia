(ns clj-book.html.assemble-test
  (:require
   [clj-book.book.number :as number]
   [clj-book.book.structure :as structure]
   [clj-book.error]
   [clj-book.html.assemble :as html-assemble]
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

(defn- page [file]
  (first (filter #(= file (:file %)) (:pages result))))

(defn- nodes
  "Every vector node in `tree` for which `pred` is true."
  [tree pred]
  (filter #(and (vector? %) (pred %)) (tree-seq vector? seq tree)))

(defn- hrefs [tree]
  (set (keep #(when (and (vector? %) (= :a (first %)) (map? (second %)))
                (:href (second %)))
             (tree-seq vector? seq tree))))

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
    (is (contains? (hrefs ch) "#fnref-1") "the backlink returns to the text")))

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
  (try (thunk) nil (catch Exception e (clj-book.error/data e))))

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
      (is (= :clj-book.html.assemble/unsafe-resource-path (:error/type d)))
      (is (= "../secret.png" (:src (:error/context d))))))
  (testing "a nested parent-escaping segment is caught too"
    (let [d (catch-data #(html-assemble/assemble
                           (one-img-book "images/../../secret.png") {}))]
      (is (= :clj-book.html.assemble/unsafe-resource-path (:error/type d)))))
  (testing "an absolute path is rejected"
    (let [d (catch-data #(html-assemble/assemble
                           (one-img-book "/etc/passwd") {}))]
      (is (= :clj-book.html.assemble/unsafe-resource-path (:error/type d))))))

(deftest safe-relative-image-paths-are-collected
  (is (= [{:src "images/sub/cat.png"}]
         (:resources (html-assemble/assemble
                       (one-img-book "images/sub/cat.png") {})))))

(deftest contents-entries-cover-chapters-and-sections
  (let [entries (:contents result)]
    (is (some #(and (= "chapter-01.html" (:href %)) (= 0 (:level %))) entries))
    (is (some #(and (= "chapter-01.html#sec-a" (:href %)) (= 1 (:level %)))
              entries))))

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
