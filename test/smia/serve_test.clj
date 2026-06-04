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
