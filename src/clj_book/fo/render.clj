(ns clj-book.fo.render
  "Imperative shell: render an XSL-FO XML string to PDF bytes via Apache
   FOP, in-process. This is the only namespace that touches FOP or an
   output stream.

   FOP runs through an identity `Transformer` that streams the FO XML
   into FOP's SAX handler. Document metadata (producer, creator, creation
   date, optional title/author) is pinned so output is structurally
   reproducible across runs. FOP events are collected: warnings are
   returned, while ERROR/FATAL events (and any transform exception) are
   surfaced as structured `clj-book.error` values."
  (:require
   [clj-book.error :as error])
  (:import
   (java.io File OutputStream StringReader)
   (java.util Date)
   (javax.xml XMLConstants)
   (javax.xml.transform TransformerFactory)
   (javax.xml.transform.sax SAXResult)
   (javax.xml.transform.stream StreamSource)
   (org.apache.fop.apps FopFactoryBuilder MimeConstants)
   (org.apache.fop.events EventFormatter EventListener)
   (org.apache.fop.events.model EventSeverity)))

(def ^:private pinned-creation-date
  "Fixed creation date (the Unix epoch) so PDF metadata does not vary run
   to run."
  (Date. 0))

(defn- severity->level [severity]
  (condp = severity
    EventSeverity/FATAL :fatal
    EventSeverity/ERROR :error
    EventSeverity/WARN  :warn
    :info))

(defn- collecting-listener [events]
  (reify EventListener
    (processEvent [_ event]
      (swap! events conj {:level   (severity->level (.getSeverity event))
                          :message (EventFormatter/format event)}))))

(defn- base-uri [base-dir]
  (.toURI (File. ^String (or base-dir "."))))

(defn render-pdf!
  "Render FO XML string `fo-xml` to PDF, writing bytes to the
   caller-owned OutputStream `out`. Options:

   - `:base-dir` directory whose `file:` URI resolves relative resources
     (e.g. image `src`); defaults to the process directory.
   - `:title` / `:author` pinned into PDF metadata.

   Returns `{:warnings [{:level :warn :message ...} ...]}`. Throws a
   structured error on FOP ERROR/FATAL events or a transform failure. The
   caller is responsible for closing `out`."
  [^String fo-xml ^OutputStream out {:keys [base-dir title author]}]
  (let [events      (atom [])
        fop-factory (.build (FopFactoryBuilder. (base-uri base-dir)))
        ua          (.newFOUserAgent fop-factory)]
    (doto ua
      (.setProducer "clj-book")
      (.setCreator "clj-book")
      (.setCreationDate pinned-creation-date))
    (when title (.setTitle ua title))
    (when author (.setAuthor ua author))
    (.addEventListener (.getEventBroadcaster ua) (collecting-listener events))
    (let [fop         (.newFop fop-factory MimeConstants/MIME_PDF ua out)
          tf          (doto (TransformerFactory/newInstance)
                        (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true))
          transformer (.newTransformer tf)
          src         (StreamSource. (StringReader. fo-xml))
          res         (SAXResult. (.getDefaultHandler fop))]
      (try
        (.transform transformer src res)
        (catch Exception e
          (throw (error/ex :clj-book.fo.render/render-failed
                           (str "FOP failed to render PDF: " (.getMessage e))
                           {:cause  (.getMessage e)
                            :events @events}))))
      (let [errors (filterv (comp #{:error :fatal} :level) @events)]
        (when (seq errors)
          (throw (error/ex :clj-book.fo.render/fo-error
                           (str "FOP reported " (count errors)
                                " error(s) in the FO document.")
                           {:errors errors}))))
      {:warnings (filterv (comp #{:warn} :level) @events)})))
