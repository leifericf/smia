(ns clj-book.api
  "Public entrypoints invoked via `clojure -X`."
  (:require
   [clj-book.build.execute :as build]
   [clj-book.request :as request]
   [clj-book.serve :as serve]))

(defn validate
  "Validate a manuscript without producing output artifacts."
  [request-map]
  (-> request-map
      (request/normalize :validate)
      build/validate))

(defn build
  "Build the requested targets. Required keys: `:book-root`, `:targets`."
  [request-map]
  (-> request-map
      (request/normalize :build)
      build/build))

(defn serve
  "Run a local preview server for the `:site` target."
  [request-map]
  (-> request-map
      (request/normalize :serve)
      serve/run))
