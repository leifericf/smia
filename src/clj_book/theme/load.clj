(ns clj-book.theme.load
  "Theme context (shell): read and validate `theme.edn`.

   The theme file lives at the book root, beside `book.edn` — book.edn is
   the manuscript, theme.edn is the appearance. All Theme IO lives here so
   the theme compilers stay pure. The pure validation of a parsed theme
   map is exposed as `validate`."
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

(declare read-edn)

(defn validate
  "Pure validation of an already-parsed `theme.edn` map. Performs no IO.
   Throws a structured `ex-info` carrying the humanized schema errors for a
   malformed map; returns the tokens map unchanged otherwise. `path` is
   ignored here but kept for call-site symmetry with the config loader."
  [tokens _path]
  (schema/check schema/Tokens tokens :clj-book.theme.load/invalid-tokens))

(defn load-tokens
  "Read, parse, and validate the book root's `theme.edn`.

   Returns `{:tokens <map> :path <abs-path>}`. Throws structured
   `ex-info` for malformed/invalid inputs."
  [{:keys [book-root]}]
  (let [f (io/file book-root "theme.edn")]
    (when-not (.exists f)
      (throw (error/ex :clj-book.theme.load/missing
                       (str "Theme file not found: " (.getPath f))
                       {:book-root book-root :path (.getPath f)})))
    (let [path   (.getPath f)
          tokens (read-edn f)]
      (validate tokens path)
      {:tokens tokens :path path})))

;; --- private helpers -------------------------------------------------------

(defn- read-edn [^java.io.File f]
  (try
    (with-open [r (PushbackReader. (io/reader f))]
      (edn/read r))
    (catch java.io.IOException e
      (throw (error/ex :clj-book.theme.load/unreadable
                       (str "Could not read theme file: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))
    (catch RuntimeException e
      (throw (error/ex :clj-book.theme.load/invalid-edn
                       (str "Theme file is not valid EDN: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))))
