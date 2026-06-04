(ns smia.md.markers
  "Pure core: the inline raw-escape marker registry — a `marker -> (fn
   [payload] -> author node)` data map mirroring the evaluator and
   highlighter registries. A `` `payload`{=marker} `` code span folds into
   the marker's node (smia.md.compile/fold-inline-escapes), so adding an
   inline construct is adding a map entry rather than editing a `case`.
   No IO; nothing is read from disk here."
  (:require
   [smia.error :as error]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn- read-escape-edn
  "Read an inline escape `payload` as a single EDN form, or throw a
   structured error naming the unreadable source."
  [payload]
  (try
    (edn/read-string payload)
    (catch Exception e
      (throw (error/ex :smia.md.compile/invalid-raw-escape
                       (str "Inline raw escape is not readable EDN: "
                            (.getMessage e))
                       {:source payload})))))

(def inline-markers
  "Inline marker keyword -> pure `(fn [payload] -> author-hiccup node)`.
   `{=hiccup}`/`{=fo}` splice the payload's EDN verbatim; `{=cite}` keys a
   citation by the payload; `{=index}` marks an index term; `{=math}` makes
   inline math."
  {:hiccup read-escape-edn
   :fo     read-escape-edn
   :cite   (fn [payload] [:cite {:key (keyword (str/trim payload))}])
   :index  (fn [payload] [:index {:term payload}])
   :math   (fn [payload] [:math {:notation payload}])})

(def marker-names
  "The marker names as strings, sorted so the inline-escape regex this list
   builds is deterministic and stable across builds."
  (->> (keys inline-markers) (map name) sort vec))

(defn marker-form
  "Build the author node for inline marker `kind` (keyword or string) with
   `payload`. An unregistered marker throws `:smia.md.compile/unknown-marker`
   — defensive, since the inline-escape regex is built from the keys and so
   only ever matches a registered marker."
  [kind payload]
  (if-let [f (get inline-markers (keyword kind))]
    (f payload)
    (throw (error/ex :smia.md.compile/unknown-marker
                     (str "Unknown inline marker: {=" (name kind) "}")
                     {:marker kind}))))
