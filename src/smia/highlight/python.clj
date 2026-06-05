(ns smia.highlight.python
  "Pure core: a Python source tokenizer for syntax highlighting. String
   rules accept the prefix letters (`f\"…\"`, `rb'…'`) and triple-quoted
   strings span lines."
  (:require
   [smia.highlight.lexer :as lexer]))

(def ^:private keywords
  #{"False" "None" "True" "and" "as" "assert" "async" "await" "break"
    "class" "continue" "def" "del" "elif" "else" "except" "finally"
    "for" "from" "global" "if" "import" "in" "is" "lambda" "match"
    "nonlocal" "not" "or" "pass" "raise" "return" "try" "while" "with"
    "yield"})

(def ^:private rules
  [[#"#[^\n]*"                                      :comment]
   [#"(?:[rRbBuUfF]{1,2})?\"\"\"[\s\S]*?\"\"\""     :string]
   [#"(?:[rRbBuUfF]{1,2})?'''[\s\S]*?'''"           :string]
   [#"(?:[rRbBuUfF]{1,2})?\"[^\"\\\n]*+(?:\\.[^\"\\\n]*+)*+\"" :string]
   [#"(?:[rRbBuUfF]{1,2})?'[^'\\\n]*+(?:\\.[^'\\\n]*+)*+'"     :string]
   [#"\d[\d_]*\.?\d*(?:[eE][+-]?\d+)?[jJ]?"         :number]
   [#"[A-Za-z_][A-Za-z0-9_]*"                       (lexer/keyword-classifier keywords)]])

(defn tokenize [code] (lexer/scan code rules))
