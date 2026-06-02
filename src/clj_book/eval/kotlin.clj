(ns clj-book.eval.kotlin
  "Imperative shell: validate Kotlin code blocks in-process with the Kotlin
   JSR-223 scripting engine. Loaded only when a book validates Kotlin;
   requires the optional `org.jetbrains.kotlin/kotlin-scripting-jsr223`
   dependency (the `:eval-kotlin` alias). Kotlin's embeddable compiler is
   the heaviest evaluator to start.

   TRUST BOUNDARY: a `{:test true}` block runs with full JVM authority; only
   validate manuscripts you trust."
  (:require
   [clj-book.eval.registry :as registry])
  (:import
   (javax.script ScriptEngineManager)))

(defn- engine []
  (or (.getEngineByName (ScriptEngineManager.) "kotlin")
      (throw (ex-info "No Kotlin JSR-223 script engine on the classpath." {}))))

(defn evaluate
  "Validate one Kotlin block. Returns the shared evaluator result map and
   never throws. The JSR-223 engine compiles and runs together, so `:parse`
   is treated as a full evaluation."
  [{:keys [source attrs]}]
  (let [level (get attrs :level :run)]
    (try
      (let [value (.eval (engine) ^String source)]
        (if (= :parse level)
          (registry/parsed)
          (registry/from-value level value)))
      (catch Throwable e
        (registry/failed [{:message   (or (.getMessage e) (str e))
                           :exception (.getName (class e))}])))))
