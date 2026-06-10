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
   [smia.fo.expand :as expand]
   [smia.fo.watermark :as watermark]))

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

(declare compile-theme* style-from-tokens fo-overrides seed-p-first page-dims
         regions masters running-regions canon-margins heading-rhythm
         checked-rhythm checked-count leading-pt fmt-pt parse-pt)

(defn compile-theme
  "Compile validated `tokens` and a page `layout` (`:screen` or
   `:print`) into `{:layout :style :master-reference :masters
   :link-color :rule-color :muted-color :chapter-drop}`. The palette
   colors are surfaced for the assembled furniture (title page, TOC,
   rules).

   `opts` may carry `:watermark` — the beta-review watermark text. When
   present, a page-sized SVG of that text (styled by the optional
   `:watermark` token group) is stamped as the `background-image` of every
   master's body region, so it rides behind the text on every page. Absent,
   the FO output is byte-identical to before."
  ([tokens layout] (compile-theme tokens layout {}))
  ([tokens layout {:keys [watermark]}]
   (compile-theme* tokens layout watermark)))

(defn- compile-theme* [tokens layout watermark]
  (let [color (:color tokens)
        wm    (when (and watermark (seq watermark))
                {:text watermark :style (get tokens :watermark)})]
    {:layout           layout
     :style            (-> (style-from-tokens tokens)
                           (fo-overrides (seed-p-first (:fo tokens)))
                           (assoc :highlight?   (get-in tokens [:type :highlight] false)
                                  :code-colors  (merge default-code-colors
                                                       (:code tokens))
                                  :line-numbers? (get-in tokens [:type :line-numbers] true)
                                  :listing-keep-lines (get-in tokens [:type :listing-keep-lines] 25)))
     :link-color       (get color :link "#1a0dab")
     :rule-color       (get color :rule "#999999")
     :muted-color      (get color :muted "#666666")
     :master-reference "book"
     :masters          (masters layout (:layout tokens) wm)
     :running-regions  (running-regions layout)
     ;; The chapter drop: white space above a chapter opening's heading.
     ;; Deeper than a web heading would sit — the classical cue that a
     ;; major division starts here.
     :chapter-drop     (get-in tokens [:layout :chapter-drop] "72pt")
     ;; Running heads in letterspaced capitals. The classical treatment is
     ;; letterspaced small caps, but FOP supports no font-variant (nor
     ;; OpenType smcp), so uppercase with tracking is the closest it can
     ;; render. Override per property via :type {:running-head {...}}.
     :running-head     (merge {:text-transform "uppercase"
                               :letter-spacing "0.08em"}
                              (get-in tokens [:type :running-head]))}))

;; --- private helpers -------------------------------------------------------

(defn- fo-overrides
  "Merge the theme's optional `:fo` styling hatch (tag -> FO property
   map) over the compiled `style`, per tag — the PDF mirror of the `:css`
   hatch in `theme.css`."
  [style overrides]
  (reduce-kv (fn [s tag props] (update s tag merge props)) style overrides))

(defn- seed-p-first
  "A `:p` override styles every paragraph: `:p-first` is the expansion
   walk's split of `:p` (the run opener), not a separate authoring
   concept. Copy the `:p` overrides onto `:p-first` — except
   `:text-indent`, the property that distinguishes them — with an
   explicit `:p-first` override winning per property."
  [overrides]
  (if-let [p (:p overrides)]
    (update overrides :p-first #(merge (dissoc p :text-indent) %))
    overrides))

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
                     (checked-count :hyphenation-ladder
                                    (get type :hyphenation-ladder 2))
                     :widows     (checked-count :widows (get type :widows 2))
                     :orphans    (checked-count :orphans (get type :orphans 2))}
        ;; Book paragraphs (the :indent default): a first-line indent on
        ;; running paragraphs and no inter-paragraph gap; the run opener
        ;; (:p-first, picked by the expansion walk) sets flush. :space
        ;; restores the gap-separated web convention.
        indent?     (= :indent (get spacing :paragraph-style :indent))
        para-style  (cond-> (assoc body-text
                                   :space-after
                                   (get spacing :paragraph
                                        (if indent? "0pt" "6pt")))
                      indent? (assoc :text-indent (get spacing :indent "1em")))
        rhythm      (merge heading-rhythm
                           (checked-rhythm (get spacing :heading-rhythm)))
        lead        (leading-pt type)]
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
        ;; Footnotes hang long citation URLs. Two settings keep those tidy.
        ;; Ragged-right (text-align start, the conventional setting for notes)
        ;; spares them the gap-toothed stretching justification forces around
        ;; an unbreakable URL. hyphenate="false" then stops FOP from breaking
        ;; a URL with an inserted hyphen (…clojure-inter- / views/), which it
        ;; will do under line pressure even across word joiners; block-level
        ;; hyphenation is the one control FOP honors reliably. A ragged note
        ;; needs no hyphenation anyway, and the URL still wraps at its own
        ;; delimiters (the zero-width breaks `break-long-urls` inserts).
        (update :footnote merge (assoc body-text :text-align "start"
                                       :hyphenate "false"))
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
        (update :hr merge {:border-top (str "0.5pt solid " rule)})
        ;; Heading spaces in leading multiples keep the vertical rhythm.
        (as-> s (reduce-kv (fn [m tag [before after]]
                             (update m tag merge
                                     {:space-before (fmt-pt (* before lead))
                                      :space-after  (fmt-pt (* after lead))}))
                           s rhythm)))))

(def ^:private mm-per-unit
  "Millimeters per supported length unit. px is the CSS reference pixel
   (96 per inch) — themes shared with the site legitimately size type
   in it, and FOP accepts it."
  {"mm" 1.0 "cm" 10.0 "in" 25.4 "pt" (/ 25.4 72.0) "px" (/ 25.4 96.0)})

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

(defn- fmt-pt
  "A deterministic fixed-decimal pt string, locale-independent."
  [x]
  (str (String/format java.util.Locale/ROOT "%.1f" (object-array [(double x)]))
       "pt"))

(defn- parse-pt
  "Parse a length string to points."
  [s]
  (/ (parse-mm s) (get mm-per-unit "pt")))

(defn- leading-pt
  "The body leading in points: the base size times a unitless (or
   percentage) line-height ratio, or the line-height directly when it
   carries a length unit. The vertical rhythm (heading spaces) is set in
   multiples of this."
  [type]
  (let [lh   (str (get type :line-height "1.4"))
        base #(parse-pt (str (get type :base-size "11pt")))]
    (if-let [[_ n pct] (re-matches #"\s*([0-9]*\.?[0-9]+)\s*(%?)\s*" lh)]
      (* (base) (cond-> (Double/parseDouble n)
                  (= "%" pct) (/ 100.0)))
      (parse-pt lh))))

(def heading-rhythm
  "Heading `[space-before space-after]` per level, in multiples of the
   body leading — vertical space stays a whole-number-ish count of lines,
   so text on facing pages sits on the same rhythm. A theme overrides per
   level via `:spacing {:heading-rhythm {...}}`."
  {:h1 [2.0 1.0] :h2 [1.5 0.5] :h3 [1.0 0.5]
   :h4 [1.0 0.25] :h5 [0.75 0.25] :h6 [0.75 0.25]})

(defn- checked-count
  "Validate a count-valued type token (`:hyphenation-ladder`, `:widows`,
   `:orphans`) as a positive integer and render it as the FO property
   string. A bad value is a structured error naming the token, not a
   render failure deep inside FOP."
  [token v]
  (when-not (and (integer? v) (pos? v))
    (throw (error/ex :smia.theme.compile/invalid-count
                     (str ":type " token " must be a positive integer, got: "
                          (pr-str v))
                     {:token token :value v})))
  (str v))

(defn- checked-rhythm
  "Validate a theme's `:heading-rhythm` override: each level must map to a
   `[before after]` pair of numbers. A malformed entry is a structured
   error naming the level, not a downstream cast failure."
  [rhythm]
  (doseq [[level v] rhythm]
    (when-not (and (vector? v) (= 2 (count v)) (every? number? v))
      (throw (error/ex :smia.theme.compile/invalid-rhythm
                       (str ":heading-rhythm entry " (pr-str level)
                            " must be a [before after] pair of numbers "
                            "(leading multiples), got: " (pr-str v))
                       {:level level :value v}))))
  rhythm)

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
   distinct recto/verso running content; nil keeps the FO default names.
   `body-bg` is merged onto the body region (the beta-review watermark
   background, or empty)."
  [header footer before-name after-name body-bg]
  [[:fo/region-body (merge {:margin-top header :margin-bottom footer} body-bg)]
   [:fo/region-before (cond-> {:extent header} before-name (assoc :region-name before-name))]
   [:fo/region-after  (cond-> {:extent footer} after-name  (assoc :region-name after-name))]])

(defn- watermark-bg
  "Background attrs painting the watermark behind the text on a body region
   whose box is `region-w`×`region-h` (pt) with its top-left at (`origin-x`,
   `origin-y`) on a `page-w`×`page-h` page. The SVG is sized to the region
   (FOP anchors an oversized background at the region's top-left and clips
   rather than centering it, so the canvas must match the region) and the
   text is centred on the *page*, expressed in region-local coordinates —
   keeping the mark page-centred despite the asymmetric binding margins.
   Empty when there is no watermark."
  [wm {:keys [region-w region-h origin-x origin-y page-w page-h]}]
  (if wm
    {:background-image    (watermark/background-image
                            (watermark/svg (merge {:text      (:text wm)
                                                   :width-pt  region-w
                                                   :height-pt region-h
                                                   :cx-pt     (- (/ page-w 2.0) origin-x)
                                                   :cy-pt     (- (/ page-h 2.0) origin-y)}
                                                  (:style wm))))
     :background-repeat   "no-repeat"
     :background-position "center center"}
    {}))

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
  [layout geometry wm]
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
        ;; The body region's box on the page (pt), so the watermark can be
        ;; centred on the physical page. The body sits inside the page
        ;; margins, then inset by its own header/footer margins; its left
        ;; edge is the binding-side margin, which swaps recto/verso.
        pw      (parse-pt width)  ph (parse-pt height)
        top-pt  (+ (parse-pt mt) (parse-pt header))
        rh      (- ph (parse-pt mt) (parse-pt mb) (parse-pt header) (parse-pt footer))
        box     (fn [left right] {:region-w (- pw (parse-pt left) (parse-pt right))
                                  :region-h rh :origin-x (parse-pt left) :origin-y top-pt
                                  :page-w pw :page-h ph})
        body-of (fn [body-bg]
                  [:fo/region-body (merge {:margin-top header :margin-bottom footer}
                                          body-bg)])
        alt     (fn [ref conditions]
                  [:fo/conditional-page-master-reference
                   (assoc conditions :master-reference ref)])]
    (if (= layout :print)
      (let [recto    (assoc page :margin-left inside :margin-right outside)
            verso    (assoc page :margin-left outside :margin-right inside)
            recto-bg (watermark-bg wm (box inside outside))
            verso-bg (watermark-bg wm (box outside inside))]
        [(into [:fo/simple-page-master (assoc recto :master-name "book-recto")]
               (regions header footer "head-recto" "foot-recto" recto-bg))
         (into [:fo/simple-page-master (assoc verso :master-name "book-verso")]
               (regions header footer "head-verso" "foot-verso" verso-bg))
         [:fo/simple-page-master (assoc recto :master-name "book-first-recto")
          (body-of recto-bg) [:fo/region-after {:extent footer :region-name "foot-recto"}]]
         [:fo/simple-page-master (assoc verso :master-name "book-first-verso")
          (body-of verso-bg) [:fo/region-after {:extent footer :region-name "foot-verso"}]]
         [:fo/simple-page-master (assoc verso :master-name "book-blank")
          (body-of verso-bg)]
         [:fo/page-sequence-master {:master-name "book"}
          [:fo/repeatable-page-master-alternatives
           (alt "book-blank"       {:blank-or-not-blank "blank"})
           (alt "book-first-recto" {:page-position "first" :odd-or-even "odd"})
           (alt "book-first-verso" {:page-position "first" :odd-or-even "even"})
           (alt "book-recto"       {:odd-or-even "odd"})
           (alt "book-verso"       {:odd-or-even "even"})]]])
      (let [sym    (assoc page :margin-left side :margin-right side)
            sym-bg (watermark-bg wm (box side side))]
        [(into [:fo/simple-page-master (assoc sym :master-name "book-page")]
               (regions header footer nil nil sym-bg))
         [:fo/simple-page-master (assoc sym :master-name "book-first")
          (body-of sym-bg) [:fo/region-after {:extent footer}]]
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
