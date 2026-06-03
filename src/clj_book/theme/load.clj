(ns clj-book.theme.load
  "Theme context (shell): read and validate `styles/tokens.edn`.

   All Theme IO lives here so the theme compilers stay pure. The pure
   validation of a parsed token map is exposed as `validate`."
  (:require
   [clj-book.error :as error]
   [clj-book.schema :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   (java.io PushbackReader)))

(def required-groups
  "Required top-level token groups. `clj-book.schema/Tokens` is the schema
   that enforces this contract; this set names the groups for consumers."
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

(defn validate
  "Pure validation of an already-parsed `tokens.edn` map. Performs no IO.
   Throws a structured `ex-info` carrying the humanized schema errors for a
   malformed map; returns the tokens map unchanged otherwise. `path` is
   ignored here but kept for call-site symmetry with the config loader."
  [tokens _path]
  (schema/check schema/Tokens tokens :clj-book.theme.load/invalid-tokens))

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
