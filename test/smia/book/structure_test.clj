(ns smia.book.structure-test
  (:require
   [smia.book.structure :as structure]
   [clojure.test :refer [deftest is testing]]))

(deftest flat-chapters-normalize-to-a-body-with-no-parts
  (let [s (structure/normalize {:book/slug "s" :book/title "t"
                                :book/chapters ["chapters/01.md" "chapters/02.md"]})]
    (is (= [{:kind :chapter :file "chapters/01.md" :part nil}
            {:kind :chapter :file "chapters/02.md" :part nil}]
           (:sections s)))
    (testing "the numbering policy defaults are filled in"
      (is (= structure/default-numbering (:numbering s))))))

(deftest parts-group-chapters-with-dividers
  (let [s (structure/normalize
           {:book/slug "s" :book/title "t"
            :book/parts [{:part/title "Foundations"
                          :part/chapters ["chapters/01.md" "chapters/02.md"]}
                         {:part/title "Practice"
                          :part/chapters ["chapters/03.md"]}]})]
    (is (= [{:kind :part :title "Foundations" :index 0}
            {:kind :chapter :file "chapters/01.md" :part 0}
            {:kind :chapter :file "chapters/02.md" :part 0}
            {:kind :part :title "Practice" :index 1}
            {:kind :chapter :file "chapters/03.md" :part 1}]
           (:sections s)))))

(deftest front-and-back-matter-and-appendices-are-ordered
  (let [s (structure/normalize
           {:book/slug "s" :book/title "t"
            :book/front-matter [{:role :preface :file "front/preface.md"}]
            :book/chapters ["chapters/01.md"]
            :book/appendices ["appendix/a-glossary.md"]
            :book/back-matter [{:role :bibliography} {:role :index}]})]
    (is (= [{:kind :matter :matter :front :role :preface :file "front/preface.md"}
            {:kind :chapter :file "chapters/01.md" :part nil}
            {:kind :appendix :file "appendix/a-glossary.md"}
            {:kind :matter :matter :back :role :bibliography :generated true}
            {:kind :matter :matter :back :role :index :generated true}]
           (:sections s)))))

(deftest numbering-overrides-merge-over-defaults
  (let [s (structure/normalize {:book/slug "s" :book/title "t"
                                :book/chapters ["c.md"]
                                :book/numbering {:sections true
                                                 :start-chapters-on :recto}})]
    (is (true? (get-in s [:numbering :sections])))
    (is (= :recto (get-in s [:numbering :start-chapters-on])))
    (is (= :arabic (get-in s [:numbering :chapters])) "untouched defaults remain")))

(deftest file-list-collects-every-referenced-source
  (let [s (structure/normalize
           {:book/slug "s" :book/title "t"
            :book/front-matter [{:role :preface :file "front/preface.md"}]
            :book/parts [{:part/title "P" :part/chapters ["chapters/01.md"]}]
            :book/appendices ["appendix/a.md"]
            :book/back-matter [{:role :index}]})]
    (is (= ["front/preface.md" "chapters/01.md" "appendix/a.md"]
           (structure/file-list s))
        "part dividers and generated matter contribute no file")))
