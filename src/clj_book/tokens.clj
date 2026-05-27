(ns clj-book.tokens
  "Loader and validator for `styles/tokens.edn`."
  (:require
   [clj-book.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def required-groups
  "Required top-level token groups."
  #{:color :type :spacing :layout})

(defn- read-edn [^java.io.File f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (edn/read r))
    (catch java.io.IOException e
      (throw (error/ex :clj-book.tokens/unreadable
                       (str "Could not read tokens file: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))
    (catch RuntimeException e
      (throw (error/ex :clj-book.tokens/invalid-edn
                       (str "Tokens file is not valid EDN: " (.getPath f))
                       {:path (.getPath f) :cause (.getMessage e)})))))

(defn- check-required-groups [tokens path]
  (let [missing (sort (remove #(contains? tokens %) required-groups))]
    (when (seq missing)
      (throw (error/ex :clj-book.tokens/missing-group
                       (str "Missing required token group(s): "
                            (str/join ", " (map pr-str missing)))
                       {:path path :missing missing})))))

(defn- check-group-shapes [tokens path]
  (doseq [g required-groups
          :let [v (get tokens g)]]
    (when-not (map? v)
      (throw (error/ex :clj-book.tokens/invalid-type
                       (str "Token group " g " must be a map.")
                       {:path path :group g :value v})))))

(defn load-tokens
  "Read, parse, and validate `styles/tokens.edn`.

   Returns `{:tokens <map> :path <abs-path>}`. Throws structured
   `ex-info` for malformed/invalid inputs."
  [{:keys [book-root]}]
  (let [f (io/file book-root "styles" "tokens.edn")]
    (when-not (.exists f)
      (throw (error/ex :clj-book.tokens/missing
                       (str "Tokens file not found: " (.getPath f))
                       {:book-root book-root :path (.getPath f)})))
    (let [path   (.getPath f)
          tokens (read-edn f)]
      (when-not (map? tokens)
        (throw (error/ex :clj-book.tokens/invalid-shape
                         "Top-level value of tokens.edn must be a map."
                         {:path path :value tokens})))
      (check-required-groups tokens path)
      (check-group-shapes tokens path)
      {:tokens tokens :path path})))
