(ns clj-book.theme.load
  "Theme context (shell): read and validate `styles/tokens.edn`.

   All Theme IO lives here so the theme compilers stay pure. The pure
   validation of a parsed token map is exposed as `validate`."
  (:require
   [clj-book.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader)))

(def required-groups
  "Required top-level token groups."
  #{:color :type :spacing :layout})

(defn- read-edn [^java.io.File f]
  (try
    (with-open [r (PushbackReader. (io/reader f))]
      (edn/read r))
    (catch java.io.IOException e
      (throw (error/ex :clj-book.theme.load/unreadable
                       (str "Could not read tokens file: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))
    (catch RuntimeException e
      (throw (error/ex :clj-book.theme.load/invalid-edn
                       (str "Tokens file is not valid EDN: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))))

(defn- check-required-groups [tokens path]
  (let [missing (sort (remove #(contains? tokens %) required-groups))]
    (when (seq missing)
      (throw (error/ex :clj-book.theme.load/missing-group
                       (str "Missing required token group(s): "
                            (str/join ", " (map pr-str missing)))
                       {:path path :missing missing})))))

(defn- check-group-shapes [tokens path]
  (doseq [g required-groups
          :let [v (get tokens g)]]
    (when-not (map? v)
      (throw (error/ex :clj-book.theme.load/invalid-type
                       (str "Token group " g " must be a map.")
                       {:path path :group g :value v})))))

(defn validate
  "Pure validation of an already-parsed `tokens.edn` map. Performs no IO.
   Throws structured `ex-info` for malformed/invalid inputs; returns the
   tokens map unchanged otherwise. `path` is used only for error context."
  [tokens path]
  (when-not (map? tokens)
    (throw (error/ex :clj-book.theme.load/invalid-shape
                     "Top-level value of tokens.edn must be a map."
                     {:path path :value tokens})))
  (check-required-groups tokens path)
  (check-group-shapes tokens path)
  tokens)

(defn load-tokens
  "Read, parse, and validate `styles/tokens.edn`.

   Returns `{:tokens <map> :path <abs-path>}`. Throws structured
   `ex-info` for malformed/invalid inputs."
  [{:keys [book-root]}]
  (let [f (io/file book-root "styles" "tokens.edn")]
    (when-not (.exists f)
      (throw (error/ex :clj-book.theme.load/missing
                       (str "Tokens file not found: " (.getPath f))
                       {:book-root book-root :path (.getPath f)})))
    (let [path   (.getPath f)
          tokens (read-edn f)]
      (validate tokens path)
      {:tokens tokens :path path})))
