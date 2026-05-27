(ns clj-book.artifacts-test
  (:require
   [clj-book.artifacts :as artifacts]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(def cfg {:book/slug "example-book" :book/title "Example"})

(deftest manifest-shape
  (let [m (artifacts/manifest
            {:config       cfg
             :targets      [:site :pdf]
             :artifacts    [{:target :site :path "build/example-book/site/index.html"}
                            {:target :pdf  :path "build/example-book/pdf/example-book.pdf"}]
             :started-at   "2026-05-27T12:00:00Z"
             :finished-at  "2026-05-27T12:00:08Z"})]
    (is (= "example-book" (:book/slug m)))
    (is (= [:site :pdf]    (:build/targets m)))
    (is (= "2026-05-27T12:00:00Z" (:build/started-at m)))
    (is (= "2026-05-27T12:00:08Z" (:build/finished-at m)))
    (is (= 2 (count (:artifacts m))))
    (testing "build metadata carries tool identifier and version"
      (is (= "clj-book" (-> m :build/metadata :tool)))
      (is (= "1.0.0-alpha" (-> m :build/metadata :version))))))

(deftest write-emits-edn-on-disk
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/clj-book-art-" (System/currentTimeMillis))
        out (artifacts/write!
              {:output-dir  tmp
               :config      cfg
               :targets     [:site]
               :artifacts   [{:target :site :path "build/example-book/site/index.html"}]
               :started-at  "2026-05-27T12:00:00Z"
               :finished-at "2026-05-27T12:00:08Z"})
        path (:manifest/path out)
        parsed (edn/read-string (slurp path))]
    (is (.exists (io/file path)))
    (is (= "example-book" (:book/slug parsed)))
    (is (= [:site] (:build/targets parsed)))))

(deftest manifest-shape-preserves-target-order
  (let [m (artifacts/manifest
            {:config cfg
             :targets [:pdf :site]
             :artifacts []
             :started-at "x" :finished-at "y"})]
    (is (= [:pdf :site] (:build/targets m)))))
