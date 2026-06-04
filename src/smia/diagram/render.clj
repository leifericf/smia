(ns smia.diagram.render
  "PlantUML text -> SVG-Hiccup, rendered in process with the Smetana
   pure-Java layout engine — no Graphviz binary, nothing outside the
   JVM. Optional: requires the PlantUML dependency (the `:diagrams` deps
   alias) — this namespace must only be loaded through
   `smia.svg.resolve`'s `requiring-resolve`.

   A bare diagram body is wrapped in `@startuml`/`@enduml` with the
   Smetana pragma; a source that carries its own `@start…` block is
   passed through as written. The generated XML is parsed back into
   Hiccup (comments — where PlantUML stamps its version — are dropped,
   generated ids are content-hash prefixed), so the existing serializers
   emit it deterministically. PlantUML draws labels as SVG `<text>`, so
   it measures fonts at build time: output is stable on one machine and
   may differ across platforms with different font metrics."
  (:require
   [smia.svg.hiccup :as svg-hiccup]
   [clojure.string :as str])
  (:import
   (java.io ByteArrayOutputStream)
   (net.sourceforge.plantuml FileFormat FileFormatOption SourceStringReader)))

(defn- wrap-source [source]
  (if (str/includes? source "@start")
    source
    (str "@startuml\n!pragma layout smetana\n" source "\n@enduml\n")))

(defn render-svg
  "Render PlantUML `source` to SVG-Hiccup. Invalid source renders
   PlantUML's drawn error description rather than throwing — the build
   succeeds and the problem is visible where the diagram would be."
  [source]
  (let [reader (SourceStringReader. (wrap-source source))
        out    (ByteArrayOutputStream.)]
    (.outputImage reader out (FileFormatOption. FileFormat/SVG))
    (svg-hiccup/parse-svg (.toString out "UTF-8")
                          (svg-hiccup/id-prefix source))))
