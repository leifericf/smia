(ns smia.fo.render
  "Imperative shell: render an XSL-FO XML string to PDF bytes via Apache
   FOP, in-process. This is the only namespace that touches FOP or an
   output stream.

   FOP is driven by a dedicated SAX `XMLReader` that streams the FO XML
   into FOP's SAX handler. We parse with our own reader rather than an
   identity `Transformer` deliberately: finishing a page makes FOP lazily
   load its event model through a *second* XML parse, from inside the
   outer parse's `endElement`. The identity-transformer path reused the
   in-progress parser for that nested parse, which a non-reentrant JAXP
   parser rejects with \"FWK005 parse may not be called while parsing\";
   a reader we own keeps the two parses independent.

   Document metadata (producer, creator, creation date, optional
   title/author) is pinned so output is structurally reproducible across
   runs. FOP events are collected: warnings are returned, while
   ERROR/FATAL events (and any parse exception) are surfaced as structured
   `smia.error` values."
  (:require
   [smia.error :as error])
  (:import
   (java.io ByteArrayInputStream File OutputStream StringReader)
   (java.util Date)
   (javax.xml XMLConstants)
   (javax.xml.parsers SAXParserFactory)
   (org.apache.fop.apps FopFactoryBuilder MimeConstants)
   (org.apache.fop.configuration DefaultConfigurationBuilder)
   (org.apache.fop.events EventFormatter EventListener)
   (org.apache.fop.events.model EventSeverity)
   (org.xml.sax InputSource)))

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
     (e.g. image `src`, configured font and ICC paths); defaults to the
     process directory.
   - `:title` / `:author` pinned into PDF metadata.
   - `:fop-config` FOP configuration XML (see `fo.fop-config/xconf`) for
     font embedding and PDF/X conformance.

   Returns `{:warnings [{:level :warn :message ...} ...]}`. Throws a
   structured error on FOP ERROR/FATAL events or a parse failure. The
   caller is responsible for closing `out`."
  [^String fo-xml ^OutputStream out {:keys [base-dir title author fop-config]}]
  (let [events      (atom [])
        fop-factory (-> (FopFactoryBuilder. (base-uri base-dir))
                        (cond-> fop-config
                          (.setConfiguration
                            (.build (DefaultConfigurationBuilder.)
                                    (ByteArrayInputStream.
                                      (.getBytes ^String fop-config "UTF-8")))))
                        (.build))
        ua          (.newFOUserAgent fop-factory)]
    (doto ua
      (.setProducer "Smia")
      (.setCreator "Smia")
      (.setCreationDate pinned-creation-date))
    (when title (.setTitle ua title))
    (when author (.setAuthor ua author))
    (.addEventListener (.getEventBroadcaster ua) (collecting-listener events))
    (let [fop    (.newFop fop-factory MimeConstants/MIME_PDF ua out)
          spf    (doto (SAXParserFactory/newInstance)
                   (.setNamespaceAware true)
                   (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true))
          reader (.getXMLReader (.newSAXParser spf))]
      (.setContentHandler reader (.getDefaultHandler fop))
      (try
        (.parse reader (InputSource. (StringReader. fo-xml)))
        (catch Exception e
          (throw (error/ex :smia.fo.render/render-failed
                           (str "FOP failed to render PDF: " (.getMessage e))
                           {:cause  (.getMessage e)
                            :events @events}))))
      (let [errors (filterv (comp #{:error :fatal} :level) @events)]
        (when (seq errors)
          (throw (error/ex :smia.fo.render/fo-error
                           (str "FOP reported " (count errors)
                                " error(s) in the FO document.")
                           {:errors errors}))))
      {:warnings (filterv (comp #{:warn} :level) @events)})))
