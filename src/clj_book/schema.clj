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

(def Manuscript
  "Parsed and validated `book.edn`. Open map: unrecognized keys are
   preserved and surfaced as warnings elsewhere, not rejected here."
  [:map
   [:book/slug :string]
   [:book/title :string]
   [:book/chapters [:and
                    [:sequential :string]
                    [:fn {:error/message "should be non-empty"} seq]]]])

(def Tokens
  "Parsed and validated `styles/tokens.edn`."
  [:map
   [:color :map]
   [:type :map]
   [:spacing :map]
   [:layout :map]])

(def Target
  "A build target supported by v1 alpha."
  [:enum :site :pdf])

(def Request
  "A normalized public request map (see `clj-book.request`)."
  [:map
   [:command [:enum :validate :build :serve]]
   [:book-root :string]
   [:config-path :string]
   [:output-root :string]
   [:profile :any]
   [:dry-run {:optional true} :boolean]
   [:targets [:maybe [:sequential :keyword]]]])

(def Paths
  "Resolved output paths for a build, derived from the request and slug."
  [:map
   [:book-output-dir :string]
   [:intermediate-dir :string]
   [:site-output-dir :string]
   [:pdf-output-dir :string]
   [:tokens-dir :string]])

(def Prereqs
  "Shared build prerequisites emitted once before any target runs."
  [:map
   [:master-path :string]
   [:site-css :string]
   [:theme-yaml :string]])

(def TargetStep
  "A single target's place in the plan: what to build and where it goes."
  [:map
   [:target Target]
   [:output-dir :string]])

(def Plan
  "An inspectable, pure description of a build: which targets, where each
   writes, the shared prerequisites to emit, and the manifest skeleton.
   Produced by clj-book.build.plan; performed by clj-book.build.execute."
  [:map
   [:book-root :string]
   [:targets [:sequential Target]]
   [:manuscript :map]
   [:paths Paths]
   [:prereqs [:sequential [:map [:kind :keyword] [:path :string]]]]
   [:target-steps [:sequential TargetStep]]
   [:manifest-skeleton [:map
                        [:book/slug :string]
                        [:build/targets [:sequential Target]]
                        [:metadata :map]]]])

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
