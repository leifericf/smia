(ns clj-book.docbook
  "Drive Asciidoctor's DocBook 5 backend to produce DocBook XML from the
   composed master. This is the effectful produce side of the DocBook
   bridge; parsing the XML back into Hiccup lives in
   clj-book.docbook.parse."
  (:require
   [clj-book.error :as error]
   [clj-book.proc :as proc]
   [clojure.java.io :as io]))

(defn generate-docbook!
  "Invoke Asciidoctor with the DocBook 5 backend on the composed master
   `book.adoc`. Writes `book.xml` next to the master file. Returns the
   path to the generated DocBook file."
  [{:keys [intermediate-dir master-path]}]
  (when-not (proc/cli-available? "asciidoctor")
    (throw (error/ex :clj-book.docbook/asciidoctor-missing
                     "asciidoctor CLI not found on PATH."
                     {:command "asciidoctor"})))
  (let [xml-out (io/file intermediate-dir "book.xml")
        {:keys [exit out]} (proc/run-cli! ["asciidoctor"
                                           "--backend" "docbook5"
                                           "--out-file" (.getPath xml-out)
                                           master-path])]
    (when-not (zero? exit)
      (throw (error/ex :clj-book.docbook/generation-failed
                       "asciidoctor DocBook generation failed."
                       {:exit-code exit :output out})))
    (.getPath xml-out)))
