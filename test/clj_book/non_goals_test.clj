(ns clj-book.non-goals-test
  "Enforce v1 alpha non-goals so they cannot regress silently."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def deps-edn (edn/read-string (slurp (io/file "deps.edn"))))

(deftest no-datomic-dependency
  (let [keys-flat (mapcat keys
                          (filter map?
                                  (tree-seq coll? seq deps-edn)))]
    (is (not (some #(str/includes? (str %) "datomic") keys-flat))
        "v1 alpha must not depend on Datomic")))

(deftest no-clojurescript-or-bundler
  (let [keys-flat (mapcat keys
                          (filter map?
                                  (tree-seq coll? seq deps-edn)))]
    (is (not (some #(str/includes? (str %) "clojurescript") keys-flat))
        "v1 alpha must not include ClojureScript")
    (is (not (some #(re-find #"webpack|shadow-cljs|figwheel" (str %))
                   keys-flat))
        "v1 alpha must not include a JS bundler")))

(deftest license-is-epl-2-0
  (let [license (slurp (io/file "LICENSE"))]
    (is (str/includes? license "Eclipse Public License - v 2.0"))
    (is (str/includes? license "Eclipse Foundation"))))

(deftest readme-references-license
  (let [readme (slurp (io/file "README.md"))]
    (is (str/includes? readme "Eclipse Public License 2.0"))))
