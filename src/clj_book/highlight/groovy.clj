(ns clj-book.highlight.groovy
  "Pure core: a Groovy source tokenizer for syntax highlighting."
  (:require
   [clj-book.highlight.lexer :as lexer]))

(def ^:private keywords
  #{"abstract" "as" "assert" "boolean" "break" "byte" "case" "catch" "char"
    "class" "const" "continue" "def" "default" "do" "double" "else" "enum"
    "extends" "false" "final" "finally" "float" "for" "goto" "if" "implements"
    "import" "in" "instanceof" "int" "interface" "long" "native" "new" "null"
    "package" "private" "protected" "public" "return" "short" "static" "super"
    "switch" "synchronized" "this" "throw" "throws" "trait" "transient" "true"
    "try" "void" "volatile" "while" "var"})

(def tokenize (lexer/c-like keywords))
