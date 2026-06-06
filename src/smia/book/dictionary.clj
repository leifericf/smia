(ns smia.book.dictionary
  "Pure core: localization of the tool's own furniture (layer 1).

   Every reader-facing string smia *generates* — the float and structure
   labels (\"Figure\", \"Chapter\"), the generated section titles
   (\"Bibliography\", \"List of Figures\"), the admonition labels, and the
   site's chrome (\"Contents\", \"Search\") — is looked up here by a stable
   key, keyed by the book's `:book/language`. The manuscript's own *content*
   is not translated; only the apparatus around it is.

   The dictionaries are m1p dictionaries (the same engine the attribute pass
   uses). `localize` resolves a key with a fallback chain: the book language,
   then `:en` (the shipped baseline), then an explicit fallback, then the
   title-cased key. Adding a language is adding a map of overrides. No IO."
  (:require
   [m1p.core :as m1p]
   [clojure.string :as str]))

(def ^:private terms
  "Apparatus terms by language. `:en` is the shipped baseline, extracted from
   the formerly-hardcoded maps; a language map need only override the keys
   that differ (the rest fall back to `:en`)."
  {:en {;; float and structure labels — compose as \"Figure 3\"
        :part "Part" :chapter "Chapter" :appendix "Appendix"
        :figure "Figure" :table "Table" :listing "Listing" :section "Section"
        ;; generated matter titles
        :bibliography "Bibliography" :index "Index"
        :list-of-figures "List of Figures" :list-of-tables "List of Tables"
        :list-of-listings "List of Listings"
        ;; admonition labels
        :note "Note" :tip "Tip" :warning "Warning"
        :important "Important" :caution "Caution"
        ;; in-flow furniture: the xref page phrase, the disclosure
        ;; summary, and the overview panel's default title
        :on-page "on page" :details "Details" :overview "Overview"
        ;; site chrome and the EPUB navigation document
        :contents "Contents" :table-of-contents "Table of contents"
        :landmarks "Landmarks" :start-of-content "Start of content"
        :downloads "Downloads" :search "Search" :edit-this-page "Edit this page"
        :toggle-color-scheme "Toggle dark mode"
        ;; reader-preferences control cluster
        :reader-settings "Reader settings" :reading-width "Width"
        :narrower "Narrower" :wider "Wider"
        :text-size "Text size" :smaller "Smaller text" :larger "Larger text"
        :contrast "Contrast" :high-contrast "High contrast"
        :reset-defaults "Reset to defaults"
        :focus-mode "Focus mode" :breadcrumb "Breadcrumb"
        ;; keyboard shortcuts and the help overlay
        :keyboard-shortcuts "Keyboard shortcuts"
        :previous-next-page "Previous / next page" :dark-mode "Dark mode"
        :show-this-help "Show this help" :close "Close"
        :pagination "Pagination" :previous-page "Previous page" :next-page "Next page"
        :search-placeholder "Search…" :search-aria "Search this book"
        :search-no-matches
        "No matches — press Enter to browse the book by category."
        :search-suggestions "Search suggestions"
        :search-fallback-note
        (str "With JavaScript enabled, the search box suggests matches as you "
             "type. Without it, the book is listed here by category.")
        ;; search category headings (plural)
        :cat/chapter "Chapters" :cat/appendix "Appendices" :cat/matter "Pages"
        :cat/downloads "Downloads" :cat/section "Sections" :cat/figure "Figures"
        :cat/table "Tables" :cat/listing "Listings" :cat/term "Index terms"}})

(def dictionaries
  "Prepared m1p dictionary per language keyword. A locale is added by adding
   a map to `terms`; `with-redefs` on this var lets tests inject one."
  (into {} (map (fn [[lang m]] [lang (m1p/prepare-dictionary m)])) terms))

(defn- normalize-lang
  "Normalize a `:book/language` (a string like \"en\" or \"en-US\", a
   keyword, or nil) to a primary-subtag keyword, e.g. `:en`."
  [language]
  (some-> language str (str/split #"-") first str/lower-case keyword))

(defn- title-case [k]
  (->> (str/split (name k) #"-") (map str/capitalize) (str/join " ")))

(defn- looked-up
  "Look `k` up in `lang`'s dictionary, or nil when absent (m1p signals a
   miss with an error-marker vector, which we treat as nil)."
  [lang k]
  (when-let [d (get dictionaries lang)]
    (let [v (m1p/lookup {} d k)]
      (when-not (and (vector? v) (= :m1p.core/error (first v))) v))))

(defn localize
  "Localize term `k` for `language`. Falls back `language -> :en ->
   `fallback` (when given) -> the title-cased key`."
  ([language k] (localize language k nil))
  ([language k fallback]
   (let [lang (normalize-lang language)]
     (or (looked-up lang k)
         (looked-up :en k)
         fallback
         (title-case k)))))
