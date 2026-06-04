(ns smia.highlight.registry-test
  (:require
   [smia.highlight.registry :as hl]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- rebuild [toks] (apply str (map :text toks)))
(defn- kinds-of [toks kind] (set (map :text (filter #(= kind (:kind %)) toks))))

(deftest tokens-rebuild-the-source-verbatim
  (doseq [lang [:clojure :java :kotlin :groovy]
          code ["a b\n  c" "x // y" "(+ 1 2)\n;; ok"]]
    (is (= code (rebuild (hl/tokenize lang code)))
        (str lang " tokenization is lossless"))))

(deftest clojure-classifies-core-forms-strings-and-comments
  (let [toks (hl/tokenize :clojure "(defn f [x] \"s\") ; note\n:kw 42")]
    (is (contains? (kinds-of toks :keyword) "defn"))
    (is (contains? (kinds-of toks :string) "\"s\""))
    (is (contains? (kinds-of toks :literal) ":kw"))
    (is (contains? (kinds-of toks :number) "42"))
    (is (some #(str/starts-with? % "; note") (kinds-of toks :comment)))))

(deftest java-classifies-keywords-and-line-comments
  (let [toks (hl/tokenize :java "public int x = 1; // c")]
    (is (contains? (kinds-of toks :keyword) "public"))
    (is (contains? (kinds-of toks :keyword) "int"))
    (is (contains? (kinds-of toks :number) "1"))
    (is (some #(str/starts-with? % "// c") (kinds-of toks :comment)))))

(deftest unregistered-language-returns-nil
  (is (nil? (hl/tokenize :rust "fn main() {}")))
  (is (not (hl/supported? :rust)))
  (is (hl/supported? :clojure)))
