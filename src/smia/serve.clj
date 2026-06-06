(ns smia.serve
  "A minimal static file server for previewing the built `site` edition
   locally.

   The site uses clean directory URLs (`part-1/quickstart/`), which a
   browser resolves to the directory's `index.html` only through a web
   server — opening such a path straight from disk does not work. This
   serves a build directory over HTTP using nothing but the JDK
   (`com.sun.net.httpserver`), so previewing stays self-contained on the
   JVM with no extra dependency, the same discipline as the rest of Smia.

   The pure path and MIME helpers are separated from the IO so they can be
   tested without opening a socket."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
   (java.net InetSocketAddress URLDecoder)
   (java.nio.file Files)))

(def ^:private content-types
  {"html"  "text/html; charset=utf-8"
   "css"   "text/css; charset=utf-8"
   "svg"   "image/svg+xml"
   "png"   "image/png"
   "jpg"   "image/jpeg"
   "jpeg"  "image/jpeg"
   "gif"   "image/gif"
   "webp"  "image/webp"
   "pdf"   "application/pdf"
   "epub"  "application/epub+zip"
   "json"  "application/json"
   "txt"   "text/plain; charset=utf-8"
   "woff2" "font/woff2"
   "ttf"   "font/ttf"})

(defn content-type
  "The MIME type for a file name, chosen by extension (default
   `application/octet-stream`)."
  [filename]
  (let [ext (str/lower-case (or (second (re-find #"\.([^.]+)$" filename)) ""))]
    (get content-types ext "application/octet-stream")))

(defn- decode-path
  "Percent-decode a raw URL path exactly once. Unlike form decoding, a
   `+` is a literal plus. Returns nil for a malformed escape sequence."
  [^String path]
  (try
    (URLDecoder/decode (str/replace path "+" "%2B") "UTF-8")
    (catch IllegalArgumentException _ nil)))

(defn resolve-file
  "Resolve a raw (still percent-encoded) request `path` to a file under
   `root`, applying the directory-index convention: a path ending in `/`,
   or one naming a directory, serves that directory's `index.html`.
   Returns the canonical `File`, or nil when the path is malformed or
   would escape `root` (a traversal attempt)."
  [root path]
  (when-let [decoded (decode-path (str/replace path #"\?.*$" ""))]
    ;; Canonicalization rejects a path the OS cannot name (e.g. an embedded
    ;; NUL byte) with an IOException; treat that as a malformed path and
    ;; resolve to nothing rather than letting it surface as a 500.
    (try
      (let [rel     (str/replace decoded #"^/+" "")
            target  (if (or (str/blank? rel) (str/ends-with? rel "/"))
                      (io/file root rel "index.html")
                      (io/file root rel))
            target  (if (.isDirectory target) (io/file target "index.html") target)
            rootc   (str (.getCanonicalFile (io/file root)) java.io.File/separator)
            filec   (.getCanonicalFile target)]
        (when (str/starts-with? (str filec) rootc)
          filec))
      (catch java.io.IOException _ nil))))

(defn directory-redirect
  "When raw `path` names an existing directory under `root` without a
   trailing slash, the location it should redirect to — the raw path plus
   `/`, so the browser resolves the page's relative links against the
   right base. Nil otherwise."
  [root ^String path]
  (when-not (str/ends-with? path "/")
    (when-let [decoded (decode-path path)]
      (try
        (let [rel    (str/replace decoded #"^/+" "")
              target (io/file root rel)
              rootc  (str (.getCanonicalFile (io/file root)) java.io.File/separator)]
          (when (and (not (str/blank? rel))
                     (.isDirectory target)
                     (str/starts-with? (str (.getCanonicalFile target)) rootc))
            (str path "/")))
        (catch java.io.IOException _ nil)))))

(defn- respond [root ^HttpExchange ex]
  (try
    (let [raw      (.getRawPath (.getRequestURI ex))
          redirect (directory-redirect root raw)
          f        (when-not redirect (resolve-file root raw))]
      (cond
        redirect
        (do (.set (.getResponseHeaders ex) "Location" redirect)
            (.sendResponseHeaders ex 301 -1))

        (and f (.isFile f))
        (let [bytes (Files/readAllBytes (.toPath f))]
          (.set (.getResponseHeaders ex) "Content-Type" (content-type (.getName f)))
          (.sendResponseHeaders ex 200 (alength bytes))
          (with-open [os (.getResponseBody ex)] (.write os bytes)))

        :else
        (let [body (.getBytes "404 Not Found\n" "UTF-8")]
          (.sendResponseHeaders ex 404 (alength body))
          (with-open [os (.getResponseBody ex)] (.write os body)))))
    (finally (.close ex))))

(defn serve!
  "Start a static file server for `dir` (default `.`) on `port` (default
   8000), bound to `host` (default loopback — a preview is for this
   machine; pass `:host \"0.0.0.0\"` to expose it deliberately). Returns a
   handle `{:server :port :dir}`; stop it with `(.stop server 0)`. No
   blocking — the caller decides whether to wait."
  [{:keys [dir port host]}]
  (let [root   (.getCanonicalFile (io/file (or dir ".")))
        port   (or port 8000)
        server (HttpServer/create
                 (InetSocketAddress. ^String (or host "127.0.0.1") (int port)) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex] (respond root ex))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port port :dir root}))

(defn stop!
  "Stop a server started by `serve!` (the handle it returned)."
  [{:keys [^HttpServer server]}]
  (.stop server 0))
