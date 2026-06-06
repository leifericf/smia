(ns build
  "Packaging: `clojure -T:jar uber` builds target/smia.jar, the standalone
   tool for non-Clojure users (`java -jar smia.jar <command>`).

   The jar bundles the optional math, diagram, and ClojureScript-island
   dependencies, so the alias composition the source checkout uses does
   not exist for jar users. The Groovy and Kotlin evaluators stay out;
   validating those languages remains a Clojure CLI concern.

   Only `smia.cli`'s static require tree is AOT-compiled. Everything else
   ships as source — in particular `smia.cljs.compile`, which is loaded
   via `requiring-resolve` the first time a site build needs an island
   bundle, and the island `.cljs` sources, which shadow-cljs reads as
   classpath resources from inside the jar."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/smia.jar")

(defn- version
  "The version stamped into the jar: the release tag via SMIA_VERSION
   (set by release.yml), else `git describe` for a dev build, else
   \"dev\". Date-based tags; never SemVer."
  []
  (or (System/getenv "SMIA_VERSION")
      (try (b/git-process {:git-args "describe --tags --always"})
           (catch Exception _ nil))
      "dev"))

(defn- short-sha []
  (try (b/git-process {:git-args "rev-parse --short HEAD"})
       (catch Exception _ nil)))

(defn uber
  "Build the standalone jar. The version surface (`smia version`) reads
   the stamped smia/version.edn resource; nothing in the render path
   does, so the stamp cannot break PDF byte-determinism."
  [_]
  (b/delete {:path "target"})
  (let [basis (b/create-basis {:aliases [:cljs :math :diagrams :slf4j-nop]})]
    (b/copy-dir {:src-dirs ["src" "cljs"] :target-dir class-dir})
    (b/write-file {:path   (str class-dir "/smia/version.edn")
                   :string (pr-str {:version (version) :sha (short-sha)})})
    (b/compile-clj {:basis      basis
                    :ns-compile '[smia.cli]
                    :class-dir  class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'smia.cli
             ;; One dependency ships LICENSE as a directory and another as
             ;; a file, which collides when merging; neither is needed at
             ;; runtime (full texts remain in the source jars).
             :exclude   ["^LICENSE(/.*)?$" "^NOTICE(/.*)?$"]})
    (println "Built" uber-file)))
