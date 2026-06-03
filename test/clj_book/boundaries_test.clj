(ns clj-book.boundaries-test
  "Encode the architecture as regression tests: functional core /
   imperative shell is machine-checked here so it cannot quietly erode."
  (:require
   [clj-book.book.config :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest uses-synthetic-fixtures-only
  (let [root (io/file "test/fixtures")
        subs (->> (.listFiles root) (map #(.getName %)) set)]
    (is (= #{"synthetic"} subs)
        "test/fixtures should contain only synthetic manuscripts")))

(deftest manual-is-the-only-shipped-manuscript
  ;; The dogfood manual at manual/ is the only manuscript the platform ships;
  ;; every other book.edn in the repo is a synthetic test fixture.
  (let [offenders (->> (file-seq (io/file "."))
                       (filter #(.isFile %))
                       (filter #(= "book.edn" (.getName %)))
                       (map #(.getPath %))
                       (remove #(str/includes? % "/manual/"))
                       (remove #(str/includes? % "/test/fixtures/"))
                       (remove #(str/includes? % "/build/")))]
    (is (empty? offenders)
        (str "Unexpected manuscript(s): " (str/join ", " offenders)))))

;; --- Functional core, imperative shell -----------------------------------

(def interface-nss
  "Interface-layer source files: the only entry points to the system."
  ["src/clj_book/api.clj"
   "src/clj_book/build/request.clj"])

(def render-internals
  "Rendering-context namespaces the interface must not reach into; it
   routes through clj-book.build.execute instead."
  ["clj-book.fo.render" "clj-book.book.assemble" "clj-book.book.load"
   "clj-book.md." "clj-book.eval." "clj-book.html." "clj-book.site."])

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
   "src/clj_book/html/serialize.clj"
   "src/clj_book/html/links.clj"
   "src/clj_book/html/expand.clj"
   "src/clj_book/html/assemble.clj"
   "src/clj_book/theme/compile.clj"
   "src/clj_book/theme/css.clj"
   "src/clj_book/site/assemble.clj"
   "src/clj_book/book/structure.clj"
   "src/clj_book/book/number.clj"
   "src/clj_book/book/assemble.clj"
   "src/clj_book/md/compile.clj"
   "src/clj_book/md/schema.clj"
   "src/clj_book/eval/registry.clj"
   "src/clj_book/highlight/lexer.clj"
   "src/clj_book/highlight/registry.clj"
   "src/clj_book/highlight/clojure.clj"
   "src/clj_book/highlight/java.clj"
   "src/clj_book/highlight/kotlin.clj"
   "src/clj_book/highlight/groovy.clj"])

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
    (is (some #(= :clj-book.book.config/unknown-key (:warning/type %)) warnings)
        "validate computes warnings without touching the disk")))
