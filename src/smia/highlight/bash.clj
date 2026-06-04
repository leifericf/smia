(ns smia.highlight.bash
  "Pure core: a shell-script tokenizer for syntax highlighting. Reserved
   words and the everyday builtins classify as keywords; `$var`,
   `${var}`, and the special parameters classify as literals. A
   single-quoted shell string has no escapes."
  (:require
   [smia.highlight.lexer :as lexer]))

(def ^:private keywords
  #{"if" "then" "else" "elif" "fi" "for" "while" "until" "do" "done"
    "case" "esac" "in" "function" "select" "time" "return" "break"
    "continue" "local" "export" "readonly" "declare" "set" "unset"
    "shift" "source" "eval" "exec" "trap" "exit" "echo" "printf" "read"
    "cd" "test" "true" "false"})

(def ^:private rules
  [[#"#[^\n]*"                             :comment]
   [#"\"(?:\\.|[^\"\\])*\""                :string]
   [#"'[^']*'"                             :string]
   [#"\$\{[^}]*\}"                         :literal]
   [#"\$(?:[A-Za-z_][A-Za-z0-9_]*|[0-9@#?$!*-])" :literal]
   [#"\d+"                                 :number]
   [#"[A-Za-z_][A-Za-z0-9_]*"              (lexer/keyword-classifier keywords)]])

(defn tokenize [code] (lexer/scan code rules))
