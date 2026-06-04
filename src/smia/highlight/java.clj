(ns smia.highlight.java
  "Pure core: a Java source tokenizer for syntax highlighting."
  (:require
   [smia.highlight.lexer :as lexer]))

(def ^:private keywords
  #{"abstract" "assert" "boolean" "break" "byte" "case" "catch" "char" "class"
    "const" "continue" "default" "do" "double" "else" "enum" "extends" "final"
    "finally" "float" "for" "goto" "if" "implements" "import" "instanceof"
    "int" "interface" "long" "native" "new" "package" "private" "protected"
    "public" "return" "short" "static" "strictfp" "super" "switch"
    "synchronized" "this" "throw" "throws" "transient" "try" "void" "volatile"
    "while" "var" "record" "sealed" "permits" "yield" "true" "false" "null"})

(def tokenize (lexer/c-like keywords))
