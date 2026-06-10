(ns smia.book.draft
  "Pure core: normalize the `:book/draft` beta-review marking and derive its
   display text.

   A `:book/draft` value (validated in `smia.book.config`) is a boolean or a
   map tuning three text slots. `normalize` turns it into a canonical map
   (defaults applied) or nil when the marking is off, so every downstream
   reader — the cover notice (`smia.book.assemble`), the per-page watermark
   (`smia.theme.compile`), and the HTML/EPUB editions — reads plain fields
   rather than re-interpreting the boolean-or-map shape. No IO."
  (:require
   [clojure.string :as str]))

(def defaults
  "The default text for an unconfigured (or partially configured) draft
   marking: a short cover-notice `:label`, the cover-notice `:notice` body,
   and the diagonal per-page `:watermark`."
  {:label     "Beta"
   :notice    "Confidential review copy. Not for distribution."
   :watermark "BETA"})

(defn normalize
  "Canonicalize a `:book/draft` value into `{:label :notice :watermark}`
   with defaults applied, or nil when the marking is off (`nil`/`false`).
   `true` yields the defaults; a map overrides the defaults per key."
  [v]
  (cond
    (or (nil? v) (false? v)) nil
    (map? v)                 (merge defaults v)
    :else                    defaults))

(defn watermark-text
  "The per-page watermark string for a normalized `draft` (or nil when the
   marking is off). When a non-blank `licensee` is supplied it is woven in
   (\"BETA — Ada\"), so a leaked review PDF is traceable to its recipient."
  [draft licensee]
  (when draft
    (let [base (:watermark draft)]
      (if (and licensee (seq (str/trim licensee)))
        (str base " — " (str/trim licensee))
        base))))

(defn notice-text
  "The cover-notice body for a normalized `draft`, or nil when off."
  [draft]
  (:notice draft))

(defn stamp-line
  "The build-stamp display line for a captured `stamp`
   (`{:built-at \"YYYY-MM-DD HH:MM\" :sha \"5d05bd7\"}`), or nil when there
   is no stamp (no `:built-at`). `labeled?` prefixes \"Build \" for the
   prominent cover line; the bare form (date-time then middot then short
   SHA) is for the discreet per-page header. The SHA is dropped when the
   build root is not a git checkout (`:sha` nil/blank), leaving just the
   timestamp — so a non-git build still stamps when it was made."
  [stamp labeled?]
  (when-let [at (:built-at stamp)]
    (let [sha  (:sha stamp)
          base (if labeled? (str "Build " at) at)]
      (if (and sha (seq (str/trim sha)))
        (str base " · " (str/trim sha))
        base))))
