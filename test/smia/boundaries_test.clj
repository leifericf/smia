(ns smia.boundaries-test
  "Encode the architecture as regression tests: functional core /
   imperative shell is machine-checked here so it cannot quietly erode."
  (:require
   [smia.book.config :as config]
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
  ["src/smia/api.clj"
   "src/smia/build/request.clj"])

(def render-internals
  "Rendering-context namespaces the interface must not reach into; it
   routes through smia.build.execute instead."
  ["smia.fo.render" "smia.book.assemble" "smia.book.load"
   "smia.md." "smia.eval." "smia.html." "smia.site."])

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
  ["src/smia/build/plan.clj"
   "src/smia/fo/attrs.clj"
   "src/smia/fo/serialize.clj"
   "src/smia/fo/expand.clj"
   "src/smia/fo/schema.clj"
   "src/smia/fo/fop_config.clj"
   "src/smia/html/serialize.clj"
   "src/smia/html/links.clj"
   "src/smia/html/expand.clj"
   "src/smia/html/assemble.clj"
   "src/smia/theme/compile.clj"
   "src/smia/theme/css.clj"
   "src/smia/site/assemble.clj"
   "src/smia/epub/assemble.clj"
   "src/smia/book/structure.clj"
   "src/smia/book/number.clj"
   "src/smia/book/assemble.clj"
   "src/smia/md/compile.clj"
   "src/smia/md/schema.clj"
   "src/smia/eval/registry.clj"
   "src/smia/highlight/lexer.clj"
   "src/smia/highlight/registry.clj"
   "src/smia/highlight/clojure.clj"
   "src/smia/highlight/java.clj"
   "src/smia/highlight/kotlin.clj"
   "src/smia/highlight/groovy.clj"
   "src/smia/highlight/bash.clj"
   "src/smia/highlight/python.clj"
   "src/smia/highlight/javascript.clj"
   "src/smia/highlight/sql.clj"
   "src/smia/math/resolve.clj"])

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
    (is (some #(= :smia.book.config/unknown-key (:warning/type %)) warnings)
        "validate computes warnings without touching the disk")))
