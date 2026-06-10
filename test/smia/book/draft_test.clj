(ns smia.book.draft-test
  (:require
   [smia.book.draft :as draft]
   [clojure.test :refer [deftest is testing]]))

(deftest normalize-off-when-falsy
  (testing "nil and false mean no draft marking"
    (is (nil? (draft/normalize nil)))
    (is (nil? (draft/normalize false)))))

(deftest normalize-true-applies-all-defaults
  (let [d (draft/normalize true)]
    (is (string? (:label d)))
    (is (string? (:notice d)))
    (is (string? (:watermark d)))
    (is (seq (:label d)))
    (is (seq (:notice d)))
    (is (seq (:watermark d)))))

(deftest normalize-map-overrides-defaults-per-key
  (testing "a partial map keeps defaults for unspecified slots"
    (let [d (draft/normalize {:watermark "DRAFT"})]
      (is (= "DRAFT" (:watermark d)))
      (is (= (:label (draft/normalize true)) (:label d)))
      (is (= (:notice (draft/normalize true)) (:notice d)))))
  (testing "a full map wins on every slot"
    (is (= {:label "Review" :notice "Do not share." :watermark "REVIEW"}
           (draft/normalize {:label "Review" :notice "Do not share."
                             :watermark "REVIEW"})))))

(deftest normalize-empty-map-is-all-defaults
  (is (= (draft/normalize true) (draft/normalize {}))))

(deftest watermark-text-composes-with-licensee
  (let [d (draft/normalize {:watermark "BETA"})]
    (testing "no licensee leaves the bare watermark"
      (is (= "BETA" (draft/watermark-text d nil)))
      (is (= "BETA" (draft/watermark-text d "  "))))
    (testing "a licensee is woven in for leak-traceability"
      (is (= "BETA — Ada Lovelace" (draft/watermark-text d "Ada Lovelace"))))))

(deftest watermark-text-nil-without-draft
  (is (nil? (draft/watermark-text nil "Ada"))))

(deftest notice-text-reads-the-notice-slot
  (is (= "Hush." (draft/notice-text (draft/normalize {:notice "Hush."}))))
  (is (nil? (draft/notice-text nil))))
