(ns smia.html.links-test
  (:require
   [smia.error :as error]
   [smia.html.links :as links]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [thunk]
  (try (thunk) nil (catch Exception e (error/data e))))

(deftest anchor-ids-collects-every-id-in-a-tree
  (is (= #{"ch-intro" "sec-a" "fig-1" "idx-1"}
         (links/anchor-ids
           [:chapter {:id :ch-intro :title "Intro"}
            [:h2 {:id "sec-a"} "Section"]
            [:p "see " [:index {:id "idx-1" :term "cats"}]]
            [:figure {:id "fig-1" :caption "A"} [:img {:src "x" :alt "y"}]]]))))

(deftest anchor-ids-ignores-non-attr-maps-and-text
  (is (= #{} (links/anchor-ids [:p "plain " [:strong "text"]]))))

(deftest table-maps-ids-to-files
  (let [t (links/table
            [{:file "chapter-01.html"
              :content [:chapter {:id :ch-one} [:h2 {:id "sec-a"} "A"]]}
             {:file "bibliography.html" :ids ["ref-smith-2020"]}])]
    (is (= "chapter-01.html" (get t "ch-one")))
    (is (= "chapter-01.html" (get t "sec-a")))
    (is (= "bibliography.html" (get t "ref-smith-2020")))))

(deftest duplicate-anchor-ids-across-files-throw
  (let [d (catch-data
            #(links/table [{:file "a.html" :ids ["dup"]}
                           {:file "b.html" :ids ["dup"]}]))]
    (is (= :smia.html.links/duplicate-id (:error/type d)))))

(deftest resolver-resolves-same-file-to-bare-fragment
  (let [resolve (links/resolver {"sec-a" "chapter-01.html"})]
    (is (= "#sec-a" (resolve "sec-a" "chapter-01.html")))))

(deftest resolver-resolves-cross-file-to-file-and-fragment
  (let [resolve (links/resolver {"sec-a" "chapter-01.html"})]
    (is (= "chapter-01.html#sec-a" (resolve "sec-a" "chapter-02.html")))))

(deftest resolver-throws-on-unknown-id
  (let [resolve (links/resolver {})
        d       (catch-data #(resolve "nope" "x.html"))]
    (is (= :smia.html.links/unknown-id (:error/type d)))
    (is (= "nope" (:id (:error/context d))))))
