(ns smia.svg.resolve
  "The assemble-time text-to-SVG pass: render every `[:math]` and
   `[:diagram]` node's source text to SVG-Hiccup and stamp it on the
   node as `:svg`, before the editions split. Every edition then carries
   the same rendered image.

   The renderers sit behind optional deps aliases and are loaded lazily
   with `requiring-resolve`, the same pattern as the code-validation
   evaluators: a manuscript using neither never loads them, and a
   manuscript that needs one without its dependency on the classpath
   fails with a structured error naming the alias. One render per
   distinct input (memoized per call), so a formula or diagram used
   twice renders once and identically."
  (:require
   [smia.error :as error]))

(def renderables
  "Tag -> how to render it: the renderer var, the deps alias that
   provides it, the structured error types, the attrs that name the
   source, and how to derive the render arguments from the node's attrs."
  {:math    {:render   'smia.math.render/render-svg
             :requires ":math"
             :error    :smia.math/renderer-unavailable
             :failed   :smia.math/render-failed
             :source-keys [:notation]
             :label    "Math rendering (JLaTeXMath)"
             :args     (fn [a] [(:notation a) (boolean (:display a))])}
   :diagram {:render   'smia.diagram.render/render-svg
             :requires ":diagrams"
             :error    :smia.diagram/renderer-unavailable
             :failed   :smia.diagram/render-failed
             :source-keys [:source]
             :label    "Diagram rendering (PlantUML)"
             :args     (fn [a] [(:source a)])}})

(defn- unavailable [tag context]
  (let [{:keys [error requires label]} (get renderables tag)]
    (error/ex error
              (str label " needs the optional " requires " alias. "
                   "Compose it with the command, e.g. clojure -M:run"
                   requires " build.")
              (assoc context :requires requires))))

(defn rendered-svg
  "The SVG-Hiccup this pass stamped on a `tag` node's `attrs`, for the
   per-format expanders. Throws the tag's renderer-unavailable error
   when the node was never stamped (the pass could not run)."
  [tag attrs]
  (or (:svg attrs)
      (throw (unavailable tag (select-keys attrs [:notation :source])))))

;; --- the pass ---------------------------------------------------------------

(defn- renderable-node?
  "A node the build-time SVG pass renders. A `:diagram` with a non-default
   `:engine` (e.g. `:mermaid`) is client-rendered, so it is left untouched
   here and handled at expansion time; naming the default engine
   (`:plantuml`) explicitly is the same as omitting it."
  [n]
  (and (vector? n) (contains? renderables (first n)) (map? (second n))
       (contains? #{nil :plantuml} (:engine (second n)))))

(defn- contains-renderable? [form]
  (boolean (some renderable-node? (tree-seq vector? seq form))))

(defn- renderer
  "Lazily resolve `tag`'s renderer fn, or throw naming its alias."
  [tag]
  (try
    @(requiring-resolve (get-in renderables [tag :render]))
    (catch Exception e
      (throw (unavailable tag {:cause (.getMessage e)})))))

(defn- render-one
  "Render one node's source, turning any renderer exception into a
   structured error naming the offending source."
  [render-for tag a]
  (let [{:keys [args failed source-keys label]} (get renderables tag)]
    (try
      (apply (render-for tag) (args a))
      (catch Exception e
        (throw (error/ex failed
                         (str label " failed: " (.getMessage e))
                         (assoc (select-keys a source-keys)
                                :cause (.getMessage e))))))))

(defn- stamp [render-for form]
  (cond
    (renderable-node? form)
    (let [[tag a] form]
      [tag (assoc a :svg (render-one render-for tag a))])

    (vector? form) (mapv #(stamp render-for %) form)
    :else          form))

(defn attach-svg
  "Stamp rendered SVG onto every renderable node of a (numbered)
   manuscript, in both `:sections` content and `:chapters`. A manuscript
   with nothing to render is returned unchanged and no renderer is ever
   loaded. Each kind's renderer loads once and renders are memoized."
  [manuscript]
  (if-not (or (some #(contains-renderable? (:content %)) (:sections manuscript))
              (contains-renderable? (vec (:chapters manuscript))))
    manuscript
    (let [render-for (memoize (fn [tag] (memoize (renderer tag))))]
      (-> manuscript
          (update :sections
                  (fn [sections]
                    (mapv #(cond-> %
                             (:content %) (update :content (partial stamp render-for)))
                          sections)))
          (update :chapters
                  (fn [chapters]
                    (mapv (partial stamp render-for) chapters)))))))
