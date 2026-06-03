(ns clj-book.schema
  "Malli schemas for the values that flow between clj-book's contexts.

   Schemas are plain Clojure data. They name the shapes that cross
   context seams (Manuscript, Tokens, Request, Paths, Prereqs, Plan) so
   those contracts are explicit rather than implied by destructuring.
   Validation happens at the seams in the imperative shell; the pure
   cores stay free of schema calls. Failures are reported through the
   shared `error` ns with a humanized explanation."
  (:require
   [clj-book.error :as error]
   [malli.core :as m]
   [malli.error :as me]))

(def ^:private non-empty
  "Malli predicate fragment: the collection must be non-empty."
  [:fn {:error/message "should be non-empty"} seq])

(def NonEmptyStrings
  "A non-empty sequence of strings."
  [:and [:sequential :string] non-empty])

(def Part
  "A part: a title plus the chapters it groups."
  [:map
   [:part/title :string]
   [:part/chapters NonEmptyStrings]])

(def MatterSection
  "A named front/back-matter section: a role keyword and, unless the role is
   generated (bibliography, index), a source file."
  [:map
   [:role :keyword]
   [:file {:optional true} :string]])

(def Manuscript
  "Parsed and validated `book.edn`. Open map: unrecognized keys are
   preserved and surfaced as warnings elsewhere, not rejected here. The
   body is a flat `:book/chapters` list or a `:book/parts` grouping; the
   either/or requirement and cross-key checks live in `clj-book.book.config`."
  [:map
   [:book/slug :string]
   [:book/title :string]
   [:book/chapters {:optional true} NonEmptyStrings]
   [:book/parts {:optional true} [:and [:sequential Part] non-empty]]
   [:book/front-matter {:optional true} [:sequential MatterSection]]
   [:book/back-matter {:optional true} [:sequential MatterSection]]
   [:book/appendices {:optional true} [:sequential :string]]
   [:book/numbering {:optional true} :map]])

(def Tokens
  "Parsed and validated `theme.edn` (the book root's theme file). Beyond
   the required token groups, two optional data-only styling hatches
   mirror the content hatches: `:fo` (tag -> FO property map, merged over
   the compiled PDF style) and `:css` (`[[selector prop-map] …]`,
   appended after the generated stylesheet rules)."
  [:map
   [:color :map]
   [:type :map]
   [:spacing :map]
   [:layout :map]
   [:fo {:optional true} [:map-of :keyword :map]]
   [:css {:optional true} [:sequential [:tuple :string :map]]]])

(def Edition
  "A deliverable edition clj-book can build."
  [:enum :screen :print :print-x :site :epub])

(def Request
  "A normalized public request map (see `clj-book.build.request`)."
  [:map
   [:command [:enum :validate :build]]
   [:book-root :string]
   [:config-path :string]
   [:output-root :string]
   [:dry-run {:optional true} :boolean]
   [:validate-code {:optional true} :boolean]
   [:editions [:maybe [:sequential :keyword]]]])

(def Paths
  "Resolved output directories for a build, derived from the request and
   slug. Per-edition file paths are computed in the plan."
  [:map
   [:book-output-dir :string]
   [:intermediate-dir :string]
   [:pdf-output-dir :string]])

(def PdfEditionStep
  "A PDF edition's place in the plan: where its intermediate FO and final
   PDF are written."
  [:map
   [:edition Edition]
   [:fo-path :string]
   [:pdf-path :string]])

(def SiteEditionStep
  "The site edition's place in the plan: the directory its page map is
   written into."
  [:map
   [:edition [:enum :site]]
   [:out-dir :string]])

(def EpubEditionStep
  "The EPUB edition's place in the plan: where the package is written."
  [:map
   [:edition [:enum :epub]]
   [:epub-path :string]])

(def EditionStep
  "A single edition's place in the plan; the shape follows the edition's
   output format."
  [:or PdfEditionStep SiteEditionStep EpubEditionStep])

(def Plan
  "An inspectable, pure description of a build: which editions, where each
   writes its output, and the manifest skeleton. Produced by
   clj-book.build.plan; performed by clj-book.build.execute."
  [:map
   [:book-root :string]
   [:editions [:sequential Edition]]
   [:manuscript :map]
   [:paths Paths]
   [:edition-steps [:sequential EditionStep]]
   [:manifest-skeleton [:map
                        [:book/slug :string]
                        [:build/editions [:sequential Edition]]
                        [:metadata :map]]]
   [:validation {:optional true} :map]
   [:numbering {:optional true} :map]])

(defn valid?
  "True when `value` conforms to `schema`."
  [schema value]
  (m/validate schema value))

(defn explain
  "Return a humanized explanation for why `value` fails `schema`, or nil
   when it conforms."
  [schema value]
  (some-> (m/explain schema value) me/humanize))

(defn check
  "Return `value` when it conforms to `schema`; otherwise throw a
   structured `ex-info` of `error-type` carrying the humanized errors.
   Use at context seams in the shell, never inside a pure core."
  [schema value error-type]
  (if (m/validate schema value)
    value
    (throw (error/ex error-type
                     "Value does not conform to schema."
                     {:errors (explain schema value)
                      :value  value}))))
