(ns smia.theme.css-test
  (:require
   [smia.theme.css :as css]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private tokens
  {:color   {:text "#1c1c1c" :link "#2a52be" :muted "#666666"
             :rule "#bbbbbb" :code-background "#f4f4f4"}
   :type    {:body-family "serif" :heading-family "sans-serif"
             :mono-family "monospace" :base-size "11pt" :line-height "1.45"
             :h1-size "22pt" :h2-size "16pt" :h3-size "13pt"
             :highlight true}
   :code    {:keyword "#0033cc" :string "#008800"}
   :spacing {:paragraph "6pt" :block "8pt"}
   :layout  {:page-size :a4}})

(defn- rule
  "The prop map of the first rule whose selector is `selector`."
  [rules selector]
  (some (fn [[s props]] (when (= s selector) props)) rules))

(deftest body-rule-carries-the-type-and-color-tokens
  (let [body (rule (css/compile-css tokens) "body")]
    (is (= "serif" (:font-family body)))
    (is (= "11pt" (:font-size body)))
    (is (= "1.45" (:line-height body)))
    (is (= "#1c1c1c" (:color body)))))

(deftest headings-use-the-heading-family-and-sizes
  (let [rules (css/compile-css tokens)]
    (is (= "sans-serif" (:font-family (rule rules "h1, h2, h3, h4, h5, h6"))))
    (is (= "22pt" (:font-size (rule rules "h1"))))
    (is (= "16pt" (:font-size (rule rules "h2"))))))

(deftest links-use-the-link-color
  (is (= "#2a52be" (:color (rule (css/compile-css tokens) "a")))))

(deftest token-classes-merge-the-code-palette-over-defaults
  (let [rules (css/compile-css tokens)]
    (testing "overridden by the book's :code group"
      (is (= "#0033cc" (:color (rule rules ".tok-keyword"))))
      (is (= "#008800" (:color (rule rules ".tok-string")))))
    (testing "defaults fill the rest of the palette"
      (is (= "#888888" (:color (rule rules ".tok-comment")))))))

(deftest book-extension-idioms-are-styled
  (let [rules     (css/compile-css tokens)
        selectors (set (map first rules))]
    (doseq [s [".admonition" ".sidebar" ".overview" ".epigraph"
               ".file-bar" ".annotations" ".footnotes"
               "figure" "figcaption" "table" ".toc-list" ".page-nav"
               ".downloads" ".downloads .default"]]
      (is (contains? selectors s) (str s " has a rule")))))

(deftest sidebar-layout-selectors-are-styled
  (let [rules     (css/compile-css tokens)
        selectors (set (map first rules))]
    (doseq [s [".book-layout" ".book-sidebar" ".book-sidebar-title"
               ".book-sidebar-list" ".book-sidebar .current" ".book-content"]]
      (is (contains? selectors s) (str s " has a rule")))
    (testing "the rail uses flex-wrap, so no width breakpoint is emitted"
      ;; the layout is fluid (flex-wrap + clamp), never a width media query;
      ;; feature queries (reduced-motion, the opt-in dark scheme) are allowed.
      (is (= "wrap" (:flex-wrap (rule rules ".book-layout"))))
      (is (not (re-find #"@media \([^)]*width" (css/css tokens)))))))

(deftest reading-measure-is-fluid-and-motion-is-respectful
  (let [rules (css/compile-css tokens)
        main  (rule rules "main")
        out   (css/css tokens)]
    (testing "the reading column and its furniture size fluidly with clamp()"
      (is (str/includes? (:max-width main) "clamp("))
      (is (str/includes? (:padding main) "clamp("))
      (is (str/includes? (:max-width (rule rules ".page-nav")) "clamp(")))
    (testing "the page breathes: headings carry vertical rhythm"
      (is (some? (:margin-top (rule rules "h2")))))
    (testing "motion yields to a reader who asks for less of it"
      (is (str/includes? out "@media (prefers-reduced-motion: reduce)")))))

(deftest chrome-links-are-quiet-and-focus-is-visible
  (let [rules (css/compile-css tokens)
        nav   (rule rules ".toc a, .page-nav a, .page-footer a")
        focus (rule rules
                "a:focus-visible, button:focus-visible, summary:focus-visible")]
    (testing "chrome navigation links are quiet muted text without an underline"
      (is (= "none" (:text-decoration nav)))
      (is (= "#666666" (:color nav))))
    (testing "body prose links keep their underline (the global rule sets only color)"
      (is (= {:color "#2a52be"} (rule rules "a"))))
    (testing "keyboard focus shows a visible ring everywhere"
      (is (some? focus))
      (is (str/includes? (:outline focus) "solid")))))

(deftest sidebar-rail-gets-a-quiet-modern-treatment
  (let [rules     (css/compile-css tokens)
        selectors (set (map first rules))]
    (testing "rail links read as quiet text, not underlined browser-blue"
      (let [a (rule rules ".book-sidebar a")]
        (is (= "none" (:text-decoration a)))
        (is (= "block" (:display a)))
        (is (some? (:border-radius a)))
        (is (str/includes? (:transition a) "0.15s")))
      (is (contains? selectors ".book-sidebar a:hover")))
    (testing "part dividers read as small uppercase group labels"
      (let [p (rule rules ".book-sidebar .part-heading")]
        (is (= "uppercase" (:text-transform p)))
        (is (= "sans-serif" (:font-family p)))))
    (testing "the current page is accented, not merely bold"
      (let [cur (rule rules ".book-sidebar .current")]
        (is (= "#2a52be" (:color cur)))
        (is (= "700" (:font-weight cur)))))))

(deftest edge-chevron-buttons-anchor-to-the-reading-column
  (let [rules (css/compile-css tokens)]
    (testing "the nav follows the scroll, vertically centered"
      (is (= "sticky" (:position (rule rules ".edge-nav"))))
      (is (= "50vh" (:top (rule rules ".edge-nav")))))
    (testing "the links sit outside main's left and right edges"
      (is (= "absolute" (:position (rule rules ".edge-link"))))
      (is (= "100%" (:right (rule rules ".edge-prev"))))
      (is (= "100%" (:left (rule rules ".edge-next")))))
    (testing "main is the positioning context, so they track its margins"
      (is (= "relative" (:position (rule rules "main")))))))

(deftest print-break-rules-cover-the-page-furniture
  (let [rules (css/compile-css tokens)]
    (is (= "page" (:break-before (rule rules ".page-break"))))
    (is (= "avoid" (:break-inside (rule rules ".keep-together"))))))

(deftest serialize-sorts-properties-for-determinism
  (testing "two prop maps with different insertion orders serialize equally"
    (is (= (css/serialize [["p" (array-map :color "#111" :margin "0")]])
           (css/serialize [["p" (array-map :margin "0" :color "#111")]])))))

(deftest serialize-formats-rules
  (is (= "p {\n  color: #111;\n  margin: 0;\n}\n"
         (css/serialize [["p" {:margin "0" :color "#111"}]]))))

(deftest css-is-deterministic-end-to-end
  (is (= (css/css tokens) (css/css tokens)))
  (is (str/includes? (css/css tokens) "body {")))

(deftest user-css-group-appends-after-the-generated-rules
  (let [themed (assoc tokens :css [["p.fancy" {:color "#bada55"}]
                                   [".hero" {:padding "2em"}]])
        rules  (css/compile-css themed)]
    (is (= [".hero" {:padding "2em"}] (last rules)))
    (is (= ["p.fancy" {:color "#bada55"}] (last (butlast rules))))
    (is (str/ends-with? (css/css themed) ".hero {\n  padding: 2em;\n}\n"))))

(deftest css-hatch-accepts-at-rule-wrappers
  (testing "a rule whose tail is more rules serializes as a wrapped block"
    (is (= (str "@media (max-width: 40em) {\n"
                "p {\n  margin: 0;\n}\n"
                "\n"
                ".hero {\n  padding: 1em;\n}\n"
                "}\n")
           (css/serialize [["@media (max-width: 40em)"
                            ["p" {:margin "0"}]
                            [".hero" {:padding "1em"}]]]))))
  (testing "wrapped rules pass through compile-css and stay deterministic"
    (let [themed (assoc tokens :css [["@media print" [".no-print" {:display "none"}]]])]
      (is (= ["@media print" [".no-print" {:display "none"}]]
             (last (css/compile-css themed))))
      (is (= (css/css themed) (css/css themed)))
      (is (str/includes? (css/css themed)
                         "@media print {\n.no-print {\n  display: none;\n}\n}\n")))))

(deftest dark-mode-is-opt-in-and-honors-the-os-setting
  (testing "a book without :site {:dark} emits no dark block"
    (is (not (str/includes? (css/css tokens) "prefers-color-scheme"))))
  (testing ":site {:dark true} emits a prefers-color-scheme media block"
    (let [themed (assoc tokens :site {:dark true})
          out    (css/css themed)]
      (is (str/includes? out "@media (prefers-color-scheme: dark)"))
      (is (str/includes? out "background-color: #1a1a1a")
          "the computed dark background is used by default")))
  (testing "a :dark token group overrides the computed palette"
    (let [themed (assoc tokens :site {:dark true} :dark {:background "#000000"})]
      (is (str/includes? (css/css themed) "background-color: #000000"))))
  (testing "dark CSS stays deterministic"
    (let [themed (assoc tokens :site {:dark true})]
      (is (= (css/css themed) (css/css themed))))))

;; --- the variables-based dark layer (site only) --------------------------------

(deftest the-default-path-emits-no-css-variables
  (testing "the literal stylesheet carries no custom properties"
    (let [out (css/css tokens)]
      (is (not (str/includes? out "var(")))
      (is (not (str/includes? out ":root")))))
  (testing "compile-css with {:dark? false} equals the no-opts arity byte for byte"
    (is (= (css/css tokens) (css/serialize (css/compile-css tokens {:dark? false}))))
    (let [themed (assoc tokens :site {:dark true})]
      (is (= (css/css themed)
             (css/serialize (css/compile-css themed {:dark? false})))
          "the EPUB path keeps today's literal dark block")))
  (testing "css/css with {:dark? false} matches the literal default"
    (is (= (css/css tokens) (css/css tokens {:dark? false})))))

(deftest dark-variables-flip-the-whole-palette
  (let [out (css/css tokens {:dark? true})]
    (testing "a leading :root carries the light values"
      (is (str/includes? out ":root {"))
      (is (str/includes? out "--ink: #1c1c1c;"))
      (is (str/includes? out "--link: #2a52be;"))
      (is (str/includes? out "--tok-keyword: #0033cc;")))
    (testing "every styled color resolves through a variable"
      (is (str/includes? out "color: var(--ink)"))
      (is (str/includes? out "color: var(--link)"))
      (is (str/includes? out "color: var(--tok-keyword)")))
    (testing "the media block overrides the root variables, headings included"
      (is (str/includes? out "@media (prefers-color-scheme: dark)"))
      (is (str/includes? out "--ink: #e6e6e6;"))
      (is (str/includes? out "--bg: #1a1a1a;")))
    (testing "the body gains a variable-driven background"
      (is (str/includes? out "background-color: var(--bg)")))))

(deftest dark-variable-overrides-come-from-the-dark-token-group
  (testing ":dark keys override the computed dark variables"
    (let [themed (assoc tokens :dark {:background "#000000" :text "#fafafa"})
          out    (css/css themed {:dark? true})]
      (is (str/includes? out "--bg: #000000;"))
      (is (str/includes? out "--ink: #fafafa;"))))
  (testing ":dark {:code …} overrides syntax variables in the dark block"
    (let [themed (assoc tokens :dark {:code {:keyword "#ffcc66"}})
          out    (css/css themed {:dark? true})
          dark   (subs out (str/index-of out "@media (prefers-color-scheme: dark)"))]
      (is (str/includes? dark "--tok-keyword: #ffcc66;"))))
  (testing "the variables path stays deterministic"
    (is (= (css/css tokens {:dark? true}) (css/css tokens {:dark? true})))))

(deftest dark-mode-ships-a-readable-default-code-palette
  (testing "without a :dark {:code} override, the dark block still brightens tokens"
    (let [out  (css/css tokens {:dark? true})
          dark (subs out (str/index-of out "@media (prefers-color-scheme: dark)"))]
      ;; the light defaults (keyword #0033cc, string #008800) are too dark on
      ;; the code background; the dark block overrides them with bright values
      (is (str/includes? dark "--tok-keyword: "))
      (is (str/includes? dark "--tok-string: "))
      (is (not (str/includes? dark "--tok-keyword: #0033cc")))))
  (testing "a book :dark {:code} still wins over the dark default"
    (let [themed (assoc tokens :dark {:code {:keyword "#ffcc66"}})
          out    (css/css themed {:dark? true})
          dark   (subs out (str/index-of out "@media (prefers-color-scheme: dark)"))]
      (is (str/includes? dark "--tok-keyword: #ffcc66;")))))

(deftest dark-mode-inverts-build-time-svg-so-strokes-show
  (testing "diagrams and math route through a media-filter variable"
    (let [out (css/css tokens {:dark? true})]
      (is (str/includes? out "filter: var(--media-filter)"))
      (testing "the filter targets the svg only, so it is not applied twice"
        (is (str/includes? out "svg.diagram {"))
        (is (str/includes? out "svg.math {"))
        (is (not (str/includes? out ".math-display {\n  filter")))
        (is (not (re-find #"(?m)^\.diagram \{\n  filter" out))))
      (testing "light keeps the filter off, dark inverts lightness but keeps hue"
        (is (str/includes? out "--media-filter: none;"))
        (let [dark (subs out (str/index-of out "@media (prefers-color-scheme: dark)"))]
          (is (str/includes? dark "--media-filter: invert("))))))
  (testing "the literal path adds no filter and no media-filter variable"
    (let [out (css/css tokens)]
      (is (not (str/includes? out "media-filter")))
      (is (not (str/includes? out "filter:"))))))

(deftest dark-mode-keeps-line-numbers-visible
  (testing "line numbers route through a variable with a brighter dark value"
    (let [out  (css/css tokens {:dark? true})]
      (is (str/includes? out "color: var(--line-no)"))
      (let [dark (subs out (str/index-of out "@media (prefers-color-scheme: dark)"))]
        (is (str/includes? dark "--line-no: ")))))
  (testing "the literal path keeps the hardcoded line-number color"
    (is (str/includes? (css/css tokens) ".line-no {\n  color: #999999;"))))

(deftest the-toggle-adds-explicit-data-theme-overrides
  (testing "without :toggle? no data-theme blocks are emitted"
    (is (not (str/includes? (css/css tokens {:dark? true}) "data-theme"))))
  (testing ":toggle? emits a light and a dark explicit override plus the button style"
    (let [out (css/css tokens {:dark? true :toggle? true})]
      (is (str/includes? out "html[data-theme=\"dark\"] {"))
      (is (str/includes? out "html[data-theme=\"light\"] {"))
      (is (str/includes? out ".theme-toggle {"))
      (testing "the explicit dark block carries the dark variable values"
        (let [dark (subs out (str/index-of out "html[data-theme=\"dark\"]"))]
          (is (str/includes? dark "--ink: #e6e6e6;"))))
      (testing "the explicit light block carries the light variable values"
        (let [light (subs out (str/index-of out "html[data-theme=\"light\"]"))]
          (is (str/includes? light "--ink: #1c1c1c;"))))))
  (testing "the toggle CSS stays deterministic"
    (is (= (css/css tokens {:dark? true :toggle? true})
           (css/css tokens {:dark? true :toggle? true})))))

;; --- the reader-preferences layer (site only) ---------------------------------

(deftest reader-preferences-add-width-and-scale-variables
  (testing "without :reader? the stylesheet has no reading variables"
    (is (not (str/includes? (css/css tokens {:dark? true}) "--reading-width"))))
  (let [out   (css/css tokens {:reader? true})
        rules (css/compile-css tokens {:reader? true})]
    (testing "a :root defines the reading width and scale defaults"
      (is (str/includes? out ":root {"))
      (is (str/includes? out "--reading-width: clamp(32em, 90vw, 44em);"))
      (is (str/includes? out "--reading-scale: 1;")))
    (testing "the reading column and its furniture consume the width variable"
      (is (= "var(--reading-width)" (:max-width (rule rules "main"))))
      (is (= "var(--reading-width)" (:max-width (rule rules ".page-nav")))))
    (testing "the base font size scales through the scale variable"
      (is (str/includes? (:font-size (rule rules "body")) "var(--reading-scale)")))
    (testing "colors resolve through variables even with dark mode off"
      (is (str/includes? out "color: var(--ink)")))))

(deftest reader-high-contrast-overrides-the-text-color
  (testing "a high-contrast override darkens the ink in light mode"
    (let [out (css/css tokens {:reader? true})]
      (is (str/includes? out "html[data-contrast=\"high\"] {"))
      (is (re-find #"data-contrast=\"high\"[^}]*--ink: #000000" out))))
  (testing "with dark on, a high-contrast variant exists for the dark scheme too"
    (let [out (css/css tokens {:reader? true :dark? true})]
      (is (str/includes? out "html[data-theme=\"dark\"][data-contrast=\"high\"]"))
      (is (re-find #"data-theme=\"dark\"\]\[data-contrast=\"high\"\][^}]*--ink: #ffffff" out)))))

(deftest reader-focus-mode-sheds-the-chrome
  (let [out (css/css tokens {:reader? true})]
    (testing "data-focus hides the navigation chrome but keeps the text"
      (is (str/includes? out "html[data-focus] .book-sidebar {"))
      (is (str/includes? out "html[data-focus] .page-nav {"))
      (is (str/includes? out "html[data-focus] .edge-nav {")))))

(deftest reader-css-stays-deterministic
  (is (= (css/css tokens {:reader? true :dark? true})
         (css/css tokens {:reader? true :dark? true}))))

(deftest the-toggle-button-is-a-clean-pill-control
  (let [rules (css/compile-css tokens {:dark? true :toggle? true})
        btn   (rule rules ".theme-toggle")]
    (testing "a rounded pill that tints from the scheme variables"
      (is (= "999px" (:border-radius btn)))
      (is (= "var(--muted)" (:color btn)))
      (is (str/includes? (:transition btn) "0.15s")))
    (testing "with a hover state"
      (is (= "var(--ink)" (:color (rule rules ".theme-toggle:hover")))))))
