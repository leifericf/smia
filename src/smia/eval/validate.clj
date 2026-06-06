(ns smia.eval.validate
  "Imperative shell: the opt-in code-validation pass. Collect the `:test`
   code blocks from loaded chapters, resolve each block's evaluator from the
   registry, run it, and aggregate failures into a single structured error.

   Evaluators live in shell namespaces and are resolved lazily with
   `requiring-resolve`, so an evaluator's optional dependency is only loaded
   when a book actually uses that language."
  (:require
   [smia.error :as error]
   [smia.eval.registry :as registry]))

(defn- resolve-evaluator
  "Resolve the evaluate fn for `lang`, or throw a structured error."
  [lang]
  (when (nil? lang)
    (throw (error/ex :smia.eval/missing-language
                     "A code block marked {:test true} has no :lang to validate against."
                     {})))
  (let [{:keys [evaluate requires] :as desc} (get registry/evaluators lang)]
    (when-not desc
      (throw (error/ex :smia.eval/unsupported-language
                       (str "No evaluator registered for language: " (pr-str lang) ". "
                            (registry/describe))
                       {:lang lang :supported (vec (sort (keys registry/evaluators)))})))
    (or (try (requiring-resolve evaluate)
             (catch Exception e
               (throw (error/ex :smia.eval/evaluator-unavailable
                                (str "Could not load the " (name lang) " evaluator"
                                     (when requires (str "; add the optional dependency " requires))
                                     ": " (.getMessage e))
                                {:lang lang :requires requires :cause (.getMessage e)}))))
        (throw (error/ex :smia.eval/evaluator-unavailable
                         (str "Evaluator symbol did not resolve: " evaluate)
                         {:lang lang :symbol evaluate})))))

(def ^:private levels
  "The validation depths a block may request."
  #{:parse :compile :run :assert})

(defn- check-level
  "Throw when a block's `:level` names no known validation depth — a typo
   like `:prase` must not silently fall through to `:run`."
  [{:keys [attrs source]}]
  (let [level (get attrs :level :run)]
    (when-not (contains? levels level)
      (throw (error/ex :smia.eval/invalid-level
                       (str "Unknown :level " (pr-str level) " on a :test "
                            "block; use one of :parse, :compile, :run, "
                            ":assert.")
                       {:level level :source source})))))

(defn validate-blocks!
  "Run every block in `blocks` (each `{:lang :source :attrs}`). Returns
   `{:status :ok :validated n :results [...]}`, or throws
   `:smia.eval/validation-failed` carrying every failure."
  [blocks]
  (let [results  (mapv (fn [{:keys [lang] :as block}]
                         (check-level block)
                         (assoc block :result ((resolve-evaluator lang) block)))
                       blocks)
        failures (filter #(= :failed (get-in % [:result :status])) results)]
    (when (seq failures)
      (throw (error/ex :smia.eval/validation-failed
                       (str (count failures) " of " (count results)
                            " code block(s) failed validation.")
                       {:failures (mapv (fn [f]
                                          {:lang        (:lang f)
                                           :source      (:source f)
                                           :diagnostics (get-in f [:result :diagnostics])})
                                        failures)})))
    {:status :ok :validated (count results) :results results}))

(defn validate-chapters!
  "Collect and validate every `:test` code block across `chapters`."
  [chapters]
  (validate-blocks! (into [] (mapcat registry/collect-test-blocks) chapters)))
