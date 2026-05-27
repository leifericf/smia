(ns clj-book.targets.pdf
  "PDF target adapter: invoke Asciidoctor PDF on the composed master."
  (:require
   [clj-book.error :as error]
   [clj-book.proc :as proc]
   [clj-book.theme.load :as theme]
   [clj-book.theme.pdf :as theme-pdf]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn preflight!
  "Verify required CLI tools and inputs exist. Throws structured
   `ex-info` on failure."
  [{:keys [master-path]}]
  (when-not (proc/cli-available? "asciidoctor-pdf")
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
  (let [extras (theme/load-pdf-extras book-root)
        yaml (theme-pdf/compile-yaml {:tokens tokens :extras extras})
        out  (io/file intermediate-dir "tokens" "pdf-theme.yml")]
    (io/make-parents out)
    (spit out yaml)
    (.getPath out)))

(defn build!
  "Build the PDF target: compile this target's theme YAML, then invoke
   asciidoctor-pdf on the master. `--doctype book` and `--base-dir` mirror
   the DocBook step so chapter `include::`s resolve from the manuscript
   root. Returns the produced artifact path."
  [{:keys [master-path output-dir config book-root tokens intermediate-dir]}]
  (preflight! {:master-path master-path})
  (let [theme-yaml (write-theme-yaml! {:book-root        book-root
                                       :tokens           tokens
                                       :intermediate-dir intermediate-dir})
        theme-file (.getAbsoluteFile (io/file theme-yaml))
        out (io/file output-dir (str (:book/slug config) ".pdf"))
        _   (io/make-parents out)
        theme-dir  (.getParent theme-file)
        theme-name (-> (.getName theme-file)
                       (str/replace #"\.ya?ml$" ""))
        args ["asciidoctor-pdf"
              "--doctype" "book"
              "--base-dir" book-root
              "--out-file" (.getAbsolutePath out)
              "-a" (str "pdf-themesdir=" theme-dir)
              "-a" (str "pdf-theme=" theme-name)
              (.getAbsolutePath (io/file master-path))]
        {exit :exit log :out} (proc/run-cli! args)]
    (when-not (zero? exit)
      (throw (error/ex :clj-book.targets.pdf/build-failed
                       "asciidoctor-pdf build failed."
                       {:exit-code exit :output log})))
    {:pdf (.getPath out)
     :dir (.getParent out)}))
