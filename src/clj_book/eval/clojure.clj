(ns clj-book.eval.clojure
  "Imperative shell: validate Clojure code blocks by evaluating them
   in-process with native `eval`.

   TRUST BOUNDARY: a `{:test true}` block runs with the full authority of
   the build JVM — it is not sandboxed. This is the same trust model as a
   `.clj` chapter under `load-file`, and a deliberate choice over a
   sandboxed interpreter so a validated sample behaves exactly as it will
   for a reader running real Clojure. Only validate manuscripts you trust.

   The block is read with `*read-eval*` disabled (no read-time `#=` eval)
   and evaluated in a fresh, throwaway namespace so blocks neither pollute
   the build runtime nor leak state into one another. `:level` selects how
   far to go: `:parse` reads only; `:compile`/`:run` evaluate; `:assert`
   additionally requires the last form's value to be truthy."
  (:require
   [clj-book.eval.registry :as registry])
  (:import
   (java.io PushbackReader StringReader)))

(defn- read-forms
  "Read every top-level form from `source` (no read-time eval)."
  [source]
  (let [reader (PushbackReader. (StringReader. source))
        eof    (Object.)]
    (binding [*read-eval* false]
      (loop [acc []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? form eof) acc (recur (conj acc form))))))))

(defn- eval-in-fresh-ns
  "Evaluate `forms` in a fresh namespace, returning the last value. The
   namespace is removed afterward."
  [forms]
  (let [tmp (create-ns (gensym 'clj-book.eval.sandbox))]
    (try
      (binding [*ns* tmp]
        (refer-clojure)
        (reduce (fn [_ form] (eval form)) nil forms))
      (finally
        (remove-ns (ns-name tmp))))))

(defn evaluate
  "Validate one Clojure code block. `input` is `{:source :attrs :lang}`;
   returns `{:status :diagnostics? :value?}` where `:status` is one of
   `:parsed`, `:ran`, `:matched`, or `:failed`. Never throws."
  [{:keys [source attrs]}]
  (let [level (get attrs :level :run)]
    (try
      (let [forms (read-forms source)]
        (case level
          :parse (registry/parsed)
          ;; :assert requires truthiness; :compile and :run both evaluate
          ;; (Clojure compiles as it evals) — `from-value` shapes each.
          (registry/from-value level (eval-in-fresh-ns forms))))
      (catch Throwable e
        (registry/failed [{:message   (or (.getMessage e) (str e))
                           :exception (.getName (class e))}])))))
