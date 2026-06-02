(ns clj-book.md.schema
  "Malli schema for Markdown chapter front-matter (the `:chapter` attribute
   map). An open map: `:id` and `:title` are optional here because the
   shell derives them from the filename and the first H1 when absent, but
   when present they must be the right type. Extra keys are preserved."
  (:require
   [clj-book.schema :as schema]))

(def ChapterAttrs
  "The front-matter / `:chapter` attribute map."
  [:map
   [:id {:optional true} :keyword]
   [:title {:optional true} :string]])

(def AdmonitionAttrs
  "The attribute map of a `:::admonition {…}` directive. `:kind` is the
   admonition flavor (`:note`, `:tip`, `:warning`, …) and must be a keyword."
  [:map
   [:kind {:optional true} :keyword]])

(defn check
  "Return `attrs` when it conforms to the chapter front-matter schema;
   otherwise throw a structured error of `error-type`."
  [attrs error-type]
  (schema/check ChapterAttrs attrs error-type))

(defn check-admonition
  "Return admonition `attrs` when they conform; otherwise throw a structured
   error of `error-type`."
  [attrs error-type]
  (schema/check AdmonitionAttrs attrs error-type))
