(ns clj-book.boundaries-test
  "Encode the architecture as regression tests: functional core /
   imperative shell is machine-checked here so it cannot quietly erode."
  (:require
   [clj-book.config :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest uses-synthetic-fixtures-only
  (let [root (io/file "test/fixtures")
        subs (->> (.listFiles root) (map #(.getName %)) set)]
    (is (= #{"synthetic"} subs)
        "test/fixtures should contain only synthetic manuscripts")))

(deftest only-manuscript-under-docs-is-the-manual
  ;; The dogfood manual is the only manuscript the platform ships; no other
  ;; book.edn should live under docs/.
  (let [offenders (->> (file-seq (io/file "docs"))
                       (filter #(.isFile %))
                       (filter #(= "book.edn" (.getName %)))
                       (map #(.getPath %))
                       (remove #(str/starts-with? % "docs/manual")))]
    (is (empty? offenders)
        (str "Unexpected manuscript(s) under docs/: " (str/join ", " offenders)))))

;; --- Functional core, imperative shell -----------------------------------

(def interface-nss
  "Interface-layer source files: the only entry points to the system."
  ["src/clj_book/api.clj"
   "src/clj_book/request.clj"])

(def render-internals
  "Rendering-context namespaces the interface must not reach into; it
   routes through clj-book.build.execute instead."
  ["clj-book.fo.render" "clj-book.book.assemble" "clj-book.book.load"
   "clj-book.md." "clj-book.eval."])

(deftest interface-routes-through-build
  (doseq [path interface-nss
          :let [src (slurp (io/file path))]
          internal render-internals]
    (is (not (str/includes? src internal))
        (str path " (interface) must route through build, not reach "
             internal " directly"))))

(def pure-core-nss
  "Source files that are pure cores: transforms with no IO, no FOP, and no
   shelling out, so they are exercisable on in-memory data alone."
  ["src/clj_book/build/plan.clj"
   "src/clj_book/fo/attrs.clj"
   "src/clj_book/fo/serialize.clj"
   "src/clj_book/fo/expand.clj"
   "src/clj_book/fo/schema.clj"
   "src/clj_book/book/theme.clj"
   "src/clj_book/book/structure.clj"
   "src/clj_book/book/assemble.clj"
   "src/clj_book/md/compile.clj"
   "src/clj_book/md/schema.clj"
   "src/clj_book/eval/registry.clj"])

(deftest pure-cores-do-no-io
  (doseq [path pure-core-nss
          :let [src (slurp (io/file path))]]
    (is (not (str/includes? src "clojure.java.io"))
        (str path " is a pure core and must not import clojure.java.io"))
    (is (not (str/includes? src "org.apache.fop"))
        (str path " is a pure core and must not reach into FOP"))
    (is (not (str/includes? src "ProcessBuilder"))
        (str path " is a pure core and must not shell out"))))

(deftest config-validate-is-filesystem-free
  ;; config.clj hosts both the pure `validate` and the shell `load-config`;
  ;; `validate` must operate on in-memory data with no file present.
  (let [warnings (config/validate
                   {:book/slug "s" :book/title "t"
                    :book/chapters ["a.clj"] :unknown-key 1}
                   "/does/not/exist/book.edn")]
    (is (vector? warnings))
    (is (some #(= :clj-book.config/unknown-key (:warning/type %)) warnings)
        "validate computes warnings without touching the disk")))
