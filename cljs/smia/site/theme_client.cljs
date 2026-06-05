(ns smia.site.theme-client
  "The opt-in dark-mode toggle island a Smia site's pages load when the
   theme sets `:site {:dark {:toggle true}}`.

   The site stylesheet already follows the operating-system scheme through
   an `@media (prefers-color-scheme: dark)` block, so dark mode works with
   no JavaScript at all. This island layers a reader-controlled override on
   top: it reads a stored choice from `localStorage` and reflects it on the
   `<html>` element as `data-theme`, which the explicit
   `html[data-theme=…]` rules honor over the media query. With JavaScript
   disabled nothing runs, the button stays hidden, and the OS setting still
   governs.

   The bundle is loaded non-deferred in `<head>` so the stored choice
   applies before first paint (no flash); the button itself is wired once
   the document is ready. Authored in ClojureScript and compiled to
   `resources/smia/site/theme.js`, mirroring the search and mermaid
   islands, so the repository's source stays Clojure."
  (:require))

(def ^:private storage-key "smia-theme")

(defn- stored
  "The reader's stored scheme choice (\"dark\"/\"light\"), or nil. Reading
   localStorage can throw (private mode, disabled storage); treat any
   failure as no choice."
  []
  (try (.getItem (.-localStorage js/window) storage-key)
       (catch :default _ nil)))

(defn- store! [v]
  (try (.setItem (.-localStorage js/window) storage-key v)
       (catch :default _ nil)))

(defn- apply-theme!
  "Reflect `theme` on the document element. A nil or unknown value clears
   the attribute, handing control back to the media query. Comparisons use
   `identical?` (host `===`) so the bundle stays pure interop and the
   optimizer strips cljs.core — this script blocks first paint, so its
   weight matters."
  [theme]
  (let [root (.-documentElement js/document)]
    (if (or (identical? theme "dark") (identical? theme "light"))
      (.setAttribute root "data-theme" theme)
      (.removeAttribute root "data-theme"))))

(defn- dark-now?
  "Whether the page is currently showing the dark scheme: the explicit
   choice if one is set, else the operating-system preference."
  []
  (let [attr (.getAttribute (.-documentElement js/document) "data-theme")]
    (if (identical? attr "dark")
      true
      (if (identical? attr "light")
        false
        (let [mm (.-matchMedia js/window)]
          (if mm
            (.-matches (.matchMedia js/window "(prefers-color-scheme: dark)"))
            false))))))

(defn- pressed! [btn]
  (.setAttribute btn "aria-pressed" (if (dark-now?) "true" "false")))

(defn- wire-button! []
  (let [btn (.querySelector js/document "[data-theme-toggle]")]
    (when btn
      (.removeAttribute btn "hidden")
      (pressed! btn)
      (.addEventListener btn "click"
        (fn [_]
          (let [next (if (dark-now?) "light" "dark")]
            (apply-theme! next)
            (store! next)
            (pressed! btn)))))))

(defn init []
  ;; pre-paint: the document element exists while <head> is parsed
  (apply-theme! (stored))
  ;; the button lives in <body>, so wire it once the document is ready
  (if (identical? "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" (fn [_] (wire-button!)))
    (wire-button!)))
