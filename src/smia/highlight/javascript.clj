(ns smia.highlight.javascript
  "Pure core: a JavaScript source tokenizer for syntax highlighting —
   the shared C-family rules plus template literals."
  (:require
   [smia.highlight.lexer :as lexer]))

(def ^:private keywords
  #{"async" "await" "break" "case" "catch" "class" "const" "continue"
    "debugger" "default" "delete" "do" "else" "enum" "export" "extends"
    "false" "finally" "for" "function" "if" "implements" "import" "in"
    "instanceof" "interface" "let" "new" "null" "of" "package" "private"
    "protected" "public" "return" "static" "super" "switch" "this"
    "throw" "true" "try" "typeof" "undefined" "var" "void" "while"
    "with" "yield"})

(def ^:private rules
  (-> (mapv (fn [[p k]] [(re-pattern p) k]) lexer/c-like-rules-base)
      (conj [#"`[^`\\]*(?:\\.[^`\\]*)*`" :string])
      (conj [#"[A-Za-z_$][A-Za-z0-9_$]*" (lexer/keyword-classifier keywords)])))

(defn tokenize [code] (lexer/scan code rules))
