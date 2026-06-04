(ns smia.book.linkcheck-test
  (:require
   [smia.book.linkcheck :as linkcheck]
   [clojure.test :refer [deftest is testing]]))

(deftest a-dead-anchor-link-is-reported
  (let [chapters [[:chapter {:id :intro :title "Intro"}
                   [:h2 {:id :setup} "Setup"]
                   [:p "see " [:a {:href "#setup"} "setup"]]
                   [:p "see " [:a {:href "#ghost"} "nowhere"]]]]
        warnings (linkcheck/dead-links chapters {"intro" {}})]
    (is (= 1 (count warnings)))
    (is (= :smia.book.linkcheck/dead-internal-link (:warning/type (first warnings))))
    (is (= "ghost" (:warning/target (first warnings))))))

(deftest a-link-to-a-registry-id-resolves
  (let [chapters [[:chapter {:id :a :title "A"}
                   [:p [:a {:href "#fig-1"} "the figure"]]]]]
    (is (empty? (linkcheck/dead-links chapters {"a" {} "fig-1" {}})))))

(deftest external-and-non-anchor-links-are-ignored
  (let [chapters [[:chapter {:id :a :title "A"}
                   [:p [:a {:href "https://example.com"} "ext"]]
                   [:p [:a {:href "other.html"} "rel"]]]]]
    (is (empty? (linkcheck/dead-links chapters {"a" {}})))))

(deftest html-hatch-anchor-links-are-checked-too
  (let [chapters [[:chapter {:id :a :title "A"}
                   [:html/a {:href "#missing"} "x"]]]]
    (testing "an :html/a with a dead anchor is reported"
      (is (= "missing" (:warning/target
                         (first (linkcheck/dead-links chapters {"a" {}}))))))))
