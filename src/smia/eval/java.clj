(ns smia.eval.java
  "Imperative shell: validate Java code blocks in-process with JShell
   (`jdk.jshell`, part of the JDK — no extra dependency). The block is split
   into snippets, each evaluated in order; a snippet that fails to compile is
   surfaced with its compiler diagnostics, and a snippet that throws at
   runtime is surfaced with its exception.

   TRUST BOUNDARY: a `{:test true}` block runs with full JVM authority; only
   validate manuscripts you trust."
  (:require
   [smia.eval.registry :as registry]
   [clojure.string :as str])
  (:import
   (java.util Locale)
   (jdk.jshell JShell Snippet$Status SnippetEvent)))

(defn- snippets
  "Split `source` into complete JShell snippets in order."
  [sca source]
  (loop [rem (str/trim source), acc []]
    (if (str/blank? rem)
      acc
      (let [ci  (.analyzeCompletion sca rem)
            src (.source ci)]
        (if (str/blank? src)
          acc
          (recur (.remaining ci) (conj acc src)))))))

(defn- diagnostics-of [^JShell js ^SnippetEvent ev]
  (->> (.toList (.diagnostics js (.snippet ev)))
       (mapv (fn [d] {:message (.getMessage d (Locale/getDefault))}))))

(defn evaluate
  "Validate one Java block. Returns the shared evaluator result map and
   never throws. JShell reports an expression's value as a string, so
   `:assert` checks that the final snippet evaluated to `\"true\"`."
  [{:keys [source attrs]}]
  (let [level (get attrs :level :run)
        js    (JShell/create)]
    (try
      (let [sca  (.sourceCodeAnalysis js)
            srcs (snippets sca source)]
        (if (= :parse level)
          (registry/parsed)
          (let [events   (into [] (mapcat #(.eval js %)) srcs)
                rejected (filter #(= Snippet$Status/REJECTED (.status ^SnippetEvent %)) events)
                thrown   (keep #(.exception ^SnippetEvent %) events)
                last-val (some-> ^SnippetEvent (last events) (.value))]
            (cond
              (seq rejected)
              (registry/failed (mapcat #(diagnostics-of js %) rejected))

              (seq thrown)
              (registry/failed (map (fn [t] {:message   (.getMessage t)
                                             :exception (.getName (class t))}) thrown))

              (= :assert level)
              (if (= "true" last-val)
                (registry/matched last-val)
                (registry/failed
                  [{:message (str "Assertion did not evaluate to true (was "
                                  (pr-str last-val) ").")}]))

              :else (registry/ran last-val)))))
      (catch Throwable e
        (registry/failed [{:message   (or (.getMessage e) (str e))
                           :exception (.getName (class e))}]))
      (finally (.close js)))))
