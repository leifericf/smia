(ns smia.highlight.clojure
  "Pure core: a Clojure source tokenizer for syntax highlighting. Recognizes
   comments, strings, character literals, keywords (`:kw`), numbers, and a
   curated set of core/special-form symbols; everything else is plain text.
   Returns `[{:kind :text} …]` whose parts rebuild the source verbatim."
  (:require
   [smia.highlight.lexer :as lexer]))

(def ^:private core-forms
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
    "defrecord" "deftype" "definline" "defonce" "ns" "require" "import" "use"
    "let" "letfn" "fn" "if" "if-let" "if-not" "if-some" "when" "when-let"
    "when-not" "when-some" "cond" "condp" "case" "do" "doto" "loop" "recur"
    "for" "doseq" "dotimes" "while" "try" "catch" "finally" "throw" "binding"
    "->" "->>" "as->" "some->" "some->>" "cond->" "cond->>" "and" "or" "not"
    "quote" "var" "set!" "new" "in-ns" "declare" "comment"})

(def ^:private rules
  (mapv (fn [[p k]] [(re-pattern p) k])
        [[";[^\\n]*"                    :comment]
         ["\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"" :string]
         ["\\\\(?:newline|space|tab|return|[\\s\\S])" :string]
         [":[A-Za-z0-9_*+!?<>=./%&-]+"  :literal]
         ["-?\\d[\\d_]*\\.?\\d*(?:[eE][+-]?\\d+)?[MN]?" :number]]))

(def ^:private symbol-rule
  [(re-pattern "[A-Za-z_*+!?<>=%&][A-Za-z0-9_*+!?<>=.%&/'-]*")
   (lexer/keyword-classifier core-forms)])

(defn tokenize [code]
  (lexer/scan code (conj rules symbol-rule)))
