(ns smia.site.mermaid-client
  "The opt-in mermaid island a Smia site's pages load when the theme sets
   `:site {:mermaid true}` (or `{:mermaid {:src \"…\"}}`).

   Every mermaid diagram is emitted as `<pre class=\"mermaid\">source</pre>`,
   so with JavaScript disabled the reader still sees the diagram's source.
   This island renders those blocks at load time by running a globally
   present `window.mermaid`. The mermaid library itself is not vendored: the
   theme's optional `:src` adds a plain loader script (a UMD build that sets
   `window.mermaid`) ahead of this bundle. Authored in ClojureScript and
   compiled to `resources/smia/site/mermaid.js`, mirroring the search island,
   so the repository's source stays Clojure."
  (:require))

(defn init []
  (when-let [^js mermaid (.-mermaid js/window)]
    (.initialize mermaid #js {:startOnLoad false})
    (.run mermaid #js {:querySelector "pre.mermaid"})))
