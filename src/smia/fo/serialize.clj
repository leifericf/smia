(ns smia.fo.serialize
  "Pure, hand-rolled serializer from FO-Hiccup to an XSL-FO XML string.

   Hiccup nodes are `[tag attrs? & children]`. A namespaced tag like
   `:fo/block` serializes to `<fo:block>`; a plain keyword serializes
   verbatim. The single `xmlns:fo` declaration is emitted on the
   `:fo/root` element. Text and attribute values are XML-escaped. There
   is deliberately no pretty-printing: inserting whitespace would corrupt
   `white-space=\"pre\"` content such as code blocks, and would also make
   output less reproducible. Attribute order is sorted (via
   `smia.attrs`) so identical trees serialize to identical bytes."
  (:require
   [smia.error :as error]
   [smia.attrs :as attrs]
   [smia.hiccup :as hiccup]
   [clojure.string :as str]))

(def ^:private fo-namespace "http://www.w3.org/1999/XSL/Format")

(declare escape-text escape-attr tag-name emit-children! emit-element! emit!)

(defn serialize
  "Serialize FO-Hiccup `node` to an XSL-FO XML string. With
   `:xml-declaration? false`, omit the `<?xml …?>` prolog (useful for
   serializing fragments in tests)."
  ([node] (serialize node {}))
  ([node {:keys [xml-declaration?] :or {xml-declaration? true}}]
   (let [sb (StringBuilder.)]
     (when xml-declaration?
       (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
     (emit! sb node)
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

(defn- emit-children! [^StringBuilder sb children]
  (doseq [child children]
    (cond
      (nil? child)     nil
      (seq? child)     (emit-children! sb child)
      :else            (emit! sb child))))

(defn- emit-element! [^StringBuilder sb node]
  (let [[tag attrs children] (hiccup/parse-node node)
        tname (tag-name tag)]
    (.append sb "<")
    (.append sb tname)
    (when (= tag :fo/root)
      (.append sb " xmlns:fo=\"")
      (.append sb fo-namespace)
      (.append sb "\""))
    (doseq [[k v] (attrs/pairs attrs)]
      (.append sb " ")
      (.append sb k)
      (.append sb "=\"")
      (.append sb (escape-attr v))
      (.append sb "\""))
    (let [kids (remove nil? children)]
      (if (seq kids)
        (do (.append sb ">")
            (emit-children! sb children)
            (.append sb "</")
            (.append sb tname)
            (.append sb ">"))
        (.append sb "/>")))))

(defn- emit! [^StringBuilder sb node]
  (cond
    (string? node)  (.append sb (escape-text node))
    (number? node)  (.append sb (str node))
    (vector? node)  (emit-element! sb node)
    (keyword? node) (.append sb (escape-text (name node)))
    :else (throw (error/ex :smia.fo.serialize/unserializable
                           (str "Cannot serialize node of type "
                                (some-> node class .getName))
                           {:node node}))))
