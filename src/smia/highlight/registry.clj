(ns smia.highlight.registry
  "Pure core: the syntax-highlight tokenizer registry — a `language ->
   tokenize` data map mirroring the evaluator registry. `tokenize` returns
   `[{:kind :text} …]` runs the renderer colors from the theme's `:code`
   palette; an unregistered language yields nil so the caller falls back to
   plain monospace. Adding a language is adding a map entry. No IO."
  (:require
   [smia.highlight.clojure :as clojure]
   [smia.highlight.groovy :as groovy]
   [smia.highlight.java :as java]
   [smia.highlight.kotlin :as kotlin]))

(def tokenizers
  "Language keyword -> pure `(fn [code] -> tokens)`."
  {:clojure clojure/tokenize
   :java    java/tokenize
   :kotlin  kotlin/tokenize
   :groovy  groovy/tokenize})

(defn supported? [lang] (contains? tokenizers (keyword lang)))

(defn tokenize
  "Tokenize `code` for `lang`, or nil when the language is unregistered."
  [lang code]
  (when-let [f (get tokenizers (keyword lang))]
    (f code)))
