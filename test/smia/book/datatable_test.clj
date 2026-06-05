(ns smia.book.datatable-test
  (:require
   [smia.book.datatable :as datatable]
   [smia.error :as error]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [thunk]
  (try (thunk) nil (catch Exception e (error/data e))))

(deftest csv-splits-fields-and-records
  (is (= [["a" "b"] ["1" "2"]]
         (datatable/rows "a,b\n1,2" :csv))))

(deftest csv-handles-quoting
  (testing "a quoted field may contain the delimiter and newlines"
    (is (= [["a,b" "c"]] (datatable/rows "\"a,b\",c" :csv)))
    (is (= [["line1\nline2" "x"]] (datatable/rows "\"line1\nline2\",x" :csv))))
  (testing "a doubled quote inside a quoted field is one literal quote"
    (is (= [["say \"hi\""]] (datatable/rows "\"say \"\"hi\"\"\"" :csv)))))

(deftest csv-empty-fields-and-trailing-newline
  (testing "an empty field is preserved"
    (is (= [["a" "" "c"]] (datatable/rows "a,,c" :csv))))
  (testing "a trailing newline does not add an empty row"
    (is (= [["a"] ["b"]] (datatable/rows "a\nb\n" :csv))))
  (testing "a trailing delimiter yields a trailing empty field"
    (is (= [["a" ""]] (datatable/rows "a," :csv)))))

(deftest blank-lines-separate-records-without-adding-rows
  (testing "an interior blank line is not a row"
    (is (= [["a" "b"] ["c" "d"]] (datatable/rows "a,b\n\nc,d" :csv)))
    (is (= [["a" "b"] ["c" "d"]] (datatable/rows "a\tb\n\nc\td" :tsv))))
  (testing "a record with real (if empty) content is kept"
    (is (= [["" ""]] (datatable/rows "," :csv))
        "a lone delimiter is two empty fields")
    (is (= [[""]] (datatable/rows "\"\"" :csv))
        "a quoted empty field is one empty cell")
    (is (= [["a"] [""] ["b"]] (datatable/rows "a\n\"\"\nb" :csv))
        "a quoted empty field line is a one-cell row")))

(deftest csv-tolerates-crlf
  (is (= [["a" "b"] ["1" "2"]]
         (datatable/rows "a,b\r\n1,2\r\n" :csv))))

(deftest tsv-splits-on-tabs
  (is (= [["a" "b"] ["1" "2"]]
         (datatable/rows "a\tb\n1\t2" :tsv)))
  (testing "commas are ordinary characters in TSV"
    (is (= [["a,b" "c"]] (datatable/rows "a,b\tc" :tsv)))))

(deftest edn-reads-a-sequence-of-rows
  (is (= [["A" "B"] ["1" "2"]]
         (datatable/rows "[[\"A\" \"B\"] [1 2]]" :edn)))
  (testing "an EDN table that is not a sequence of sequences is an error"
    (is (= :smia.book.datatable/invalid-edn
           (:error/type (catch-data #(datatable/rows "{:a 1}" :edn)))))))

(deftest unknown-format-is-a-clean-error
  (is (= :smia.book.datatable/unknown-format
         (:error/type (catch-data #(datatable/rows "a,b" :json))))))

(deftest empty-input-yields-no-rows
  (is (= [] (datatable/rows "" :csv))))
