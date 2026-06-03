(ns clj-book.fo.fop-config
  "Pure core: build the FOP configuration for print hardening.

   Turns a `book.edn` `:book/print-x` map into the FOP configuration XML
   (the fop.xconf shape, built in memory — no file on disk): font
   registration with one triplet per provided style, and, for the
   `:print-x` edition, the PDF/X-4 conformance mode plus the ICC output
   intent. Font and ICC paths stay book-relative; FOP resolves them
   against the renderer's base URI (the book root). The shell
   (`fo.render`) parses the string with FOP's configuration builder.
   No IO."
  (:require
   [clj-book.fo.serialize :as serialize]))

(def ^:private style-triplets
  "Provided font style -> its FOP triplet style/weight, in output order."
  [[:normal      {:style "normal" :weight "normal"}]
   [:bold        {:style "normal" :weight "bold"}]
   [:italic      {:style "italic" :weight "normal"}]
   [:bold-italic {:style "italic" :weight "bold"}]])

(defn- font-elements
  "One `<font>` per provided style of `font`, each with its triplet."
  [{:keys [family] :as font}]
  (for [[style-key {:keys [style weight]}] style-triplets
        :let [url (get font style-key)]
        :when url]
    [:font {:embed-url url}
     [:font-triplet {:name family :style style :weight weight}]]))

(defn xconf
  "The FOP configuration XML string for a validated `:book/print-x` map.
   Fonts are always registered (every PDF edition embeds them, so the
   book's typography does not depend on the conformance mode); with
   `:pdf-x?` the renderer is additionally put into PDF/X-4 mode with the
   configured ICC output intent."
  [{:keys [output-intent fonts]} {:keys [pdf-x?]}]
  (serialize/serialize
    [:fop {:version "1.0"}
     [:renderers
      (into [:renderer {:mime "application/pdf"}]
            (concat
              (when pdf-x?
                [[:pdf-x-mode "PDF/X-4"]
                 [:output-profile (:icc output-intent)]])
              [(into [:fonts] (mapcat font-elements fonts))]))]]))
