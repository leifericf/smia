(ns smia.book.datatable
  "Pure core: parse a data file's text into table rows.

   A `:::table {:data \"x.csv\" :format :csv}` directive (see
   `smia.book.load`) names a delimited or EDN file; this namespace turns
   that file's text into a vector of row vectors of strings, which the
   loader wraps into a `[:table …]`. Three formats: `:csv` (RFC-4180-ish,
   with quoted fields and doubled quotes), `:tsv` (the same over tabs), and
   `:edn` (a sequence of row sequences). No IO — the read happens in the
   shell; this only parses."
  (:require
   [smia.error :as error]
   [clojure.edn :as edn]))

(defn- parse-delimited
  "Parse `text` as delimiter-separated values: fields split on `delim`,
   records on newlines. A field may be wrapped in double quotes, inside
   which the delimiter and newlines are literal and a doubled quote `\"\"`
   is one quote. A trailing newline does not add an empty row. Returns a
   vector of row vectors of strings."
  [text delim]
  (loop [chars   (seq text)
         field   (StringBuilder.)
         row     []
         rows    []
         quoted? false]
    (if (nil? chars)
      ;; flush the final field/record, unless we are sitting just past a
      ;; record terminator (nothing buffered) — that trailing newline is
      ;; not an empty row.
      (if (or (pos? (.length field)) (seq row))
        (conj rows (conj row (str field)))
        rows)
      (let [c    (first chars)
            more (next chars)]
        (cond
          quoted?
          (if (= c \")
            (if (= (first more) \")
              (do (.append field \") (recur (next more) field row rows true))
              (recur more field row rows false))
            (do (.append field c) (recur more field row rows true)))

          (= c \")     (recur more field row rows true)
          (= c delim)  (recur more (StringBuilder.) (conj row (str field)) rows false)
          (= c \newline) (recur more (StringBuilder.) [] (conj rows (conj row (str field))) false)
          (= c \return)  (recur more field row rows false) ; swallow CR in CRLF
          :else        (do (.append field c) (recur more field row rows false)))))))

(defn- parse-edn
  "Parse `text` as a sequence of row sequences. Non-string cells stringify
   (a number becomes its digits), so the rows are uniformly strings."
  [text]
  (let [data (edn/read-string text)]
    (when-not (and (sequential? data) (every? sequential? data))
      (throw (error/ex :smia.book.datatable/invalid-edn
                       "An :edn data table must be a sequence of row sequences."
                       {:data data})))
    (mapv (fn [row] (mapv #(if (string? %) % (str %)) row)) data)))

(defn rows
  "Parse `text` per `format` (`:csv`, `:tsv`, or `:edn`) into a vector of
   row vectors of strings. An unknown format is a structured error."
  [text format]
  (case format
    :csv (parse-delimited text \,)
    :tsv (parse-delimited text \tab)
    :edn (parse-edn text)
    (throw (error/ex :smia.book.datatable/unknown-format
                     (str "Unknown data-table :format " (pr-str format)
                          ". Known formats: :csv, :tsv, :edn.")
                     {:format format}))))
