(ns smia.fo.serialize-test
  (:require
   [smia.error :as error]
   [smia.fo.serialize :as ser]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- frag [node]
  (ser/serialize node {:xml-declaration? false}))

(deftest serializes-simple-element
  (is (= "<fo:block>hello</fo:block>"
         (frag [:fo/block "hello"]))))

(deftest serializes-attributes-sorted
  (is (= "<fo:block font-size=\"11pt\" space-before=\"6pt\">x</fo:block>"
         (frag [:fo/block {:space-before "6pt" :font-size "11pt"} "x"]))))

(deftest empty-element-self-closes
  (is (= "<fo:page-number-citation ref-id=\"ch1\"/>"
         (frag [:fo/page-number-citation {:ref-id "ch1"}]))))

(deftest root-carries-the-fo-namespace
  (let [out (frag [:fo/root [:fo/block "x"]])]
    (is (str/includes? out "<fo:root xmlns:fo=\"http://www.w3.org/1999/XSL/Format\">"))))

(deftest escapes-text-and-attributes
  (is (= "<fo:block>a &amp; b &lt;c&gt;</fo:block>"
         (frag [:fo/block "a & b <c>"])))
  (is (= "<fo:block role=\"&quot;q&quot;\">x</fo:block>"
         (frag [:fo/block {:role "\"q\""} "x"]))))

(deftest preserves-preformatted-whitespace
  (testing "no pretty-printing: newlines and spaces in text survive"
    (is (= "<fo:block white-space=\"pre\">line1\n  line2</fo:block>"
           (frag [:fo/block {:white-space "pre"} "line1\n  line2"])))))

(deftest flattens-seq-children
  (is (= "<fo:block><fo:inline>a</fo:inline><fo:inline>b</fo:inline></fo:block>"
         (frag [:fo/block (for [x ["a" "b"]] [:fo/inline x])]))))

(deftest skips-nil-children
  (is (= "<fo:block>x</fo:block>"
         (frag [:fo/block nil "x" nil]))))

(deftest xml-declaration-prepended-by-default
  (is (str/starts-with? (ser/serialize [:fo/root])
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")))

(deftest serialization-is-deterministic
  (let [node [:fo/block {:z "1" :a "2" :m "3"} "t"]]
    (is (= (frag node) (frag node)))))

(deftest unserializable-node-throws-structured-error
  (let [d (try (frag [:fo/block {:k :v} {:not "hiccup"}]) nil
               (catch Exception e (error/data e)))]
    (is (= :smia.fo.serialize/unserializable (:error/type d)))))
