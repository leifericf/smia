(ns smia.html.chrome
  "Pure page furniture for the HTML chromes: the island markup and
   script tags (search, theme toggle, reader preferences, keyboard
   shortcuts, mermaid), the breadcrumb, the edit link, and the margin
   chevrons. Every component takes the chrome `ctx` and renders nil when
   its affordance is off, so a chrome composes them unconditionally.
   Reader-facing strings come from `smia.book.dictionary`. No IO."
  (:require
   [smia.book.dictionary :as dictionary]
   [clojure.string :as str]))

(defn search-form
  "The search form the chromes render when the search island is on: a
   plain GET form targeting the static fallback page, so it works with
   no JavaScript at all. The island mounts on the `data-island` marker
   and reads the page-relative index url and site root from the data
   attributes."
  [ctx]
  (when (:search? ctx)
    (let [href-to (:href-to ctx)]
      [:form {:class          "search"
              :role           "search"
              :method         "get"
              :action         (href-to (:search-url ctx))
              :data-island    "smia-search"
              :data-index-url (href-to "search-index.json")
              :data-root      (href-to "")
              ;; the island renders client-side, so its strings travel as
              ;; localized data attributes rather than hardcoded English
              :data-no-matches
              (dictionary/localize (:language ctx) :search-no-matches)
              :data-suggestions-label
              (dictionary/localize (:language ctx) :search-suggestions)}
       [:input {:type "search" :name "q"
                :placeholder (dictionary/localize (:language ctx) :search-placeholder)
                :aria-label  (dictionary/localize (:language ctx) :search-aria)
                :autocomplete "off"}]])))

(defn search-script
  "The deferred script tag for the search island, when it is on."
  [ctx]
  (when (:search? ctx)
    [:script {:defer "defer" :src ((:href-to ctx) "search.js")}]))

(defn mermaid-scripts
  "The deferred script tags for the mermaid island, when it is on. The
   optional `:mermaid-src` (a UMD mermaid build that sets `window.mermaid`)
   is loaded first; then the committed island bundle (compiled from
   `cljs/smia/site/mermaid_client.cljs`) runs it over the `<pre class=
   \"mermaid\">` blocks. With no `:mermaid-src`, the bundle expects the
   library to be present some other way; with no JavaScript, the diagram
   source shows. Returns a (possibly empty) seq of script tags."
  [ctx]
  (when (:mermaid ctx)
    (cond-> []
      (string? (:mermaid-src ctx))
      (conj [:script {:defer "defer" :src (:mermaid-src ctx)}])
      :always
      (conj [:script {:defer "defer" :src ((:href-to ctx) "mermaid.js")}]))))

(defn theme-script
  "The dark-mode toggle island's script tag, when the toggle is on. Unlike
   the search and mermaid scripts it is NOT deferred: it runs in `<head>`
   before first paint so a stored light/dark choice applies with no flash.
   With JavaScript off the script never runs and the OS setting governs."
  [ctx]
  (when (:dark-toggle ctx)
    [:script {:src ((:href-to ctx) "theme.js")}]))

(defn theme-toggle
  "The dark-mode toggle button, when the toggle is on. It ships `hidden`
   and inert; the island unhides and wires it, so a no-JavaScript reader
   never sees a dead control while the OS scheme still applies. Positioned
   out of flow by `.theme-toggle`, so its place in the markup is just tab
   order."
  [ctx]
  (when (:dark-toggle ctx)
    [:button {:type "button"
              :class "theme-toggle"
              :data-theme-toggle "data-theme-toggle"
              :hidden "hidden"
              :aria-pressed "false"
              :aria-label (dictionary/localize (:language ctx)
                                               :toggle-color-scheme)}
     (dictionary/localize (:language ctx) :toggle-color-scheme)]))

(defn draft-watermark
  "The beta-review watermark, when the build is marked `:draft`. A single
   faint, click-through line of text the stylesheet fixes diagonally across
   the viewport, so it rides over every page exactly like the PDF's
   per-page background. `aria-hidden` keeps it out of the reading order."
  [ctx]
  (when-let [draft (:draft ctx)]
    [:div {:class "draft-watermark" :aria-hidden "true"}
     (:watermark draft)]))

(defn draft-banner
  "The beta-review cover banner, when the build is marked `:draft`: a clear
   notice bar declaring the copy a review draft — the visible half of the
   marking, mirroring the PDF cover notice."
  [ctx]
  (when-let [draft (:draft ctx)]
    [:div {:class "draft-banner" :role "note"}
     (when-let [label (:label draft)]
       [:strong {:class "draft-banner-label"} label])
     (when-let [notice (:notice draft)]
       [:span {:class "draft-banner-text"} notice])]))

(defn reader-script
  "The reader-preferences island's script tag, when the controls are on.
   Like the dark-toggle script it is NOT deferred: it runs in `<head>`
   before first paint so stored width/scale/contrast/scheme choices apply
   with no flash. With JavaScript off the script never runs, the controls
   stay hidden, and the stylesheet defaults govern."
  [ctx]
  (when (:reader ctx)
    [:script {:src ((:href-to ctx) "reader.js")}]))

(defn reader-controls
  "The reader-preferences control cluster, when the controls are on. It
   ships `hidden` and inert; the island unhides and wires it, so a
   no-JavaScript reader never sees a dead control. A compact button reveals
   a small panel of width, text-size, contrast, and — when dark mode is on
   — color-scheme controls. The theme control reuses the dark toggle's
   `data-theme-toggle` marker, so the cluster supersedes the standalone
   button."
  [ctx]
  (when (:reader ctx)
    (let [lang   (:language ctx)
          loc    #(dictionary/localize lang %)
          label  (fn [term] [:span {:class "reader-label"} (loc term)])
          step   (fn [attr val term glyph]
                   [:button {:type "button" attr val
                             :aria-label (loc term)} glyph])
          pair   (fn [& bs] (into [:div {:class "reader-pair"}] bs))
          ;; an on/off control with no text label — the switch shows its state,
          ;; and the row label beside it names what it toggles.
          switch (fn [attr term]
                   [:button (assoc {:type "button" :class "reader-switch"
                                    :aria-pressed "false"
                                    :aria-label (loc term)}
                                   attr "")])]
      (into [:div {:class "reader-controls" :data-reader-controls "" :hidden "hidden"}
             [:button {:type "button" :class "reader-button"
                       :data-reader-toggle "" :aria-expanded "false"
                       :aria-label (loc :reader-settings)}
              "Aa"]]
            [(into [:div {:class "reader-panel" :data-reader-panel "" :hidden "hidden"}]
                   (concat
                     [(label :reading-width)
                      (pair (step :data-reader-width "-" :narrower "–")
                            (step :data-reader-width "+" :wider "+"))
                      (label :text-size)
                      (pair (step :data-reader-scale "-" :smaller "A–")
                            (step :data-reader-scale "+" :larger "A+"))
                      (label :contrast)
                      (switch :data-reader-contrast :high-contrast)
                      (label :focus-mode)
                      (switch :data-focus-toggle :focus-mode)]
                     (when (:reader-theme ctx)
                       [(label :dark-mode)
                        (switch :data-theme-toggle :toggle-color-scheme)])
                     [[:button {:type "button" :class "reader-reset" :data-reader-reset ""}
                       (loc :reset-defaults)]]))]))))

(defn keys-script
  "The keyboard-shortcuts island's deferred script tag, when the shortcuts
   are on. It only binds handlers, so it need not run before paint."
  [ctx]
  (when (:keyboard ctx)
    [:script {:defer "defer" :src ((:href-to ctx) "keys.js")}]))

(defn keyboard-help
  "The keyboard-shortcuts help dialog, when the shortcuts are on. It ships
   `hidden`; the island reveals it on `?` and dismisses it on Escape, a
   click outside, or the close button. Every shortcut it lists also has a
   visible control, so the dialog is a reference, not a requirement."
  [ctx]
  (when (:keyboard ctx)
    (let [lang (:language ctx)
          loc  #(dictionary/localize lang %)
          row  (fn [keys label] (list [:dt {} keys] [:dd {} label]))]
      [:div {:class "kbd-help" :data-kbd-help "" :hidden "hidden"
             :role "dialog" :aria-modal "true"
             :aria-label (loc :keyboard-shortcuts)}
       [:div {:class "kbd-help-panel"}
        [:h2 {} (loc :keyboard-shortcuts)]
        (into [:dl {}]
              (concat
                (row [:span {} [:kbd {} "←"] [:kbd {} "→"]
                      [:kbd {} "h"] [:kbd {} "l"]]
                     (loc :previous-next-page))
                (row [:kbd {} "/"] (loc :search))
                (row [:kbd {} "d"] (loc :dark-mode))
                (row [:kbd {} "f"] (loc :focus-mode))
                (row [:kbd {} "g"] (loc :contents))
                (row [:kbd {} "?"] (loc :show-this-help))))
        [:button {:type "button" :class "kbd-help-close" :data-kbd-close ""}
         (loc :close)]]])))

(defn reading-progress
  "A thin reading-progress bar pinned to the top of the viewport, when the
   reader controls are on. It ships `hidden` and empty; the island reveals
   it and drives its width from the scroll position. With no JavaScript it
   stays hidden, so a reader never sees a bar that cannot move. Decorative,
   so `aria-hidden`."
  [ctx]
  (when (:reader ctx)
    [:div {:class "reading-progress" :data-reading-progress ""
           :hidden "hidden" :aria-hidden "true"}
     [:div {:class "reading-progress-bar"}]]))

(defn breadcrumb
  "A `Part › Chapter` orientation trail atop the reading column, when the
   reader controls are on and the page sits in the contents (not the home
   page). Pure HTML built from the already-assembled `:contents`: the
   page's own entry, preceded by the nearest part divider above it. Most
   valuable in focus mode, where the sidebar is hidden, but a quiet aid in
   any layout. Nil when the page has no contents entry."
  [ctx]
  (when (and (:reader ctx) (not= :home (:kind (:page ctx))))
    (let [page-url (:url (:page ctx))
          contents (vec (:contents ctx))
          here     (first (keep-indexed
                            (fn [i e]
                              (when (and (:href e)
                                         (not= :part (:kind e))
                                         (= (first (str/split (:href e) #"#")) page-url))
                                i))
                            contents))]
      (when here
        (let [chap (nth contents here)
              part (some (fn [e] (when (= :part (:kind e)) e))
                         (reverse (subvec contents 0 here)))]
          (into [:nav {:class "breadcrumb"
                       :aria-label (dictionary/localize (:language ctx) :breadcrumb)}]
                (concat
                  (when part
                    [[:span {:class "breadcrumb-part"} (:text part)]
                     [:span {:class "breadcrumb-sep" :aria-hidden "true"} "›"]])
                  [[:span {:class "breadcrumb-page"} (:text chap)]])))))))

(defn edit-link
  "An \"Edit this page\" link, when the book set `:book/edit-url` and the
   current page has a known source file. The href is the base URL joined to
   the page's source path (e.g. a repository's blob/edit URL)."
  [ctx]
  (when-let [base (:edit-url ctx)]
    (when-let [src (:source-file (:page ctx))]
      [:a {:class "edit-page"
           :href  (str (str/replace base #"/*$" "/") src)}
       (dictionary/localize (:language ctx) :edit-this-page)])))

(defn edge-nav
  "Icon-only previous/next chevrons pinned to the page margins. Plain
   links (no JavaScript): each carries `rel` and a descriptive
   `aria-label` (\"Previous page: <title>\"), so the visual glyph stays
   bare while assistive tech gets the destination. Nil on the home page or
   when the page chains nowhere; the bottom `page-nav` carries the labeled,
   in-flow navigation that this supplements."
  [ctx]
  (when-not (= :home (:kind (:page ctx)))
    (let [href-to (:href-to ctx)
          lang    (:language ctx)
          prev    (:prev ctx)
          next    (:next ctx)
          link    (fn [page klass rel term glyph]
                    [:a {:class           (str "edge-link " klass)
                         :rel             rel
                         :href            (href-to (:url page))
                         :aria-label      (str (dictionary/localize lang term)
                                               ": " (:title page))}
                     [:span {:aria-hidden "true"} glyph]])]
      (when (or prev next)
        (into [:nav {:class      "edge-nav"
                     :aria-label (dictionary/localize lang :pagination)}]
              (concat
                (when prev [(link prev "edge-prev" "prev" :previous-page "‹")])
                (when next [(link next "edge-next" "next" :next-page "›")])))))))
