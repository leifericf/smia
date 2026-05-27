(ns clj-book.serve
  "Local preview server for the :site target."
  (:require
   [clj-book.docbook :as docbook]
   [clj-book.pipeline :as pipeline]
   [clj-book.targets.site :as site-target]
   [clojure.java.io :as io]
   [ring.adapter.jetty :as jetty]
   [stasis.core :as stasis]))

(defn- build-site-pages [{:keys [intermediate-dir book-root tokens config] :as ctx}]
  (docbook/generate-docbook! ctx)
  (let [docbook (docbook/parse-docbook-file
                  (str intermediate-dir "/book.xml"))
        body    (#'site-target/->hiccup docbook)]
    (site-target/page-map {:book/title  (:book/title config)
                           :book/slug   (:book/slug config)
                           :css-href    "/assets/site.css"
                           :body-hiccup body})))

(defn- assemble-context [request]
  (let [ctx (pipeline/prepare request)]
    (io/make-parents (io/file (:intermediate-dir ctx) "book.adoc"))
    (assoc ctx
           :master-path
           (clj-book.compose/write-master! ctx))))

(defn handler-fn
  "Build a Ring handler that serves the rendered site preview."
  [request]
  (let [ctx   (assemble-context request)
        pages (build-site-pages ctx)]
    (stasis/serve-pages pages)))

(defn run
  "Start a local preview server. Returns the running Jetty server."
  [{:keys [port] :or {port 3000} :as request}]
  (jetty/run-jetty (handler-fn request)
                   {:port port :join? false}))
