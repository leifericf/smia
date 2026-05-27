(ns clj-book.targets.pdf
  "PDF target adapter: invoke Asciidoctor PDF on the composed master."
  (:require
   [clj-book.error :as error]
   [clj-book.tokens.pdf :as tokens-pdf]
   [clojure.java.io :as io]))

(defn- cli-available? [cmd]
  (try
    (let [pb (doto (ProcessBuilder. [cmd "--version"])
               (.redirectErrorStream true))
          p  (.start pb)]
      (.waitFor p)
      (zero? (.exitValue p)))
    (catch Exception _ false)))

(defn preflight!
  "Verify required CLI tools and inputs exist. Throws structured
   `ex-info` on failure."
  [{:keys [master-path]}]
  (when-not (cli-available? "asciidoctor-pdf")
    (throw (error/ex :clj-book.targets.pdf/cli-missing
                     "asciidoctor-pdf CLI not found on PATH."
                     {:command "asciidoctor-pdf"})))
  (when (or (nil? master-path) (not (.exists (io/file master-path))))
    (throw (error/ex :clj-book.targets.pdf/missing-master
                     "Composed master book.adoc not found."
                     {:master-path master-path}))))

(defn write-theme-yaml!
  "Write the compiled PDF theme YAML next to the master adoc. Returns
   the path."
  [{:keys [book-root tokens intermediate-dir]}]
  (let [yaml (tokens-pdf/compile-yaml {:book-root book-root :tokens tokens})
        out  (io/file intermediate-dir "tokens" "pdf-theme.yml")]
    (io/make-parents out)
    (spit out yaml)
    (.getPath out)))

(defn build!
  "Build the PDF target. Returns the produced artifact path."
  [{:keys [master-path output-dir config theme-yaml]}]
  (preflight! {:master-path master-path})
  (let [out (io/file output-dir (str (:book/slug config) ".pdf"))
        _   (io/make-parents out)
        theme-dir (-> (io/file theme-yaml) .getParent)
        theme-name (-> (io/file theme-yaml) .getName
                       (clojure.string/replace #"\.ya?ml$" ""))
        args ["asciidoctor-pdf"
              "--out-file" (.getPath out)
              "-a" (str "pdf-themesdir=" theme-dir)
              "-a" (str "pdf-theme=" theme-name)
              master-path]
        pb   (doto (ProcessBuilder. ^java.util.List args)
               (.redirectErrorStream true))
        p    (.start pb)
        log  (slurp (.getInputStream p))
        _    (.waitFor p)]
    (when-not (zero? (.exitValue p))
      (throw (error/ex :clj-book.targets.pdf/build-failed
                       "asciidoctor-pdf build failed."
                       {:exit-code (.exitValue p)
                        :output    log})))
    {:pdf (.getPath out)
     :dir (.getParent out)}))
