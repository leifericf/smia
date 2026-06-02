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
          :parse
          {:status :parsed}

          :assert
          (let [v (eval-in-fresh-ns forms)]
            (if v
              {:status :matched :value v}
              {:status      :failed
               :diagnostics [{:message "Assertion block evaluated to a falsey value."}]}))

          ;; :compile and :run both evaluate (Clojure compiles as it evals).
          (let [v (eval-in-fresh-ns forms)]
            {:status :ran :value v})))
      (catch Throwable e
        {:status      :failed
         :diagnostics [{:message   (or (.getMessage e) (str e))
                        :exception (.getName (class e))}]}))))
