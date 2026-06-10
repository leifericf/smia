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
