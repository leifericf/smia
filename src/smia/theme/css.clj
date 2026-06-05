(ns smia.theme.css
  "Pure core: compile design tokens into the stylesheet the HTML editions
   share.

   The CSS analogue of `theme.compile`: the same `theme.edn` tokens drive
   every edition, but where FO styling is per-block property maps, HTML
   styling is one generated stylesheet over the classes
   `smia.html.expand` emits. Rules are data — `[[selector prop-map] …]`
   — and `serialize` writes them with properties sorted by name, so equal
   tokens produce byte-identical stylesheets (the same determinism
   discipline as `fo.attrs`). The `:layout` token group is paged-output
   geometry and plays no part here; the reading column is the stylesheet's
   own layout. No IO."
  (:require
   [smia.fo.attrs :as attrs]
   [smia.theme.compile :as compile]
   [clojure.string :as str]))

(def ^:private default-dark
  "The computed dark palette, overridden per key by the theme's `:dark`
   token group."
  {:text             "#e6e6e6"
   :background       "#1a1a1a"
   :link             "#6ea8fe"
   :muted            "#9aa0a6"
   :rule             "#444444"
   :code-background  "#2a2a2a"
   :panel            "#242424"})

(defn- dark-rules
  "When `:site {:dark true}`, an `@media (prefers-color-scheme: dark)`
   wrapper overriding the palette-driven selectors from the dark palette
   (the `:dark` token group over `default-dark`). Empty otherwise, so a book
   that does not opt in emits byte-identical CSS."
  [tokens]
  (when (get-in tokens [:site :dark])
    (let [d (merge default-dark (:dark tokens))]
      [["@media (prefers-color-scheme: dark)"
        ["body" {:color (:text d) :background-color (:background d)}]
        ["a" {:color (:link d)}]
        ["pre" {:background-color (:code-background d)}]
        [".file-bar" {:background-color (:panel d)}]
        [".admonition" {:background-color (:panel d) :border-color (:rule d)}]
        [".sidebar" {:background-color (:panel d) :border-color (:rule d)}]
        [".overview" {:background-color (:panel d)}]
        ["kbd, .button" {:background-color (:panel d) :border-color (:rule d)}]
        ["th, td" {:border-color (:rule d)}]
        ["blockquote" {:color (:muted d)}]
        [".book-sidebar" {:background-color (:panel d)}]]])))

;; --- the variables-based dark layer (site only) --------------------------------
;;
;; The literal stylesheet above is what the EPUB and the default path ship,
;; byte for byte. The site opts into a second representation of the same
;; colors: each becomes a CSS custom property, so one `:root` override under
;; `@media (prefers-color-scheme: dark)` flips the *whole* palette — headings,
;; labels, captions, tokens, and all — rather than the handful of selectors the
;; literal block reaches. Custom-property support is uneven on older e-readers,
;; so this layer never reaches the EPUB.

(def ^:private dark-var-defaults
  "Computed dark values for the custom properties, keyed by bare variable
   name. The `:dark` token group overrides any of them through
   `dark-author-keys`."
  {"--ink"     "#e6e6e6"
   "--bg"      "#1a1a1a"
   "--link"    "#6ea8fe"
   "--muted"   "#9aa0a6"
   "--rule"    "#444444"
   "--code-bg" "#2a2a2a"
   "--panel"   "#242424"
   "--panel-2" "#2d2d2d"
   "--card"    "#333333"
   "--line-no" "#8b949e"
   ;; build-time SVG (diagrams, math) carries dark strokes on a transparent
   ;; ground, so it vanishes on the dark page. The SVG is byte-identical
   ;; across editions and cannot be re-themed per edition, so the site
   ;; inverts its lightness in CSS while keeping hue (hue-rotate undoes the
   ;; hue flip invert causes), so colored diagrams stay recognizable.
   "--media-filter" "invert(1) hue-rotate(180deg)"})

(def ^:private default-dark-code
  "A readable dark syntax-highlight palette. The light defaults
   (`theme.compile/default-code-colors`) are tuned for a light code
   background and read poorly on the dark one, so dark mode brightens them
   by default; a book's `:dark {:code …}` overrides any entry."
  {:keyword "#ff7b72" :string "#a5d6ff" :comment "#8b949e"
   :number  "#79c0ff" :literal "#d2a8ff"})

(def ^:private dark-author-keys
  "Map a `:dark` token-group key onto the variable it overrides, so a book
   tunes the dark palette in the same vocabulary as the light one."
  {:text            "--ink"
   :background      "--bg"
   :link            "--link"
   :muted           "--muted"
   :rule            "--rule"
   :code-background "--code-bg"
   :panel           "--panel"
   :panel-2         "--panel-2"
   :card            "--card"})

(defn- vars->props
  "Turn a `{bare-name → value}` variable map into a property map serialize
   can render (`{:--ink \"#1c1c1c\"}`)."
  [m]
  (into {} (map (fn [[k v]] [(keyword k) v])) m))

(defn- root-light-vars
  "The `:root` rule defining every custom property at its light value: the
   color tokens, the panel surfaces, and the syntax palette."
  [light palette]
  [":root"
   (vars->props
     (merge {"--ink"     (:text light)
             "--bg"      (:background light)
             "--link"    (:link light)
             "--muted"   (:muted light)
             "--rule"    (:rule light)
             "--code-bg" (:code-background light)
             "--panel"   (:panel light)
             "--panel-2" (:panel-2 light)
             "--card"    (:card light)
             "--line-no" (:line-no light)
             "--media-filter" "none"}
            (into {} (map (fn [[kind c]] [(str "--tok-" (name kind)) c])) palette)))])

(defn- dark-var-props
  "The dark palette as a custom-property map: the computed defaults under
   the `:dark` token group, plus any `:dark {:code …}` syntax overrides."
  [tokens]
  (let [overrides (reduce-kv (fn [m author-k var-name]
                               (if-let [v (get (:dark tokens) author-k)]
                                 (assoc m var-name v)
                                 m))
                             {} dark-author-keys)
        dark-tok  (into {} (map (fn [[kind c]] [(str "--tok-" (name kind)) c]))
                        (merge default-dark-code (get-in tokens [:dark :code])))]
    (vars->props (merge dark-var-defaults overrides dark-tok))))

(defn- dark-media-vars
  "An `@media (prefers-color-scheme: dark)` wrapper whose single `:root`
   rule overrides the custom properties with the dark palette, so the OS
   setting governs with no JavaScript."
  [tokens]
  [["@media (prefers-color-scheme: dark)"
    [":root" (dark-var-props tokens)]]])

(defn- toggle-rules
  "The opt-in toggle layer (`:site {:dark {:toggle true}}`): explicit
   `html[data-theme=…]` overrides whose specificity beats both `:root` and
   the media query, so a reader's stored choice wins either way, and the
   styling for the toggle button itself. The button is positioned out of
   flow and tinted from the same variables, so it tracks the active scheme."
  [tokens light palette]
  (let [[_ light-props] (root-light-vars light palette)]
    [["html[data-theme=\"dark\"]" (dark-var-props tokens)]
     ["html[data-theme=\"light\"]" light-props]
     [".theme-toggle" {:position      "fixed"
                       :top           "0.75em"
                       :right         "0.75em"
                       :z-index       "20"
                       :font          "inherit"
                       :font-size     "0.8em"
                       :cursor        "pointer"
                       :padding       "0.3em 0.6em"
                       :color         "var(--ink)"
                       :background    "var(--panel)"
                       :border        "1px solid var(--rule)"
                       :border-radius "4px"}]]))

(defn compile-css
  "Compile validated `tokens` into ordered CSS rules
   `[[selector prop-map] …]`.

   With `{:dark? true}` (the site assembler passes it when the book opts
   into dark mode) the colors are emitted as `var(--…)` references over a
   leading `:root` of light values and a dark `@media` override. The default
   and EPUB path (`:dark?` false) emit the literal stylesheet byte for byte.
   `{:toggle? true}` (which implies `:dark?`) also emits the explicit
   `html[data-theme=…]` overrides the toggle island flips, and the button
   style."
  ([tokens] (compile-css tokens {}))
  ([tokens {:keys [dark? toggle?]}]
   (let [{:keys [color type spacing code]} tokens
         body-family (get type :body-family "serif")
         head-family (get type :heading-family "sans-serif")
         mono-family (get type :mono-family "monospace")
         light       {:text            (get color :text "#1a1a1a")
                      :background      (get color :background "#ffffff")
                      :muted           (get color :muted "#666666")
                      :rule            (get color :rule "#999999")
                      :link            (get color :link "#1a0dab")
                      :code-background (get color :code-background "#f4f4f4")
                      :panel           "#f7f7f7"
                      :panel-2         "#e8e8e8"
                      :card            "#eeeeee"
                      :line-no         "#999999"}
         var-or      (fn [name lit] (if dark? (str "var(" name ")") lit))
         text        (var-or "--ink"     (:text light))
         muted       (var-or "--muted"   (:muted light))
         rule        (var-or "--rule"    (:rule light))
         link        (var-or "--link"    (:link light))
         code-bg     (var-or "--code-bg" (:code-background light))
         bg          (var-or "--bg"      (:background light))
         panel       (var-or "--panel"   (:panel light))
         panel-2     (var-or "--panel-2" (:panel-2 light))
         card        (var-or "--card"    (:card light))
         line-no     (var-or "--line-no" (:line-no light))
         paragraph   (get spacing :paragraph "6pt")
         block       (get spacing :block "8pt")
         palette     (merge compile/default-code-colors code)
         tok-color   (fn [kind c] (if dark? (str "var(--tok-" (name kind) ")") c))]
    (vec
      (concat
        (when dark? [(root-light-vars light palette)])
        [;; reading column and base typography
         ["body" (cond-> {:font-family body-family
                          :font-size   (get type :base-size "11pt")
                          :line-height (get type :line-height "1.4")
                          :color       text
                          :margin      "0"}
                   dark? (assoc :background-color bg))]
         ["main" {:max-width "42em"
                  :margin    "0 auto"
                  :padding   "0 1em 4em"
                  :position  "relative"}]
         ["h1, h2, h3, h4, h5, h6" {:font-family head-family
                                    :color       text
                                    :line-height "1.2"}]
         ["h1" {:font-size (get type :h1-size "20pt")}]
         ["h2" {:font-size (get type :h2-size "16pt")}]
         ["h3" {:font-size (get type :h3-size "13pt")}]
         ["p" {:margin (str "0 0 " paragraph)}]
         ["a" {:color link}]
         ;; a visible focus ring for keyboard users, on every interactive
         ;; element, tinted from the link color so it tracks the scheme.
         ["a:focus-visible, button:focus-visible, summary:focus-visible"
          {:outline        (str "2px solid " link)
           :outline-offset "2px"
           :border-radius  "2px"}]

         ;; code — inline code is sized down to sit level with the serif
         ;; body (monospace x-heights run large); the `pre code` reset keeps
         ;; the two factors from compounding inside listings.
         ["code" {:font-family mono-family
                  :font-size   "0.85em"}]
         ["pre code" {:font-size "1em"}]
         ["pre" {:font-family      mono-family
                 :background-color code-bg
                 :border-left      (str "3pt solid " link)
                 :padding          "6pt 6pt 6pt 8pt"
                 :overflow-x       "auto"
                 :font-size        "0.85em"
                 :margin           (str "0 0 " block)}]
         [".line-no" {:color line-no :user-select "none"}]
         [".file-bar" {:font-family      mono-family
                       :font-size        "0.75em"
                       :font-weight      "bold"
                       :background-color panel-2
                       :padding          "3pt 6pt"}]
         [".annotations" {:font-size "0.85em" :margin (str "4pt 0 " block)}]
         [".annotation-mark" {:font-family      "sans-serif"
                              :font-size        "0.7em"
                              :font-weight      "bold"
                              :color            "#ffffff"
                              :background-color "#555555"
                              :padding          "0 0.35em"
                              :border-radius    "2px"}]

         ;; quotations and callouts
         ["blockquote" {:border-left  (str "3pt solid " rule)
                        :padding-left "10pt"
                        :margin       (str "0 0 " paragraph)
                        :font-style   "italic"
                        :color        muted}]
         [".admonition" {:border           (str "0.75pt solid " rule)
                         :background-color panel
                         :padding          "6pt"
                         :margin           (str block " 0")}]
         [".admonition-title" {:font-weight "bold"}]
         [".sidebar" {:border           (str "0.75pt solid " rule)
                      :background-color panel
                      :padding          "6pt"
                      :margin           (str block " 0")}]
         [".sidebar-title" {:font-weight "bold"}]
         [".overview" {:border-left      (str "3pt solid " rule)
                       :background-color panel
                       :padding          "8pt 10pt"
                       :margin           (str block " 0 12pt")}]
         [".overview-title" {:font-weight "bold"}]
         [".example" {:border-left (str "3pt solid " rule)
                      :padding     "6pt 10pt"
                      :margin      (str block " 0")}]
         [".example-title" {:font-weight "bold"}]
         ["details" {:border-left  (str "1pt solid " rule)
                     :padding-left "10pt"
                     :margin       (str block " 0")}]
         ["summary" {:font-weight "bold" :cursor "pointer"}]

         ;; interface vocabulary: keys, menu paths, buttons, highlight
         ["kbd" {:font-family      mono-family
                 :font-size        "0.85em"
                 :background-color card
                 :border           (str "1px solid " rule)
                 :border-radius    "3px"
                 :padding          "0 0.3em"}]
         [".menu-sep" {:color muted}]
         [".button" {:background-color panel-2
                     :border           (str "1px solid " rule)
                     :border-radius    "3px"
                     :padding          "0 0.4em"}]
         ["mark" {:background-color "#fff3b0"}]
         [".epigraph" {:border-left  "none"
                       :margin-left  "24pt"
                       :font-style   "italic"
                       :color        text}]
         [".attribution" {:font-style "normal"
                          :text-align "right"
                          :color      muted}]

         ;; figures and tables
         ["figure" {:margin     (str "10pt 0")
                    :text-align "center"}]
         ["figure.listing" {:text-align "left"}]
         ["figure img" {:max-width "100%"}]
         ["figcaption" {:font-size  "0.85em"
                        :color      muted
                        :margin-top "4pt"}]
         [".caption-label" {:font-weight "bold"}]
         ["table" {:border-collapse "collapse"
                   :width           "100%"
                   :margin          (str "0 0 " block)}]
         ["th, td" {:border  (str "0.5pt solid " rule)
                    :padding "4pt"}]
         ["caption" {:font-size      "0.85em"
                     :color          muted
                     :caption-side   "bottom"
                     :padding-top    "4pt"}]

         ;; furniture: headers, navigation, footnotes, back matter
         [".chapter-label" {:color muted :margin-bottom "0"}]
         [".chapter-header h1" {:border-bottom  (str "1pt solid " rule)
                                :padding-bottom "6pt"
                                :margin-top     "0.25em"}]
         [".book-author" {:color muted}]
         [".page-nav" {:display         "flex"
                       :gap             "1em"
                       :justify-content "center"
                       :font-size       "0.9em"
                       :margin          "1em auto"
                       :max-width       "42em"
                       :padding         "0 1em"}]
         [".page-footer" {:max-width "42em"
                          :margin    "1em auto 0"
                          :padding   "0 1em"
                          :font-size "0.85em"}]
         ;; chrome navigation reads as quiet text — the contents nav, the
         ;; bottom prev/next, the footer — so the underline stays a signal
         ;; reserved for links in the prose. Hover restores it.
         [".toc a, .page-nav a, .page-footer a"
          {:text-decoration "none" :transition "color 0.15s"}]
         [".toc a:hover, .page-nav a:hover, .page-footer a:hover"
          {:text-decoration "underline"}]

         ;; icon-only previous/next chevrons that sit in the reading column's
         ;; own margins, a visual shortcut beside the labeled bottom nav.
         ;; They anchor to `main` (which lives inside the content column), so
         ;; they never land on the sidebar rail; `sticky` keeps them
         ;; vertically centered as the page scrolls. Tinted from the shared
         ;; colors, so they track the light and dark schemes.
         [".edge-nav" {:position "sticky" :top "50vh" :z-index "15"}]
         [".edge-link" {:position        "absolute"
                        :top             "0"
                        :transform       "translateY(-50%)"
                        :display         "flex"
                        :align-items     "center"
                        :justify-content "center"
                        :width           "1.7em"
                        :height          "2.6em"
                        :font-family     head-family
                        :font-size       "1.5em"
                        :line-height     "1"
                        :text-decoration "none"
                        :color           muted
                        :background-color panel
                        :border          (str "1px solid " rule)
                        :border-radius   "6px"
                        :opacity         "0.55"
                        :transition      "opacity 0.15s, color 0.15s"}]
         [".edge-link:hover" {:opacity "1" :color text}]
         [".edge-prev" {:right "100%" :margin-right "0.6em"}]
         [".edge-next" {:left "100%" :margin-left "0.6em"}]
         [".edit-page" {:color muted}]
         ["details.fold > summary" {:cursor "pointer" :font-weight "bold"}]
         [".toc-list" {:list-style "none" :padding-left "0"}]
         [".toc-list .toc-level-1" {:padding-left "1.5em"}]
         [".toc-list .toc-level-2" {:padding-left "3em"}]
         [".footnotes" {:border-top (str "0.5pt solid " rule)
                        :margin-top "2em"
                        :font-size  "0.85em"}]
         [".noteref" {:text-decoration "none"}]
         [".index-entry" {:margin "0 0 2pt"}]
         [".float-list" {:list-style "none" :padding-left "0"}]

         ;; the :sidebar site layout — a full-height tinted TOC rail beside
         ;; a centered reading column. The rail stretches to the layout's
         ;; height (its background reaches the bottom edge) while the inner
         ;; wrapper stays sticky; `flex-wrap` stacks the two on a narrow
         ;; viewport, so no @media query is needed (the emitter stays flat
         ;; and deterministic).
         [".book-layout" {:display    "flex"
                          :flex-wrap  "wrap"
                          :min-height "100vh"}]
         [".book-sidebar" {:flex             "1 1 15em"
                           :background-color panel
                           :border-right     (str "1px solid " rule)}]
         [".book-sidebar-inner" {:position   "sticky"
                                 :top        "0"
                                 :max-height "100vh"
                                 :overflow-y "auto"
                                 :padding    "2.5em 1.5em"
                                 :font-size  "0.9em"}]
         [".book-sidebar-title" {:font-family   head-family
                                 :font-weight   "700"
                                 :font-size     "1.05em"
                                 :line-height   "1.3"
                                 :text-decoration "none"
                                 :color         text
                                 :display       "block"
                                 :margin-bottom "1.5em"}]
         [".book-sidebar-list" {:list-style "none" :padding-left "0" :margin "0"}]
         [".book-sidebar-list li" {:margin "0.1em 0"}]
         [".book-sidebar-list .toc-level-1" {:padding-left "0.75em"}]
         [".book-sidebar-list .toc-level-2" {:padding-left "1.75em"}]
         ;; rail links are quiet text, not the browser-blue underline: muted
         ;; until hovered or current, with a soft hover plate and a cheap
         ;; color/background transition.
         [".book-sidebar a" {:display         "block"
                             :color           muted
                             :text-decoration "none"
                             :padding         "0.25em 0.6em"
                             :border-radius   "6px"
                             :transition      "color 0.15s, background-color 0.15s"}]
         [".book-sidebar a:hover" {:color text :background-color card}]
         ;; part dividers (the no-link entries) read as small uppercase
         ;; section labels grouping the chapters beneath them.
         [".book-sidebar .part-heading" {:margin         "1.6em 0 0.4em"
                                         :font-family    head-family
                                         :font-size      "0.72em"
                                         :font-weight    "700"
                                         :letter-spacing "0.07em"
                                         :text-transform "uppercase"
                                         :color          muted}]
         [".book-sidebar .current" {:color            link
                                    :font-weight      "700"
                                    :background-color card}]
         [".book-content" {:flex      "999 1 28em"
                           :min-width "0"
                           :padding   "2em 2em 0"}]
         [".book-content main" {:margin "0 auto"}]

         ;; the site-only downloads page
         [".downloads" {:margin (str block " 0")}]
         [".downloads .default" {:font-size   "1.15em"
                                 :font-weight "bold"}]
         [".downloads .note" {:color  muted
                              :margin (str "0 0 " block)}]
         [".downloads ul" {:list-style "none" :padding-left "0"}]
         [".downloads li" {:margin "0 0 4pt"}]

         ;; the search island (opt-in; these rules are inert without it)
         ["form.search" {:position "relative" :margin "0 0 1em"}]
         ["form.search input" {:width      "100%"
                               :box-sizing "border-box"
                               :padding    "0.4em 0.6em"
                               :font       "inherit"
                               :border     (str "1px solid " rule)}]
         [".search-island" {:position "relative"}]
         [".search-popover" {:position   "absolute"
                             :left       "0"
                             :right      "0"
                             :z-index    "10"
                             :background bg
                             :border     (str "1px solid " rule)
                             :box-shadow "0 2px 8px rgba(0, 0, 0, 0.15)"
                             :max-height "60vh"
                             :overflow-y "auto"
                             :padding    "0.5em"
                             :text-align "left"}]
         [".search-cat h4" {:margin         "0.5em 0 0.25em"
                            :color          muted
                            :font-family    head-family
                            :font-size      "0.8em"
                            :text-transform "uppercase"}]
         [".search-cat ul" {:list-style "none" :margin "0" :padding "0"}]
         [".search-cat li a" {:display         "block"
                              :padding         "0.25em 0.4em"
                              :text-decoration "none"}]
         [".search-cat li.active a" {:background code-bg}]
         [".search-hit-snippet" {:display   "block"
                                 :color     muted
                                 :font-size "0.85em"}]
         [".search-fallback-note" {:color muted}]

         ;; the mermaid island: until the script transforms it (or with no
         ;; JavaScript) the source shows as a centered preformatted block
         ["pre.mermaid" {:text-align "center"
                         :background "none"
                         :border-left "none"
                         :overflow-x "auto"}]

         ;; rendered math and diagrams (inline SVG)
         ["svg.math" {:vertical-align "middle"}]
         [".math-display" {:text-align "center"
                           :margin     (str block " 0")}]
         [".diagram" {:text-align "center"
                      :margin     (str block " 0")}]

         ;; paged-media hints (honored when the site is printed)
         [".page-break" {:break-before "page"}]
         [".keep-together" {:break-inside "avoid"}]]

        ;; syntax-highlight palette, book :code group over the defaults
        (map (fn [[kind color]]
               [(str ".tok-" (name kind)) {:color (tok-color kind color)}])
             (sort-by key palette))

        ;; opt-in dark mode. The site (`:dark?`) flips every custom property
        ;; through one `:root` override; the literal path keeps the older
        ;; `@media` block that recolors the palette-driven selectors directly.
        (if dark?
          (dark-media-vars tokens)
          (dark-rules tokens))

        ;; the opt-in toggle: explicit overrides that beat the media query
        ;; both ways, plus the button style. Only reachable under `:dark?`.
        (when toggle? (toggle-rules tokens light palette))

        ;; build-time SVG (diagrams, math) inverts its lightness in the dark
        ;; scheme so dark strokes show; `--media-filter` is `none` in light.
        ;; The filter targets the `svg` itself, not its wrapper: a diagram is
        ;; `div.diagram > svg.diagram` and display math is `div.math-display >
        ;; svg.math`, so filtering both levels would invert twice and cancel.
        ;; Site-only, so the literal/EPUB stylesheet stays byte-identical.
        (when dark?
          [["svg.diagram" {:filter "var(--media-filter)"}]
           ["svg.math" {:filter "var(--media-filter)"}]])

        ;; the theme's :css styling hatch, last so user rules win
        (:css tokens))))))

(defn serialize
  "Serialize `rules` to a CSS string. A rule is `[selector prop-map]`,
   or `[at-rule rule …]` — an `@media`-style wrapper holding plain rules
   one level deep. Properties are sorted by name (via `fo.attrs/pairs`)
   so equal rule data serializes to identical bytes; rule order is the
   vector's order."
  [rules]
  (->> rules
       (map (fn [[selector & [props :as tail]]]
              (if (map? props)
                (str selector " {\n"
                     (->> (attrs/pairs props)
                          (map (fn [[k v]] (str "  " k ": " v ";")))
                          (str/join "\n"))
                     "\n}\n")
                (str selector " {\n" (serialize tail) "}\n"))))
       (str/join "\n")))

(defn css
  "The stylesheet for `tokens`: compile and serialize in one step. The
   site assembler passes `{:dark? true}` to emit the custom-property dark
   layer; the default and EPUB path keep the literal stylesheet."
  ([tokens] (serialize (compile-css tokens)))
  ([tokens opts] (serialize (compile-css tokens opts))))
