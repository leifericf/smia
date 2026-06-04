(ns smia.eval.registry
  "Pure core: the language-keyed evaluator registry and the helpers that
   find code blocks to validate.

   `evaluators` is a plain data map (language keyword -> descriptor),
   mirroring smia.fo.expand/expanders: adding a language is adding a
   data entry, not editing a `cond`. A descriptor names the evaluate fn as
   a *symbol* (resolved later in the shell via `requiring-resolve`) so this
   core stays pure and an evaluator's optional dependency is only loaded
   when actually used. No IO; nothing is evaluated here."
  (:require
   [clojure.string :as str]))

(def evaluators
  "Language keyword -> `{:evaluate <fully-qualified-symbol> :requires
   <dep-hint|nil> :in-process <bool>}`. The shell resolves `:evaluate` and
   calls `(evaluate {:lang :source :attrs})`. `:requires` is a human hint
   naming the optional dependency to add when the namespace cannot load."
  {:clojure {:evaluate   'smia.eval.clojure/evaluate
             :requires   nil
             :in-process true}
   :groovy  {:evaluate   'smia.eval.groovy/evaluate
             :requires   'org.apache.groovy/groovy
             :in-process true}
   :java    {:evaluate   'smia.eval.java/evaluate
             :requires   nil
             :in-process true}
   :kotlin  {:evaluate   'smia.eval.kotlin/evaluate
             :requires   'org.jetbrains.kotlin/kotlin-scripting-jsr223
             :in-process true}})

;; --- evaluator result constructors ----------------------------------------
;; The shared shape every evaluator returns, so the four in-process JVM
;; evaluators stay consistent. Pure data; no engine is referenced here.

(defn parsed
  "A successful parse-only result."
  [] {:status :parsed})

(defn ran
  "A successful run result carrying the last value."
  [value] {:status :ran :value value})

(defn matched
  "A successful assertion result (the block evaluated to a truthy value)."
  [value] {:status :matched :value value})

(defn failed
  "A failure result. `diagnostics` is a seq of message strings or maps."
  [diagnostics]
  {:status      :failed
   :diagnostics (mapv #(if (map? %) % {:message (str %)}) diagnostics)})

(defn from-value
  "Shape an evaluated `value` for the requested `level`: `:assert` requires
   truthiness; anything else is a plain run."
  [level value]
  (if (= :assert level)
    (if value (matched value) (failed ["Assertion block evaluated to a falsey value."]))
    (ran value)))

(defn supported?
  "True when a language has a registered evaluator."
  [lang]
  (contains? evaluators lang))

(defn- pre-test-block?
  [node]
  (and (vector? node)
       (= :pre (first node))
       (map? (second node))
       (:test (second node))))

(defn collect-test-blocks
  "Walk author Hiccup `node`, returning a vector of `{:lang :source :attrs
   :pos?}` for every `[:pre {… :test true} \"…\"]` block. The block's source
   is the concatenation of its string children."
  [node]
  (cond
    (pre-test-block? node)
    (let [attrs (second node)]
      [{:lang   (:lang attrs)
        :attrs  attrs
        :source (apply str (filter string? (drop 2 node)))}])

    (vector? node) (into [] (mapcat collect-test-blocks) node)
    (seq? node)    (into [] (mapcat collect-test-blocks) node)
    :else          []))

(defn plan-validation
  "Summarize the validation work for `chapters` (a seq of author-Hiccup
   chapter forms): total `:test` blocks and a per-language breakdown. Pure —
   for `--dry-run` visibility, it runs nothing."
  [chapters]
  (let [blocks (mapcat collect-test-blocks chapters)]
    {:total       (count blocks)
     :by-language (frequencies (map :lang blocks))
     :unsupported (->> blocks
                       (map :lang)
                       (remove supported?)
                       distinct
                       vec)}))

(defn describe
  "A short human description of the registered languages."
  []
  (str "registered evaluators: "
       (str/join ", " (map name (sort (keys evaluators))))))
