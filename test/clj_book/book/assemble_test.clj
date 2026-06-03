(ns clj-book.book.assemble-test
  (:require
   [clj-book.book.assemble :as assemble]
   [clj-book.book.structure :as structure]
   [clj-book.book.theme :as theme]
   [clj-book.error :as error]
   [clj-book.fo.expand :as expand]
   [clj-book.fo.serialize :as ser]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def the-theme
  (theme/compile-theme {:color {} :type {} :spacing {} :layout {}} :print))

(def manuscript
  {:title    "A Book"
   :author   "An Author"
   :chapters [[:chapter {:id :intro :title "Introduction"}
               [:p "Welcome. See " [:xref {:to :config}] "."]]
              [:chapter {:id :config :title "Configuration"}
               [:p "Set things up."]
               [:h2 {:id :keys} "Keys"]
               [:p "Back to " [:xref {:to :intro} "the start"] "."]]]})

(defn- tag= [t] (fn [n] (and (vector? n) (= t (first n)))))

(defn- find-all [tag tree]
  (filter (tag= tag) (tree-seq vector? seq tree)))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

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
  (let [out (assemble/assemble manuscript the-theme)
        bms (find-all :fo/bookmark out)]
    (is (= 1 (count (find-all :fo/bookmark-tree out))))
    (is (= #{"intro" "config"}
           (set (map #(:internal-destination (second %)) bms))))))

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

(deftest unresolved-xref-is-a-hard-error
  (let [bad (assoc manuscript :chapters
                   [[:chapter {:id :only :title "Only"}
                     [:p "See " [:xref {:to :nowhere}] "."]]])
        d   (catch-data #(assemble/assemble bad the-theme))]
    (is (= :clj-book.book.assemble/unresolved-xref (:error/type d)))
    (is (= ["nowhere"] (:missing (:error/context d))))))

(deftest xref-to-an-inner-heading-id-resolves
  (testing ":keys is defined by an inner [:h2 {:id :keys}] heading"
    (let [m (update-in manuscript [:chapters 0] conj
                       [:p "Jump to " [:xref {:to :keys}] "."])]
      (is (vector? (assemble/assemble m the-theme))))))

(deftest malformed-chapter-is-a-hard-error
  (is (= :clj-book.book.assemble/invalid-chapter
         (:error/type (catch-data
                        #(assemble/assemble {:chapters [[:p "not a chapter"]]}
                                            the-theme)))))
  (is (= :clj-book.book.assemble/missing-chapter-id
         (:error/type (catch-data
                        #(assemble/assemble {:chapters [[:chapter {:title "T"} "x"]]}
                                            the-theme))))))

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
  (testing "screen profile inserts no parity blanks"
    (let [screen (theme/compile-theme {:color {} :type {} :spacing {} :layout {}} :screen)
          out    (assemble/assemble
                  (assoc structured :numbering
                         (assoc structure/default-numbering :start-chapters-on :recto))
                  screen)
          attrs  (map second (page-sequences out))]
      (is (not-any? #(= "auto-odd" (:initial-page-number %)) attrs)))))

(deftest generated-back-matter-renders-a-titled-placeholder
  (let [out    (assemble/assemble structured the-theme)
        blocks (find-all :fo/block out)
        ids    (set (keep #(:id (second %)) blocks))]
    (is (contains? ids "bibliography"))
    (is (some #(= "Bibliography" (last %)) blocks))))
