(ns clj-book.compose
  "Compose a deterministic master `book.adoc` with `include::` directives
   in configured chapter order.

   Chapter existence and uniqueness are validated upstream by the
   Manuscript context (clj-book.config), so this namespace trusts its
   input and only assembles. `master-adoc` is a pure function of the
   config; `write-master!` is the thin shell that persists it."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- header-lines [config]
  (let [{:book/keys [title authors revision]} config]
    (cond-> ["= " title]
      authors  (conj "\n" (str/join "; " authors))
      revision (conj "\n" revision))))

(defn master-adoc
  "Return the deterministic master AsciiDoc string for the given config.
   Lines and chapter order follow the configuration map exactly. Pure."
  [{:keys [config]}]
  (let [header (apply str (header-lines config))
        body   (->> (:book/chapters config)
                    (map #(str "include::" % "[]"))
                    (str/join "\n"))]
    (str header "\n\n" body "\n")))

(defn write-master!
  "Write the master adoc string to `<intermediate-dir>/book.adoc`.
   Returns the written file path."
  [{:keys [intermediate-dir] :as ctx}]
  (let [content (master-adoc ctx)
        out (io/file intermediate-dir "book.adoc")]
    (io/make-parents out)
    (spit out content)
    (.getPath out)))
