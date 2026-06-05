(ns smia.site.reader-client
  "The opt-in reader-preferences island a Smia site's pages load when the
   theme sets `:site {:reader true}`.

   The site stylesheet exposes the reading column width, the document font
   scale, the color scheme, and a high-contrast variant as custom
   properties and document-element attributes, all with sensible defaults
   so the page is fully readable with no JavaScript. This island layers a
   small control cluster on top: it reads the reader's stored choices from
   `localStorage` and reflects them on the `<html>` element — an inline
   `--reading-width`/`--reading-scale`, and `data-theme`/`data-contrast`
   attributes — which the stylesheet honors. With JavaScript disabled
   nothing runs, the cluster stays hidden, and the defaults govern.

   The bundle loads non-deferred in `<head>` so stored choices apply before
   first paint (no flash of the wrong width, size, or scheme); the controls
   are wired once the document is ready. Authored in ClojureScript and
   compiled to `resources/smia/site/reader.js`, mirroring the search,
   mermaid, and theme islands, so the repository's source stays Clojure.

   Comparisons use `identical?` (host `===`) and the body is host interop,
   so the optimizer strips `cljs.core` and the bundle stays small — this
   script blocks first paint, so its weight matters."
  (:require))

(defn- root [] (.-documentElement js/document))

(defn- get-item [k]
  (try (.getItem (.-localStorage js/window) k)
       (catch :default _ nil)))

(defn- set-item! [k v]
  (try (.setItem (.-localStorage js/window) k v)
       (catch :default _ nil)))

;; --- reading width: a fixed ladder of comfortable measures -------------------

(def ^:private widths #js ["30em" "34em" "38em" "44em" "52em"])

(defn- apply-width! [v]
  (when v (.setProperty (.-style (root)) "--reading-width" v)))

(defn- step-width! [dir]
  (let [cur (get-item "smia-width")
        i   (if cur (.indexOf widths cur) 2)
        i2  (js/Math.max 0 (js/Math.min (- (.-length widths) 1) (+ i dir)))
        v   (aget widths i2)]
    (set-item! "smia-width" v)
    (apply-width! v)))

;; --- font scale: a multiplier on the base size, clamped ----------------------

(defn- apply-scale! [v]
  (when v (.setProperty (.-style (root)) "--reading-scale" v)))

(defn- step-scale! [dir]
  (let [cur (get-item "smia-scale")
        n   (if cur (js/parseFloat cur) 1)
        n2  (/ (js/Math.round (* (js/Math.max 0.8 (js/Math.min 1.6 (+ n (* dir 0.1)))) 10)) 10)
        v   (.toString n2)]
    (set-item! "smia-scale" v)
    (apply-scale! v)))

;; --- contrast: a high-contrast variant ---------------------------------------

(defn- high-contrast? []
  (identical? "high" (.getAttribute (root) "data-contrast")))

(defn- apply-contrast! [v]
  (if (identical? v "high")
    (.setAttribute (root) "data-contrast" "high")
    (.removeAttribute (root) "data-contrast")))

(defn- toggle-contrast! [btn]
  (apply-contrast! (if (high-contrast?) "" "high"))
  (set-item! "smia-contrast" (if (high-contrast?) "high" ""))
  (.setAttribute btn "aria-pressed" (if (high-contrast?) "true" "false")))

;; --- focus mode: shed the chrome, keep the text ------------------------------

(defn- focus-now? []
  (.hasAttribute (root) "data-focus"))

(defn- apply-focus! [v]
  (if (identical? v "on")
    (.setAttribute (root) "data-focus" "")
    (.removeAttribute (root) "data-focus")))

(defn- toggle-focus! [btn]
  (let [on (focus-now?)]
    (apply-focus! (if on "" "on"))
    (set-item! "smia-focus" (if on "" "on"))
    (.setAttribute btn "aria-pressed" (if on "false" "true"))))

;; --- color scheme: the same override the standalone theme island applies -----

(defn- apply-theme! [t]
  (if (or (identical? t "dark") (identical? t "light"))
    (.setAttribute (root) "data-theme" t)
    (.removeAttribute (root) "data-theme")))

(defn- dark-now? []
  (let [attr (.getAttribute (root) "data-theme")]
    (if (identical? attr "dark")
      true
      (if (identical? attr "light")
        false
        (let [mm (.-matchMedia js/window)]
          (if mm
            (.-matches (.matchMedia js/window "(prefers-color-scheme: dark)"))
            false))))))

(defn- toggle-theme! [btn]
  (let [next (if (dark-now?) "light" "dark")]
    (apply-theme! next)
    (set-item! "smia-theme" next)
    (.setAttribute btn "aria-pressed" (if (dark-now?) "true" "false"))))

;; --- wiring ------------------------------------------------------------------

(defn- on-click [sel f]
  (let [el (.querySelector js/document sel)]
    (when el (.addEventListener el "click" f))))

(defn- wire-panel! []
  (let [panel  (.querySelector js/document "[data-reader-panel]")
        toggle (.querySelector js/document "[data-reader-toggle]")]
    (when (and panel toggle)
      (.addEventListener toggle "click"
        (fn [_]
          (let [open (.hasAttribute panel "hidden")]
            (if open
              (.removeAttribute panel "hidden")
              (.setAttribute panel "hidden" "hidden"))
            (.setAttribute toggle "aria-expanded" (if open "true" "false"))))))))

(defn- wire-pressed! [sel pressed?]
  (let [btn (.querySelector js/document sel)]
    (when btn
      (.setAttribute btn "aria-pressed" (if (pressed?) "true" "false"))
      btn)))

(defn- wire! []
  ;; reveal the cluster the no-JS page kept hidden
  (let [controls (.querySelector js/document "[data-reader-controls]")]
    (when controls (.removeAttribute controls "hidden")))
  (wire-panel!)
  (on-click "[data-reader-width=\"-\"]" (fn [_] (step-width! -1)))
  (on-click "[data-reader-width=\"+\"]" (fn [_] (step-width! 1)))
  (on-click "[data-reader-scale=\"-\"]" (fn [_] (step-scale! -1)))
  (on-click "[data-reader-scale=\"+\"]" (fn [_] (step-scale! 1)))
  (when-let [c (wire-pressed! "[data-reader-contrast]" high-contrast?)]
    (.addEventListener c "click" (fn [_] (toggle-contrast! c))))
  (when-let [f (wire-pressed! "[data-focus-toggle]" focus-now?)]
    (.addEventListener f "click" (fn [_] (toggle-focus! f))))
  (when-let [t (wire-pressed! "[data-theme-toggle]" dark-now?)]
    (.addEventListener t "click" (fn [_] (toggle-theme! t)))))

(defn init []
  ;; pre-paint: reflect every stored choice before the page is drawn
  (apply-width! (get-item "smia-width"))
  (apply-scale! (get-item "smia-scale"))
  (apply-contrast! (get-item "smia-contrast"))
  (apply-focus! (get-item "smia-focus"))
  (apply-theme! (get-item "smia-theme"))
  ;; the cluster lives in <body>, so wire it once the document is ready
  (if (identical? "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" (fn [_] (wire!)))
    (wire!)))
