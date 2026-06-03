(ns clj-book.site.assemble
  "Pure core: assemble a numbered manuscript into a static site.

   The thinnest possible layer over the shared HTML core: pages come from
   `html.assemble`, the stylesheet from `theme.css`, and this namespace
   serializes both into one page map `{path → content-string}` (the
   Stasis convention, without the dependency). The shell that writes the
   map to disk is `clj-book.site.emit`. No IO."
  (:require
   [clj-book.html.assemble :as html-assemble]
   [clj-book.html.serialize :as html-serialize]
   [clj-book.theme.css :as css]))

(defn assemble
  "Assemble a numbered `book` and theme `tokens` into
   `{:pages {path → content-string} :resources [{:src} …]}`. The page map
   holds every HTML page (doctyped HTML5) plus `styles.css`; `:resources`
   names the image files the pages reference, for the emit shell to copy."
  [book tokens]
  (let [{:keys [pages resources]}
        (html-assemble/assemble
          book {:highlight? (get-in tokens [:type :highlight] false)})]
    {:pages     (into {"styles.css" (css/css tokens)}
                      (map (fn [{:keys [file hiccup]}]
                             [file (html-serialize/serialize
                                     hiccup {:doctype? true})])
                           pages))
     :resources resources}))
