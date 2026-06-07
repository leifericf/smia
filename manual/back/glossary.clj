;; The glossary is generated: its entries live in glossary.edn at the book
;; root, and this file sorts and renders them. Each entry carries a short
;; definition and a reference that becomes a page number in the PDF
;; editions and a link on the site and in the EPUB.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def book-dir
  ;; *file* is this file's path while the build evaluates it; the book
  ;; root is two directories up (back/glossary.clj).
  (-> (io/file *file*) .getAbsoluteFile .getParentFile .getParentFile))

(def entries
  (sort-by :term (edn/read-string (slurp (io/file book-dir "glossary.edn")))))

(defn- entry-pair [{:keys [term definition see]}]
  [[:dt term]
   [:dd definition " See " [:xref {:to see :page true}] "."]])

(defn- entry-list [es]
  (into [:dl] (mapcat entry-pair es)))

(defn- entry-block
  "One dictionary entry for the paged columns: the term run in bold, the
   definition following it, wrapped lines hanging under the first. Ragged
   right, since justification opens ugly gaps in a measure this narrow.
   Entries may break across pages, as dictionary entries do, but never
   strand a single line."
  [{:keys [term definition see]}]
  [:fo/block {:space-after "5pt" :start-indent "8pt" :text-indent "-8pt"
              :text-align "start" :hyphenate "true"
              :widows "2" :orphans "2"}
   [:fo/inline {:font-weight "bold"} term] "  " definition " See "
   [:xref {:to see :page true}] "."])

(defn- entry-column
  "A column of entries, set a step below the body size with tighter
   leading, the way dictionaries earn their density."
  [es]
  (into [:fo/block {:font-size "9.5pt" :line-height "1.35"}]
        (map entry-block es)))

(def halves
  (split-at (quot (inc (count entries)) 2) entries))

(def pdf-editions
  {:any-of [{:equals [:edition :screen]}
            {:equals [:edition :print]}
            {:equals [:edition :print-x]}]})

[:chapter {:id :glossary :title "Glossary"}
 [:p "Key terms used throughout this manual, each with a pointer to the "
  "chapter that treats it in full. The index that follows locates every "
  "occurrence; this page is for finding the right concept first."]

 ;; Two dictionary columns on paper, set as a borderless FO table; the
 ;; reflowing editions read better as one list and get exactly that.
 [:when pdf-editions
  [:fo/table {:table-layout "fixed" :width "100%" :space-before "8pt"}
   [:fo/table-column {:column-width "proportional-column-width(1)"}]
   [:fo/table-column {:column-width "proportional-column-width(1)"}]
   [:fo/table-body
    [:fo/table-row
     [:fo/table-cell {:padding-right "8pt"} (entry-column (first halves))]
     [:fo/table-cell {:padding-left "8pt"} (entry-column (second halves))]]]]]

 [:when {:not {:any-of [{:equals [:edition :screen]}
                        {:equals [:edition :print]}
                        {:equals [:edition :print-x]}]}}
  (entry-list entries)]]
