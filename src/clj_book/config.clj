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

(defn- check-types [config path]
  (when-not (string? (:book/slug config))
    (throw (error/ex :clj-book.config/invalid-type
                     ":book/slug must be a string."
                     {:path path :key :book/slug :value (:book/slug config)})))
  (when-not (string? (:book/title config))
    (throw (error/ex :clj-book.config/invalid-type
                     ":book/title must be a string."
                     {:path path :key :book/title :value (:book/title config)})))
  (when-not (and (sequential? (:book/chapters config))
                 (every? string? (:book/chapters config))
                 (seq (:book/chapters config)))
    (throw (error/ex :clj-book.config/invalid-type
                     ":book/chapters must be a non-empty vector of strings."
                     {:path path :key :book/chapters
                      :value (:book/chapters config)}))))

(defn- check-chapters-exist [config book-root path]
  (let [missing (->> (:book/chapters config)
                     (remove #(.exists (io/file book-root %)))
                     vec)]
    (when (seq missing)
      (throw (error/ex :clj-book.config/missing-chapter
                       (str "Chapter file(s) not found relative to "
                            book-root ": " (str/join ", " missing))
                       {:path path :book-root book-root :missing missing})))))

(defn- validate-layout [config path]
  (let [layout (select-keys config layout-keys)
        warnings (atom [])]
    (doseq [[k allowed] known-layout-values
            :let [v (get layout k)]
            :when (some? v)]
      (when-not (contains? allowed v)
        (swap! warnings conj
               {:warning/type :clj-book.config/unknown-layout-value
                :warning/key  k
                :warning/value v
                :warning/allowed (vec (sort allowed))})))
    (let [unknown (->> (keys config)
                       (remove #(or (= "book" (namespace %))
                                    (contains? layout-keys %)))
                       vec)]
      (when (seq unknown)
        (swap! warnings conj
               {:warning/type  :clj-book.config/unknown-key
                :warning/keys  unknown
                :warning/note  "Preserved but not interpreted by clj-book."})))
    @warnings))

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
    (let [path   (.getPath f)
          config (read-edn f)]
      (when-not (map? config)
        (throw (error/ex :clj-book.config/invalid-shape
                         "Top-level value of book.edn must be a map."
                         {:path path :value config})))
      (check-required-keys config path)
      (check-types config path)
      (check-chapters-exist config book-root path)
      {:config   config
       :path     path
       :warnings (validate-layout config path)})))
