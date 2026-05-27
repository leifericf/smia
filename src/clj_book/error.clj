(ns clj-book.error
  "Structured error constructors. Errors are `ex-info` instances with
   `:error/type`, `:error/message`, and `:error/context` in `ex-data`.")

(defn ex
  "Build an `ex-info` carrying a structured error payload."
  ([error-type message]
   (ex error-type message {}))
  ([error-type message context]
   (ex-info message
            {:error/type    error-type
             :error/message message
             :error/context context})))

(defn data
  "Return the structured error payload of an `ex-info`, or nil."
  [^Throwable t]
  (let [d (ex-data t)]
    (when (and d (:error/type d))
      d)))
