(ns clj-book.book.assemble-test
  (:require
   [clj-book.book.assemble :as assemble]
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
    (is (some #(= "config" (:ref-id (second %))) citations))))

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
