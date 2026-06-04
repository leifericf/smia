(ns smia.highlight.languages-test
  (:require
   [smia.highlight.registry :as hl]
   [clojure.test :refer [deftest is testing]]))

(defn- rebuild [toks] (apply str (map :text toks)))
(defn- kinds-of [toks kind] (set (map :text (filter #(= kind (:kind %)) toks))))

(deftest the-family-languages-are-registered
  (testing "a representative language from each family is supported"
    (doseq [lang [:go :rust :cpp :csharp :swift     ; c-family
                  :typescript                         ; c-family + template
                  :ruby :r :julia :dockerfile         ; hash-script
                  :scheme :racket :commonlisp         ; lisp
                  :ocaml :fsharp                       ; ml
                  :latex :erlang                       ; percent
                  :haskell :elm :lua]]                 ; dash
      (is (hl/supported? lang) (str lang " is registered"))))
  (testing "an unregistered language is still nil"
    (is (not (hl/supported? :brainfuck)))))

(deftest tokenization-stays-lossless-across-families
  (doseq [[lang code] [[:go     "func f() { return 1 } // c"]
                       [:ruby   "def f\n  1 # c\nend"]
                       [:ocaml  "let x = 1 (* c *)"]
                       [:latex  "\\section{Hi} % c"]
                       [:haskell "main = id 1 -- c"]
                       [:scheme "(define x 1) ; c"]]]
    (is (= code (rebuild (hl/tokenize lang code)))
        (str lang " tokenization is lossless"))))

(deftest c-family-classifies-keywords-comments-and-strings
  (let [toks (hl/tokenize :go "func main() { return \"hi\" } // note")]
    (is (contains? (kinds-of toks :keyword) "func"))
    (is (contains? (kinds-of toks :keyword) "return"))
    (is (contains? (kinds-of toks :string) "\"hi\""))
    (is (some #(= "// note" %) (kinds-of toks :comment)))))

(deftest typescript-classifies-template-literals
  (let [toks (hl/tokenize :typescript "const x: number = `v=${y}`; // c")]
    (is (contains? (kinds-of toks :keyword) "const"))
    (is (contains? (kinds-of toks :keyword) "number"))
    (is (contains? (kinds-of toks :string) "`v=${y}`"))))

(deftest hash-script-classifies-hash-comments
  (let [toks (hl/tokenize :ruby "def f # c\n  true\nend")]
    (is (contains? (kinds-of toks :keyword) "def"))
    (is (contains? (kinds-of toks :keyword) "end"))
    (is (some #(= "# c" %) (kinds-of toks :comment)))))

(deftest ml-family-classifies-block-comments
  (let [toks (hl/tokenize :ocaml "let rec f x = x (* the identity *)")]
    (is (contains? (kinds-of toks :keyword) "let"))
    (is (contains? (kinds-of toks :keyword) "rec"))
    (is (some #(= "(* the identity *)" %) (kinds-of toks :comment)))))

(deftest dash-comment-classifies-line-and-block-comments
  (let [toks (hl/tokenize :haskell "f x = x -- c\n{- block -}")]
    (is (some #(= "-- c" %) (kinds-of toks :comment)))
    (is (some #(= "{- block -}" %) (kinds-of toks :comment)))))

(deftest percent-comment-classifies-percent-comments
  (let [toks (hl/tokenize :latex "\\begin{document} % start")]
    (is (some #(= "% start" %) (kinds-of toks :comment)))))

(deftest lisp-family-classifies-comments-and-keywords
  (let [toks (hl/tokenize :scheme "(define (f x) x) ; c")]
    (is (contains? (kinds-of toks :keyword) "define"))
    (is (some #(= "; c" %) (kinds-of toks :comment)))))

(deftest long-literals-do-not-overflow-across-new-families
  (doseq [[lang code] [[:go      (str "\"" (apply str (repeat 20000 "a")) "\"")]
                       [:haskell (str "\"" (apply str (repeat 20000 "a")) "\"")]
                       [:ocaml   (str "\"" (apply str (repeat 20000 "a")) "\"")]
                       [:scheme  (str "\"" (apply str (repeat 20000 "a")) "\"")]]]
    (let [toks (hl/tokenize lang code)]
      (is (= code (rebuild toks)) (str lang " stays lossless on a long literal"))
      (is (contains? (set (map :kind toks)) :string)
          (str lang " classifies the long literal as a string")))))
