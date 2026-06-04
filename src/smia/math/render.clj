(ns smia.math.render
  "LaTeX -> SVG-Hiccup, rendered in process by JLaTeXMath onto Batik's
   SVG generator (Batik ships with FOP, JLaTeXMath with the optional
   `:math` deps alias — this namespace must only be loaded through
   `smia.svg.resolve`'s `requiring-resolve`).

   Glyphs are emitted as path outlines, not font references, so the SVG
   is self-contained: it looks identical in a browser, an e-reader, and
   FOP, with no font available at view time. The XML Batik streams is
   parsed back into Hiccup (see `smia.svg.hiccup`) so the existing
   serializers emit it with sorted attributes — the same notation
   renders to the same bytes, build after build."
  (:require
   [smia.svg.hiccup :as svg-hiccup])
  (:import
   (java.awt Color Dimension Insets)
   (java.io StringWriter)
   (javax.swing JLabel)
   (org.apache.batik.dom GenericDOMImplementation)
   (org.apache.batik.svggen SVGGeneratorContext SVGGraphics2D)
   (org.scilab.forge.jlatexmath TeXConstants TeXFormula)))

(def ^:private font-size
  "JLaTeXMath point size; STYLE_TEXT shrinks inline math naturally."
  18.0)

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
      (svg-hiccup/parse-svg (str out)
                            (svg-hiccup/id-prefix (str notation "\n" display?))))))
