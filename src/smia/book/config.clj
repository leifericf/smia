(ns smia.book.config
  "Loader and validator for `book.edn` manuscript build configuration."
  (:require
   [smia.book.structure :as structure]
   [smia.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def required-keys
  "Always-required `book.edn` keys: slug and title. The body (a flat
   `:book/chapters` list or a `:book/parts` grouping) is required too, but
   is checked separately so the error can name either alternative."
  #{:book/slug
    :book/title})

(def ^:private type-checks
  "Always-present type contracts as data: `[key predicate message]`."
  [[:book/slug  string? ":book/slug must be a string."]
   [:book/title string? ":book/title must be a string."]])

(declare read-edn check-required-keys non-empty-string-seq? invalid-type!
         check-types check-body-present check-unambiguous-body check-chapters
         valid-part? check-parts valid-matter? check-matter check-appendices
         check-numbering check-no-duplicate-files check-files-exist
         valid-download-asset? check-downloads check-redirects check-site-url
         check-edit-url check-attributes unknown-key-warnings compute-warnings)

(defn validate
  "Pure validation of an already-parsed `book.edn` map. Performs no IO.
   Throws structured `ex-info` for shape/type errors; returns the
   warning vector otherwise. `path` is used only for error context."
  [config path]
  (when-not (map? config)
    (throw (error/ex :smia.book.config/invalid-shape
                     "Top-level value of book.edn must be a map."
                     {:path path :value config})))
  (check-required-keys config path)
  (check-types config path)
  (check-body-present config path)
  (check-unambiguous-body config path)
  (check-chapters config path)
  (check-parts config path)
  (check-matter config path :book/front-matter)
  (check-matter config path :book/back-matter)
  (check-appendices config path)
  (check-numbering config path)
  (check-downloads config path)
  (check-redirects config path)
  (check-site-url config path)
  (check-edit-url config path)
  (check-attributes config path)
  (check-no-duplicate-files config path)
  (compute-warnings config))

(defn load-config
  "Read, parse, and validate the manuscript `book.edn`.

   Returns `{:config <preserved-map> :path <abs-path> :warnings [..]}`.
   Throws structured `ex-info` for malformed/invalid inputs."
  [{:keys [book-root config-path]}]
  (let [f (io/file book-root config-path)]
    (when-not (.exists f)
      (throw (error/ex :smia.book.config/missing
                       (str "Configuration file not found: "
                            (.getPath f))
                       {:book-root book-root :config-path config-path})))
    (let [path     (.getPath f)
          config   (read-edn f)
          warnings (validate config path)]
      (check-files-exist config book-root path)
      {:config   config
       :path     path
       :warnings warnings})))

;; --- private helpers -------------------------------------------------------

(defn- read-edn [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (edn/read r))
    (catch java.io.IOException e
      (throw (error/ex :smia.book.config/unreadable
                       (str "Could not read config file: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))
    (catch RuntimeException e
      (throw (error/ex :smia.book.config/invalid-edn
                       (str "Config file is not valid EDN: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))))

(defn- check-required-keys [config path]
  (let [missing (sort (remove #(contains? config %) required-keys))]
    (when (seq missing)
      (throw (error/ex :smia.book.config/missing-required-key
                       (str "Missing required key(s) in " path ": "
                            (str/join ", " (map pr-str missing)))
                       {:path path :missing missing})))))

(defn- non-empty-string-seq? [v]
  (and (sequential? v) (seq v) (every? string? v)))

(defn- invalid-type! [path k value msg]
  (throw (error/ex :smia.book.config/invalid-type msg
                   {:path path :key k :value value})))

(defn- check-types [config path]
  (doseq [[k pred msg] type-checks
          :when (not (pred (get config k)))]
    (invalid-type! path k (get config k) msg)))

(defn- check-body-present [config path]
  (when-not (or (contains? config :book/chapters)
                (contains? config :book/parts))
    (throw (error/ex :smia.book.config/missing-required-key
                     (str "Missing a body in " path
                          ": declare :book/chapters or :book/parts.")
                     {:path path :missing [:book/chapters]}))))

(defn- check-unambiguous-body [config path]
  (when (and (contains? config :book/chapters) (contains? config :book/parts))
    (throw (error/ex :smia.book.config/ambiguous-body
                     (str "Declare the body once in " path
                          ": use either :book/chapters or :book/parts, not both.")
                     {:path path}))))

(defn- check-chapters [config path]
  (when (and (contains? config :book/chapters)
             (not (non-empty-string-seq? (:book/chapters config))))
    (invalid-type! path :book/chapters (:book/chapters config)
                   ":book/chapters must be a non-empty vector of strings.")))

(defn- valid-part? [p]
  (and (map? p) (string? (:part/title p))
       (non-empty-string-seq? (:part/chapters p))))

(defn- check-parts [config path]
  (when (contains? config :book/parts)
    (let [parts (:book/parts config)]
      (when-not (and (sequential? parts) (seq parts) (every? valid-part? parts))
        (invalid-type! path :book/parts parts
                       (str ":book/parts must be a non-empty vector of "
                            "{:part/title <string> :part/chapters [<string> …]} maps."))))))

(defn- valid-matter? [m]
  (and (map? m) (keyword? (:role m))
       (or (nil? (:file m)) (string? (:file m)))
       (or (string? (:file m)) (structure/generated-role? (:role m)))))

(defn- check-matter [config path k]
  (when (contains? config k)
    (let [ms (get config k)]
      (when-not (and (sequential? ms) (every? map? ms))
        (invalid-type! path k ms
                       (str k " must be a vector of {:role <keyword> :file <string>?} maps.")))
      (doseq [m ms :when (not (valid-matter? m))]
        (throw (error/ex :smia.book.config/invalid-matter
                         (str "Invalid " k " entry in " path
                              ": each needs a keyword :role, and a :file unless "
                              "the role is generated (e.g. :bibliography, :index).")
                         {:path path :key k :entry m}))))))

(defn- check-appendices [config path]
  (when (and (contains? config :book/appendices)
             (not (and (sequential? (:book/appendices config))
                       (every? string? (:book/appendices config)))))
    (invalid-type! path :book/appendices (:book/appendices config)
                   ":book/appendices must be a vector of strings.")))

(defn- check-numbering [config path]
  (when (and (contains? config :book/numbering)
             (not (map? (:book/numbering config))))
    (invalid-type! path :book/numbering (:book/numbering config)
                   ":book/numbering must be a map.")))

(defn- valid-download-asset? [a]
  (and (map? a)
       (string? (:label a))
       (string? (:file a))
       (or (nil? (:note a)) (string? (:note a)))
       (or (nil? (:default a)) (boolean? (:default a)))))

(defn- check-downloads
  "Validate the optional `:book/downloads` block the site edition reads:
   a map with a string `:base` and a non-empty `:assets` vector of
   `{:label <string> :file <string> :note <string>? :default <boolean>?}`
   maps, with at most one asset flagged `:default`."
  [config path]
  (when (contains? config :book/downloads)
    (let [d (:book/downloads config)]
      (when-not (and (map? d)
                     (string? (:base d))
                     (vector? (:assets d))
                     (seq (:assets d))
                     (every? valid-download-asset? (:assets d))
                     (<= (count (filter :default (:assets d))) 1))
        (throw (error/ex :smia.book.config/invalid-downloads
                         (str ":book/downloads in " path " must be a map with a "
                              "string :base and a non-empty :assets vector of "
                              "{:label <string> :file <string> :note <string>? "
                              ":default <boolean>?} maps, with at most one "
                              ":default asset.")
                         {:path path :value d}))))))

(defn- check-no-duplicate-files [config path]
  (let [dupes (structure/duplicates
                (structure/file-list (structure/normalize config)))]
    (when (seq dupes)
      (throw (error/ex :smia.book.config/duplicate-chapter
                       (str "Duplicate source file reference(s) in " path ": "
                            (str/join ", " dupes))
                       {:path path :duplicates dupes})))))

(defn- check-files-exist [config book-root path]
  (let [missing (->> (structure/file-list (structure/normalize config))
                     (remove #(.exists (io/file book-root %)))
                     vec)]
    (when (seq missing)
      (throw (error/ex :smia.book.config/missing-chapter
                       (str "Source file(s) not found relative to "
                            book-root ": " (str/join ", " missing))
                       {:path path :book-root book-root :missing missing})))))

(defn- check-redirects
  "`:book/redirects` (optional) must map old URL paths (strings) to
   target ids (keywords). The targets resolve against the assembled
   site's anchors later, at site assembly."
  [config path]
  (when-let [r (:book/redirects config)]
    (when-not (and (map? r)
                   (every? (fn [[k v]] (and (string? k) (keyword? v))) r))
      (throw (error/ex :smia.book.config/invalid-redirects
                       (str ":book/redirects must be a map of old URL path "
                            "(string) -> target id (keyword) in " path ".")
                       {:path path :redirects r})))))

(defn- check-site-url
  "`:book/site-url` (optional) must be an absolute http(s) URL — the
   sitemap needs the site's public address."
  [config path]
  (when-let [u (:book/site-url config)]
    (when-not (and (string? u) (re-find #"^https?://" u))
      (throw (error/ex :smia.book.config/invalid-site-url
                       (str ":book/site-url must be an absolute http(s) URL "
                            "string in " path ".")
                       {:path path :site-url u})))))

(defn- check-edit-url
  "`:book/edit-url` (optional) must be an absolute http(s) URL — the site
   joins it to each page's source path for an \"Edit this page\" link."
  [config path]
  (when-let [u (:book/edit-url config)]
    (when-not (and (string? u) (re-find #"^https?://" u))
      (throw (error/ex :smia.book.config/invalid-edit-url
                       (str ":book/edit-url must be an absolute http(s) URL "
                            "string in " path ".")
                       {:path path :edit-url u})))))

(defn- valid-attribute-value?
  "A document attribute value is a string, a number, or author Hiccup (a
   vector). Richer values flow through the substitution pass unchanged."
  [v]
  (or (string? v) (number? v) (vector? v)))

(defn- check-attributes
  "`:book/attributes` (optional) must be a map of keyword -> value, each
   value a string, number, or author Hiccup vector. The substitution pass
   replaces `[:attr :k]` references with these before numbering."
  [config path]
  (when-let [attrs (:book/attributes config)]
    (when-not (and (map? attrs)
                   (every? keyword? (keys attrs))
                   (every? valid-attribute-value? (vals attrs)))
      (throw (error/ex :smia.book.config/invalid-attributes
                       (str ":book/attributes in " path " must be a map of "
                            "keyword -> (string | number | author Hiccup).")
                       {:path path :value attrs})))))

(def ^:private known-book-keys
  "Every `:book/*` key smia interprets. A `:book/*` key outside this set
   is almost certainly a typo, so it earns a warning — unlike keys in
   other namespaces, which are presumed deliberate extensions."
  #{:book/accessibility :book/appendices :book/attributes :book/author
    :book/back-matter :book/chapters :book/downloads :book/edit-url
    :book/front-matter :book/identifier :book/language :book/numbering
    :book/parts :book/print-x :book/redirects :book/references
    :book/running-heads :book/site-url :book/slug :book/title})

(defn- unknown-key-warnings
  "Warn about top-level keys smia does not interpret: keys outside the
   `book/*` namespace (preserved verbatim — the open map) and, more
   urgently, `book/*` keys it does not recognize (likely misspellings)."
  [config]
  (let [{book-ns true, other-ns false} (group-by #(= "book" (namespace %))
                                                 (keys config))
        misspelled (vec (remove known-book-keys book-ns))
        unknown    (vec other-ns)]
    (cond-> []
      (seq misspelled)
      (conj {:warning/type :smia.book.config/unknown-key
             :warning/keys misspelled
             :warning/note (str "Not a key smia recognizes — possibly "
                                "misspelled.")})
      (seq unknown)
      (conj {:warning/type :smia.book.config/unknown-key
             :warning/keys unknown
             :warning/note "Preserved but not interpreted by smia."}))))

(defn- compute-warnings
  "Return the warning vector for `config` as a pure value."
  [config]
  (vec (unknown-key-warnings config)))
