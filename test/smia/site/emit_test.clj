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
