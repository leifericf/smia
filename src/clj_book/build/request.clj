(ns clj-book.build.request
  "Normalization and validation of public request maps.

   A build's deliverables are **editions** — flat names for the forms the
   book ships in (`:screen`, `:print`, …). The real structure an edition
   implies (output format, page layout) lives in the internal
   `edition-descriptors`, never in the user-facing vocabulary, so nonsense
   combinations are unrepresentable."
  (:require
   [clj-book.error :as error]
   [clojure.string :as str]))

(def edition-descriptors
  "Edition -> internal descriptor: the output `:format` the build
   dispatches on, and for PDF editions the page `:layout` the theme
   compiles for."
  {:screen {:format :pdf :layout :screen}
   :print  {:format :pdf :layout :print}
   :site   {:format :html}})

(def supported-editions
  "Editions clj-book can build."
  (set (keys edition-descriptors)))

(def default-editions
  "Editions built when a build request does not name any. Both PDF
   editions are produced by default; a request may select any subset."
  [:screen :print])

(def ^:private default-output-root "build")
(def ^:private default-config-path "book.edn")
(def ^:private default-book-root ".")

(declare string-or-throw normalize-editions resolve-editions resolve-book-root)

(defn normalize
  "Normalize and validate a public request map for the given `command`
   (`:validate` or `:build`). Returns a normalized map or throws a
   structured `ex-info`. A build with no `:editions` defaults to both PDF
   editions; a subset may be selected."
  [request-map command]
  (when-not (map? request-map)
    (throw (error/ex :clj-book.build.request/invalid-request
                     "Request must be a map."
                     {:request request-map})))
  (let [{:keys [book-root config-path editions output-root dry-run
                validate-code]} request-map
        normalized {:command       command
                    :book-root     (resolve-book-root book-root)
                    :config-path   (or (string-or-throw :config-path config-path)
                                       default-config-path)
                    :output-root   (or (string-or-throw :output-root output-root)
                                       default-output-root)
                    :dry-run       (boolean dry-run)
                    :validate-code (boolean validate-code)
                    :editions      (normalize-editions editions)}]
    (cond-> normalized
      (= command :build) (update :editions resolve-editions))))

;; --- private helpers -------------------------------------------------------

(defn- string-or-throw [k v]
  (when (and (some? v) (not (string? v)))
    (throw (error/ex :clj-book.build.request/invalid-value
                     (str k " must be a string when provided.")
                     {k v})))
  v)

(defn- normalize-editions [editions]
  (cond
    (nil? editions) nil
    (sequential? editions) (vec editions)
    :else
    (throw (error/ex :clj-book.build.request/invalid-editions
                     ":editions must be a vector of keywords."
                     {:editions editions}))))

(defn- resolve-editions
  "Resolve the editions to build: default to both PDF editions when none
   are named, otherwise validate the requested subset."
  [editions]
  (let [editions (if (empty? editions) default-editions editions)
        bad      (remove keyword? editions)]
    (when (seq bad)
      (throw (error/ex :clj-book.build.request/invalid-editions
                       ":editions must contain only keywords."
                       {:editions editions :non-keywords (vec bad)})))
    (let [unknown (remove supported-editions editions)]
      (when (seq unknown)
        (throw (error/ex :clj-book.build.request/unknown-edition
                         (str "Unsupported edition(s): "
                              (str/join ", " (map pr-str unknown)))
                         {:editions         editions
                          :unknown-editions (vec unknown)
                          :supported        (vec (sort supported-editions))}))))
    (vec editions)))

(defn- resolve-book-root
  "Resolve `:book-root` to a directory path. A missing or blank value
   defaults to the current directory; a present non-string is rejected."
  [book-root]
  (if (or (nil? book-root)
          (and (string? book-root) (str/blank? book-root)))
    default-book-root
    (string-or-throw :book-root book-root)))
