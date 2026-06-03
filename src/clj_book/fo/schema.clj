(ns clj-book.fo.schema
  "Malli schema for the author Hiccup vocabulary.

   Validates the HTML-flavored sugar and book-extension layers, giving
   humanized authoring errors. Raw `:fo/*` tags are accepted (their
   detailed validity is left to FOP, whose diagnostics the renderer
   surfaces). The schema is recursive: an element is `[tag attrs?
   & children]` where each child is text, a number, or another element."
  (:require
   [clj-book.schema :as schema]
   [malli.core :as m]))

(def sugar-tags
  "Bare keywords the vocabulary recognizes (HTML sugar + book extensions)."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6
    :ul :ol :li
    :strong :em :code :pre :a :br
    :blockquote :img :hr
    :table :thead :tbody :tr :td :th
    :admonition :sidebar :epigraph :footnote :xref
    :page-break :keep-together})

(defn fo-tag?
  "True for a raw FO tag: a keyword in the `fo` namespace, e.g. `:fo/block`."
  [t]
  (and (keyword? t) (= "fo" (namespace t))))

(defn known-tag?
  "True for any tag the vocabulary accepts: known sugar/book tag or `:fo/*`."
  [t]
  (or (contains? sugar-tags t) (fo-tag? t)))

(def Content
  "An author Hiccup element (recursive). The entry point is an element
   (a vector); its children may be elements, text, or numbers. The
   recursive child ref is wrapped in `[:schema …]` so the sequence regex
   has a clear value boundary (malli rejects a directly recursive seqex)."
  (m/schema
    [:schema
     {:registry
      {::node    [:orn
                  [:element [:ref ::element]]
                  [:text :string]
                  [:number number?]]
       ::element [:catn
                  [:tag [:fn {:error/message "must be a known sugar/book tag or a :fo/* tag"}
                         known-tag?]]
                  [:attrs [:? map?]]
                  [:children [:* [:schema [:ref ::node]]]]]}}
     ::element]))

(defn valid?
  "True when `node` conforms to the author vocabulary."
  [node]
  (schema/valid? Content node))

(defn explain
  "Humanized explanation for why `node` violates the vocabulary, or nil."
  [node]
  (schema/explain Content node))

(defn check
  "Return `node` when it conforms; otherwise throw a structured error of
   `error-type` carrying the humanized explanation."
  [node error-type]
  (schema/check Content node error-type))
