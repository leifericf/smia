(ns clj-book.build.request
  "Normalization and validation of public request maps."
  (:require
   [clj-book.error :as error]
   [clojure.string :as str]))

(def supported-profiles
  "PDF layout profiles clj-book can render."
  #{:screen :print})

(def default-profiles
  "Profiles built when a build request does not name any. Both editions
   are produced by default; a request may select a subset."
  [:screen :print])

(def ^:private default-output-root "build")
(def ^:private default-config-path "book.edn")
(def ^:private default-book-root ".")

(defn- string-or-throw [k v]
  (when (and (some? v) (not (string? v)))
    (throw (error/ex :clj-book.build.request/invalid-value
                     (str k " must be a string when provided.")
                     {k v})))
  v)

(defn- normalize-profiles [profiles]
  (cond
    (nil? profiles) nil
    (sequential? profiles) (vec profiles)
    :else
    (throw (error/ex :clj-book.build.request/invalid-profiles
                     ":profiles must be a vector of keywords."
                     {:profiles profiles}))))

(defn- resolve-profiles
  "Resolve the profiles to build: default to both editions when none are
   named, otherwise validate the requested subset."
  [profiles]
  (let [profiles (if (empty? profiles) default-profiles profiles)
        bad      (remove keyword? profiles)]
    (when (seq bad)
      (throw (error/ex :clj-book.build.request/invalid-profiles
                       ":profiles must contain only keywords."
                       {:profiles profiles :non-keywords (vec bad)})))
    (let [unknown (remove supported-profiles profiles)]
      (when (seq unknown)
        (throw (error/ex :clj-book.build.request/unknown-profile
                         (str "Unsupported profile(s): "
                              (str/join ", " (map pr-str unknown)))
                         {:profiles         profiles
                          :unknown-profiles (vec unknown)
                          :supported        (vec (sort supported-profiles))}))))
    (vec profiles)))

(defn- resolve-book-root
  "Resolve `:book-root` to a directory path. A missing or blank value
   defaults to the current directory; a present non-string is rejected."
  [book-root]
  (if (or (nil? book-root)
          (and (string? book-root) (str/blank? book-root)))
    default-book-root
    (string-or-throw :book-root book-root)))

(defn normalize
  "Normalize and validate a public request map for the given `command`
   (`:validate` or `:build`). Returns a normalized map or throws a
   structured `ex-info`. A build with no `:profiles` defaults to both
   editions; a subset may be selected."
  [request-map command]
  (when-not (map? request-map)
    (throw (error/ex :clj-book.build.request/invalid-request
                     "Request must be a map."
                     {:request request-map})))
  (let [{:keys [book-root config-path profiles output-root dry-run]} request-map
        normalized {:command     command
                    :book-root   (resolve-book-root book-root)
                    :config-path (or (string-or-throw :config-path config-path)
                                     default-config-path)
                    :output-root (or (string-or-throw :output-root output-root)
                                     default-output-root)
                    :dry-run     (boolean dry-run)
                    :validate-code (boolean (:validate-code request-map))
                    :profiles    (normalize-profiles profiles)}]
    (cond-> normalized
      (= command :build) (update :profiles resolve-profiles))))
