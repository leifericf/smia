(ns clj-book.request
  "Normalization and validation of public request maps."
  (:require
   [clj-book.error :as error]
   [clojure.string :as str]))

(def supported-targets
  "Targets supported by v1 alpha."
  #{:site :pdf})

(def ^:private default-output-root "build")
(def ^:private default-config-path "book.edn")

(defn- string-or-throw [k v]
  (when (and (some? v) (not (string? v)))
    (throw (error/ex :clj-book.request/invalid-value
                     (str k " must be a string when provided.")
                     {k v})))
  v)

(defn- normalize-targets [targets]
  (cond
    (nil? targets) nil
    (sequential? targets) (vec targets)
    :else
    (throw (error/ex :clj-book.request/invalid-targets
                     ":targets must be a vector of keywords."
                     {:targets targets}))))

(defn- require-targets [targets]
  (when (empty? targets)
    (throw (error/ex :clj-book.request/missing-targets
                     "Build requires :targets with one or more values."
                     {:targets targets})))
  (let [bad (remove keyword? targets)]
    (when (seq bad)
      (throw (error/ex :clj-book.request/invalid-targets
                       ":targets must contain only keywords."
                       {:targets targets :non-keywords (vec bad)}))))
  (let [unknown (remove supported-targets targets)]
    (when (seq unknown)
      (throw (error/ex :clj-book.request/unknown-target
                       (str "Unsupported target(s): "
                            (str/join ", " (map pr-str unknown)))
                       {:targets         targets
                        :unknown-targets (vec unknown)
                        :supported       (vec (sort supported-targets))}))))
  targets)

(defn- require-book-root [book-root]
  (when (or (nil? book-root) (and (string? book-root) (empty? book-root)))
    (throw (error/ex :clj-book.request/missing-book-root
                     ":book-root is required."
                     {:book-root book-root})))
  (string-or-throw :book-root book-root))

(defn normalize
  "Normalize and validate a public request map for the given `command`
   (`:validate`, `:build`, or `:serve`). Returns a normalized map or
   throws a structured `ex-info`."
  [request-map command]
  (when-not (map? request-map)
    (throw (error/ex :clj-book.request/invalid-request
                     "Request must be a map."
                     {:request request-map})))
  (let [{:keys [book-root config-path targets output-root profile]} request-map
        normalized {:command     command
                    :book-root   (require-book-root book-root)
                    :config-path (or (string-or-throw :config-path config-path)
                                     default-config-path)
                    :output-root (or (string-or-throw :output-root output-root)
                                     default-output-root)
                    :profile     profile
                    :targets     (normalize-targets targets)}]
    (cond-> normalized
      (= command :build) (update :targets require-targets))))
