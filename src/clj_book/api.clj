(ns clj-book.api
  "Public entrypoints invoked via `clojure -X`."
  (:require
   [clj-book.build.execute :as build]
   [clj-book.request :as request]
   [clojure.pprint :as pp]))

(defn validate
  "Validate a manuscript without producing output artifacts."
  [request-map]
  (-> request-map
      (request/normalize :validate)
      build/validate))

(defn build
  "Build a book to PDF. All keys optional: `:book-root` (default \".\"),
   `:config-path` (default \"book.edn\"), `:output-root` (default \"build\"),
   `:profiles` (default [:screen :print]). With `:dry-run true`, print and
   return the plan without building."
  [request-map]
  (let [req    (request/normalize request-map :build)
        result (build/build req)]
    (when (:dry-run req)
      (pp/pprint result))
    result))
