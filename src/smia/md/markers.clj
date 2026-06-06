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
  "Read an inline escape `payload` as a single EDN form spanning the whole
   payload, or throw a structured error naming the unreadable source.
   Content after the form is an error, not silently dropped."
  [payload]
  (let [r    (java.io.PushbackReader. (java.io.StringReader. payload))
        form (try
               (edn/read {:eof ::eof} r)
               (catch Exception e
                 (throw (error/ex :smia.md.compile/invalid-raw-escape
                                  (str "Inline raw escape is not readable EDN: "
                                       (.getMessage e))
                                  {:source payload}))))
        more (try
               (edn/read {:eof ::eof} r)
               (catch Exception _ ::trailing))]
    (when-not (= ::eof more)
      (throw (error/ex :smia.md.compile/invalid-raw-escape
                       (str "Inline raw escape must be a single EDN form; "
                            "unexpected content after it: " (pr-str payload))
                       {:source payload})))
    (when-not (= ::eof form) form)))

(defn- keyword-payload
  "Trim `payload` to a single keyword-safe token and intern it, or throw —
   a blank or whitespace-bearing payload can never name a references or
   attributes key."
  [kind payload]
  (let [t (str/trim payload)]
    (when (or (str/blank? t) (re-find #"\s" t))
      (throw (error/ex :smia.md.compile/invalid-raw-escape
                       (str "The {=" (name kind) "} payload must be a single "
                            "key token, got: " (pr-str payload))
                       {:marker kind :source payload})))
    (keyword t)))

(defn- as-seq
  "Read an EDN payload as a sequence: a vector/list stays as-is, a scalar
   becomes a one-element seq. Used by the chord/path markers."
  [payload]
  (let [form (read-escape-edn payload)]
    (if (sequential? form) form [form])))

(def inline-markers
  "Inline marker keyword -> pure `(fn [payload] -> author-hiccup node)`.
   `{=hiccup}`/`{=fo}` splice the payload's EDN verbatim; `{=cite}` keys a
   citation by the payload; `{=index}` marks an index term; `{=math}` makes
   inline math; `{=attr}` references a document attribute by name. The UI
   markers build the interface-vocabulary tags:
   `{=kbd}`/`{=menu}` take an EDN sequence (a key chord, a menu path);
   `{=button}`/`{=mark}`/`{=sub}`/`{=sup}` wrap a literal label."
  {:hiccup read-escape-edn
   :fo     read-escape-edn
   :cite   (fn [payload] [:cite {:key (keyword-payload :cite payload)}])
   :index  (fn [payload] [:index {:term payload}])
   :math   (fn [payload] [:math {:notation payload}])
   :attr   (fn [payload] [:attr (keyword-payload :attr payload)])
   :kbd    (fn [payload]
             (let [keys (map str (as-seq payload))]
               (if (= 1 (count keys))
                 [:kbd (first keys)]
                 (into [:span] (interpose "+" (map (fn [k] [:kbd k]) keys))))))
   :menu   (fn [payload] (into [:menu] (map str (as-seq payload))))
   :button (fn [payload] [:button (str/trim payload)])
   :mark   (fn [payload] [:mark payload])
   :sub    (fn [payload] [:sub payload])
   :sup    (fn [payload] [:sup payload])})

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
