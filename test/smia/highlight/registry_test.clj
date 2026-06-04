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

(deftest tokens-rebuild-the-source-verbatim-for-the-script-languages
  (doseq [lang [:bash :python :javascript :sql]
          code ["a b\n  c" "x # y" "select 1 -- ok" "echo \"$HOME\""]]
    (is (= code (rebuild (hl/tokenize lang code)))
        (str lang " tokenization is lossless"))))

(deftest bash-classifies-keywords-variables-strings-and-comments
  (let [toks (hl/tokenize :bash "if [ -f x ]; then\n  echo $HOME \"a b\" # note\nfi\n")]
    (is (contains? (kinds-of toks :keyword) "if"))
    (is (contains? (kinds-of toks :keyword) "then"))
    (is (contains? (kinds-of toks :keyword) "fi"))
    (is (contains? (kinds-of toks :keyword) "echo"))
    (is (contains? (kinds-of toks :literal) "$HOME"))
    (is (contains? (kinds-of toks :string) "\"a b\""))
    (is (some #(str/starts-with? % "# note") (kinds-of toks :comment)))))

(deftest bash-single-quotes-have-no-escapes
  (let [toks (hl/tokenize :bash "echo 'a\\' x")]
    (is (contains? (kinds-of toks :string) "'a\\'"))))

(deftest python-classifies-keywords-strings-and-comments
  (let [toks (hl/tokenize :python "def f(x):\n    return f\"v={x}\"  # c\nTrue\n")]
    (is (contains? (kinds-of toks :keyword) "def"))
    (is (contains? (kinds-of toks :keyword) "return"))
    (is (contains? (kinds-of toks :keyword) "True"))
    (is (contains? (kinds-of toks :string) "f\"v={x}\""))
    (is (some #(str/starts-with? % "# c") (kinds-of toks :comment)))))

(deftest python-triple-quoted-strings
  (let [toks (hl/tokenize :python "\"\"\"doc\nstring\"\"\"\nx = 1")]
    (is (contains? (kinds-of toks :string) "\"\"\"doc\nstring\"\"\""))
    (is (contains? (kinds-of toks :number) "1"))))

(deftest javascript-classifies-keywords-and-template-literals
  (let [toks (hl/tokenize :javascript
                          "const f = async (x) => {\n  return `v=${x}`; // c\n}")]
    (is (contains? (kinds-of toks :keyword) "const"))
    (is (contains? (kinds-of toks :keyword) "async"))
    (is (contains? (kinds-of toks :keyword) "return"))
    (is (contains? (kinds-of toks :string) "`v=${x}`"))
    (is (some #(str/starts-with? % "// c") (kinds-of toks :comment)))))

(deftest sql-keywords-are-case-insensitive
  (let [toks (hl/tokenize :sql "SELECT name FROM users where id = 1; -- c")]
    (is (contains? (kinds-of toks :keyword) "SELECT"))
    (is (contains? (kinds-of toks :keyword) "FROM"))
    (is (contains? (kinds-of toks :keyword) "where"))
    (is (contains? (kinds-of toks :number) "1"))
    (is (some #(str/starts-with? % "-- c") (kinds-of toks :comment)))))

(deftest sql-strings-double-their-quotes
  (let [toks (hl/tokenize :sql "select 'it''s'")]
    (is (contains? (kinds-of toks :string) "'it''s'"))))

(deftest unregistered-language-returns-nil
  (is (nil? (hl/tokenize :rust "fn main() {}")))
  (is (not (hl/supported? :rust)))
  (is (hl/supported? :clojure)))
