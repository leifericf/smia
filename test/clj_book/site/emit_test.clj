(ns clj-book.site.emit-test
  (:require
   [clj-book.site.emit :as emit]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-site-emit-" tag "-" (System/nanoTime)))

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
      (is (= :clj-book.site.emit/missing-resource
             (:warning/type (first (:warnings result))))))))
