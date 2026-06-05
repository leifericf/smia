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
        [".book-sidebar" {:background-color (:panel d)}]
        [".book-mobile-contents" {:background-color (:panel d)}]]])))

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
   "--media-filter" "invert(1) hue-rotate(180deg)"
   ;; the light `mark` yellow glares on the dark page; dim it to an amber
   ;; the light ink still reads on
   "--mark-bg" "#5c4d20"
   ;; black shadows vanish against the dark page, so each elevation
   ;; deepens to stay visible
   "--shadow-button"  "0 1px 3px rgba(0, 0, 0, 0.5)"
   "--shadow-popover" "0 2px 8px rgba(0, 0, 0, 0.55)"
   "--shadow-panel"   "0 4px 16px rgba(0, 0, 0, 0.55)"
   "--shadow-overlay" "0 8px 32px rgba(0, 0, 0, 0.75)"})

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
             "--media-filter" "none"
             "--mark-bg" "#fff3b0"
             "--shadow-button"  "0 1px 3px rgba(0, 0, 0, 0.08)"
             "--shadow-popover" "0 2px 8px rgba(0, 0, 0, 0.15)"
             "--shadow-panel"   "0 4px 16px rgba(0, 0, 0, 0.15)"
             "--shadow-overlay" "0 8px 32px rgba(0, 0, 0, 0.3)"}
            (into {} (map (fn [[kind c]] [(str "--tok-" (name kind)) c])) palette)))])

(defn- root-vars
  "The leading `:root` rule: the color custom properties at their light
   values, and — when the reader controls are on — the reading-preference
   variables (`--reading-width`, `--reading-scale`) at their defaults, the
   values the island overrides per reader."
  [light palette reader? measure]
  (let [[sel props] (root-light-vars light palette)]
    [sel (cond-> props
           reader? (assoc :--reading-width measure
                          :--reading-scale "1"))]))

(def ^:private contrast-light
  "The high-contrast override for the light scheme: maximal text contrast
   and a darker rule, leaving the background white."
  {:--ink "#000000" :--muted "#2b2b2b" :--rule "#767676"})

(def ^:private contrast-dark
  "The high-contrast override for the dark scheme: white on black."
  {:--ink "#ffffff" :--bg "#000000" :--muted "#d0d0d0" :--rule "#909090"})

(defn- contrast-rules
  "The opt-in high-contrast overrides (`:site {:reader true}`): a reader who
   chooses high contrast sets `data-contrast=\"high\"` on the document
   element, and these override the color variables accordingly. The light
   variant always applies; when dark mode is on, the dark variant covers the
   OS scheme and both explicit choices, so contrast and theme compose."
  [dark?]
  (cond-> [["html[data-contrast=\"high\"]" contrast-light]]
    dark?
    (into [["@media (prefers-color-scheme: dark)"
            ["html[data-contrast=\"high\"]" contrast-dark]]
           ["html[data-theme=\"dark\"][data-contrast=\"high\"]" contrast-dark]
           ["html[data-theme=\"light\"][data-contrast=\"high\"]" contrast-light]])))

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
    [["html[data-theme=\"dark\"]" (assoc (dark-var-props tokens)
                                         :color-scheme "dark")]
     ["html[data-theme=\"light\"]" (assoc light-props
                                          :color-scheme "light")]
     [".theme-toggle" {:position      "fixed"
                       :top           "1em"
                       :right         "1em"
                       :z-index       "20"
                       :font          "inherit"
                       :font-size     "0.8em"
                       :line-height   "1"
                       :cursor        "pointer"
                       :padding       "0.45em 0.85em"
                       :color         "var(--muted)"
                       :background    "var(--panel)"
                       :border        "1px solid var(--rule)"
                       :border-radius "999px"
                       :box-shadow    "var(--shadow-button)"
                       :transition    "color 0.15s, background-color 0.15s, border-color 0.15s"}]
     [".theme-toggle:hover" {:color "var(--ink)" :border-color "var(--muted)"}]]))

(defn- style-context
  "Resolve `tokens` and the option flags into the bindings the rule
   builders share — the type families, the spacing steps, the light
   palette, and each color bound to either its literal value or its
   `var(--…)` reference. One map, computed once, so every builder draws
   the same values."
  [tokens {:keys [dark? reader?]}]
  (let [{:keys [color type spacing code]} tokens
        ;; the custom-property layer turns on for either site affordance:
        ;; dark mode flips the color variables, the reader controls add
        ;; width/scale variables and a high-contrast override. Both need the
        ;; colors expressed as `var(--…)` over a leading `:root`.
        vars?     (or dark? reader?)
        light     {:text            (get color :text "#1a1a1a")
                   :background      (get color :background "#ffffff")
                   :muted           (get color :muted "#666666")
                   :rule            (get color :rule "#999999")
                   :link            (get color :link "#1a0dab")
                   :code-background (get color :code-background "#f4f4f4")
                   :panel           "#f7f7f7"
                   :panel-2         "#e8e8e8"
                   :card            "#eeeeee"
                   :line-no         "#999999"}
        var-or    (fn [name lit] (if vars? (str "var(" name ")") lit))
        base-size (get type :base-size "11pt")
        ;; one fluid reading measure shared by the column and its detached
        ;; furniture (the bottom nav, the footer), so they stay aligned: a
        ;; comfortable line length that grows a little on wide screens and
        ;; never forces a horizontal scroll on a phone. The min sits above a
        ;; phone's width, so there the cap never binds and the column is full
        ;; width less its padding; no width media query is needed.
        measure   "clamp(32em, 90vw, 44em)"
        palette   (merge compile/default-code-colors code)]
    {:tokens      tokens
     :type        type
     :vars?       vars?
     :dark?       dark?
     :reader?     reader?
     :light       light
     :palette     palette
     :var-or      var-or
     :tok-color   (fn [kind c] (if vars? (str "var(--tok-" (name kind) ")") c))
     :body-family (get type :body-family "serif")
     :head-family (get type :heading-family "sans-serif")
     :mono-family (get type :mono-family "monospace")
     :base-size   base-size
     :measure     measure
     ;; under the reader controls the column width and the document font
     ;; size are reader-driven variables; otherwise they are the static
     ;; fluid measure and the base size.
     :col-width   (if reader? "var(--reading-width)" measure)
     :font-size   (if reader?
                    (str "calc(" base-size " * var(--reading-scale))")
                    base-size)
     :paragraph   (get spacing :paragraph "6pt")
     :block       (get spacing :block "8pt")
     :text        (var-or "--ink"     (:text light))
     :muted       (var-or "--muted"   (:muted light))
     :rule        (var-or "--rule"    (:rule light))
     :link        (var-or "--link"    (:link light))
     :code-bg     (var-or "--code-bg" (:code-background light))
     :bg          (var-or "--bg"      (:background light))
     :panel       (var-or "--panel"   (:panel light))
     :panel-2     (var-or "--panel-2" (:panel-2 light))
     :card        (var-or "--card"    (:card light))
     :line-no     (var-or "--line-no" (:line-no light))}))

(defn- base-rules
  "Reading column and base typography."
  [{:keys [type vars? body-family head-family font-size col-width
           paragraph text link bg]}]
  [["body" (cond-> {:font-family body-family
                    :font-size   font-size
                    :line-height (get type :line-height "1.4")
                    :color       text
                    :margin      "0"}
             vars? (assoc :background-color bg))]
   ["main" {:max-width col-width
            :margin    "0 auto"
            :padding   "0 clamp(1em, 4vw, 2em) 4em"
            :position  "relative"}]
   ["h1, h2, h3, h4, h5, h6" {:font-family head-family
                              :color       text
                              :line-height "1.2"}]
   ["h1" {:font-size (get type :h1-size "20pt")}]
   ["h2" {:font-size   (get type :h2-size "16pt")
          :margin-top  "1.6em"
          :margin-bottom "0.5em"}]
   ["h3" {:font-size   (get type :h3-size "13pt")
          :margin-top  "1.3em"
          :margin-bottom "0.4em"}]
   ["p" {:margin (str "0 0 " paragraph)}]
   ["a" {:color link}]
   ;; a visible focus ring for keyboard users, on every interactive
   ;; element, tinted from the link color so it tracks the scheme.
   ["a:focus-visible, button:focus-visible, summary:focus-visible"
    {:outline        (str "2px solid " link)
     :outline-offset "2px"
     :border-radius  "2px"}]])

(defn- code-rules
  "Code — inline code is sized down to sit level with the serif body
   (monospace x-heights run large); the `pre code` reset keeps the two
   factors from compounding inside listings."
  [{:keys [mono-family code-bg link block line-no panel-2]}]
  [["code" {:font-family mono-family
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
                        :border-radius    "2px"}]])

(defn- callout-rules
  "Quotations and callouts."
  [{:keys [rule panel block paragraph muted]}]
  [["blockquote" {:border-left  (str "3pt solid " rule)
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
   ["summary" {:font-weight "bold" :cursor "pointer"}]])

(defn- ui-vocab-rules
  "Interface vocabulary: keys, menu paths, buttons, highlight."
  [{:keys [vars? var-or mono-family card rule muted panel-2 text]}]
  [["kbd" {:font-family      mono-family
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
   ["mark" (cond-> {:background-color (var-or "--mark-bg" "#fff3b0")}
             ;; with color-scheme declared the UA may flip its own
             ;; mark text color; pin it to the palette ink
             vars? (assoc :color "var(--ink)"))]
   [".epigraph" {:border-left  "none"
                 :margin-left  "24pt"
                 :font-style   "italic"
                 :color        text}]
   [".attribution" {:font-style "normal"
                    :text-align "right"
                    :color      muted}]])

(defn- figure-table-rules
  "Figures and tables."
  [{:keys [muted block rule]}]
  [["figure" {:margin     (str "10pt 0")
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
               :padding-top    "4pt"}]])

(defn- furniture-rules
  "Furniture: headers, navigation, footnotes, back matter."
  [{:keys [muted rule text head-family col-width]}]
  [[".chapter-label" {:color muted :margin-bottom "0"}]
   [".chapter-header h1" {:border-bottom  (str "1pt solid " rule)
                          :padding-bottom "6pt"
                          :margin-top     "0.25em"}]
   [".book-author" {:color muted}]
   ;; the home page's title block. A plain landing centers its title
   ;; above the contents; a title-card landing (the sidebar layout,
   ;; whose rail already carries the contents) becomes a full-height
   ;; centered cover so the page does not read as empty.
   [".book-header" {:text-align "center"}]
   [".book-header.cover" {:display         "flex"
                          :flex-direction  "column"
                          :justify-content "center"
                          :align-items     "center"
                          :min-height      "72vh"}]
   [".book-header.cover h1" {:font-size   "clamp(2em, 6vw, 3.2em)"
                             :line-height "1.1"
                             :margin      "0 0 0.5em"}]
   [".book-header.cover .book-author" {:font-size "1.15em"}]
   [".page-nav" {:display         "flex"
                 :gap             "1em"
                 :justify-content "center"
                 :font-size       "0.9em"
                 :margin          "1em auto"
                 :max-width       col-width
                 :padding         "0 1em"}]
   [".page-footer" {:max-width col-width
                    :margin    "1em auto 0"
                    :padding   "0 1em"
                    :font-size "0.85em"}]
   ;; chrome navigation reads as quiet muted text — the contents nav,
   ;; the bottom prev/next, the footer — matching the sidebar rail, so
   ;; the link blue and the underline stay signals reserved for the
   ;; prose. Hover darkens to the body ink.
   [".toc a, .page-nav a, .page-footer a"
    {:color muted :text-decoration "none" :transition "color 0.15s"}]
   [".toc a:hover, .page-nav a:hover, .page-footer a:hover"
    {:color text}]

   ;; faint previous/next chevrons in the reading column's margins: a
   ;; bare glyph, no box, kept well clear of the text and nearly
   ;; invisible until hovered. The labeled bottom nav and the keyboard
   ;; shortcuts carry the real navigation; these are only a hint. They
   ;; anchor to `main` (inside the content column), so they never land
   ;; on the sidebar rail; `sticky` keeps them centered as the page
   ;; scrolls.
   [".edge-nav" {:position "sticky" :top "50vh" :z-index "15"}]
   [".edge-link" {:position        "absolute"
                  :top             "0"
                  :transform       "translateY(-50%)"
                  :font-family     head-family
                  :font-size       "1.6em"
                  :line-height     "1"
                  :text-decoration "none"
                  :color           muted
                  :opacity         "0.3"
                  :transition      "opacity 0.15s, color 0.15s"}]
   [".edge-link:hover" {:opacity "0.9" :color text}]
   [".edge-prev" {:right "100%" :margin-right "1.75em"}]
   [".edge-next" {:left "100%" :margin-left "1.75em"}]
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
   [".float-list" {:list-style "none" :padding-left "0"}]])

(defn- sidebar-rules
  "The :sidebar site layout — a full-height tinted TOC rail beside a
   centered reading column. The rail stretches to the layout's height
   (its background reaches the bottom edge) while the inner wrapper
   stays sticky. Below the stylesheet's one width breakpoint (the media
   block in `adaptive-rules`) the rail yields to the
   `.book-mobile-contents` disclosure."
  [{:keys [panel rule head-family text muted card link]}]
  [[".book-layout" {:display    "flex"
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
   [".book-content main" {:margin "0 auto"}]])

(defn- mobile-contents-rules
  "The narrow-screen contents fold: a compact, closed disclosure that
   replaces the rail below the width breakpoint. Hidden by default; the
   media block in `adaptive-rules` shows it. Styled like the rail (same
   panel/rule bindings, so dark and contrast flow through), sticky so
   the contents stay one tap away while reading."
  [{:keys [panel rule head-family text]}]
  [[".book-mobile-contents" {:display          "none"
                             :position         "sticky"
                             :top              "0"
                             :z-index          "15"
                             :background-color panel
                             :border-bottom    (str "1px solid " rule)}]
   [".book-mobile-summary" {:cursor      "pointer"
                            :padding     "0.8em 1em"
                            :font-family head-family
                            :font-weight "700"
                            :font-size   "0.9em"
                            :color       text}]
   [".book-mobile-contents[open] .book-mobile-summary"
    {:border-bottom (str "1px solid " rule)}]
   [".book-mobile-contents .book-sidebar-list"
    {:padding "0.5em 1em 1em"}]
   [".book-mobile-contents form.search" {:margin "0.5em 1em"}]])

(defn- downloads-rules
  "The site-only downloads page."
  [{:keys [block muted]}]
  [[".downloads" {:margin (str block " 0")}]
   [".downloads .default" {:font-size   "1.15em"
                           :font-weight "bold"}]
   [".downloads .note" {:color  muted
                        :margin (str "0 0 " block)}]
   [".downloads ul" {:list-style "none" :padding-left "0"}]
   [".downloads li" {:margin "0 0 4pt"}]])

(defn- search-rules
  "The search island (opt-in; these rules are inert without it)."
  [{:keys [rule bg var-or muted head-family code-bg]}]
  [["form.search" {:position "relative" :margin "0 0 1em"}]
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
                       :box-shadow (var-or "--shadow-popover"
                                           "0 2px 8px rgba(0, 0, 0, 0.15)")
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
   [".search-fallback-note" {:color muted}]])

(defn- media-rules
  "Rendered media: the mermaid island's source block (until the script
   transforms it, or with no JavaScript, it shows as a centered
   preformatted block), and inline SVG math and diagrams."
  [{:keys [block]}]
  [["pre.mermaid" {:text-align "center"
                   :background "none"
                   :border-left "none"
                   :overflow-x "auto"}]
   ["svg.math" {:vertical-align "middle"}]
   [".math-display" {:text-align "center"
                     :margin     (str block " 0")}]
   [".diagram" {:text-align "center"
                :margin     (str block " 0")}]])

(def ^:private adaptive-rules
  "Paged-media hints (honored when the site is printed), the
   reduced-motion collapse, and the stylesheet's one width breakpoint.
   Everything else stays fluid (flex-wrap + clamp), but below the point
   where the rail and the reading column stop fitting side by side, the
   rail folds into the contents disclosure and the margin chevrons
   (which would overflow a phone's viewport) yield to the labeled
   bottom prev/next."
  [[".page-break" {:break-before "page"}]
   [".keep-together" {:break-inside "avoid"}]
   ;; a reader who asks the system for less motion gets none: every
   ;; transition and animation collapses to an instant.
   ["@media (prefers-reduced-motion: reduce)"
    ["*, ::before, ::after" {:transition-duration "0.01ms !important"
                             :animation-duration  "0.01ms !important"}]]
   ["@media (max-width: 48em)"
    [".book-sidebar" {:display "none"}]
    [".book-mobile-contents" {:display "block" :flex "1 1 100%"}]
    [".edge-nav" {:display "none"}]]])

(defn- palette-rules
  "Syntax-highlight palette, book :code group over the defaults."
  [{:keys [palette tok-color]}]
  (map (fn [[kind color]]
         [(str ".tok-" (name kind)) {:color (tok-color kind color)}])
       (sort-by key palette)))

(defn- reader-control-rules
  "The opt-in reader controls (`:site {:reader true}`): the control
   cluster, the preference panel, the breadcrumb, focus mode, and the
   reading-progress bar. Everything tints from the scheme variables."
  [{:keys [head-family]}]
  [[".reader-controls" {:position "fixed" :top "1em" :right "1em"
                        :z-index "20"}]
   [".reader-button" {:font          "inherit"
                      :font-size     "0.8em"
                      :line-height   "1"
                      :cursor        "pointer"
                      :padding       "0.45em 0.85em"
                      :color         "var(--muted)"
                      :background    "var(--panel)"
                      :border        "1px solid var(--rule)"
                      :border-radius "999px"
                      :box-shadow    "var(--shadow-button)"
                      :transition    "color 0.15s, background-color 0.15s, border-color 0.15s"}]
   [".reader-button:hover" {:color "var(--ink)" :border-color "var(--muted)"}]
   ;; a two-column grid keeps every label and control on one baseline:
   ;; labels left, controls right, whatever the control's width.
   [".reader-panel" {:position              "absolute"
                    :top                   "calc(100% + 0.5em)"
                    :right                 "0"
                    :display               "grid"
                    :grid-template-columns "auto auto"
                    :align-items           "center"
                    :column-gap            "1.5em"
                    :row-gap               "0.7em"
                    :min-width             "15em"
                    :padding               "1em 1.1em"
                    :color                 "var(--ink)"
                    :background            "var(--panel)"
                    :border                "1px solid var(--rule)"
                    :border-radius         "10px"
                    :box-shadow            "var(--shadow-panel)"}]
   ;; `display: grid` would otherwise beat the browser's
   ;; `[hidden] { display: none }`, pinning the panel open; restore it
   ;; so the toggle can close the panel (the same trap as `.kbd-help`).
   [".reader-panel[hidden]" {:display "none"}]
   [".reader-label" {:justify-self "start"
                    :color        "var(--muted)"
                    :font-size    "0.85em"}]
   ;; the steppers (width, text size) sit as a tight pair on the right
   [".reader-pair" {:justify-self "end" :display "inline-flex" :gap "0.4em"}]
   [".reader-pair button" {:font          "inherit"
                           :cursor        "pointer"
                           :min-width     "2.6em"
                           :padding       "0.25em 0"
                           :text-align    "center"
                           :color         "var(--ink)"
                           :background    "var(--card)"
                           :border        "1px solid var(--rule)"
                           :border-radius "6px"
                           :transition    "border-color 0.15s, background-color 0.15s"}]
   [".reader-pair button:hover" {:border-color "var(--muted)"}]
   ;; the on/off controls (contrast, focus, theme) are compact switches:
   ;; the state shows in the switch, so the row label needs no echo.
   [".reader-switch" {:justify-self  "end"
                     :position      "relative"
                     :width         "2.8em"
                     :height        "1.5em"
                     :padding       "0"
                     :cursor        "pointer"
                     :background    "var(--card)"
                     :border        "1px solid var(--rule)"
                     :border-radius "999px"
                     :transition    "background-color 0.15s, border-color 0.15s"}]
   [".reader-switch::after" {:content          "\"\""
                            :position         "absolute"
                            :top              "50%"
                            :left             "0.18em"
                            :width            "1.05em"
                            :height           "1.05em"
                            :border-radius    "50%"
                            :background       "var(--muted)"
                            :transform        "translateY(-50%)"
                            :transition       "transform 0.15s, background-color 0.15s"}]
   [".reader-switch[aria-pressed=\"true\"]"
    {:background "var(--link)" :border-color "var(--link)"}]
   [".reader-switch[aria-pressed=\"true\"]::after"
    {:background "var(--bg)" :transform "translate(1.3em, -50%)"}]
   ;; a quiet reset spanning both columns, set off by a hairline
   [".reader-reset" {:grid-column  "1 / -1"
                    :margin-top   "0.4em"
                    :padding      "0.5em 0 0"
                    :font         "inherit"
                    :font-size    "0.85em"
                    :cursor       "pointer"
                    :color        "var(--muted)"
                    :background   "none"
                    :border       "none"
                    :border-top   "1px solid var(--rule)"
                    :border-radius "0"
                    :transition   "color 0.15s"}]
   [".reader-reset:hover" {:color "var(--ink)"}]
   ;; the orientation breadcrumb atop the reading column: quiet, small,
   ;; and unobtrusive in normal reading, the only signpost in focus mode.
   [".breadcrumb" {:margin      "0 0 1.5em"
                  :font-family head-family
                  :font-size   "0.8em"
                  :color       "var(--muted)"}]
   [".breadcrumb-sep" {:margin "0 0.5em"}]
   ;; focus mode: a reader who turns it on (`data-focus` on the document
   ;; element) sheds the chrome and keeps the text and its breadcrumb.
   ;; The control cluster stays — it carries the switch back out.
   ["html[data-focus] .book-sidebar" {:display "none"}]
   ["html[data-focus] .book-mobile-contents" {:display "none"}]
   ["html[data-focus] .page-nav" {:display "none"}]
   ["html[data-focus] .page-footer" {:display "none"}]
   ["html[data-focus] .edge-nav" {:display "none"}]
   ["html[data-focus] .theme-toggle" {:display "none"}]
   ;; reading-progress bar: a hairline at the top of the viewport whose
   ;; inner fill the island scales from the scroll position. Transform
   ;; only, so the paint stays cheap.
   [".reading-progress" {:position      "fixed"
                        :top           "0"
                        :left          "0"
                        :right         "0"
                        :height        "3px"
                        :z-index       "30"
                        :background    "transparent"
                        :pointer-events "none"}]
   [".reading-progress-bar" {:height           "100%"
                            :width            "100%"
                            :transform        "scaleX(0)"
                            :transform-origin "left"
                            :background       "var(--link)"}]])

(defn- kbd-help-rules
  "The opt-in keyboard-shortcuts help dialog. The colors come from the
   literal-or-variable bindings, so it works whether or not the variable
   layer (dark/reader) is on."
  [{:keys [panel text rule muted var-or]}]
  [[".kbd-help" {:position        "fixed"
                :inset           "0"
                :z-index         "40"
                :display         "flex"
                :align-items     "center"
                :justify-content "center"
                :background      "rgba(0, 0, 0, 0.4)"}]
   ;; the class sets `display: flex`, which would otherwise beat the
   ;; browser's `[hidden] { display: none }` (equal specificity, author
   ;; origin wins) and pin the dialog open. Restore the attribute's
   ;; effect so the island can hide it.
   [".kbd-help[hidden]" {:display "none"}]
   [".kbd-help-panel" {:background    panel
                      :color         text
                      :border        (str "1px solid " rule)
                      :border-radius "12px"
                      :padding       "1.5em 1.75em"
                      :max-width     "24em"
                      :width         "90%"
                      :box-shadow    (var-or "--shadow-overlay"
                                             "0 8px 32px rgba(0, 0, 0, 0.3)")}]
   [".kbd-help-panel h2" {:margin-top "0" :font-size "1.1em"}]
   [".kbd-help-panel dl" {:display               "grid"
                         :grid-template-columns "auto 1fr"
                         :gap                   "0.5em 1.25em"
                         :margin                "0 0 1.25em"}]
   [".kbd-help-panel dt" {:margin "0"}]
   [".kbd-help-panel dt kbd" {:margin-right "0.2em"}]
   [".kbd-help-panel dd" {:margin "0" :color muted}]])

(def ^:private svg-invert-rules
  "Build-time SVG (diagrams, math) inverts its lightness in the dark
   scheme so dark strokes show; `--media-filter` is `none` in light.
   The filter targets the `svg` itself, not its wrapper: a diagram is
   `div.diagram > svg.diagram` and display math is `div.math-display >
   svg.math`, so filtering both levels would invert twice and cancel.
   Site-only, so the literal/EPUB stylesheet stays byte-identical."
  [["svg.diagram" {:filter "var(--media-filter)"}]
   ["svg.math" {:filter "var(--media-filter)"}]])

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
  ([tokens {:keys [dark? toggle? reader? keyboard?] :as opts}]
   (let [{:keys [light palette vars? measure tokens] :as ctx}
         (style-context tokens opts)]
     (vec
       (concat
         ;; with dark mode on, tell the UA both schemes exist so native UI
         ;; (scrollbars, form controls) follows the palette flip; the
         ;; toggle's data-theme rules pin it to an explicit choice
         (when vars? [(cond-> (root-vars light palette reader? measure)
                        dark? (update 1 assoc :color-scheme "light dark"))])
         (base-rules ctx)
         (code-rules ctx)
         (callout-rules ctx)
         (ui-vocab-rules ctx)
         (figure-table-rules ctx)
         (furniture-rules ctx)
         (sidebar-rules ctx)
         (mobile-contents-rules ctx)
         (downloads-rules ctx)
         (search-rules ctx)
         (media-rules ctx)
         adaptive-rules
         (palette-rules ctx)

         ;; opt-in dark mode. The site (`:dark?`) flips every custom property
         ;; through one `:root` override; the literal path keeps the older
         ;; `@media` block that recolors the palette-driven selectors directly.
         (if dark?
           (dark-media-vars tokens)
           (dark-rules tokens))

         ;; the opt-in toggle: explicit overrides that beat the media query
         ;; both ways, plus the button style. Only reachable under `:dark?`.
         (when toggle? (toggle-rules tokens light palette))

         ;; the opt-in reader controls: high-contrast overrides keyed off
         ;; `data-contrast="high"`, after the dark blocks so they win, and the
         ;; control-cluster styling. Both tint from the scheme variables.
         (when reader? (contrast-rules dark?))
         (when reader? (reader-control-rules ctx))

         (when keyboard? (kbd-help-rules ctx))
         (when dark? svg-invert-rules)

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
