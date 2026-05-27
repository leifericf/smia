(ns clj-book.serve
  "Local preview server for the :site target."
  (:require
   [clj-book.compose :as compose]
   [clj-book.docbook :as docbook]
   [clj-book.pipeline :as pipeline]
   [clj-book.targets.site :as site-target]
   [clojure.java.io :as io]
   [ring.adapter.jetty :as jetty]
   [stasis.core :as stasis]))

(defn build-preview-pages
  "Run the shared prerequisites and return the Stasis page map for the
   site target. Suitable both for local preview and for tests."
  [{:keys [intermediate-dir book-root tokens config] :as ctx}]
  (io/make-parents (io/file intermediate-dir "book.adoc"))
  (let [_ (compose/write-master! ctx)
        _ (docbook/generate-docbook! ctx)
        docbook (docbook/parse-docbook-file
                  (str intermediate-dir "/book.xml"))
        body    (#'site-target/->hiccup docbook)]
    (site-target/page-map {:book/title  (:book/title config)
                           :book/slug   (:book/slug config)
                           :css-href    "/assets/site.css"
                           :body-hiccup body})))

(defn handler
  "Build a Ring handler that serves a freshly-rendered preview of the
   manuscript. Pages are rebuilt on each request so edits are picked
   up; the underlying tools are cached so the cost is small."
  [request]
  (let [ctx   (pipeline/prepare request)
        ctx*  (assoc ctx
                     :master-path
                     (compose/write-master! ctx))]
    (stasis/serve-pages (fn [] (build-preview-pages ctx*)))))

(defn run
  "Start a local preview server. Returns the running Jetty server."
  [{:keys [port] :or {port 3000} :as request}]
  (jetty/run-jetty (handler request)
                   {:port port :join? false}))
