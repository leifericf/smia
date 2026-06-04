(ns smia.highlight.bash
  "Pure core: a shell-script tokenizer for syntax highlighting. Reserved
   words and the everyday builtins classify as keywords; `$var`,
   `${var}`, and the special parameters classify as literals. A
   single-quoted shell string has no escapes."
  (:require
   [smia.highlight.fragments :as fragments]))

(def ^:private keywords
  #{"if" "then" "else" "elif" "fi" "for" "while" "until" "do" "done"
    "case" "esac" "in" "function" "select" "time" "return" "break"
    "continue" "local" "export" "readonly" "declare" "set" "unset"
    "shift" "source" "eval" "exec" "trap" "exit" "echo" "printf" "read"
    "cd" "test" "true" "false"})

(def tokenize
  (fragments/from-fragments
    [fragments/hash-line fragments/dq-string fragments/sq-no-escape
     fragments/shell-var-brace fragments/shell-var-simple fragments/int-number]
    fragments/plain-ident keywords))
