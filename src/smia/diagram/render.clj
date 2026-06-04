(ns smia.diagram.render
  "PlantUML text -> SVG-Hiccup, rendered in process with the Smetana
   pure-Java layout engine — no Graphviz binary, nothing outside the
   JVM. Optional: requires the PlantUML dependency (the `:diagrams` deps
   alias) — this namespace must only be loaded through
   `smia.svg.resolve`'s `requiring-resolve`.

   A bare diagram body is wrapped in `@startuml`/`@enduml` with the
   Smetana pragma; a source that carries its own `@start…` block is
   passed through as written. PlantUML draws labels as SVG `<text>`,
   which would make every edition depend on fonts at view time (and
   break PDF/X, where nothing un-embedded may render), so the generated
   SVG is re-painted through Batik's GVT bridge with text as shapes:
   like math, a diagram ships as self-contained outline paths. The
   result is parsed back into Hiccup (see `smia.svg.hiccup`) so the
   existing serializers emit it deterministically. Label widths are
   measured with the build machine's font metrics, so output is stable
   per machine and may differ in detail across platforms."
  (:require
   [smia.svg.hiccup :as svg-hiccup]
   [clojure.string :as str])
  (:import
   (java.awt Dimension)
   (java.io ByteArrayOutputStream StringReader StringWriter)
   (net.sourceforge.plantuml FileFormat FileFormatOption SourceStringReader)
   (org.apache.batik.anim.dom SAXSVGDocumentFactory)
   (org.apache.batik.bridge BridgeContext DocumentLoader GVTBuilder
                            UserAgentAdapter)
   (org.apache.batik.dom GenericDOMImplementation)
   (org.apache.batik.svggen SVGGeneratorContext SVGGraphics2D)
   (org.apache.batik.util XMLResourceDescriptor)))

(defn- wrap-source [source]
  (if (str/includes? source "@start")
    source
    (str "@startuml\n!pragma layout smetana\n" source "\n@enduml\n")))

(defn- plantuml-svg
  "Run PlantUML over `source`, returning its SVG XML string."
  [source]
  (let [reader (SourceStringReader. (wrap-source source))
        out    (ByteArrayOutputStream.)]
    (.outputImage reader out (FileFormatOption. FileFormat/SVG))
    (.toString out "UTF-8")))

(defn- outline-text
  "Re-paint `svg-xml` through Batik's GVT bridge onto the SVG generator
   with text as shapes: every label becomes outline paths, so the
   diagram renders identically with no font available — in a browser, an
   e-reader, or FOP under PDF/X."
  [svg-xml]
  (let [factory (SAXSVGDocumentFactory.
                  (XMLResourceDescriptor/getXMLParserClassName))
        doc     (.createSVGDocument factory "smia://diagram"
                                    (StringReader. svg-xml))
        agent   (UserAgentAdapter.)
        bridge  (BridgeContext. agent (DocumentLoader. agent))
        gvt     (.build (GVTBuilder.) bridge doc)
        size    (.getDocumentSize bridge)
        w       (int (Math/ceil (.getWidth size)))
        h       (int (Math/ceil (.getHeight size)))
        out-doc (.createDocument (GenericDOMImplementation/getDOMImplementation)
                                 "http://www.w3.org/2000/svg" "svg" nil)
        ctx     (doto (SVGGeneratorContext/createDefault out-doc)
                  ;; The default comment embeds the Batik version string;
                  ;; pin it so output never varies with the toolchain.
                  (.setComment "diagram"))
        g       (doto (SVGGraphics2D. ctx true)
                  (.setSVGCanvasSize (Dimension. w h)))]
    (.paint gvt g)
    (let [sw (StringWriter.)]
      (.stream g sw false)
      (str sw))))

(defn render-svg
  "Render PlantUML `source` to SVG-Hiccup whose glyphs are outline
   paths. Invalid source renders PlantUML's drawn error description
   rather than throwing — the build succeeds and the problem is visible
   where the diagram would be."
  [source]
  (svg-hiccup/parse-svg (outline-text (plantuml-svg source))
                        (svg-hiccup/id-prefix source)))
