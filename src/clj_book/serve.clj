(ns clj-book.serve
  "Local preview server for the :site target. As an interface namespace
   it routes through clj-book.build.execute and does not reach into the
   rendering internals (docbook/compose/targets/document) directly."
  (:require
   [clj-book.build.execute :as build]
   [ring.adapter.jetty :as jetty]
   [stasis.core :as stasis]))

(def ^:private preview-css-href
  "Stylesheet URL embedded in preview pages (served at the site root)."
  "/assets/site.css")

(defn build-preview-pages
  "Render the Stasis page map for the site target from a prepared build
   context (see `build/prepare`). Shares the build's single rendering
   path. Suitable both for local preview and for tests."
  [prepared]
  (build/render-site! prepared preview-css-href))

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
