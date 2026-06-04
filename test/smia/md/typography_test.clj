(ns smia.md.typography-test
  (:require
   [smia.md.typography :as typography]
   [clojure.test :refer [deftest is testing]]))

(defn- smarten-1
  "Smarten a single block form."
  [form]
  (first (typography/smarten [form])))

;; --- quotes -----------------------------------------------------------------

(deftest double-quotes-pair
  (is (= [:p "She said “yes” twice."]
         (smarten-1 [:p "She said \"yes\" twice."]))))

(deftest single-quotes-pair
  (is (= [:p "the ‘inner’ word"]
         (smarten-1 [:p "the 'inner' word"]))))

(deftest apostrophes-stay-right-single-quotes
  (is (= [:p "don’t, it’s the smith’s"]
         (smarten-1 [:p "don't, it's the smith's"]))))

(deftest nested-quotes
  (is (= [:p "“He said ‘go’ now”"]
         (smarten-1 [:p "\"He said 'go' now\""]))))

(deftest decade-abbreviation-is-an-apostrophe
  (is (= [:p "the ’90s"]
         (smarten-1 [:p "the '90s"]))))

(deftest quote-after-open-bracket-opens
  (is (= [:p "(“quoted”)"]
         (smarten-1 [:p "(\"quoted\")"]))))

(deftest quote-pairing-threads-across-inline-elements
  (testing "an opening quote before an :em closes after it"
    (is (= [:p "“" [:em "word"] "”"]
           (smarten-1 [:p "\"" [:em "word"] "\""])))))

(deftest quote-state-resets-per-block
  (testing "an unbalanced quote in one paragraph does not leak into the next"
    (is (= [[:p "“unbalanced"] [:p "“fresh”"]]
           (typography/smarten [[:p "\"unbalanced"] [:p "\"fresh\""]])))))

;; --- dashes and ellipsis ----------------------------------------------------

(deftest double-hyphen-becomes-en-dash
  (is (= [:p "pages 3–5"]
         (smarten-1 [:p "pages 3--5"]))))

(deftest triple-hyphen-becomes-em-dash
  (is (= [:p "wait — now"]
         (smarten-1 [:p "wait --- now"]))))

(deftest three-dots-become-ellipsis
  (is (= [:p "and so on…"]
         (smarten-1 [:p "and so on..."]))))

(deftest single-hyphen-untouched
  (is (= [:p "front-matter stays as-is"]
         (smarten-1 [:p "front-matter stays as-is"]))))

;; --- exemptions ---------------------------------------------------------------

(deftest code-spans-are-exempt
  (is (= [:p "use " [:code "--clean"] " here"]
         (smarten-1 [:p "use " [:code "--clean"] " here"]))))

(deftest pre-blocks-are-exempt
  (is (= [:pre {:lang :clojure} "(str \"a--b...\")"]
         (smarten-1 [:pre {:lang :clojure} "(str \"a--b...\")"]))))

(deftest namespaced-tags-are-exempt
  (is (= [:fo/block "raw \"FO\" content"]
         (smarten-1 [:fo/block "raw \"FO\" content"])))
  (is (= [:html/div "raw \"HTML\" content"]
         (smarten-1 [:html/div "raw \"HTML\" content"]))))

(deftest attribute-maps-are-untouched
  (is (= [:a {:href "https://example.com/a--b"} "a “link”"]
         (smarten-1 [:a {:href "https://example.com/a--b"} "a \"link\""]))))

(deftest apostrophe-directly-after-a-code-span
  (testing "a quote straight after inline code reads as mid-word"
    (is (= [:p [:code "x"] "’s value"]
           (smarten-1 [:p [:code "x"] "'s value"])))))

;; --- nesting ------------------------------------------------------------------

(deftest nested-blocks-are-walked
  (is (= [:blockquote [:p "a “quote” inside"]]
         (smarten-1 [:blockquote [:p "a \"quote\" inside"]]))))

(deftest emphasis-content-is-smartened
  (is (= [:p "so " [:em "it’s"] " fine"]
         (smarten-1 [:p "so " [:em "it's"] " fine"]))))

;; --- titles -------------------------------------------------------------------

(deftest smarten-string-handles-a-title
  (is (= "What’s in a “Build”"
         (typography/smarten-string "What's in a \"Build\""))))
