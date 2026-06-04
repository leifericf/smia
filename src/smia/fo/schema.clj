(ns smia.fo.schema
  "Malli schema for the author Hiccup vocabulary.

   Validates the HTML-flavored sugar and book-extension layers, giving
   humanized authoring errors. Raw `:fo/*` and `:html/*` tags are
   accepted as per-format escape hatches (their detailed validity is
   left to the format's renderer; using one in the other format is a
   render-time error). The schema is recursive: an element is `[tag
   attrs? & children]` where each child is text, a number, or another
   element."
  (:require
   [smia.schema :as schema]
   [malli.core :as m]))

(def sugar-tags
  "Bare keywords the vocabulary recognizes (HTML sugar + book extensions)."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6
    :ul :ol :li
    :dl :dt :dd
    :strong :em :code :span :pre :a :br
    :kbd :menu :button :mark :sub :sup
    :blockquote :img :hr :figure
    :table :thead :tbody :tr :td :th
    :admonition :sidebar :overview :epigraph :footnote :xref :cite :index
    :example :details :open
    :math :diagram :page-break :keep-together})

(def resolve-tags
  "Tags the vocabulary accepts but that a resolve pass eliminates before
   expansion — they have no expander of their own. Document attributes and
   conditional content land here as those features ship; empty for now. Kept
   separate from `sugar-tags` so the expander-parity invariant (every sugar
   tag has an expander in every format) stays exact."
  #{})

(defn fo-tag?
  "True for a raw FO tag: a keyword in the `fo` namespace, e.g. `:fo/block`."
  [t]
  (and (keyword? t) (= "fo" (namespace t))))

(defn html-tag?
  "True for a raw HTML tag: a keyword in the `html` namespace,
   e.g. `:html/aside`."
  [t]
  (and (keyword? t) (= "html" (namespace t))))

(defn known-tag?
  "True for any tag the vocabulary accepts: a known sugar/book tag, a
   resolve-time tag, `:fo/*`, or `:html/*`."
  [t]
  (or (contains? sugar-tags t) (contains? resolve-tags t)
      (fo-tag? t) (html-tag? t)))

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
                  [:tag [:fn {:error/message "must be a known sugar/book tag, a :fo/* tag, or a :html/* tag"}
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
