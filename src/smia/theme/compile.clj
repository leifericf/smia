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
   :link-color :rule-color :muted-color :chapter-drop}`. The palette
   colors are surfaced for the assembled furniture (title page, TOC,
   rules)."
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
     :running-regions  (running-regions layout)
     ;; The chapter drop: white space above a chapter opening's heading.
     ;; Deeper than a web heading would sit — the classical cue that a
     ;; major division starts here.
     :chapter-drop     (get-in tokens [:layout :chapter-drop] "72pt")}))

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
                     (str (get type :hyphenation-ladder 2))}
        ;; Book paragraphs (the :indent default): a first-line indent on
        ;; running paragraphs and no inter-paragraph gap; the run opener
        ;; (:p-first, picked by the expansion walk) sets flush. :space
        ;; restores the gap-separated web convention.
        indent?     (= :indent (get spacing :paragraph-style :indent))
        para-style  (cond-> (assoc body-text
                                   :space-after
                                   (get spacing :paragraph
                                        (if indent? "0pt" "6pt")))
                      indent? (assoc :text-indent (get spacing :indent "1em")))]
    (-> expand/default-style
        (assoc :body {:font-family body-family
                      :font-size   (get type :base-size "11pt")
                      :line-height (get type :line-height "1.4")
                      :color       text})
        (update :p merge para-style)
        (update :p-first merge (assoc para-style :text-indent "0"))
        (update :li merge body-text)
        (update :dd merge body-text)
        (update :blockquote merge body-text)
        (update :footnote merge body-text)
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

(def ^:private mm-per-unit
  "Millimeters per supported length unit."
  {"mm" 1.0 "cm" 10.0 "in" 25.4 "pt" (/ 25.4 72.0)})

(defn- parse-mm
  "Parse a length string (\"140mm\", \"8.5in\") to millimeters. An unknown
   unit is a structured error, mirroring `page-dims` for unknown trims."
  [s]
  (let [[_ n unit] (re-matches #"\s*([0-9]*\.?[0-9]+)\s*([a-zA-Z]+)\s*" (str s))
        factor     (get mm-per-unit unit)]
    (when-not (and n factor)
      (throw (error/ex :smia.theme.compile/invalid-length
                       (str "Cannot parse length " (pr-str s)
                            "; expected a number with one of the units "
                            (pr-str (vec (sort (keys mm-per-unit)))) ".")
                       {:length s :known-units (vec (sort (keys mm-per-unit)))})))
    (* (Double/parseDouble n) factor)))

(defn- fmt-mm
  "A deterministic fixed-decimal mm string, locale-independent."
  [x]
  (str (String/format java.util.Locale/ROOT "%.1f" (object-array [(double x)]))
       "mm"))

(defn canon-margins
  "The classical page construction: margins inner:top:outer:bottom in the
   ratio 2:3:4:6, solved from the trim `width` so the text block covers
   `coverage` of the page width. The default 2/3 coverage reproduces the
   Van de Graaf construction on a 2:3 page (inner = width/9). Returns
   deterministic fixed-decimal mm strings, plus `:margin-symmetric`
   (3 units) — the side margin a symmetric (screen) page needs for the
   same coverage. Pure; structured errors on an unknown unit or a
   coverage outside (0, 1)."
  [width coverage]
  (when-not (and (number? coverage) (< 0.0 (double coverage) 1.0))
    (throw (error/ex :smia.theme.compile/invalid-coverage
                     (str ":text-coverage must be a number between 0 and 1 "
                          "(exclusive), got: " (pr-str coverage))
                     {:coverage coverage})))
  (let [w (parse-mm width)
        u (/ (* (- 1.0 (double coverage)) w) 6.0)
        f (fn [k] (fmt-mm (* k u)))]
    {:margin-inside    (f 2)
     :margin-top       (f 3)
     :margin-outside   (f 4)
     :margin-bottom    (f 6)
     :margin-symmetric (f 3)}))

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
  "Page-master fragments for the page `layout`, all reachable through the
   `master-reference` \"book\". Beyond the parity (or symmetric) body
   masters, every layout carries a `page-position=\"first\"` master per
   parity whose page has no before-region at all — a chapter opener cannot
   show a running head, while its after-region reuses the parity footer
   name so the folio stays. `:print` adds a body-only master selected for
   parity-inserted blank pages (`blank-or-not-blank`), so a forced verso
   renders truly empty. The alternatives are listed most specific first,
   in a fixed order, for deterministic FO."
  [layout geometry]
  (let [{:keys [width height]} (page-dims geometry)
        ;; Margins default to the classical canon, derived from the trim;
        ;; an explicit margin key in the theme wins for that key alone.
        canon   (canon-margins width (get geometry :text-coverage 2/3))
        mt      (get geometry :margin-top (:margin-top canon))
        mb      (get geometry :margin-bottom (:margin-bottom canon))
        inside  (get geometry :margin-inside (:margin-inside canon))
        outside (get geometry :margin-outside (:margin-outside canon))
        side    (get geometry :margin-outside (:margin-symmetric canon))
        header  (get geometry :header-extent "12mm")
        footer  (get geometry :footer-extent "12mm")
        page    {:page-width width :page-height height
                 :margin-top mt :margin-bottom mb}
        body    [:fo/region-body {:margin-top header :margin-bottom footer}]
        alt     (fn [ref conditions]
                  [:fo/conditional-page-master-reference
                   (assoc conditions :master-reference ref)])]
    (if (= layout :print)
      (let [recto (assoc page :margin-left inside :margin-right outside)
            verso (assoc page :margin-left outside :margin-right inside)]
        [(into [:fo/simple-page-master (assoc recto :master-name "book-recto")]
               (regions header footer "head-recto" "foot-recto"))
         (into [:fo/simple-page-master (assoc verso :master-name "book-verso")]
               (regions header footer "head-verso" "foot-verso"))
         [:fo/simple-page-master (assoc recto :master-name "book-first-recto")
          body [:fo/region-after {:extent footer :region-name "foot-recto"}]]
         [:fo/simple-page-master (assoc verso :master-name "book-first-verso")
          body [:fo/region-after {:extent footer :region-name "foot-verso"}]]
         [:fo/simple-page-master (assoc verso :master-name "book-blank")
          body]
         [:fo/page-sequence-master {:master-name "book"}
          [:fo/repeatable-page-master-alternatives
           (alt "book-blank"       {:blank-or-not-blank "blank"})
           (alt "book-first-recto" {:page-position "first" :odd-or-even "odd"})
           (alt "book-first-verso" {:page-position "first" :odd-or-even "even"})
           (alt "book-recto"       {:odd-or-even "odd"})
           (alt "book-verso"       {:odd-or-even "even"})]]])
      (let [sym (assoc page :margin-left side :margin-right side)]
        [(into [:fo/simple-page-master (assoc sym :master-name "book-page")]
               (regions header footer nil nil))
         [:fo/simple-page-master (assoc sym :master-name "book-first")
          body [:fo/region-after {:extent footer}]]
         [:fo/page-sequence-master {:master-name "book"}
          [:fo/repeatable-page-master-alternatives
           (alt "book-first" {:page-position "first"})
           (alt "book-page"  {})]]]))))

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
