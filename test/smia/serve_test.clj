(ns smia.serve-test
  (:require
   [smia.serve :as serve]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- tmp-site []
  (let [dir (io/file (str (System/getProperty "java.io.tmpdir")
                          "/smia-serve-" (System/nanoTime)))]
    (doseq [[path content] {"index.html"        "<h1>home</h1>"
                            "part-1/ch/index.html" "<h1>chapter</h1>"
                            "images/x.txt"      "pixels"}]
      (io/make-parents (io/file dir path))
      (spit (io/file dir path) content))
    dir))

(deftest content-type-is-chosen-by-extension
  (is (= "text/html; charset=utf-8" (serve/content-type "index.html")))
  (is (= "text/css; charset=utf-8" (serve/content-type "styles.css")))
  (is (= "image/svg+xml" (serve/content-type "pipeline.svg")))
  (is (= "application/octet-stream" (serve/content-type "noextension"))))

(deftest resolve-file-applies-the-directory-index-convention
  (let [dir (tmp-site)]
    (testing "the root and a directory url serve their index.html"
      (is (= (.getCanonicalFile (io/file dir "index.html"))
             (serve/resolve-file dir "/")))
      (is (= (.getCanonicalFile (io/file dir "part-1/ch/index.html"))
             (serve/resolve-file dir "/part-1/ch/"))))
    (testing "a file path resolves to that file"
      (is (= (.getCanonicalFile (io/file dir "images/x.txt"))
             (serve/resolve-file dir "/images/x.txt"))))
    (testing "a query string is ignored"
      (is (= (.getCanonicalFile (io/file dir "index.html"))
             (serve/resolve-file dir "/?v=1"))))
    (testing "a traversal outside the root is refused"
      (is (nil? (serve/resolve-file dir "/../secret")))
      (is (nil? (serve/resolve-file dir "/part-1/../../secret"))))))

(deftest resolve-file-decodes-paths-without-form-semantics
  (let [dir (tmp-site)]
    (spit (io/file dir "a+b.txt") "plus")
    (spit (io/file dir "a b.txt") "space")
    (spit (io/file dir "100%.txt") "percent")
    (testing "a literal + in a file name stays a +"
      (is (= (.getCanonicalFile (io/file dir "a+b.txt"))
             (serve/resolve-file dir "/a+b.txt"))))
    (testing "percent escapes decode exactly once"
      (is (= (.getCanonicalFile (io/file dir "a b.txt"))
             (serve/resolve-file dir "/a%20b.txt")))
      (is (= (.getCanonicalFile (io/file dir "100%.txt"))
             (serve/resolve-file dir "/100%25.txt"))))
    (testing "a malformed escape resolves to nothing rather than throwing"
      (is (nil? (serve/resolve-file dir "/100%zz.txt"))))
    (testing "a path the OS cannot name (an embedded NUL) resolves to nothing"
      (is (nil? (serve/resolve-file dir "/sub%00/index.html"))))))

(deftest serve!-serves-names-with-url-special-characters
  (let [dir (tmp-site)]
    (spit (io/file dir "a+b.txt") "plus")
    (spit (io/file dir "100%.txt") "percent")
    (let [{:keys [server]} (serve/serve! {:dir dir :port 0})
          port             (.getPort (.getAddress server))]
      (try
        (is (= "plus" (slurp (str "http://localhost:" port "/a+b.txt"))))
        (is (= "percent" (slurp (str "http://localhost:" port "/100%25.txt"))))
        (finally (serve/stop! {:server server}))))))

(deftest serve!-serves-files-over-http
  (let [dir              (tmp-site)
        {:keys [server]} (serve/serve! {:dir dir :port 0})
        port             (.getPort (.getAddress server))]
    (try
      (testing "a directory url resolves to its index.html"
        (is (= "<h1>home</h1>" (slurp (str "http://localhost:" port "/"))))
        (is (= "<h1>chapter</h1>"
               (slurp (str "http://localhost:" port "/part-1/ch/")))))
      (testing "a missing path is a 404, not a crash"
        (is (thrown? java.io.IOException
                     (slurp (str "http://localhost:" port "/nope/")))))
      (finally (serve/stop! {:server server})))))

(deftest serve!-binds-loopback-by-default
  (let [dir              (tmp-site)
        {:keys [server]} (serve/serve! {:dir dir :port 0})]
    (try
      (is (.isLoopbackAddress (.getAddress (.getAddress server))))
      (finally (serve/stop! {:server server})))))

(deftest slashless-directory-url-redirects-to-the-slashed-form
  (let [dir              (tmp-site)
        {:keys [server]} (serve/serve! {:dir dir :port 0})
        port             (.getPort (.getAddress server))]
    (try
      (let [conn (doto (.openConnection
                        (java.net.URL. (str "http://localhost:" port "/part-1/ch")))
                   (.setInstanceFollowRedirects false))]
        (is (= 301 (.getResponseCode conn)))
        (is (= "/part-1/ch/" (.getHeaderField conn "Location"))))
      (finally (serve/stop! {:server server})))))

(deftest head-requests-answer-without-a-body-or-server-warnings
  (let [dir              (tmp-site)
        {:keys [server]} (serve/serve! {:dir dir :port 0})
        port             (.getPort (.getAddress server))
        records          (atom [])
        logger           (java.util.logging.Logger/getLogger "com.sun.net.httpserver")
        handler          (proxy [java.util.logging.Handler] []
                           (publish [r] (swap! records conj r))
                           (flush [])
                           (close []))]
    (.addHandler logger handler)
    (try
      (let [conn (doto (.openConnection
                        (java.net.URL. (str "http://localhost:" port "/")))
                   (.setRequestMethod "HEAD"))]
        (is (= 200 (.getResponseCode conn)))
        (is (= "text/html; charset=utf-8" (.getHeaderField conn "Content-Type")))
        (is (= "" (slurp (.getInputStream conn))))
        (is (empty? (filter #(= java.util.logging.Level/WARNING (.getLevel %))
                            @records))
            "the server answers HEAD without complaint"))
      (finally
        (.removeHandler logger handler)
        (serve/stop! {:server server})))))
