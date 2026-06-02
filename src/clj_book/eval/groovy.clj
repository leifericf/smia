(ns clj-book.eval.groovy
  "Imperative shell: validate Groovy code blocks in-process with
   `groovy.lang.GroovyShell`. Loaded only when a book validates Groovy;
   requires the optional `org.apache.groovy/groovy` dependency (the
   `:eval-groovy` alias).

   TRUST BOUNDARY: a `{:test true}` block runs with full JVM authority; only
   validate manuscripts you trust."
  (:require
   [clj-book.eval.registry :as registry])
  (:import
   (groovy.lang GroovyShell)))

(defn evaluate
  "Validate one Groovy block. Returns the shared evaluator result map and
   never throws."
  [{:keys [source attrs]}]
  (let [level (get attrs :level :run)]
    (try
      (let [shell (GroovyShell.)]
        (if (= :parse level)
          (do (.parse shell ^String source) (registry/parsed))
          (registry/from-value level (.evaluate shell ^String source))))
      (catch Throwable e
        (registry/failed [{:message   (or (.getMessage e) (str e))
                           :exception (.getName (class e))}])))))
