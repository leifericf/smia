(ns smia.svg.hiccup
  "Pure core: parse generated SVG XML into Hiccup.

   Shared by the build-time renderers (math, diagrams). Parsing is not
   namespace-aware, so `xmlns` stays a plain attribute — exactly what
   inline SVG needs in HTML, XHTML, and FO alike — and no external DTD
   is ever fetched. Comments are dropped (generators stamp toolchain
   versions there), and generated element ids plus their `url(#…)` /
   `#…` references are prefixed with a short content hash, so several
   inline SVGs coexist in one page without id collisions. The existing
   serializers emit the result with sorted attributes: the same input
   XML always yields the same bytes."
  (:require
   [clojure.string :as str])
  (:import
   (java.io StringReader)
   (java.security MessageDigest)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.w3c.dom Element Node)
   (org.xml.sax InputSource)))

(defn id-prefix
  "A short content hash of `seed` for namespacing generated element ids."
  [seed]
  (let [d (.digest (MessageDigest/getInstance "SHA-1")
                   (.getBytes (str seed) "UTF-8"))]
    (str "s" (format "%02x%02x%02x%02x"
                     (aget d 0) (aget d 1) (aget d 2) (aget d 3)) "-")))

(defn- prefix-refs
  "Prefix generated ids and their `url(#…)` / `#…` references."
  [prefix k v]
  (cond
    (= :id k)                      (str prefix v)
    (str/includes? v "url(#")      (str/replace v "url(#" (str "url(#" prefix))
    (and (= :xlink:href k)
         (str/starts-with? v "#")) (str "#" prefix (subs v 1))
    :else v))

(defn- element->hiccup [^Element el prefix]
  (let [attr-nodes (.getAttributes el)
        attrs (into {}
                    (for [i (range (.getLength attr-nodes))
                          :let [n (.item attr-nodes i)
                                k (keyword (.getNodeName n))]]
                      [k (prefix-refs prefix k (.getNodeValue n))]))
        kid-nodes (.getChildNodes el)
        kids (for [i (range (.getLength kid-nodes))
                   :let [n (.item kid-nodes i)]
                   :when (or (= Node/ELEMENT_NODE (.getNodeType n))
                             (and (= Node/TEXT_NODE (.getNodeType n))
                                  (not (str/blank? (.getNodeValue n)))))]
               (if (= Node/ELEMENT_NODE (.getNodeType n))
                 (element->hiccup n prefix)
                 (.getNodeValue n)))]
    (into [(keyword (.getNodeName el)) attrs] kids)))

(defn parse-svg
  "Parse an SVG XML string into Hiccup, namespacing generated ids with
   `prefix` (see `id-prefix`)."
  [xml prefix]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setNamespaceAware false)
                  (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))
        doc     (.parse (.newDocumentBuilder factory)
                        (InputSource. (StringReader. xml)))]
    (element->hiccup (.getDocumentElement doc) prefix)))
