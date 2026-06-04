(ns smia.site.search-index-test
  (:require
   [smia.site.search-index :as idx]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private specs
  [{:kind :chapter :id "ch-one" :title "One" :number "1" :url "part-1/ch-one/"
    :body [[:h2 {:id :sec-a} "1.1 " "Alpha"]
           [:p "Prose about " [:em "things"] "."]
           [:figure {:id "fig-1" :number "1" :label "Figure 1"
                     :caption "The pipeline"}
            [:img {:src "p.svg" :alt "pipeline"}]]
           [:p "More" [:index {:term "Determinism" :id "idx-1"}] " prose."]]}
   {:kind :matter :id "preface" :title "Preface" :url "preface/"
    :body [[:p "Welcome."]]}])

(def ^:private index (idx/index specs))

(deftest pages-become-entries-with-numbered-titles
  (let [pages (filter #(= "chapter" (:kind %)) (:entries index))]
    (is (= [{:kind "chapter" :title "1  One" :url "part-1/ch-one/"
             :text "Prose about things. More prose."}]
           (mapv #(update % :text str) pages)))))

(deftest headings-floats-and-terms-become-anchored-entries
  (let [by-kind (group-by :kind (:entries index))]
    (is (= [{:kind "section" :title "1.1 Alpha" :url "part-1/ch-one/#sec-a"}]
           (get by-kind "section")))
    (is (= [{:kind "figure" :title "Figure 1 — The pipeline"
             :url "part-1/ch-one/#fig-1"}]
           (get by-kind "figure")))
    (is (= [{:kind "term" :title "Determinism" :url "part-1/ch-one/#idx-1"}]
           (get by-kind "term")))))

(deftest kinds-carry-labels-in-display-order-for-present-kinds-only
  (is (= [{:kind "chapter" :label "Chapters"}
          {:kind "matter" :label "Pages"}
          {:kind "section" :label "Sections"}
          {:kind "figure" :label "Figures"}
          {:kind "term" :label "Index terms"}]
         (:kinds index))))

(deftest json-is-deterministic-with-sorted-keys
  (let [json (idx/index-json index)]
    (is (= json (idx/index-json (idx/index specs))))
    (is (str/starts-with? json "{\"entries\":["))
    (testing "object keys are sorted"
      (is (str/includes? json "{\"kind\":\"chapter\",\"label\":\"Chapters\"}")))))

(deftest json-escapes-strings
  (let [json (idx/index-json
               {:kinds   [{:kind "chapter" :label "Ch\"s"}]
                :entries [{:kind "chapter" :title "a\\b\nc" :url "x/" :text "t"}]})]
    (is (str/includes? json "\"label\":\"Ch\\\"s\""))
    (is (str/includes? json "\"title\":\"a\\\\b\\nc\""))))
