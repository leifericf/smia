(ns smia.api
  "Public entrypoints invoked via `clojure -X`."
  (:require
   [smia.book.scaffold :as scaffold]
   [smia.build.execute :as build]
   [smia.build.request :as request]
   [clojure.pprint :as pp]))

(defn init
  "Scaffold a minimal, buildable book into `:target` (default \".\"),
   creating the directory when missing. A non-empty target is refused.
   Returns `{:target <path> :files [<rel-path> …]}`."
  [{:keys [target] :or {target "."}}]
  (scaffold/init! target))

(defn validate
  "Validate a manuscript without producing output artifacts."
  [request-map]
  (-> request-map
      (request/normalize :validate)
      build/validate))

(defn build
  "Build a book's editions. All keys optional: `:book-root` (default \".\"),
   `:config-path` (default \"book.edn\"), `:output-root` (default \"build\"),
   `:editions` (default [:screen :print]). With `:dry-run true`, print and
   return the plan without building."
  [request-map]
  (let [req    (request/normalize request-map :build)
        result (build/build req)]
    (when (:dry-run req)
      (pp/pprint result))
    result))
