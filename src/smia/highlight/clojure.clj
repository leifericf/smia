(ns smia.highlight.clojure
  "Pure core: a Clojure source tokenizer for syntax highlighting. Recognizes
   comments, strings, character literals, keywords (`:kw`), numbers, and a
   curated set of core/special-form symbols; everything else is plain text.
   Returns `[{:kind :text} …]` whose parts rebuild the source verbatim."
  (:require
   [smia.highlight.fragments :as fragments]))

(def ^:private core-forms
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
    "defrecord" "deftype" "definline" "defonce" "ns" "require" "import" "use"
    "let" "letfn" "fn" "if" "if-let" "if-not" "if-some" "when" "when-let"
    "when-not" "when-some" "cond" "condp" "case" "do" "doto" "loop" "recur"
    "for" "doseq" "dotimes" "while" "try" "catch" "finally" "throw" "binding"
    "->" "->>" "as->" "some->" "some->>" "cond->" "cond->>" "and" "or" "not"
    "quote" "var" "set!" "new" "in-ns" "declare" "comment"})

(def tokenize (fragments/lisp-family core-forms))
