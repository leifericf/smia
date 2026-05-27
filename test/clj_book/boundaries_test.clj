(ns clj-book.boundaries-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest uses-synthetic-fixtures-only
  (let [root (io/file "test/fixtures")
        subs (->> (.listFiles root) (map #(.getName %)) set)]
    (is (= #{"synthetic"} subs)
        "test/fixtures should contain only synthetic manuscripts")))

(deftest no-third-party-manuscript-in-platform
  (let [docs (io/file "docs")
        offenders (->> (file-seq docs)
                       (filter #(.isFile %))
                       (filter #(re-find #"\.adoc$" (.getName %)))
                       (filter #(not (str/starts-with? (.getPath %)
                                                       "docs/manual")))
                       (map #(.getPath %)))]
    (is (empty? offenders)
        (str "Non-manual AsciiDoc files found in docs/: "
             (str/join ", " offenders)))))
