(ns smia.highlight.javascript
  "Pure core: a JavaScript source tokenizer for syntax highlighting —
   the shared C-family rules plus template literals."
  (:require
   [smia.highlight.fragments :as fragments]))

(def ^:private keywords
  #{"async" "await" "break" "case" "catch" "class" "const" "continue"
    "debugger" "default" "delete" "do" "else" "enum" "export" "extends"
    "false" "finally" "for" "function" "if" "implements" "import" "in"
    "instanceof" "interface" "let" "new" "null" "of" "package" "private"
    "protected" "public" "return" "static" "super" "switch" "this"
    "throw" "true" "try" "typeof" "undefined" "var" "void" "while"
    "with" "yield"})

(def tokenize (fragments/c-family+template keywords))
