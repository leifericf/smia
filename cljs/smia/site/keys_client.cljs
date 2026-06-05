(ns smia.site.keys-client
  "The opt-in keyboard-shortcuts island a Smia site's pages load when the
   theme sets `:site {:keyboard true}`.

   It binds a handful of reading shortcuts and a help overlay, all of which
   act on controls and links the page already carries, so every shortcut has
   a visible no-JavaScript equivalent: `[`/`]` follow the rendered
   `rel=\"prev\"`/`rel=\"next\"` links, `/` focuses the search box, `d` and
   `f` click the dark and focus controls, `g` follows the contents link, and
   `?` toggles a help dialog (Escape or a click outside closes it).
   Keystrokes are ignored while the reader is typing in a field. With
   JavaScript disabled nothing binds and every action stays reachable by its
   visible control.

   Authored in ClojureScript and compiled to
   `resources/smia/site/keys.js`, mirroring the other islands; host interop
   and `identical?` keep `cljs.core` out of the bundle."
  (:require))

(defn- qs [sel] (.querySelector js/document sel))

(defn- go [sel]
  (let [a (qs sel)] (when a (.click a))))

(defn- typing? [t]
  (when t
    (let [tag (.-tagName t)]
      (or (identical? tag "INPUT")
          (identical? tag "TEXTAREA")
          (identical? tag "SELECT")
          (.-isContentEditable t)))))

;; --- the help overlay --------------------------------------------------------

(defn- help-el [] (qs "[data-kbd-help]"))

(defn- help-open? []
  (let [h (help-el)] (if h (not (.hasAttribute h "hidden")) false)))

(defn- hide-help! []
  (let [h (help-el)] (when h (.setAttribute h "hidden" "hidden"))))

(defn- show-help! []
  (let [h (help-el)] (when h (.removeAttribute h "hidden"))))

(defn- toggle-help! []
  (if (help-open?) (hide-help!) (show-help!)))

(defn- focus-search! []
  (let [i (qs "form.search input")] (when i (.focus i))))

;; --- the key handler ---------------------------------------------------------

(defn- on-key [e]
  (let [k (.-key e)]
    (if (identical? k "Escape")
      (when (help-open?) (hide-help!))
      (when-not (typing? (.-target e))
        (cond
          (identical? k "?") (do (.preventDefault e) (toggle-help!))
          (identical? k "/") (do (.preventDefault e) (focus-search!))
          ;; arrows, plus the vim/reader letters (h/k left, l/j right). The
          ;; bracket keys are avoided: they need AltGr on Nordic, German, and
          ;; French layouts. d/f/g stay clear for the actions below.
          (or (identical? k "ArrowLeft") (identical? k "h") (identical? k "k"))
          (go "a[rel=\"prev\"]")
          (or (identical? k "ArrowRight") (identical? k "l") (identical? k "j"))
          (go "a[rel=\"next\"]")
          (identical? k "d") (go "[data-theme-toggle]")
          (identical? k "f") (go "[data-focus-toggle]")
          (identical? k "g") (go ".book-sidebar-title, .page-nav a")
          :else nil)))))

(defn- wire-help! []
  (when-let [c (qs "[data-kbd-close]")]
    (.addEventListener c "click" (fn [_] (hide-help!))))
  (when-let [h (help-el)]
    ;; a click on the backdrop itself (not its inner panel) dismisses it
    (.addEventListener h "click"
      (fn [e] (when (identical? (.-target e) h) (hide-help!))))))

(defn init []
  (.addEventListener js/document "keydown" on-key)
  (if (identical? "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" (fn [_] (wire-help!)))
    (wire-help!)))
