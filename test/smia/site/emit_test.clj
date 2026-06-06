(ns smia.site.emit-test
  (:require
   [smia.site.emit :as emit]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/smia-site-emit-" tag "-" (System/nanoTime)))

(deftest stale-html-pages-are-swept-but-user-files-survive
  (let [out (tmp-dir "sweep")]
    (doseq [f ["index.html" "chapter-01.html" "chapter-09.html"]]
      (io/make-parents (io/file out f))
      (spit (io/file out f) "<old/>"))
    (spit (io/file out "CNAME") "example.com")
    (spit (io/file out ".nojekyll") "")
    (emit/emit! {:out-dir   out
                 :book-root (tmp-dir "sweep-root")
                 :pages     {"index.html"      "<!DOCTYPE html>\n<html></html>"
                             "chapter-01.html" "<!DOCTYPE html>\n<html></html>"
                             "styles.css"      "body {\n}\n"}
                 :resources []})
    (testing "a page the build no longer generates is removed"
      (is (not (.exists (io/file out "chapter-09.html")))))
    (testing "current pages are rewritten fresh"
      (is (.startsWith ^String (slurp (io/file out "index.html")) "<!DOCTYPE"))
      (is (.exists (io/file out "styles.css"))))
    (testing "hand-added non-HTML files are left untouched"
      (is (= "example.com" (slurp (io/file out "CNAME"))))
      (is (.exists (io/file out ".nojekyll"))))))

(deftest stale-nested-pages-are-swept-and-emptied-dirs-pruned
  (let [out (tmp-dir "nested-sweep")]
    (doseq [f ["index.html" "part-1/quickstart/index.html" "old-ch/index.html"]]
      (io/make-parents (io/file out f))
      (spit (io/file out f) "<old/>"))
    (spit (io/file out "CNAME") "example.com")
    (emit/emit! {:out-dir   out
                 :book-root (tmp-dir "nested-sweep-root")
                 :pages     {"index.html" "<!DOCTYPE html>\n<html></html>"
                             "part-1/quickstart/index.html" "<!DOCTYPE html>\n<html></html>"
                             "styles.css" "body {\n}\n"}
                 :resources []})
    (testing "a current nested page survives"
      (is (.exists (io/file out "part-1/quickstart/index.html"))))
    (testing "a page the build no longer generates is removed, its directory pruned"
      (is (not (.exists (io/file out "old-ch/index.html"))))
      (is (not (.exists (io/file out "old-ch")))))
    (testing "hand-added non-HTML files are left untouched"
      (is (= "example.com" (slurp (io/file out "CNAME")))))))

(deftest writes-every-page-to-the-output-directory
  (let [out (tmp-dir "pages")
        result (emit/emit! {:out-dir   out
                            :book-root (tmp-dir "empty-root")
                            :pages     {"index.html" "<!DOCTYPE html>\n<html></html>"
                                        "styles.css" "body {\n}\n"}
                            :resources []})]
    (is (= "<!DOCTYPE html>\n<html></html>"
           (slurp (io/file out "index.html"))))
    (is (.exists (io/file out "styles.css")))
    (is (= [] (:warnings result)))))

(deftest copies-referenced-resources-from-the-book-root
  (let [root (tmp-dir "root")
        out  (tmp-dir "res")]
    (io/make-parents (io/file root "images/x.png"))
    (spit (io/file root "images/x.png") "png-bytes")
    (let [result (emit/emit! {:out-dir   out
                              :book-root root
                              :pages     {"index.html" "<html></html>"}
                              :resources [{:src "images/x.png"}]})]
      (is (= "png-bytes" (slurp (io/file out "images/x.png"))))
      (is (= [] (:warnings result))))))

(deftest missing-resource-is-a-warning-not-a-failure
  (let [result (emit/emit! {:out-dir   (tmp-dir "missing")
                            :book-root (tmp-dir "missing-root")
                            :pages     {"index.html" "<html></html>"}
                            :resources [{:src "images/nope.png"}]})]
    (testing "the build completes and reports the gap"
      (is (= 1 (count (:warnings result))))
      (is (= :smia.site.emit/missing-resource
             (:warning/type (first (:warnings result))))))))

(deftest bundled-files-are-copied
  (let [out    (tmp-dir "bundled")
        bundle (io/file (tmp-dir "bundled-cache") "search.js")]
    (io/make-parents bundle)
    (spit bundle "console.log('island');")
    (emit/emit! {:out-dir   out
                 :book-root (tmp-dir "bundled-root")
                 :pages     {"index.html" "<!DOCTYPE html>\n<html></html>"}
                 :resources []
                 :bundled   [{:file bundle :path "search.js"}]})
    (testing "the compiled bundle lands beside the pages"
      (is (.exists (io/file out "search.js")))
      (is (pos? (.length (io/file out "search.js")))))
    (testing "the sweep never touches it"
      (emit/emit! {:out-dir   out
                   :book-root (tmp-dir "bundled-root")
                   :pages     {"index.html" "<!DOCTYPE html>\n<html></html>"}
                   :resources []})
      (is (.exists (io/file out "search.js"))))))

(deftest missing-bundled-file-is-a-hard-error
  (let [out (tmp-dir "bundled-missing")
        d   (try (emit/emit! {:out-dir   out
                              :book-root (tmp-dir "x")
                              :pages     {}
                              :resources []
                              :bundled   [{:file (io/file (tmp-dir "empty-cache")
                                                          "nope.js")
                                           :path "nope.js"}]})
                 nil
                 (catch Exception e (ex-data e)))]
    (is (= :smia.site.emit/missing-bundled-file (:error/type d)))))

(deftest symlinked-resource-cannot-leave-the-book-root
  (let [out    (tmp-dir "symlink-out")
        root   (tmp-dir "symlink-root")
        secret (io/file (str root "-secret.png"))]
    (spit secret "outside-bytes")
    (.mkdirs (io/file root "img"))
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file root "img/link.png"))
     (.toPath secret)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (let [d (try (emit/emit! {:out-dir   out
                              :book-root root
                              :pages     {}
                              :resources [{:src "img/link.png"}]})
                 nil
                 (catch Exception e (smia.error/data e)))]
      (is (= :smia.site.emit/unsafe-resource (:error/type d)))
      (is (not (.exists (io/file out "img/link.png")))))))

(deftest page-paths-cannot-leave-the-output-directory
  (let [root (tmp-dir "page-escape")
        out  (str root "/site")]
    (.mkdirs (io/file out))
    (doseq [bad ["../escaped.html" "/abs.html" "a/../../b.html"]]
      (let [d (try (emit/emit! {:out-dir   out
                                :book-root out
                                :pages     {bad "<!DOCTYPE html>\n<html></html>"}
                                :resources []})
                   nil
                   (catch Exception e (smia.error/data e)))]
        (is (= :smia.site.emit/unsafe-page-path (:error/type d))
            (str (pr-str bad) " is rejected"))))
    (is (not (.exists (io/file root "escaped.html"))))))
