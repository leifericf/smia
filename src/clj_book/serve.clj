(ns clj-book.serve
  "Local preview server for the :site target."
  (:require
   [clj-book.build.execute :as build]
   [clj-book.compose :as compose]
   [clj-book.docbook :as docbook]
   [clj-book.targets.site :as site-target]
   [clojure.java.io :as io]
   [ring.adapter.jetty :as jetty]
   [stasis.core :as stasis]))

(defn build-preview-pages
  "Run the shared prerequisites and return the Stasis page map for the
   site target. Takes the structured value from `build/prepare`.
   Writes the master adoc and generates DocBook once per call; the
   resulting HTML model goes through the same `document`/`site` path as a
   full build."
  [{:keys [request manuscript paths]}]
  (let [{:keys [book-root]}        request
        {:keys [config]}           manuscript
        {:keys [intermediate-dir]} paths]
    (io/make-parents (io/file intermediate-dir "book.adoc"))
    (let [master-path (compose/write-master! {:book-root        book-root
                                              :config           config
                                              :intermediate-dir intermediate-dir})]
      (docbook/generate-docbook! {:intermediate-dir intermediate-dir
                                  :master-path      master-path})
      (site-target/render-pages {:intermediate-dir intermediate-dir
                                 :config           config
                                 :css-href         "/assets/site.css"}))))

(defn handler
  "Build a Ring handler that serves a freshly-rendered preview of the
   manuscript. Pages are rebuilt on each request so edits are picked
   up; the underlying tools are cached so the cost is small."
  [request]
  (let [prepared (build/prepare request)]
    (stasis/serve-pages (fn [] (build-preview-pages prepared)))))

(defn run
  "Start a local preview server. Returns the running Jetty server."
  [{:keys [port] :or {port 3000} :as request}]
  (jetty/run-jetty (handler request)
                   {:port port :join? false}))
