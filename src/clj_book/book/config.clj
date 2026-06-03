(ns clj-book.book.config
  "Loader and validator for `book.edn` manuscript build configuration."
  (:require
   [clj-book.book.structure :as structure]
   [clj-book.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def required-keys
  "Always-required `book.edn` keys: slug and title. The body (a flat
   `:book/chapters` list or a `:book/parts` grouping) is required too, but
   is checked separately so the error can name either alternative."
  #{:book/slug
    :book/title})

(defn- read-edn [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (edn/read r))
    (catch java.io.IOException e
      (throw (error/ex :clj-book.book.config/unreadable
                       (str "Could not read config file: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))
    (catch RuntimeException e
      (throw (error/ex :clj-book.book.config/invalid-edn
                       (str "Config file is not valid EDN: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))))

(defn- check-required-keys [config path]
  (let [missing (sort (remove #(contains? config %) required-keys))]
    (when (seq missing)
      (throw (error/ex :clj-book.book.config/missing-required-key
                       (str "Missing required key(s) in " path ": "
                            (str/join ", " (map pr-str missing)))
                       {:path path :missing missing})))))

(defn- non-empty-string-seq? [v]
  (and (sequential? v) (seq v) (every? string? v)))

(def ^:private type-checks
  "Always-present type contracts as data: `[key predicate message]`."
  [[:book/slug  string? ":book/slug must be a string."]
   [:book/title string? ":book/title must be a string."]])

(defn- invalid-type! [path k value msg]
  (throw (error/ex :clj-book.book.config/invalid-type msg
                   {:path path :key k :value value})))

(defn- check-types [config path]
  (doseq [[k pred msg] type-checks
          :when (not (pred (get config k)))]
    (invalid-type! path k (get config k) msg)))

(defn- check-body-present [config path]
  (when-not (or (contains? config :book/chapters)
                (contains? config :book/parts))
    (throw (error/ex :clj-book.book.config/missing-required-key
                     (str "Missing a body in " path
                          ": declare :book/chapters or :book/parts.")
                     {:path path :missing [:book/chapters]}))))

(defn- check-unambiguous-body [config path]
  (when (and (contains? config :book/chapters) (contains? config :book/parts))
    (throw (error/ex :clj-book.book.config/ambiguous-body
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
        (throw (error/ex :clj-book.book.config/invalid-matter
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

(defn- duplicates [coll]
  (->> (frequencies coll)
       (filter (fn [[_ n]] (> n 1)))
       (map key)
       sort
       vec))

(defn- check-no-duplicate-files [config path]
  (let [dupes (duplicates (structure/file-list (structure/normalize config)))]
    (when (seq dupes)
      (throw (error/ex :clj-book.book.config/duplicate-chapter
                       (str "Duplicate source file reference(s) in " path ": "
                            (str/join ", " dupes))
                       {:path path :duplicates dupes})))))

(defn- check-files-exist [config book-root path]
  (let [missing (->> (structure/file-list (structure/normalize config))
                     (remove #(.exists (io/file book-root %)))
                     vec)]
    (when (seq missing)
      (throw (error/ex :clj-book.book.config/missing-chapter
                       (str "Source file(s) not found relative to "
                            book-root ": " (str/join ", " missing))
                       {:path path :book-root book-root :missing missing})))))

(defn- unknown-key-warnings
  "Warn about top-level keys outside the `book/*` namespace. They are
   preserved verbatim (open map) but not interpreted by clj-book."
  [config]
  (let [unknown (->> (keys config)
                     (remove #(= "book" (namespace %)))
                     vec)]
    (when (seq unknown)
      [{:warning/type :clj-book.book.config/unknown-key
        :warning/keys unknown
        :warning/note "Preserved but not interpreted by clj-book."}])))

(defn- compute-warnings
  "Return the warning vector for `config` as a pure value."
  [config]
  (vec (unknown-key-warnings config)))

(defn validate
  "Pure validation of an already-parsed `book.edn` map. Performs no IO.
   Throws structured `ex-info` for shape/type errors; returns the
   warning vector otherwise. `path` is used only for error context."
  [config path]
  (when-not (map? config)
    (throw (error/ex :clj-book.book.config/invalid-shape
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
  (check-no-duplicate-files config path)
  (compute-warnings config))

(defn load-config
  "Read, parse, and validate the manuscript `book.edn`.

   Returns `{:config <preserved-map> :path <abs-path> :warnings [..]}`.
   Throws structured `ex-info` for malformed/invalid inputs."
  [{:keys [book-root config-path]}]
  (let [f (io/file book-root config-path)]
    (when-not (.exists f)
      (throw (error/ex :clj-book.book.config/missing
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
