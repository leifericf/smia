(ns clj-book.highlight.kotlin
  "Pure core: a Kotlin source tokenizer for syntax highlighting."
  (:require
   [clj-book.highlight.lexer :as lexer]))

(def ^:private keywords
  #{"as" "break" "class" "continue" "do" "else" "false" "for" "fun" "if" "in"
    "interface" "is" "null" "object" "package" "return" "super" "this" "throw"
    "true" "try" "typealias" "typeof" "val" "var" "when" "while" "by" "catch"
    "constructor" "delegate" "dynamic" "field" "file" "finally" "get" "import"
    "init" "param" "property" "receiver" "set" "setparam" "value" "where"
    "abstract" "actual" "annotation" "companion" "const" "crossinline" "data"
    "enum" "expect" "external" "final" "infix" "inline" "inner" "internal"
    "lateinit" "noinline" "open" "operator" "out" "override" "private"
    "protected" "public" "reified" "sealed" "suspend" "tailrec" "vararg"})

(def tokenize (lexer/c-like keywords))
