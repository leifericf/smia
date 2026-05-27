(ns clj-book.docbook
  "Drive Asciidoctor's DocBook 5 backend and parse the resulting XML into
   a Hiccup-friendly Clojure data structure."
  (:require
   [clj-book.error :as error]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io ByteArrayInputStream StringReader)
   (javax.xml.parsers DocumentBuilderFactory)
   (org.w3c.dom Element Node)))

(defn- asciidoctor-available? []
  (try
    (let [pb (doto (ProcessBuilder. ["asciidoctor" "--version"])
               (.redirectErrorStream true))
          p  (.start pb)]
      (.waitFor p)
      (zero? (.exitValue p)))
    (catch Exception _ false)))

(defn generate-docbook!
  "Invoke Asciidoctor with the DocBook 5 backend on the composed master
   `book.adoc`. Writes `book.xml` next to the master file. Returns the
   path to the generated DocBook file."
  [{:keys [intermediate-dir master-path]}]
  (when-not (asciidoctor-available?)
    (throw (error/ex :clj-book.docbook/asciidoctor-missing
                     "asciidoctor CLI not found on PATH."
                     {:command "asciidoctor"})))
  (let [out (io/file intermediate-dir "book.xml")
        pb  (doto (ProcessBuilder.
                    ["asciidoctor"
                     "--backend" "docbook5"
                     "--out-file" (.getPath out)
                     master-path])
              (.redirectErrorStream true))
        p   (.start pb)
        _   (.waitFor p)]
    (when-not (zero? (.exitValue p))
      (let [output (slurp (.getInputStream p))]
        (throw (error/ex :clj-book.docbook/generation-failed
                         "asciidoctor DocBook generation failed."
                         {:exit-code (.exitValue p)
                          :output    output}))))
    (.getPath out)))

(defn- parse-xml-string [^String s]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setNamespaceAware false))
        builder (.newDocumentBuilder factory)]
    (with-open [in (ByteArrayInputStream. (.getBytes s "UTF-8"))]
      (.parse builder in))))

(declare element->hiccup)

(defn- node-children [^Node node]
  (let [nodes (.getChildNodes node)]
    (for [i (range (.getLength nodes))]
      (.item nodes i))))

(defn- attrs-map [^Element el]
  (let [attrs (.getAttributes el)]
    (into {}
          (for [i (range (.getLength attrs))
                :let [n (.item attrs i)]]
            [(keyword (.getNodeName n)) (.getNodeValue n)]))))

(defn- text-node? [^Node node]
  (= Node/TEXT_NODE (.getNodeType node)))

(defn- element-node? [^Node node]
  (= Node/ELEMENT_NODE (.getNodeType node)))

(defn- ->child [^Node node]
  (cond
    (text-node? node) (.getNodeValue node)
    (element-node? node) (element->hiccup node)
    :else nil))

(defn- element->hiccup [^Element el]
  (let [tag      (keyword (.getTagName el))
        attrs    (attrs-map el)
        kids     (->> (node-children el)
                      (map ->child)
                      (remove nil?)
                      (remove #(and (string? %) (str/blank? %))))]
    (vec (concat [tag attrs] kids))))

(defn parse-docbook-string
  "Parse DocBook 5 XML (as a string) into a Hiccup-like data structure.
   Element attributes are kept as a keyword-keyed map in position 1."
  [s]
  (let [doc  (parse-xml-string s)
        root (.getDocumentElement doc)]
    (element->hiccup root)))

(defn parse-docbook-file [path]
  (-> path slurp parse-docbook-string))
