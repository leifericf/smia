(ns clj-book.config
  "Loader and validator for `book.edn` manuscript build configuration."
  (:require
   [clj-book.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def required-keys
  "Required `book.edn` keys for v1 alpha."
  #{:book/slug
    :book/title
    :book/chapters})

(def layout-keys
  "Tier-2 layout keys translated to both targets."
  #{:page-size
    :page-margins
    :chapter-opener
    :toc-depth
    :code-line-numbers
    :admonition-style})

(def known-layout-values
  {:page-size         #{:a4 :letter :digest}
   :chapter-opener    #{:page-break :inline}
   :admonition-style  #{:icon :label}})

(defn- read-edn [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (edn/read r))
    (catch java.io.IOException e
      (throw (error/ex :clj-book.config/unreadable
                       (str "Could not read config file: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))
    (catch RuntimeException e
      (throw (error/ex :clj-book.config/invalid-edn
                       (str "Config file is not valid EDN: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))))

(defn- check-required-keys [config path]
  (let [missing (sort (remove #(contains? config %) required-keys))]
    (when (seq missing)
      (throw (error/ex :clj-book.config/missing-required-key
                       (str "Missing required key(s) in " path ": "
                            (str/join ", " (map pr-str missing)))
                       {:path path :missing missing})))))

(defn- non-empty-string-seq? [v]
  (and (sequential? v) (seq v) (every? string? v)))

(def ^:private type-checks
  "Required-key type contracts as data: `[key predicate message]`."
  [[:book/slug     string? ":book/slug must be a string."]
   [:book/title    string? ":book/title must be a string."]
   [:book/chapters non-empty-string-seq?
    ":book/chapters must be a non-empty vector of strings."]])

(defn- check-types [config path]
  (doseq [[k pred msg] type-checks
          :when (not (pred (get config k)))]
    (throw (error/ex :clj-book.config/invalid-type
                     msg
                     {:path path :key k :value (get config k)}))))

(defn- duplicate-chapters [chapters]
  (->> (frequencies chapters)
       (filter (fn [[_ n]] (> n 1)))
       (map key)
       sort
       vec))

(defn- check-no-duplicate-chapters [config path]
  (let [dupes (duplicate-chapters (:book/chapters config))]
    (when (seq dupes)
      (throw (error/ex :clj-book.config/duplicate-chapter
                       (str "Duplicate chapter reference(s) in " path ": "
                            (str/join ", " dupes))
                       {:path path :duplicates dupes})))))

(defn- check-chapters-exist [config book-root path]
  (let [missing (->> (:book/chapters config)
                     (remove #(.exists (io/file book-root %)))
                     vec)]
    (when (seq missing)
      (throw (error/ex :clj-book.config/missing-chapter
                       (str "Chapter file(s) not found relative to "
                            book-root ": " (str/join ", " missing))
                       {:path path :book-root book-root :missing missing})))))

(defn- value-warnings
  "Warn about layout keys set to values clj-book does not recognize."
  [config]
  (let [layout (select-keys config layout-keys)]
    (for [[k allowed] known-layout-values
          :let [v (get layout k)]
          :when (and (some? v) (not (contains? allowed v)))]
      {:warning/type    :clj-book.config/unknown-layout-value
       :warning/key     k
       :warning/value   v
       :warning/allowed (vec (sort allowed))})))

(defn- unknown-key-warnings
  "Warn about top-level keys that are neither `book/*` nor known layout
   keys. They are preserved verbatim but not interpreted."
  [config]
  (let [unknown (->> (keys config)
                     (remove #(or (= "book" (namespace %))
                                  (contains? layout-keys %)))
                     vec)]
    (when (seq unknown)
      [{:warning/type :clj-book.config/unknown-key
        :warning/keys unknown
        :warning/note "Preserved but not interpreted by clj-book."}])))

(defn- validate-layout
  "Return the layout warning vector for `config` as a pure value."
  [config]
  (vec (concat (value-warnings config)
               (unknown-key-warnings config))))

(defn validate
  "Pure validation of an already-parsed `book.edn` map. Performs no IO.
   Throws structured `ex-info` for shape/type errors; returns the layout
   warning vector otherwise. `path` is used only for error context."
  [config path]
  (when-not (map? config)
    (throw (error/ex :clj-book.config/invalid-shape
                     "Top-level value of book.edn must be a map."
                     {:path path :value config})))
  (check-required-keys config path)
  (check-types config path)
  (check-no-duplicate-chapters config path)
  (validate-layout config))

(defn load-config
  "Read, parse, and validate the manuscript `book.edn`.

   Returns `{:config <preserved-map> :path <abs-path> :warnings [..]}`.
   Throws structured `ex-info` for malformed/invalid inputs."
  [{:keys [book-root config-path]}]
  (let [f (io/file book-root config-path)]
    (when-not (.exists f)
      (throw (error/ex :clj-book.config/missing
                       (str "Configuration file not found: "
                            (.getPath f))
                       {:book-root book-root :config-path config-path})))
    (let [path     (.getPath f)
          config   (read-edn f)
          warnings (validate config path)]
      (check-chapters-exist config book-root path)
      {:config   config
       :path     path
       :warnings warnings})))
