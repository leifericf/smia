(ns clj-book.non-goals-test
  "Enforce the PDF-first non-goals so they cannot regress silently."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def deps-edn (edn/read-string (slurp (io/file "deps.edn"))))

(defn- src-files []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))))

(defn- dep-symbols []
  (->> (tree-seq coll? seq deps-edn)
       (filter map?)
       (mapcat keys)
       (map str)))

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

(deftest no-external-process-or-asciidoctor
  (doseq [f (src-files)
          :let [src (slurp f)]]
    (is (not (str/includes? src "ProcessBuilder"))
        (str (.getPath f) " must not shell out to an external process"))
    (is (not (re-find #"(?i)asciidoctor" src))
        (str (.getPath f) " must not reference asciidoctor"))))

(deftest no-html-site-or-css-dependencies
  (let [deps (dep-symbols)]
    (doseq [banned ["stasis" "garden" "hiccup" "ring/ring" "clj-yaml"]]
      (is (not (some #(str/includes? % banned) deps))
          (str "PDF-first build must not depend on " banned)))))

(deftest deliverables-are-flat-editions
  ;; No profile/target split: an edition is the only deliverable concept,
  ;; and the structure it implies lives in the internal descriptors.
  (let [src (slurp (io/file "src/clj_book/build/request.clj"))]
    (is (str/includes? src "edition-descriptors"))
    (is (not (re-find #"(?i)profile" src))
        "no profile vocabulary in the request seam")))

(deftest license-is-epl-2-0
  (let [license (slurp (io/file "LICENSE"))]
    (is (str/includes? license "Eclipse Public License - v 2.0"))
    (is (str/includes? license "Eclipse Foundation"))))

(deftest readme-references-license
  (let [readme (slurp (io/file "README.md"))]
    (is (str/includes? readme "Eclipse Public License 2.0"))))
