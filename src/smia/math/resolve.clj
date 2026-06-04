(ns smia.math.resolve
  "The assembly-time math pass: render every `[:math]` node's LaTeX
   notation to SVG-Hiccup and stamp it on the node as `:svg`, before the
   editions split. Every edition then carries the same rendered math.

   The renderer itself (smia.math.render) sits behind the optional
   `:math` deps alias and is loaded lazily with `requiring-resolve`, the
   same pattern as the code-validation evaluators: a manuscript with no
   math never loads it, and a manuscript with math but no renderer on the
   classpath fails with a structured error naming the alias. One render
   per distinct `[notation display]` pair (memoized per call), so a
   formula used twice renders once and identically."
  (:require
   [smia.error :as error]))

(defn- unavailable [context]
  (error/ex :smia.math/renderer-unavailable
            (str "Math rendering needs the optional :math alias "
                 "(JLaTeXMath). Compose it with the command, e.g. "
                 "clojure -M:run:math build.")
            (assoc context :requires ":math")))

(defn rendered-svg
  "The SVG-Hiccup this pass stamped on a math node's `attrs`, for the
   per-format expanders. Throws the renderer-unavailable error when the
   node was never stamped (the pass could not run)."
  [attrs]
  (or (:svg attrs)
      (throw (unavailable {:notation (:notation attrs)}))))

;; --- the pass ---------------------------------------------------------------

(defn- math-node? [n]
  (and (vector? n) (= :math (first n)) (map? (second n))))

(defn- contains-math? [form]
  (boolean (some math-node? (tree-seq vector? seq form))))

(defn- renderer
  "Lazily resolve the optional renderer fn `(fn [notation display?] ->
   svg-hiccup)`, or throw naming the alias."
  []
  (try
    (requiring-resolve 'smia.math.render/render-svg)
    (catch Exception e
      (throw (unavailable {:cause (.getMessage e)})))))

(defn- stamp [render form]
  (cond
    (math-node? form)
    (let [a (second form)]
      [:math (assoc a :svg (render (:notation a) (boolean (:display a))))])

    (vector? form) (mapv #(stamp render %) form)
    :else          form))

(defn attach-svg
  "Stamp rendered SVG onto every `[:math]` node of a (numbered)
   manuscript, in both `:sections` content and `:chapters`. A manuscript
   without math is returned unchanged and the renderer is never loaded."
  [manuscript]
  (if-not (or (some #(contains-math? (:content %)) (:sections manuscript))
              (contains-math? (vec (:chapters manuscript))))
    manuscript
    (let [render (memoize @(renderer))]
      (-> manuscript
          (update :sections
                  (fn [sections]
                    (mapv #(cond-> %
                             (:content %) (update :content (partial stamp render)))
                          sections)))
          (update :chapters
                  (fn [chapters]
                    (mapv (partial stamp render) chapters)))))))
