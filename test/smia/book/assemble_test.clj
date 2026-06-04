(ns smia.book.assemble-test
  (:require
   [smia.book.assemble :as assemble]
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.fo.expand :as expand]
   [smia.fo.serialize :as ser]
   [smia.theme.compile :as theme]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def the-theme
  (theme/compile-theme {:color {} :type {} :spacing {} :layout {}} :print))

(def manuscript
  {:title    "A Book"
   :author   "An Author"
   :numbering structure/default-numbering
   :sections [{:kind :chapter
               :content [:chapter {:id :intro :title "Introduction"}
                         [:p "Welcome. See " [:xref {:to :config}] "."]]}
              {:kind :chapter
               :content [:chapter {:id :config :title "Configuration"}
                         [:p "Set things up."]
                         [:h2 {:id :keys} "Keys"]
                         [:p "Back to " [:xref {:to :intro} "the start"] "."]]}]})

(defn- tag= [t] (fn [n] (and (vector? n) (= t (first n)))))

(defn- find-all [tag tree]
  (filter (tag= tag) (tree-seq vector? seq tree)))

(deftest assembles-to-fo-root
  (let [out (assemble/assemble manuscript the-theme)]
    (is (= :fo/root (first out)))
    (is (= 1 (count (find-all :fo/layout-master-set out))))))

(deftest one-page-sequence-per-chapter-plus-front-matter
  (let [out (assemble/assemble manuscript the-theme)
        seqs (find-all :fo/page-sequence out)]
    (is (= 3 (count seqs)) "front matter + two chapters")))

(deftest table-of-contents-has-leader-and-citation-per-chapter
  (let [out (assemble/assemble manuscript the-theme)
        citations (find-all :fo/page-number-citation out)
        leaders   (find-all :fo/leader out)]
    (is (<= 2 (count leaders)) "a dotted leader per TOC entry")
    (is (some #(= "intro" (:ref-id (second %))) citations))
    (is (some #(= "config" (:ref-id (second %))) citations))
    (testing "leaders stretch to 100% so the page number sits flush right"
      (is (every? #(= "100%" (:leader-length.maximum (second %))) leaders)
          "a fixed-length leader would spill slack into the title spacing"))))

(deftest bookmark-tree-lists-chapters
  (let [out  (assemble/assemble manuscript the-theme)
        tree (first (find-all :fo/bookmark-tree out))
        tops (filter (tag= :fo/bookmark) (rest tree))]
    (is (= 1 (count (find-all :fo/bookmark-tree out))))
    (is (= #{"intro" "config"}
           (set (map #(:internal-destination (second %)) tops)))
        "chapters are the top-level bookmarks")
    (testing "the config chapter's :keys section nests beneath it"
      (let [config (first (filter #(= "config" (:internal-destination (second %))) tops))]
        (is (= #{"keys"}
               (set (map #(:internal-destination (second %))
                         (filter (tag= :fo/bookmark) (rest config))))))))))

(deftest running-head-markers-carry-chapter-titles
  (let [out (assemble/assemble manuscript the-theme)
        markers (find-all :fo/marker out)
        retrieves (find-all :fo/retrieve-marker out)]
    (is (= #{"Introduction" "Configuration"} (set (map last markers))))
    (is (seq retrieves) "running heads retrieve the marker")))

(deftest chapter-heading-carries-id-for-xref-targets
  (let [out (assemble/assemble manuscript the-theme)
        blocks (find-all :fo/block out)
        ids    (keep #(:id (second %)) blocks)]
    (is (contains? (set ids) "intro"))
    (is (contains? (set ids) "config"))))

(deftest assembled-tree-expands-and-serializes-end-to-end
  (testing "the sugar bodies expand and the whole document serializes"
    (let [out (assemble/assemble manuscript the-theme)
          xml (ser/serialize (expand/expand out (:style the-theme)))]
      (is (str/starts-with? xml "<?xml"))
      (is (str/includes? xml "<fo:root"))
      (is (str/includes? xml "Introduction"))
      (is (str/includes? xml "internal-destination=\"config\"")
          "the xref expanded to a link to the config chapter"))))

;; --- typed document structure (parts, matter, appendices) -----------------

(defn- chapter [id title & body]
  (into [:chapter {:id id :title title}] body))

(def structured
  {:title    "A Structured Book"
   :author   "An Author"
   :numbering structure/default-numbering
   :sections [{:kind :matter :matter :front :role :preface
               :content (chapter :preface "Preface" [:p "Before we begin."])}
              {:kind :part :title "Foundations" :index 0}
              {:kind :chapter :part 0
               :content (chapter :intro "Introduction" [:p "Hi."])}
              {:kind :chapter :part 0
               :content (chapter :model "The Model" [:p "Data."])}
              {:kind :part :title "Practice" :index 1}
              {:kind :chapter :part 1
               :content (chapter :build "Building" [:p "Go."])}
              {:kind :appendix
               :content (chapter :glossary "Glossary" [:p "Terms."])}
              {:kind :matter :matter :back :role :bibliography :generated true}]})

(defn- page-sequences [out] (find-all :fo/page-sequence out))

(deftest typed-structure-emits-a-sequence-per-section
  (let [out  (assemble/assemble structured the-theme)
        seqs (page-sequences out)]
    ;; title+TOC furniture, preface, 2 part dividers, 3 chapters,
    ;; appendix, bibliography = 9
    (is (= 9 (count seqs)))))

(deftest part-dividers-show-their-titles
  (let [out    (assemble/assemble structured the-theme)
        blocks (find-all :fo/block out)
        ids    (set (keep #(:id (second %)) blocks))]
    (is (contains? ids "part-0"))
    (is (contains? ids "part-1"))
    (is (some #(= "Foundations" (last %)) blocks))
    (is (some #(= "Practice" (last %)) blocks))))

(deftest front-matter-is-roman-and-the-body-resets-to-arabic
  (let [out  (assemble/assemble structured the-theme)
        seqs (page-sequences out)
        attrs (map second seqs)]
    (testing "a front-matter section is numbered in roman"
      (is (some #(= "i" (:format %)) attrs)))
    (testing "the first body section resets to arabic page 1"
      (is (some #(and (= "1" (:format %)) (= "1" (:initial-page-number %))) attrs)))))

(deftest bookmark-tree-nests-chapters-under-parts
  (let [out  (assemble/assemble structured the-theme)
        tree (first (find-all :fo/bookmark-tree out))
        ;; top-level bookmarks are the direct children of the tree
        tops (filter (tag= :fo/bookmark) (rest tree))
        part0 (first (filter #(= "part-0" (:internal-destination (second %))) tops))]
    (is (some? part0))
    (let [child-bms (filter (tag= :fo/bookmark) (rest part0))]
      (is (= #{"intro" "model"}
             (set (map #(:internal-destination (second %)) child-bms)))
          "the part's chapters nest beneath it"))
    (testing "the preface and appendix are top-level bookmarks"
      (is (contains? (set (map #(:internal-destination (second %)) tops))
                     "preface"))
      (is (contains? (set (map #(:internal-destination (second %)) tops))
                     "glossary")))))

(deftest recto-parity-starts-body-sections-on-an-odd-page-in-print
  (let [out  (assemble/assemble
              (assoc structured :numbering
                     (assoc structure/default-numbering :start-chapters-on :recto))
              the-theme)
        attrs (map second (page-sequences out))]
    (is (some #(= "auto-odd" (:initial-page-number %)) attrs)))
  (testing "screen layout inserts no parity blanks"
    (let [screen (theme/compile-theme {:color {} :type {} :spacing {} :layout {}} :screen)
          out    (assemble/assemble
                  (assoc structured :numbering
                         (assoc structure/default-numbering :start-chapters-on :recto))
                  screen)
          attrs  (map second (page-sequences out))]
      (is (not-any? #(= "auto-odd" (:initial-page-number %)) attrs)))))

;; --- running heads and footers -------------------------------------------

(deftest print-has-distinct-recto-and-verso-running-content
  (let [out   (assemble/assemble manuscript the-theme)
        flows (set (map #(:flow-name (second %)) (find-all :fo/static-content out)))]
    (is (= #{"head-recto" "head-verso" "foot-recto" "foot-verso"} flows)
        "print emits separate header/footer content per page parity")
    (testing "verso shows the chapter, recto shows the section, both number the page"
      (let [sc (fn [name] (first (filter #(= name (:flow-name (second %)))
                                         (find-all :fo/static-content out))))
            classes (fn [name] (set (map #(:retrieve-class-name (second %))
                                         (find-all :fo/retrieve-marker (sc name)))))]
        (is (= #{"chapter-title"} (classes "head-verso")))
        (is (= #{"section-title"} (classes "head-recto")))
        (is (seq (find-all :fo/page-number (sc "foot-recto"))))))))

(deftest screen-keeps-a-single-symmetric-running-head
  (let [screen (theme/compile-theme {:color {} :type {} :spacing {} :layout {}} :screen)
        out    (assemble/assemble manuscript screen)
        flows  (set (map #(:flow-name (second %)) (find-all :fo/static-content out)))]
    (is (= #{"xsl-region-before" "xsl-region-after"} flows))))

(deftest running-heads-config-overrides-the-defaults
  (let [out (assemble/assemble (assoc manuscript :running-heads
                                      {:verso {:before :book-title}})
                               the-theme)
        verso (first (filter #(= "head-verso" (:flow-name (second %)))
                             (find-all :fo/static-content out)))]
    (is (some #{"A Book"} (tree-seq vector? seq verso))
        "the verso header now carries the book title")))

(defn- footer [out name]
  (first (filter #(= name (:flow-name (second %)))
                 (find-all :fo/static-content out))))

(defn- text-of [tree]
  (apply str (filter string? (tree-seq vector? seq tree))))

(deftest licensee-stamps-a-footer-notice-on-every-page
  (let [out (assemble/assemble (assoc manuscript :licensee "Ada Lovelace <ada@x.io>")
                               the-theme)]
    (testing "both print footers carry the licensee notice"
      (is (.contains (text-of (footer out "foot-recto"))
                     "Licensed to Ada Lovelace <ada@x.io>"))
      (is (.contains (text-of (footer out "foot-verso"))
                     "Licensed to Ada Lovelace <ada@x.io>")))
    (testing "the page number is still in the footer"
      (is (seq (find-all :fo/page-number (footer out "foot-recto")))))))

(deftest licensee-rides-the-screen-footer-too
  (let [screen (theme/compile-theme {:color {} :type {} :spacing {} :layout {}} :screen)
        out    (assemble/assemble (assoc manuscript :licensee "Ada") screen)]
    (is (.contains (text-of (footer out "xsl-region-after")) "Licensed to Ada"))))

(deftest no-licensee-leaves-the-footer-unstamped
  (let [out (assemble/assemble manuscript the-theme)]
    (is (not (.contains (text-of (footer out "foot-recto")) "Licensed to")))))

;; --- multi-level table of contents and nested outline --------------------

(def toc-src
  {:title  "T" :author "A" :numbering structure/default-numbering
   :sections [{:kind :part :title "Foundations" :index 0}
              {:kind :chapter :part 0
               :content (chapter :intro "Introduction"
                                 [:h2 {:id :setup} "Setup"]
                                 [:p "x"])}
              {:kind :appendix :content (chapter :gloss "Glossary")}]})

(defn- toc-link-text [out id]
  (->> (find-all :fo/basic-link out)
       (filter #(= id (:internal-destination (second %))))
       first
       (drop 2)
       (apply str)))

(deftest table-of-contents-is-multilevel-and-numbered
  (let [out (assemble/assemble (:manuscript (number/assign toc-src)) the-theme)]
    (testing "the part, its chapter, the chapter's section, and the appendix all link"
      (is (= "Part I  Foundations" (toc-link-text out "part-0")))
      (is (= "1  Introduction" (toc-link-text out "intro")))
      (is (= "Setup" (toc-link-text out "setup")))
      (is (= "A  Glossary" (toc-link-text out "gloss"))))))

(deftest outline-nests-sections-under-chapters
  (let [out  (assemble/assemble (:manuscript (number/assign toc-src)) the-theme)
        bms  (find-all :fo/bookmark out)
        intro (first (filter #(= "intro" (:internal-destination (second %))) bms))
        kids (filter (tag= :fo/bookmark) (rest intro))]
    (is (= #{"setup"} (set (map #(:internal-destination (second %)) kids)))
        "the chapter's section is a nested bookmark")))

(deftest generated-bibliography-and-index-render
  (let [book {:title "B" :author "A" :numbering structure/default-numbering
              :references {:smith2020 {:author "Smith" :title "On Data" :year 2020}}
              :index {"Data" ["idx-1" "idx-2"] "FOP" ["idx-3"]}
              :sections [{:kind :chapter :content (chapter :a "A" [:p "x"])}
                         {:kind :matter :matter :back :role :bibliography :generated true}
                         {:kind :matter :matter :back :role :index :generated true}]}
        out    (assemble/assemble book the-theme)
        blocks (find-all :fo/block out)
        ids    (set (keep #(:id (second %)) blocks))
        cites  (find-all :fo/page-number-citation out)]
    (testing "the bibliography entry is a citation target with formatted text"
      (is (contains? ids "ref-smith2020"))
      (is (some #(and (string? (last %)) (str/includes? (last %) "Smith. On Data. 2020."))
                blocks)))
    (testing "the index page-cites each mark's anchor"
      (is (some #(= "idx-1" (:ref-id (second %))) cites))
      (is (some #(= "idx-3" (:ref-id (second %))) cites)))))

(deftest linkless-theme-assembles-without-link-annotations
  ;; The print-x edition forbids PDF link annotations; the assembled
  ;; furniture (TOC, float lists) keeps text and page citations only.
  (let [linkless (assoc the-theme :links? false)
        out      (assemble/assemble (:manuscript (number/assign manuscript))
                                    linkless)]
    (is (empty? (find-all :fo/basic-link out)))
    (testing "TOC entries still resolve page numbers"
      (is (seq (find-all :fo/page-number-citation out))))))

(deftest generated-lists-of-floats-render-with-links-and-page-numbers
  (let [src  {:title "B" :author "A" :numbering structure/default-numbering
              :sections [{:kind :matter :matter :front :role :list-of-figures}
                         {:kind :matter :matter :front :role :list-of-tables}
                         {:kind :matter :matter :front :role :list-of-listings}
                         {:kind :chapter
                          :content (chapter :a "A"
                                            [:figure {:caption "A diagram"} [:img {:src "d.png"}]]
                                            [:table {:caption "A grid"} [:tr [:td "x"]]]
                                            [:pre {:lang :clojure :caption "A snippet"} "(+ 1 2)"])}
                         {:kind :chapter
                          :content (chapter :b "B"
                                            [:figure {:caption "A flow"} [:img {:src "f.png"}]])}]}
        out  (assemble/assemble (:manuscript (number/assign src)) the-theme)
        links (find-all :fo/basic-link out)
        cites (find-all :fo/page-number-citation out)
        link-text (fn [id] (some #(when (= id (:internal-destination (second %)))
                                    (last %))
                                 links))]
    (testing "the list of figures links each figure, in order, to its anchor"
      (is (= "Figure 1. A diagram" (link-text "fig-1")))
      (is (= "Figure 2. A flow" (link-text "fig-2"))))
    (testing "the list of tables and listings link their floats"
      (is (= "Table 1. A grid" (link-text "tbl-1")))
      (is (= "Listing 1. A snippet" (link-text "lst-1"))))
    (testing "each list entry resolves a page number against its anchor"
      (is (some #(= "fig-1" (:ref-id (second %))) cites))
      (is (some #(= "tbl-1" (:ref-id (second %))) cites))
      (is (some #(= "lst-1" (:ref-id (second %))) cites)))))

(deftest an-author-title-overrides-a-generated-role-title
  (let [src  {:title "B" :author "A" :numbering structure/default-numbering
              :sections [{:kind :matter :matter :front :role :list-of-figures
                          :title "Figures"}
                         {:kind :chapter
                          :content (chapter :a "A"
                                            [:figure {:caption "D"} [:img {:src "d.png"}]])}]}
        out  (assemble/assemble (:manuscript (number/assign src)) the-theme)
        blocks (find-all :fo/block out)]
    (is (some #(= "Figures" (last %)) blocks)
        "the author's :title replaces the default \"List of Figures\"")))

(deftest generated-back-matter-renders-a-titled-placeholder
  (let [out    (assemble/assemble structured the-theme)
        blocks (find-all :fo/block out)
        ids    (set (keep #(:id (second %)) blocks))]
    (is (contains? ids "bibliography"))
    (is (some #(= "Bibliography" (last %)) blocks))))
