(ns clj-book.book.number-test
  (:require
   [clj-book.book.number :as number]
   [clj-book.book.structure :as structure]
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

(deftest childless-xref-is-rewritten-to-the-composed-label
  (let [out (assign {:sections [(chapter-section :intro "Introduction"
                                                 [:p "See " [:xref {:to :config}] "."])
                                (chapter-section :config "Configuration")]})
        para (nth (content-for out :intro) 2)]
    (is (= [:p "See " [:xref {:to :config} "Chapter 2"] "."] para))))

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
    (is (= [:p "Jump to " [:xref {:to :setup} "Getting Set Up"] "."] para))))

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

(deftest counts-summarize-the-numbered-targets
  (let [out (assign {:sections [{:kind :part :title "P" :index 0}
                                (assoc (chapter-section :a "A") :part 0)
                                {:kind :appendix :content [:chapter {:id :x :title "X"}]}]})]
    (is (= {:parts 1 :chapters 1 :appendices 1} (number/counts out)))))
