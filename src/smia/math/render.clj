(ns smia.math.render
  "LaTeX -> SVG-Hiccup, rendered in process by JLaTeXMath onto Batik's
   SVG generator (Batik ships with FOP, JLaTeXMath with the optional
   `:math` deps alias — this namespace must only be loaded through
   `smia.math.resolve`'s `requiring-resolve`).

   Glyphs are emitted as path outlines, not font references, so the SVG
   is self-contained: it looks identical in a browser, an e-reader, and
   FOP, with no font available at view time. The XML Batik streams is
   parsed back into Hiccup (JDK DOM, no namespace expansion) so the
   existing serializers emit it with sorted attributes — the same
   notation renders to the same bytes, build after build. Generated ids
   (clip paths and the like) are prefixed with a hash of the notation, so
   several formulas inlined into one page cannot collide."
  (:require
   [clojure.string :as str])
  (:import
   (java.awt Color Dimension Insets)
   (java.io StringReader StringWriter)
   (java.security MessageDigest)
   (javax.swing JLabel)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.apache.batik.dom GenericDOMImplementation)
   (org.apache.batik.svggen SVGGeneratorContext SVGGraphics2D)
   (org.scilab.forge.jlatexmath TeXConstants TeXFormula)
   (org.w3c.dom Element Node)
   (org.xml.sax InputSource)))

(def ^:private font-size
  "JLaTeXMath point size; STYLE_TEXT shrinks inline math naturally."
  18.0)

;; --- SVG XML -> Hiccup -------------------------------------------------------

(defn- id-prefix
  "A short content hash of `[notation display?]` for namespacing the
   generator's element ids."
  [notation display?]
  (let [d (.digest (MessageDigest/getInstance "SHA-1")
                   (.getBytes (str notation "\n" display?) "UTF-8"))]
    (str "m" (format "%02x%02x%02x%02x" (aget d 0) (aget d 1) (aget d 2) (aget d 3)) "-")))

(defn- prefix-refs
  "Prefix generated ids and their `url(#…)` / `#…` references so several
   inline SVGs coexist in one document."
  [prefix k v]
  (cond
    (= :id k)                        (str prefix v)
    (str/includes? v "url(#")        (str/replace v "url(#" (str "url(#" prefix))
    (and (= :xlink:href k)
         (str/starts-with? v "#"))   (str "#" prefix (subs v 1))
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

(defn- parse-svg
  "Parse an SVG XML string into Hiccup. Not namespace-aware, so `xmlns`
   stays a plain attribute (which is exactly what inline SVG needs); no
   external DTDs are fetched."
  [xml prefix]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setNamespaceAware false)
                  (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))
        doc     (.parse (.newDocumentBuilder factory)
                        (InputSource. (StringReader. xml)))]
    (element->hiccup (.getDocumentElement doc) prefix)))

;; --- rendering ---------------------------------------------------------------

(defn render-svg
  "Render LaTeX `notation` to SVG-Hiccup, in display style when
   `display?`. Same input, same value — every glyph is an outline path."
  [notation display?]
  (let [formula (TeXFormula. ^String notation)
        icon    (doto (.createTeXIcon formula
                                      (if display?
                                        TeXConstants/STYLE_DISPLAY
                                        TeXConstants/STYLE_TEXT)
                                      (float font-size))
                  (.setInsets (Insets. 1 1 1 1)))
        w       (.getIconWidth icon)
        h       (.getIconHeight icon)
        doc     (.createDocument (GenericDOMImplementation/getDOMImplementation)
                                 "http://www.w3.org/2000/svg" "svg" nil)
        ctx     (doto (SVGGeneratorContext/createDefault doc)
                  ;; The default comment embeds the Batik version string;
                  ;; pin it so output never varies with the toolchain.
                  (.setComment "math"))
        g       (doto (SVGGraphics2D. ctx true)
                  (.setSVGCanvasSize (Dimension. w h)))
        label   (doto (JLabel.) (.setForeground Color/BLACK))]
    (.paintIcon icon label g 0 0)
    (let [out (StringWriter.)]
      (.stream g out false)
      (parse-svg (str out) (id-prefix notation display?)))))
