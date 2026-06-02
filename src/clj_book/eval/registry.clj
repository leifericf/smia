(ns clj-book.eval.registry
  "Pure core: the language-keyed evaluator registry and the helpers that
   find code blocks to validate.

   `evaluators` is a plain data map (language keyword -> descriptor),
   mirroring clj-book.fo.expand/expanders: adding a language is adding a
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
  {:clojure {:evaluate   'clj-book.eval.clojure/evaluate
             :requires   nil
             :in-process true}})

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
