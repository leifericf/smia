(ns clj-book.api
  "Public entrypoints invoked via `clojure -X`."
  (:require
   [clj-book.build.execute :as build]
   [clj-book.request :as request]
   [clj-book.serve :as serve]
   [clojure.pprint :as pp]))

(defn validate
  "Validate a manuscript without producing output artifacts."
  [request-map]
  (-> request-map
      (request/normalize :validate)
      build/validate))

(defn build
  "Build the requested targets. Required keys: `:book-root`, `:targets`.
   With `:dry-run true`, print and return the plan without building."
  [request-map]
  (let [req    (request/normalize request-map :build)
        result (build/build req)]
    (when (:dry-run req)
      (pp/pprint result))
    result))

(defn serve
  "Run a local preview server for the `:site` target."
  [request-map]
  (-> request-map
      (request/normalize :serve)
      serve/run))
