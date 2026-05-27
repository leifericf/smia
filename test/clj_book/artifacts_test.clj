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
             :profiles     [:screen :print]
             :artifacts    [{:profile :screen :path "build/example-book/pdf/example-book-screen.pdf"}
                            {:profile :print  :path "build/example-book/pdf/example-book-print.pdf"}]
             :started-at   "2026-05-27T12:00:00Z"
             :finished-at  "2026-05-27T12:00:08Z"})]
    (is (= "example-book" (:book/slug m)))
    (is (= [:screen :print]    (:build/profiles m)))
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
               :profiles    [:screen]
               :artifacts   [{:profile :screen :path "build/example-book/pdf/example-book-screen.pdf"}]
               :started-at  "2026-05-27T12:00:00Z"
               :finished-at "2026-05-27T12:00:08Z"})
        path (:manifest/path out)
        parsed (edn/read-string (slurp path))]
    (is (.exists (io/file path)))
    (is (= "example-book" (:book/slug parsed)))
    (is (= [:screen] (:build/profiles parsed)))))

(deftest manifest-shape-preserves-profile-order
  (let [m (artifacts/manifest
            {:config cfg
             :profiles [:print :screen]
             :artifacts []
             :started-at "x" :finished-at "y"})]
    (is (= [:print :screen] (:build/profiles m)))))
