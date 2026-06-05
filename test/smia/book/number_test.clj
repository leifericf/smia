(ns smia.book.number-test
  (:require
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [clojure.test :refer [deftest is testing]]))

(defn- chapter-section [id title & body]
  {:kind :chapter :part nil
   :content (into [:chapter {:id id :title title}] body)})

(defn- assign [manuscript]
  (number/assign (merge {:numbering structure/default-numbering} manuscript)))

(defn- content-for [manuscript id]
  (->> (:sections (:manuscript manuscript))
       (keep :content)
       (filter #(= id (:id (second %))))
       first))

(deftest chapters-are-numbered-arabic-by-default
  (let [out (assign {:sections [(chapter-section :intro "Introduction")
                                (chapter-section :config "Configuration")]})
        reg (:registry out)]
    (is (= {:kind :chapter :number "1" :label "Chapter 1" :title "Introduction"}
           (get reg "intro")))
    (is (= "2" (:number (get reg "config"))))
    (testing "the label is annotated onto the chapter for the heading"
      (is (= "Chapter 1" (:label (second (content-for out :intro))))))))

(deftest parts-number-roman-and-chapters-continue-across-them
  (let [out (assign {:sections [{:kind :part :title "One" :index 0}
                                (assoc (chapter-section :a "A") :part 0)
                                (assoc (chapter-section :b "B") :part 0)
                                {:kind :part :title "Two" :index 1}
                                (assoc (chapter-section :c "C") :part 1)]})
        reg (:registry out)]
    (is (= "Part I" (:label (get reg "part-0"))))
    (is (= "Part II" (:label (get reg "part-1"))))
    (is (= ["1" "2" "3"] (map #(:number (get reg %)) ["a" "b" "c"]))
        "chapter numbering is continuous, independent of parts")))

(deftest appendices-are-lettered-independently
  (let [out (assign {:sections [(chapter-section :intro "Intro")
                                {:kind :appendix :content [:chapter {:id :gloss :title "Glossary"}]}
                                {:kind :appendix :content [:chapter {:id :refs :title "References"}]}]})
        reg (:registry out)]
    (is (= "1" (:number (get reg "intro"))))
    (is (= {:kind :appendix :number "A" :label "Appendix A" :title "Glossary"}
           (get reg "gloss")))
    (is (= "Appendix B" (:label (get reg "refs"))))))

(deftest appendix-lettering-continues-past-z-with-double-letters
  (let [apps (for [i (range 28)]
               {:kind :appendix
                :content [:chapter {:id (keyword (str "app" i))
                                    :title (str "App " i)}]})
        out  (assign {:sections (vec apps)})
        reg  (:registry out)]
    (is (= "Appendix Z" (:label (get reg "app25"))))
    (is (= "Appendix AA" (:label (get reg "app26"))))
    (is (= "Appendix AB" (:label (get reg "app27"))))))

(deftest childless-xref-is-rewritten-to-the-composed-label
  (let [out (assign {:sections [(chapter-section :intro "Introduction"
                                                 [:p "See " [:xref {:to :config}] "."])
                                (chapter-section :config "Configuration")]})
        para (nth (content-for out :intro) 2)]
    (is (= [:p "See "
            [:xref {:to :config :label "Chapter 2" :title "Configuration" :kind :chapter}]
            "."]
           para))))

(deftest xref-with-author-text-is-left-untouched
  (let [out (assign {:sections [(chapter-section :intro "Introduction"
                                                 [:p [:xref {:to :config} "the config chapter"]])
                                (chapter-section :config "Configuration")]})
        para (nth (content-for out :intro) 2)]
    (is (= [:p [:xref {:to :config} "the config chapter"]] para))))

(deftest unnumbered-sections-resolve-to-their-title
  (let [out (assign {:sections [(chapter-section :intro "Introduction"
                                                 [:h2 {:id :setup} "Getting Set Up"]
                                                 [:p "Jump to " [:xref {:to :setup}] "."])]})
        reg  (:registry out)
        para (nth (content-for out :intro) 3)]
    (is (= {:kind :section :title "Getting Set Up"} (get reg "setup")))
    (is (= [:p "Jump to "
            [:xref {:to :setup :title "Getting Set Up" :kind :section}]
            "."]
           para))))

(deftest disabling-chapter-numbering-drops-the-number
  (let [out (assign {:numbering (assoc structure/default-numbering :chapters false)
                     :sections [(chapter-section :intro "Introduction")]})
        reg (:registry out)]
    (is (nil? (:number (get reg "intro"))))
    (is (nil? (:label (second (content-for out :intro)))))))

(deftest decimal-sections-number-within-the-chapter
  (let [out (assign {:numbering (assoc structure/default-numbering :sections true)
                     :sections [(chapter-section :intro "Introduction"
                                                 [:h2 {:id :a} "First"]
                                                 [:h2 {:id :b} "Second"])]})
        reg (:registry out)]
    (is (= "1.1" (:number (get reg "a"))))
    (is (= "1.2" (:number (get reg "b"))))))

(deftest figures-tables-and-listings-number-book-wide
  (let [out (assign {:sections
                     [(chapter-section :a "A"
                                       [:figure {:id :diagram :caption "A diagram"} [:img {:src "d.png"}]]
                                       [:table {:id :grid :caption "A grid"} [:tr [:td "x"]]])
                      (chapter-section :b "B"
                                       [:figure {:id :flow :caption "A flow"} [:img {:src "f.png"}]]
                                       [:pre {:lang :clojure :id :listing :caption "A listing"} "(+ 1 2)"])]})
        reg (:registry out)]
    (is (= {:kind :figure :number "1" :label "Figure 1" :title "A diagram"} (get reg "diagram")))
    (is (= "Figure 2" (:label (get reg "flow"))) "figures continue across chapters")
    (is (= "Table 1" (:label (get reg "grid"))))
    (is (= "Listing 1" (:label (get reg "listing")))) ))

(deftest floats-are-collected-in-document-order
  (let [out (assign {:sections
                     [(chapter-section :a "A"
                                       [:figure {:caption "A diagram"} [:img {:src "d.png"}]]
                                       [:table {:caption "A grid"} [:tr [:td "x"]]])
                      (chapter-section :b "B"
                                       [:pre {:lang :clojure :caption "A listing"} "(+ 1 2)"])]})
        floats (:floats (:manuscript out))]
    (is (= [{:kind :figure  :id "fig-1" :number "1" :label "Figure 1"  :title "A diagram"}
            {:kind :table   :id "tbl-1" :number "1" :label "Table 1"   :title "A grid"}
            {:kind :listing :id "lst-1" :number "1" :label "Listing 1" :title "A listing"}]
           floats)
        "every numbered float, in document order, with an anchor id")))

(deftest a-float-with-no-author-id-gets-a-synthesized-anchor
  (let [out (assign {:sections [(chapter-section :a "A"
                                                 [:figure {:caption "D"} [:img {:src "d.png"}]])]})
        fig (->> (content-for out :a) (tree-seq vector? seq)
                 (filter #(and (vector? %) (= :figure (first %)))) first)]
    (is (= "fig-1" (:id (second fig)))
        "the synthesized id is stamped onto the node so the float list can link to it")))

(deftest an-author-id-on-a-float-is-used-as-its-anchor
  (let [out (assign {:sections [(chapter-section :a "A"
                                                 [:figure {:id :diagram :caption "D"} [:img {:src "d.png"}]])]})
        floats (:floats (:manuscript out))
        fig    (->> (content-for out :a) (tree-seq vector? seq)
                    (filter #(and (vector? %) (= :figure (first %)))) first)]
    (is (= "diagram" (:id (first floats))) "an explicit id is the anchor in the float list")
    (is (= :diagram (:id (second fig))) "the author's keyword id is left untouched on the node")))

(deftest a-table-without-a-caption-is-not-numbered
  (let [out (assign {:sections [(chapter-section :a "A" [:table [:tr [:td "x"]]])]})]
    (is (empty? (filter #(= :table (:kind (val %))) (:registry out))))))

(deftest xref-to-a-figure-composes-its-label
  (let [out (assign {:sections [(chapter-section :a "A"
                                                 [:figure {:id :diagram :caption "D"} [:img {:src "d.png"}]]
                                                 [:p "See " [:xref {:to :diagram}] "."])]})
        para (nth (content-for out :a) 3)]
    (is (= [:xref {:to :diagram :label "Figure 1" :title "D" :kind :figure}]
           (nth para 2)))))

(deftest citations-resolve-against-the-references
  (let [out (number/assign
             {:numbering structure/default-numbering
              :references {:smith2020 {:author "Smith" :year 2020 :title "On Data"}}
              :sections [(chapter-section :a "A" [:p "As in " [:cite {:key :smith2020}] "."])]})
        para (nth (content-for out :a) 2)]
    (is (= [:p "As in "
            [:cite {:key :smith2020 :label "Smith 2020" :ref-id "ref-smith2020"}]
            "."]
           para))))

(deftest an-unknown-citation-is-a-hard-error
  (let [d (try (number/assign
                {:numbering structure/default-numbering :references {}
                 :sections [(chapter-section :a "A" [:p [:cite {:key :nope}]])]})
               nil
               (catch Exception e (smia.error/data e)))]
    (is (= :smia.book.number/unknown-citation (:error/type d)))))

(deftest an-unresolved-xref-is-a-hard-error
  (testing "an :xref to an id no target defines fails the numbering pass"
    (let [d (try (assign {:sections [(chapter-section :a "A"
                                                      [:p "See " [:xref {:to :nowhere}] "."])]})
                 nil
                 (catch Exception e (smia.error/data e)))]
      (is (= :smia.book.number/unresolved-xref (:error/type d)))
      (is (= :nowhere (:to (:error/context d)))))))

(defn- duplicate-id-error [manuscript]
  (try (assign manuscript) nil
       (catch Exception e (smia.error/data e))))

(deftest a-duplicate-anchor-id-is-a-hard-error
  (testing "the same heading :id in two chapters fails the numbering pass"
    (let [d (duplicate-id-error
              {:sections [(chapter-section :a "A" [:h2 {:id :setup} "Setup"])
                          (chapter-section :b "B" [:h2 {:id :setup} "Again"])]})]
      (is (= :smia.book.number/duplicate-id (:error/type d)))
      (is (= "setup" (:id (:error/context d))))))
  (testing "a heading id colliding with a captioned float id is caught"
    (let [d (duplicate-id-error
              {:sections [(chapter-section :a "A"
                            [:figure {:id :dup :caption "F"} [:img {:src "x"}]]
                            [:h2 {:id :dup} "Heading"])]})]
      (is (= :smia.book.number/duplicate-id (:error/type d)))))
  (testing "unique ids across the book still pass"
    (is (nil? (duplicate-id-error
                {:sections [(chapter-section :a "A" [:h2 {:id :one} "One"])
                            (chapter-section :b "B" [:h2 {:id :two} "Two"])]})))))

(deftest an-xref-to-an-inner-heading-id-resolves
  (testing ":keys is defined by an inner [:h2 {:id :keys}] heading, so it resolves"
    (let [out  (assign {:sections [(chapter-section :a "A"
                                                    [:h2 {:id :keys} "Keys"]
                                                    [:p "Jump to " [:xref {:to :keys}] "."])]})
          para (nth (content-for out :a) 3)]
      (is (= [:p "Jump to "
              [:xref {:to :keys :title "Keys" :kind :section}]
              "."]
             para)))))

(deftest index-marks-collect-terms-with-anchor-ids
  (let [out (number/assign
             {:numbering structure/default-numbering
              :sections [(chapter-section :a "A"
                                          [:p "x" [:index {:term "Data"}]]
                                          [:p "y" [:index {:term "Data"}]]
                                          [:p "z" [:index {:term "FOP"}]])]})
        index (:index (:manuscript out))]
    (is (= {"Data" ["idx-1" "idx-2"] "FOP" ["idx-3"]} index))
    (testing "each mark is stamped with its anchor id"
      (let [para (nth (content-for out :a) 2)]
        (is (= [:p "x" [:index {:term "Data" :id "idx-1"}]] para))))))

(deftest counts-summarize-the-numbered-targets
  (let [out (assign {:sections [{:kind :part :title "P" :index 0}
                                (assoc (chapter-section :a "A") :part 0)
                                {:kind :appendix :content [:chapter {:id :x :title "X"}]}]})]
    (is (= {:parts 1 :chapters 1 :appendices 1} (number/counts out)))))
