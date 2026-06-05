(ns smia.highlight.fragments
  "Pure core: reusable lexer fragments and per-family tokenizer factories.

   A fragment is a `[regex-string kind]` pair (the lexer compiles the
   string); a family factory assembles a fragment list plus a
   keyword-classified identifier rule into a tokenizer for a whole language
   family, keyed only by that family's keyword set. Adding a language in a
   known family is then a keyword set plus a factory call — no bespoke
   scanner.

   The string fragments use the unrolled-loop form (`chars* (esc chars*)*`,
   never `(esc|char)*`) with possessive quantifiers: the unrolling keeps
   the match linear, and possessiveness keeps the JDK engine iterative (a
   greedy group loop recurses one stack frame per escape pair, so a long
   escape-heavy literal would overflow the stack). The char classes are
   disjoint from the escape branch, so possessive matching never changes
   what is matched. Everything here is pure and Smia-type-free, so the
   lexer could later be extracted as a standalone library."
  (:require
   [smia.highlight.lexer :as lexer]))

;; --- comment fragments ------------------------------------------------------

(def slash-line     ["//[^\\n]*"            :comment])
(def hash-line      ["#[^\\n]*"             :comment])
(def dash-line      ["--[^\\n]*"            :comment])
(def semicolon-line [";[^\\n]*"             :comment])
(def percent-line   ["%[^\\n]*"             :comment])
(def block-c        ["/\\*[\\s\\S]*?\\*/"   :comment])
(def block-ml       ["\\(\\*[\\s\\S]*?\\*\\)" :comment])
(def block-haskell  ["\\{-[\\s\\S]*?-\\}"   :comment])

;; --- string fragments (unrolled, overflow-safe) -----------------------------

(def dq-string       ["\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"" :string])
(def sq-string       ["'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+'"     :string])
(def backtick-string ["`[^`\\\\]*+(?:\\\\.[^`\\\\]*+)*+`"     :string])
(def sq-no-escape    ["'[^']*'"                            :string])

;; --- number fragments -------------------------------------------------------

(def c-number   ["\\d[\\d_]*\\.?\\d*(?:[eE][+-]?\\d+)?[fFlLdD]?" :number])
(def int-number ["\\d+"                                          :number])

;; --- identifier patterns (bare strings; classified against a keyword set) ---

(def c-ident     "[A-Za-z_$][A-Za-z0-9_$]*")
(def plain-ident "[A-Za-z_][A-Za-z0-9_]*")

;; --- lisp fragments ---------------------------------------------------------

(def lisp-char    ["\\\\(?:newline|space|tab|return|[\\s\\S])"      :string])
(def lisp-keyword [":[A-Za-z0-9_*+!?<>=./%&-]+"                     :literal])
(def lisp-number  ["-?\\d[\\d_]*\\.?\\d*(?:[eE][+-]?\\d+)?[MN]?"    :number])
(def lisp-symbol  "[A-Za-z_*+!?<>=%&][A-Za-z0-9_*+!?<>=.%&/'-]*")

;; --- shell fragments --------------------------------------------------------

(def shell-var-brace  ["\\$\\{[^}]*\\}"                            :literal])
(def shell-var-simple ["\\$(?:[A-Za-z_][A-Za-z0-9_]*|[0-9@#?$!*-])" :literal])

;; --- the general factory ----------------------------------------------------

(defn from-fragments
  "Compile a vector of `[regex-string kind]` `fragments` plus a
   keyword-classified `ident-pattern` into a `code -> tokens` tokenizer."
  [fragments ident-pattern keywords]
  (let [rules (-> (mapv (fn [[p k]] [(re-pattern p) k]) fragments)
                  (conj [(re-pattern ident-pattern)
                         (lexer/keyword-classifier keywords)]))]
    (fn [code] (lexer/scan code rules))))

;; --- named family factories -------------------------------------------------

(def ^:private c-fragments [slash-line block-c dq-string sq-string c-number])

(defn c-family
  "C-family: `//` and `/* */` comments, double/single-quoted strings, C
   numbers, `$`-friendly identifiers."
  [keywords]
  (from-fragments c-fragments c-ident keywords))

(defn c-family+template
  "C-family plus backtick template literals (JavaScript, TypeScript)."
  [keywords]
  (from-fragments (conj c-fragments backtick-string) c-ident keywords))

(defn hash-script
  "Hash-comment scripting family: `#` comments, double/single-quoted strings,
   C numbers, plain identifiers (Ruby, R, Perl, YAML, TOML, …)."
  [keywords]
  (from-fragments [hash-line dq-string sq-string c-number] plain-ident keywords))

(defn lisp-family
  "Lisp family: `;` comments, strings, `\\char` literals, `:keyword`s,
   lisp numbers, lisp symbols (Clojure, Scheme, Common Lisp, Racket)."
  [keywords]
  (from-fragments [semicolon-line dq-string lisp-char lisp-keyword lisp-number]
                  lisp-symbol keywords))

(defn ml-family
  "ML family: `(* *)` comments, double-quoted strings, C numbers, plain
   identifiers (OCaml, F#, Standard ML)."
  [keywords]
  (from-fragments [block-ml dq-string c-number] plain-ident keywords))

(defn percent-comment
  "Percent-comment family: `%` comments, strings, C numbers, plain
   identifiers (LaTeX, Erlang, MATLAB, Prolog)."
  [keywords]
  (from-fragments [percent-line dq-string sq-string c-number] plain-ident keywords))

(defn dash-comment
  "Dash-comment family: `--` line and `{- -}` block comments, double-quoted
   strings, C numbers, plain identifiers (Haskell, Elm, Lua, Ada)."
  [keywords]
  (from-fragments [dash-line block-haskell dq-string c-number] plain-ident keywords))
