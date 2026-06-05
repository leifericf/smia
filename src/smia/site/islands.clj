(ns smia.site.islands
  "On-demand compilation of the site's opt-in ClojureScript islands.

   Nothing compiled is committed: each island ships as a `.cljs` source
   under `cljs/smia/site/` and compiles to its browser bundle the first
   time a site build needs it. The compiler runs on the JVM (shadow-cljs
   as a Maven dependency, no Node anywhere) and sits behind the optional
   `:cljs` deps alias, loaded lazily with `requiring-resolve` — the same
   pattern as the math and diagram renderers: a book using no island
   never loads it, and a book that needs one without the alias on the
   classpath fails with a structured error naming it.

   Compiled bundles land in a shared cache directory keyed by a hash of
   the island sources, so repeated builds (and the test suite) reuse one
   compilation until the sources change."
  (:require
   [clojure.java.io :as io]
   [smia.error :as error])
  (:import
   [java.security MessageDigest]))

(def islands
  "Island key -> the `.cljs` entry namespace, its classpath source, and
   the bundle file name a site build copies into its output."
  {:search  {:entry 'smia.site.search-client/init
             :source "smia/site/search_client.cljs"
             :file  "search.js"}
   :mermaid {:entry 'smia.site.mermaid-client/init
             :source "smia/site/mermaid_client.cljs"
             :file  "mermaid.js"}
   :theme   {:entry 'smia.site.theme-client/init
             :source "smia/site/theme_client.cljs"
             :file  "theme.js"}
   :reader  {:entry 'smia.site.reader-client/init
             :source "smia/site/reader_client.cljs"
             :file  "reader.js"}
   :keys    {:entry 'smia.site.keys-client/init
             :source "smia/site/keys_client.cljs"
             :file  "keys.js"}})

(defn- unavailable [context]
  (error/ex :smia.site.islands/compiler-unavailable
            (str "Site islands (ClojureScript) need the optional :cljs alias. "
                 "Compose it with the command, e.g. clojure -M:run:cljs build.")
            (assoc context :requires ":cljs")))

(defn- sha256-hex [^bytes bs]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" %) d))))

(defn- source-bytes
  "The island's `.cljs` source as bytes, off the classpath. A missing
   source is a packaging bug, not author error."
  [island]
  (let [{:keys [source]} (get islands island)
        url (io/resource source)]
    (when-not url
      (throw (error/ex :smia.site.islands/missing-source
                       (str "Island ClojureScript source not found: " source)
                       {:island island :source source})))
    (with-open [in (io/input-stream url)
                out (java.io.ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))))

(defn cache-dir
  "The shared cache directory for the given islands: keyed by a hash of
   every island source, so any source edit lands in a fresh directory
   and stale bundles can never be reused. Under the JVM temp dir — it
   is a cache, not a build artifact, so `--clean` need not know it."
  [island-keys]
  (let [h (sha256-hex (byte-array (mapcat source-bytes (sort island-keys))))]
    (io/file (System/getProperty "java.io.tmpdir")
             (str "smia-islands-" (subs h 0 16)))))

(defn ensure-bundles!
  "Ensure every island in `island-keys` has a compiled bundle, compiling
   the missing ones, and return `{island-key java.io.File}`. Loads the
   compiler lazily; throws the structured `:cljs`-alias error when it is
   not on the classpath."
  [island-keys]
  (when (seq island-keys)
    (let [dir     (cache-dir island-keys)
          missing (remove #(.exists (io/file dir (:file (islands %))))
                          island-keys)]
      (when (seq missing)
        (let [release! (or (try (requiring-resolve
                                  'smia.site.islands.compile/release!)
                                (catch Throwable _ nil))
                           (throw (unavailable {:islands (vec island-keys)})))]
          (release! (map #(assoc (islands %) :island %) missing) dir)))
      (into {} (map (fn [k] [k (io/file dir (:file (islands k)))]))
            island-keys))))
