(ns smia.book.conditional-test
  (:require
   [smia.book.conditional :as cond]
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

;; --- condition evaluation ---------------------------------------------------

(deftest eval-condition-covers-the-vocabulary
  (let [ctx {:edition :epub :draft true :licensee "ACME"}]
    (is (cond/eval-condition {:defined :draft} ctx))
    (is (not (cond/eval-condition {:defined :absent} ctx)))
    (is (cond/eval-condition {:equals [:edition :epub]} ctx))
    (is (not (cond/eval-condition {:equals [:edition :print]} ctx)))
    (is (cond/eval-condition {:any-of [{:equals [:edition :print]}
                                       {:equals [:edition :epub]}]} ctx))
    (is (cond/eval-condition {:all-of [{:defined :draft}
                                       {:equals [:licensee "ACME"]}]} ctx))
    (is (cond/eval-condition {:not {:equals [:edition :print]}} ctx))))

(deftest invalid-condition-is-a-structured-error
  (let [d (catch-data #(cond/eval-condition {:bogus 1} {}))]
    (is (= :smia.book.conditional/invalid-condition (:error/type d)))))

(deftest mentions-edition-detects-edition-references
  (is (cond/mentions-edition? {:equals [:edition :epub]}))
  (is (cond/mentions-edition? {:not {:equals [:edition :print]}}))
  (is (cond/mentions-edition? {:any-of [{:defined :draft} {:defined :edition}]}))
  (is (not (cond/mentions-edition? {:equals [:licensee "ACME"]})))
  (is (not (cond/mentions-edition? {:all-of [{:defined :draft}]}))))

;; --- single-form pruning ----------------------------------------------------

(deftest prune-splices-or-drops
  (testing "a held condition splices its body"
    (is (= [:chapter {} [:p "a"] [:p "b"]]
           (cond/prune [:chapter {} [:when {:defined :x} [:p "a"] [:p "b"]]]
                       {:x 1} nil))))
  (testing "a failed condition drops its body"
    (is (= [:chapter {} [:p "after"]]
           (cond/prune [:chapter {} [:when {:defined :x} [:p "gone"]] [:p "after"]]
                       {} nil))))
  (testing "skip? leaves a matching condition in place, pruning within it"
    (is (= [:chapter {} [:when {:equals [:edition :epub]} [:p "a"]]]
           (cond/prune [:chapter {} [:when {:equals [:edition :epub]}
                                     [:when {:defined :x} [:p "a"]]]]
                       {:x 1} cond/mentions-edition?)))))

(deftest prune-handles-nested-conditions
  (is (= [:chapter {} [:p "deep"]]
         (cond/prune [:chapter {} [:when {:defined :a}
                                   [:when {:defined :b} [:p "deep"]]]]
                     {:a 1 :b 1} nil))))

;; --- manuscript-wide pruning ------------------------------------------------

(defn- manuscript [body]
  (let [content (into [:chapter {:id :intro :title "Intro"}] body)]
    {:numbering structure/default-numbering
     :sections  [{:kind :chapter :content content}]
     :chapters  [content]}))

(deftest a-manuscript-with-no-when-is-untouched
  (let [m (manuscript [[:p "plain"]])]
    (is (identical? m (cond/prune-manuscript m {} {} {} nil)))))

(deftest non-edition-pruning-resolves-now-and-defers-edition
  (let [m   (manuscript [[:when {:defined :draft} [:p "draft note"]]
                         [:when {:equals [:edition :epub]} [:p "epub only"]]])
        out (cond/prune-manuscript m {:draft true} {} {}
                                   cond/mentions-edition?)]
    (testing "the non-edition branch resolves; the edition branch is left"
      (is (= [:chapter {:id :intro :title "Intro"}
              [:p "draft note"]
              [:when {:equals [:edition :epub]} [:p "epub only"]]]
             (first (:chapters out)))))
    (is (cond/edition-dependent? out))))

(deftest edition-pruning-keeps-the-matching-branch
  (let [m   (manuscript [[:when {:equals [:edition :epub]} [:p "epub"]]
                         [:when {:equals [:edition :screen]} [:p "screen"]]])
        epub (cond/prune-manuscript m {} {} {:edition :epub} nil)]
    (is (= [:chapter {:id :intro :title "Intro"} [:p "epub"]]
           (first (:chapters epub))))))

;; --- the apparatus moat -----------------------------------------------------

(deftest edition-conditional-numbering-diverges-per-edition
  ;; A figure that only appears in the epub edition is Figure 1 there and
  ;; absent from screen — proving pruning precedes numbering.
  (let [m       (manuscript
                  [[:when {:equals [:edition :epub]}
                    [:figure {:id :extra :caption "Bonus"} [:img {:src "b.png"}]]]
                   [:figure {:id :main :caption "Main"} [:img {:src "m.png"}]]])
        number  (fn [edition]
                  (-> (cond/prune-manuscript m {} {} {:edition edition} nil)
                      number/assign :registry))
        epub    (number :epub)
        screen  (number :screen)]
    (is (= "Figure 1" (:label (get epub "extra"))))
    (is (= "Figure 2" (:label (get epub "main"))) "the bonus figure shifts main to 2")
    (is (nil? (get screen "extra")) "the bonus figure is absent from screen")
    (is (= "Figure 1" (:label (get screen "main"))) "so main is Figure 1 there")))

(deftest a-book-without-edition-conditionals-numbers-once
  (let [m (manuscript [[:when {:defined :draft} [:p "x"]]])]
    (is (not (cond/edition-dependent?
               (cond/prune-manuscript m {:draft true} {} {}
                                      cond/mentions-edition?))))))

(deftest conditional-inside-an-attribute-value-is-an-error
  (let [d (catch-data
            #(cond/prune [:chapter {}
                          [:figure {:caption [:when {:defined :x} "c"]}
                           [:p "b"]]]
                         {} nil))]
    (is (= :smia.book.conditional/conditional-in-attribute (:error/type d)))
    (is (= :caption (get-in d [:error/context :attribute])))))
