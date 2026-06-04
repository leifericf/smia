(ns smia.vocabulary-parity-test
  "Pins the expander tables to the schema vocabulary: every sugar and
   book-extension tag has an expander in each output format, and no
   expander handles a tag the schema does not accept. A new tag must
   land in the schema and in every format's expander table at once."
  (:require
   [smia.fo.expand :as fo-expand]
   [smia.fo.schema :as fo-schema]
   [smia.html.expand :as html-expand]
   [clojure.test :refer [deftest is]]))

(deftest fo-expanders-cover-the-vocabulary-exactly
  (is (= fo-schema/sugar-tags (set (keys fo-expand/expanders)))))

(deftest html-expanders-cover-the-vocabulary-exactly
  (is (= fo-schema/sugar-tags (set (keys html-expand/expanders)))))
