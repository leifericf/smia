(ns clj-book.fo.schema-test
  (:require
   [clj-book.error :as error]
   [clj-book.fo.schema :as fo-schema]
   [clojure.test :refer [deftest is testing]]))

(deftest accepts-ordinary-html-hiccup
  (is (fo-schema/valid? [:p "plain"]))
  (is (fo-schema/valid? [:p {:id "x"} "with attrs"]))
  (is (fo-schema/valid? [:p "a " [:strong "b"] " " [:em "c"]]))
  (is (fo-schema/valid? [:ul [:li "one"] [:li "two"]]))
  (is (fo-schema/valid? [:h2 {:id :intro} "Heading"])))

(deftest accepts-book-extensions
  (is (fo-schema/valid? [:admonition {:kind :note} [:p "x"]]))
  (is (fo-schema/valid? [:p "see " [:xref {:to :ch-config}]])))

(deftest accepts-raw-fo
  (testing ":fo/* tags pass the vocabulary; FOP validates their details"
    (is (fo-schema/valid? [:fo/block {:space-before "12pt"} "raw"]))
    (is (fo-schema/valid? [:fo/table [:fo/table-body [:fo/table-row]]]))))

(deftest accepts-raw-html
  (testing ":html/* tags pass the vocabulary like :fo/*"
    (is (fo-schema/known-tag? :html/aside))
    (is (fo-schema/valid? [:html/aside {:class "warn"} [:p "x"]]))
    (is (fo-schema/valid? [:p "inline " [:html/kbd "Ctrl"]]))))

(deftest rejects-unknown-namespaced-tags
  (is (not (fo-schema/known-tag? :bogus/x)))
  (is (not (fo-schema/valid? [:bogus/x "no"]))))

(deftest accepts-numbers-as-content
  (is (fo-schema/valid? [:p "answer " 42])))

(deftest rejects-unknown-tag-with-humanized-error
  (is (not (fo-schema/valid? [:marquee "no"])))
  (let [explanation (fo-schema/explain [:marquee "no"])]
    (is (some? explanation)
        "a humanized explanation is returned for an unknown tag")))

(deftest rejects-non-vector-element
  (is (not (fo-schema/valid? "just a string")))
  (is (not (fo-schema/valid? {:not "hiccup"}))))

(deftest check-throws-structured-error
  (let [d (try (fo-schema/check [:bogus "x"] :clj-book.fo.schema-test/bad)
               nil
               (catch Exception e (error/data e)))]
    (is (= :clj-book.fo.schema-test/bad (:error/type d)))
    (is (some? (:errors (:error/context d))))))

(deftest check-returns-node-when-valid
  (let [node [:p "ok"]]
    (is (= node (fo-schema/check node :clj-book.fo.schema-test/bad)))))
