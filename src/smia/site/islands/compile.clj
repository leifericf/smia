(ns smia.site.islands.compile
  "The island compiler (shell): shadow-cljs release builds, in-process.

   Sits behind the optional `:cljs` deps alias — this namespace must
   only be loaded through `smia.site.islands`' `requiring-resolve`. The
   compiler is a JVM library; no Node, npm, or shadow-cljs.edn is
   involved. A transient shadow server is started when none is running
   and stopped again afterwards, with its cache kept inside the bundle
   cache directory so no working-directory state is left behind."
  (:require
   [clojure.java.io :as io]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as server]
   [shadow.cljs.devtools.server.runtime :as runtime]))

(defn- build-config [{:keys [island entry]} ^java.io.File dir]
  {:build-id      (keyword (str "smia-island-" (name island)))
   :target        :browser
   :output-dir    (.getPath dir)
   :asset-path    "."
   :modules       {island {:init-fn entry}}
   :build-options {:cache-root (.getPath (io/file dir ".shadow-cache"))}})

(defn release!
  "Compile each island spec (`{:island :entry :file}`) into `dir` as an
   advanced-optimized browser bundle."
  [specs dir]
  (io/make-parents (io/file dir "x"))
  (let [started? (nil? (runtime/get-instance))]
    (when started?
      (server/start! {:cache-root (.getPath (io/file dir ".shadow-cache"))
                      :http       {:port 0}}))
    (try
      (doseq [spec specs]
        (shadow/release* (build-config spec dir) {}))
      (finally
        (when started?
          (server/stop!))))))
