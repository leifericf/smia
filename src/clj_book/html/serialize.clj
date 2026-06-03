(ns clj-book.html.serialize
  "Pure, hand-rolled serializer from HTML-Hiccup to an HTML5 or XHTML
   string.

   Hiccup nodes are `[tag attrs? & children]`. The `:mode` option picks
   the dialect: `:html5` (default) writes void elements bare (`<br>`),
   `:xhtml` self-closes them (`<br/>`) and prefixes the document with an
   XML declaration plus an `xmlns` on the `<html>` element (EPUB content
   documents are XHTML). Non-void empty elements are always written as
   `<tag></tag>` — browsers mis-parse a self-closed `<div/>`. Text and
   attribute values are escaped; attribute order is sorted (via
   `clj-book.fo.attrs`) so identical trees serialize to identical bytes.
   There is deliberately no pretty-printing: inserting whitespace would
   corrupt `<pre>` content and make output less reproducible."
  (:require
   [clj-book.error :as error]
   [clj-book.fo.attrs :as attrs]
   [clj-book.fo.hiccup :as hiccup]
   [clojure.string :as str]))

(def ^:private xhtml-namespace "http://www.w3.org/1999/xhtml")

(def void-tags
  "Elements written without a closing tag (self-closed in XHTML)."
  #{:br :img :hr :meta :link :col})

(declare emit!)

(defn serialize
  "Serialize HTML-Hiccup `node` to a string. Options:
   `:mode`             — `:html5` (default) or `:xhtml`.
   `:doctype?`         — when true, prefix `<!DOCTYPE html>` (after the
                         XML declaration in `:xhtml` mode).
   `:xml-declaration?` — `:xhtml` mode only; default true. Pass false to
                         serialize fragments without the `<?xml …?>`
                         prolog (mirrors `fo/serialize`)."
  ([node] (serialize node {}))
  ([node {:keys [mode doctype? xml-declaration?]
          :or   {mode :html5 xml-declaration? true}}]
   (let [sb (StringBuilder.)]
     (when (and (= :xhtml mode) xml-declaration?)
       (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
     (when doctype?
       (.append sb "<!DOCTYPE html>\n"))
     (emit! sb node mode)
     (.toString sb))))

;; --- private helpers -------------------------------------------------------

(defn- escape-text [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- escape-attr [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- tag-name [tag]
  (if-let [ns (namespace tag)]
    (str ns ":" (name tag))
    (name tag)))

(defn- emit-children! [^StringBuilder sb children mode]
  (doseq [child children]
    (cond
      (nil? child) nil
      (seq? child) (emit-children! sb child mode)
      :else        (emit! sb child mode))))

(defn- emit-element! [^StringBuilder sb node mode]
  (let [[tag attrs children] (hiccup/parse-node node)
        tname (tag-name tag)
        kids  (remove nil? (flatten children))]
    (.append sb "<")
    (.append sb tname)
    (when (and (= :html tag) (= :xhtml mode))
      (.append sb " xmlns=\"")
      (.append sb xhtml-namespace)
      (.append sb "\""))
    (doseq [[k v] (attrs/pairs attrs)]
      (.append sb " ")
      (.append sb k)
      (.append sb "=\"")
      (.append sb (escape-attr v))
      (.append sb "\""))
    (if (contains? void-tags tag)
      (do (when (seq kids)
            (throw (error/ex :clj-book.html.serialize/void-element-children
                             (str "<" tname "> is a void element and cannot have children.")
                             {:node node})))
          (.append sb (if (= :xhtml mode) "/>" ">")))
      (do (.append sb ">")
          (emit-children! sb children mode)
          (.append sb "</")
          (.append sb tname)
          (.append sb ">")))))

(defn- emit! [^StringBuilder sb node mode]
  (cond
    (string? node)  (.append sb (escape-text node))
    (number? node)  (.append sb (str node))
    (vector? node)  (emit-element! sb node mode)
    (keyword? node) (.append sb (escape-text (name node)))
    :else (throw (error/ex :clj-book.html.serialize/unserializable
                           (str "Cannot serialize node of type "
                                (some-> node class .getName))
                           {:node node}))))
