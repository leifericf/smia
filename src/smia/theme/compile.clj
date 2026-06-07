(ns smia.theme.compile
  "Pure core: compile design tokens plus a page layout into the FO
   styling the book layer needs.

   Produces (a) a `style` map (tag -> FO property map) that overrides the
   renderer's base-14 defaults from `smia.fo.expand`, and (b) the
   page geometry: `simple-page-master` fragments for the layout and the
   `master-reference` chapters point at. The `:screen` layout uses one
   symmetric master; `:print` uses mirrored recto/verso masters (binding
   gutter on the inside edge) selected by a `page-sequence-master`. An
   edition's descriptor names the layout it renders with. No IO."
  (:require
   [smia.error :as error]
   [smia.fo.expand :as expand]))

(def page-sizes
  "Trim sizes by name (width x height)."
  {:a4     {:width "210mm" :height "297mm"}
   :letter {:width "8.5in" :height "11in"}
   :digest {:width "140mm" :height "216mm"}})

(def default-code-colors
  "Fallback syntax-highlight palette (token class -> color), overridden by
   the `:code` token group."
  {:keyword "#0033cc" :string "#008800" :comment "#888888"
   :number  "#aa5500" :literal "#7700aa"})

(declare style-from-tokens fo-overrides page-dims regions masters
         running-regions)

(defn compile-theme
  "Compile validated `tokens` and a page `layout` (`:screen` or
   `:print`) into `{:layout :style :master-reference :masters
   :link-color :rule-color :muted-color}`. The palette colors are
   surfaced for the assembled furniture (title page, TOC, rules)."
  [tokens layout]
  (let [color (:color tokens)]
    {:layout           layout
     :style            (-> (style-from-tokens tokens)
                           (fo-overrides (:fo tokens))
                           (assoc :highlight?   (get-in tokens [:type :highlight] false)
                                  :code-colors  (merge default-code-colors
                                                       (:code tokens))))
     :link-color       (get color :link "#1a0dab")
     :rule-color       (get color :rule "#999999")
     :muted-color      (get color :muted "#666666")
     :master-reference "book"
     :masters          (masters layout (:layout tokens))
     :running-regions  (running-regions layout)}))

;; --- private helpers -------------------------------------------------------

(defn- fo-overrides
  "Merge the theme's optional `:fo` styling hatch (tag -> FO property
   map) over the compiled `style`, per tag — the PDF mirror of the `:css`
   hatch in `theme.css`."
  [style overrides]
  (reduce-kv (fn [s tag props] (update s tag merge props)) style overrides))

(defn- style-from-tokens
  "Override the renderer defaults with token-driven typography."
  [{:keys [color type spacing]}]
  (let [body-family (get type :body-family "serif")
        head-family (get type :heading-family "sans-serif")
        mono-family (get type :mono-family "monospace")
        text        (get color :text "#1a1a1a")
        muted       (get color :muted "#666666")
        rule        (get color :rule "#999999")
        link        (get color :link "#1a0dab")
        code-bg     (get color :code-background "#f4f4f4")
        ;; Justified, hyphenated body text is the book default (the canon);
        ;; the base-14 library style stays ragged, so these live here, on
        ;; the themed path only. Hyphenation needs a :book/language on the
        ;; root to select its pattern set.
        body-text   {:text-align (if (get type :justify true) "justify" "start")
                     :hyphenate  (str (boolean (get type :hyphenate true)))
                     :hyphenation-ladder-count
                     (str (get type :hyphenation-ladder 2))}]
    (-> expand/default-style
        (assoc :body {:font-family body-family
                      :font-size   (get type :base-size "11pt")
                      :line-height (get type :line-height "1.4")
                      :color       text})
        (update :p merge body-text
                {:space-after (get spacing :paragraph "6pt")})
        (update :li merge body-text)
        (update :dd merge body-text)
        (update :blockquote merge body-text)
        (update :h1 merge {:font-family head-family :color text
                           :font-size   (get type :h1-size "20pt")})
        (update :h2 merge {:font-family head-family :color text
                           :font-size   (get type :h2-size "16pt")})
        (update :h3 merge {:font-family head-family :color text
                           :font-size   (get type :h3-size "13pt")})
        (update :code merge {:font-family mono-family})
        (update :pre merge {:font-family   mono-family
                            :background-color code-bg
                            :border-left   (str "3pt solid " link)
                            :padding-left  "8pt"
                            :font-size     (get type :code-size "9.5pt")})
        (update :blockquote merge {:border-left  (str "3pt solid " rule)
                                   :padding-left "10pt"
                                   :start-indent "0pt"
                                   :color        muted})
        (update :hr merge {:border-top (str "0.5pt solid " rule)}))))

(defn- page-dims
  "The trim dimensions for the layout's `:page-size` (default `:a4`).
   An unknown name is a structured error, not a silent A4 — the schema
   rejects it at load time; this guards programmatic callers."
  [layout]
  (let [size (get layout :page-size :a4)]
    (or (get page-sizes size)
        (throw (error/ex :smia.theme.compile/unknown-page-size
                         (str "Unknown :page-size " (pr-str size)
                              "; the trim names are "
                              (pr-str (vec (sort (keys page-sizes)))) ".")
                         {:page-size size
                          :known     (vec (sort (keys page-sizes)))})))))

(defn- regions
  "Body, header, and footer regions. `before-name`/`after-name` give the
   header/footer regions explicit names so a page-sequence can target
   distinct recto/verso running content; nil keeps the FO default names."
  [header footer before-name after-name]
  [[:fo/region-body {:margin-top header :margin-bottom footer}]
   [:fo/region-before (cond-> {:extent header} before-name (assoc :region-name before-name))]
   [:fo/region-after  (cond-> {:extent footer} after-name  (assoc :region-name after-name))]])

(defn- masters
  "Page-master fragments for the page `layout`, all reachable through
   the `master-reference` \"book\"."
  [layout geometry]
  (let [{:keys [width height]} (page-dims geometry)
        mt      (get geometry :margin-top "22mm")
        mb      (get geometry :margin-bottom "22mm")
        inside  (get geometry :margin-inside "26mm")
        outside (get geometry :margin-outside "20mm")
        header  (get geometry :header-extent "12mm")
        footer  (get geometry :footer-extent "12mm")]
    (if (= layout :print)
      [(into [:fo/simple-page-master
              {:master-name "book-recto" :page-width width :page-height height
               :margin-top mt :margin-bottom mb
               :margin-left inside :margin-right outside}]
             (regions header footer "head-recto" "foot-recto"))
       (into [:fo/simple-page-master
              {:master-name "book-verso" :page-width width :page-height height
               :margin-top mt :margin-bottom mb
               :margin-left outside :margin-right inside}]
             (regions header footer "head-verso" "foot-verso"))
       [:fo/page-sequence-master {:master-name "book"}
        [:fo/repeatable-page-master-alternatives
         [:fo/conditional-page-master-reference
          {:master-reference "book-recto" :odd-or-even "odd"}]
         [:fo/conditional-page-master-reference
          {:master-reference "book-verso" :odd-or-even "even"}]]]]
      [(into [:fo/simple-page-master
              {:master-name "book" :page-width width :page-height height
               :margin-top mt :margin-bottom mb
               :margin-left outside :margin-right outside}]
             (regions header footer nil nil))])))

(defn- running-regions
  "Describe the header/footer regions for a layout: which `:flow-name` a
   page-sequence's static content targets, and the page parity it shows on.
   `:print` carries distinct recto/verso content; `:screen` is symmetric."
  [layout]
  (if (= layout :print)
    [{:slot :before :name "head-recto" :parity :recto}
     {:slot :before :name "head-verso" :parity :verso}
     {:slot :after  :name "foot-recto" :parity :recto}
     {:slot :after  :name "foot-verso" :parity :verso}]
    [{:slot :before :name "xsl-region-before" :parity :any}
     {:slot :after  :name "xsl-region-after" :parity :any}]))
