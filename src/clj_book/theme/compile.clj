(ns clj-book.theme.compile
  "Pure core: compile design tokens plus a layout profile into the FO
   styling the book layer needs.

   Produces (a) a `style` map (tag -> FO property map) that overrides the
   renderer's base-14 defaults from `clj-book.fo.expand`, and (b) the
   page geometry: `simple-page-master` fragments for the profile and the
   `master-reference` chapters point at. `:screen` uses one symmetric
   master; `:print` uses mirrored recto/verso masters (binding gutter on
   the inside edge) selected by a `page-sequence-master`. No IO."
  (:require
   [clj-book.fo.expand :as expand]))

(def page-sizes
  "Trim sizes by name (width x height)."
  {:a4     {:width "210mm" :height "297mm"}
   :letter {:width "8.5in" :height "11in"}
   :digest {:width "140mm" :height "216mm"}})

(defn- token [m k default] (get m k default))

(defn- style-from-tokens
  "Override the renderer defaults with token-driven typography."
  [{:keys [color type spacing]}]
  (let [body-family (token type :body-family "serif")
        head-family (token type :heading-family "sans-serif")
        mono-family (token type :mono-family "monospace")
        text        (token color :text "#1a1a1a")
        muted       (token color :muted "#666666")
        rule        (token color :rule "#999999")
        link        (token color :link "#1a0dab")
        code-bg     (token color :code-background "#f4f4f4")]
    (-> expand/default-style
        (assoc :body {:font-family body-family
                      :font-size   (token type :base-size "11pt")
                      :line-height (token type :line-height "1.4")
                      :color       text})
        (update :p merge {:space-after (token spacing :paragraph "6pt")})
        (update :h1 merge {:font-family head-family :color text
                           :font-size   (token type :h1-size "20pt")})
        (update :h2 merge {:font-family head-family :color text
                           :font-size   (token type :h2-size "16pt")})
        (update :h3 merge {:font-family head-family :color text
                           :font-size   (token type :h3-size "13pt")})
        (update :code merge {:font-family mono-family})
        (update :pre merge {:font-family   mono-family
                            :background-color code-bg
                            :border-left   (str "3pt solid " link)
                            :padding-left  "8pt"})
        (update :blockquote merge {:border-left  (str "3pt solid " rule)
                                   :padding-left "10pt"
                                   :start-indent "0pt"
                                   :color        muted})
        (update :hr merge {:border-top (str "0.5pt solid " rule)}))))

(def default-code-colors
  "Fallback syntax-highlight palette (token class -> color), overridden by
   the `:code` token group."
  {:keyword "#0033cc" :string "#008800" :comment "#888888"
   :number  "#aa5500" :literal "#7700aa"})

(defn- page-dims [layout]
  (get page-sizes (token layout :page-size :a4) (:a4 page-sizes)))

(defn- regions
  "Body, header, and footer regions. `before-name`/`after-name` give the
   header/footer regions explicit names so a page-sequence can target
   distinct recto/verso running content; nil keeps the FO default names."
  [header footer before-name after-name]
  [[:fo/region-body {:margin-top header :margin-bottom footer}]
   [:fo/region-before (cond-> {:extent header} before-name (assoc :region-name before-name))]
   [:fo/region-after  (cond-> {:extent footer} after-name  (assoc :region-name after-name))]])

(defn- masters
  "Page-master fragments for `profile`, all reachable through the
   `master-reference` \"book\"."
  [profile layout]
  (let [{:keys [width height]} (page-dims layout)
        mt      (token layout :margin-top "22mm")
        mb      (token layout :margin-bottom "22mm")
        inside  (token layout :margin-inside "26mm")
        outside (token layout :margin-outside "20mm")
        header  (token layout :header-extent "12mm")
        footer  (token layout :footer-extent "12mm")]
    (if (= profile :print)
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
  "Describe the header/footer regions for a profile: which `:flow-name` a
   page-sequence's static content targets, and the page parity it shows on.
   `:print` carries distinct recto/verso content; `:screen` is symmetric."
  [profile]
  (if (= profile :print)
    [{:slot :before :name "head-recto" :parity :recto}
     {:slot :before :name "head-verso" :parity :verso}
     {:slot :after  :name "foot-recto" :parity :recto}
     {:slot :after  :name "foot-verso" :parity :verso}]
    [{:slot :before :name "xsl-region-before" :parity :any}
     {:slot :after  :name "xsl-region-after" :parity :any}]))

(defn compile-theme
  "Compile validated `tokens` and a layout `profile` (`:screen` or
   `:print`) into `{:profile :style :master-reference :masters
   :link-color :rule-color :muted-color}`. The palette colors are
   surfaced for the assembled furniture (title page, TOC, rules)."
  [tokens profile]
  (let [color (:color tokens)]
    {:profile          profile
     :style            (-> (style-from-tokens tokens)
                           (assoc :highlight?   (get-in tokens [:type :highlight] false)
                                  :code-colors  (merge default-code-colors
                                                       (:code tokens))))
     :link-color       (token color :link "#1a0dab")
     :rule-color       (token color :rule "#999999")
     :muted-color      (token color :muted "#666666")
     :master-reference "book"
     :masters          (masters profile (:layout tokens))
     :running-regions  (running-regions profile)}))
