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

(defn compile-css
  "Compile validated `tokens` into ordered CSS rules
   `[[selector prop-map] …]`."
  [tokens]
  (let [{:keys [color type spacing code]} tokens
        body-family (get type :body-family "serif")
        head-family (get type :heading-family "sans-serif")
        mono-family (get type :mono-family "monospace")
        text        (get color :text "#1a1a1a")
        muted       (get color :muted "#666666")
        rule        (get color :rule "#999999")
        link        (get color :link "#1a0dab")
        code-bg     (get color :code-background "#f4f4f4")
        paragraph   (get spacing :paragraph "6pt")
        block       (get spacing :block "8pt")
        palette     (merge compile/default-code-colors code)]
    (vec
      (concat
        [;; reading column and base typography
         ["body" {:font-family body-family
                  :font-size   (get type :base-size "11pt")
                  :line-height (get type :line-height "1.4")
                  :color       text
                  :margin      "0"}]
         ["main" {:max-width "42em"
                  :margin    "0 auto"
                  :padding   "0 1em 4em"}]
         ["h1, h2, h3, h4, h5, h6" {:font-family head-family
                                    :color       text
                                    :line-height "1.2"}]
         ["h1" {:font-size (get type :h1-size "20pt")}]
         ["h2" {:font-size (get type :h2-size "16pt")}]
         ["h3" {:font-size (get type :h3-size "13pt")}]
         ["p" {:margin (str "0 0 " paragraph)}]
         ["a" {:color link}]

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
         [".line-no" {:color "#999999" :user-select "none"}]
         [".file-bar" {:font-family      mono-family
                       :font-size        "0.75em"
                       :font-weight      "bold"
                       :background-color "#e8e8e8"
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
                         :background-color "#f7f7f7"
                         :padding          "6pt"
                         :margin           (str block " 0")}]
         [".admonition-title" {:font-weight "bold"}]
         [".sidebar" {:border           (str "0.75pt solid " rule)
                      :background-color "#f7f7f7"
                      :padding          "6pt"
                      :margin           (str block " 0")}]
         [".sidebar-title" {:font-weight "bold"}]
         [".overview" {:border-left      (str "3pt solid " rule)
                       :background-color "#f7f7f7"
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
                 :background-color "#eeeeee"
                 :border           (str "1px solid " rule)
                 :border-radius    "3px"
                 :padding          "0 0.3em"}]
         [".menu-sep" {:color muted}]
         [".button" {:background-color "#e8e8e8"
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
         [".book-sidebar" {:flex             "1 1 14em"
                           :background-color "#f7f7f7"
                           :border-right     (str "1px solid " rule)}]
         [".book-sidebar-inner" {:position   "sticky"
                                 :top        "0"
                                 :max-height "100vh"
                                 :overflow-y "auto"
                                 :padding    "2em 1.5em"
                                 :font-size  "0.9em"}]
         [".book-sidebar-title" {:font-family  head-family
                                 :font-weight  "bold"
                                 :display      "block"
                                 :margin-bottom "0.75em"}]
         [".book-sidebar-list" {:list-style "none" :padding-left "0"}]
         [".book-sidebar-list .toc-level-1" {:padding-left "1em"}]
         [".book-sidebar-list .toc-level-2" {:padding-left "2em"}]
         [".book-sidebar .current" {:font-weight "bold"}]
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
                             :background (get color :background "#ffffff")
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
               [(str ".tok-" (name kind)) {:color color}])
             (sort-by key palette))

        ;; the theme's :css styling hatch, last so user rules win
        (:css tokens)))))

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
  "The stylesheet for `tokens`: compile and serialize in one step."
  [tokens]
  (serialize (compile-css tokens)))
