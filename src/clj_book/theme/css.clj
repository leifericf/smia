(ns clj-book.theme.css
  "Pure core: compile design tokens into the stylesheet the HTML editions
   share.

   The CSS analogue of `theme.compile`: the same `theme.edn` tokens drive
   every edition, but where FO styling is per-block property maps, HTML
   styling is one generated stylesheet over the classes
   `clj-book.html.expand` emits. Rules are data — `[[selector prop-map] …]`
   — and `serialize` writes them with properties sorted by name, so equal
   tokens produce byte-identical stylesheets (the same determinism
   discipline as `fo.attrs`). The `:layout` token group is paged-output
   geometry and plays no part here; the reading column is the stylesheet's
   own layout. No IO."
  (:require
   [clj-book.fo.attrs :as attrs]
   [clj-book.theme.compile :as compile]
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

         ;; code
         ["code" {:font-family mono-family}]
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

         ;; the site-only downloads page
         [".downloads" {:margin (str block " 0")}]
         [".downloads .default" {:font-size   "1.15em"
                                 :font-weight "bold"}]
         [".downloads .note" {:color  muted
                              :margin (str "0 0 " block)}]
         [".downloads ul" {:list-style "none" :padding-left "0"}]
         [".downloads li" {:margin "0 0 4pt"}]

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
  "Serialize `rules` to a CSS string. Properties are sorted by name (via
   `fo.attrs/pairs`) so equal rule data serializes to identical bytes;
   rule order is the vector's order."
  [rules]
  (->> rules
       (map (fn [[selector props]]
              (str selector " {\n"
                   (->> (attrs/pairs props)
                        (map (fn [[k v]] (str "  " k ": " v ";")))
                        (str/join "\n"))
                   "\n}\n")))
       (str/join "\n")))

(defn css
  "The stylesheet for `tokens`: compile and serialize in one step."
  [tokens]
  (serialize (compile-css tokens)))
