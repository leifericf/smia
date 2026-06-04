(ns smia.html.serialize-test
  (:require
   [smia.error :as error]
   [smia.html.serialize :as html]
   [clojure.test :refer [deftest is testing]]))

(deftest escapes-text-and-attributes
  (is (= "<p>a &amp; b &lt; c &gt; d</p>"
         (html/serialize [:p "a & b < c > d"])))
  (is (= "<p title=\"a &amp; &quot;b&quot;\">x</p>"
         (html/serialize [:p {:title "a & \"b\""} "x"]))))

(deftest html5-void-elements-have-no-closing-tag
  (is (= "<br>" (html/serialize [:br])))
  (is (= "<hr>" (html/serialize [:hr])))
  (is (= "<img alt=\"a cat\" src=\"cat.png\">"
         (html/serialize [:img {:src "cat.png" :alt "a cat"}]))))

(deftest xhtml-void-elements-self-close
  (let [opts {:mode :xhtml :xml-declaration? false}]
    (is (= "<br/>" (html/serialize [:br] opts)))
    (is (= "<img alt=\"a cat\" src=\"cat.png\"/>"
           (html/serialize [:img {:src "cat.png" :alt "a cat"}] opts)))))

(deftest non-void-empty-elements-never-self-close
  (testing "browsers mis-parse self-closed non-void elements"
    (is (= "<div></div>" (html/serialize [:div])))
    (is (= "<span class=\"x\"></span>" (html/serialize [:span {:class "x"}])))
    (is (= "<div></div>"
           (html/serialize [:div] {:mode :xhtml :xml-declaration? false})))))

(deftest attributes-are-sorted-for-determinism
  (is (= "<a class=\"nav\" href=\"x.html\" id=\"n1\">go</a>"
         (html/serialize [:a {:id "n1" :href "x.html" :class "nav"} "go"]))))

(deftest nil-attribute-values-are-dropped
  (is (= "<p>x</p>" (html/serialize [:p {:title nil} "x"]))))

(deftest nested-structure-and-seq-children
  (is (= "<ul><li>one</li><li>two</li></ul>"
         (html/serialize [:ul (list [:li "one"] [:li "two"])])))
  (is (= "<p>a<strong>b</strong>c</p>"
         (html/serialize [:p "a" nil [:strong "b"] "c"]))))

(deftest numbers-serialize-as-text
  (is (= "<p>answer 42</p>" (html/serialize [:p "answer " 42]))))

(deftest xhtml-mode-emits-prolog-and-namespace
  (let [out (html/serialize [:html [:head] [:body [:p "x"]]]
                            {:mode :xhtml})]
    (is (.startsWith ^String out "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
    (is (.contains ^String out
                   "<html xmlns=\"http://www.w3.org/1999/xhtml\">"))))

(deftest html5-mode-has-no-prolog-or-namespace
  (let [out (html/serialize [:html [:body [:p "x"]]])]
    (is (.startsWith ^String out "<html>"))))

(deftest doctype-option-emits-doctype
  (is (.startsWith ^String (html/serialize [:html [:body]] {:doctype? true})
                   "<!DOCTYPE html>\n<html>"))
  (testing "in xhtml mode the doctype follows the XML declaration"
    (is (.startsWith ^String (html/serialize [:html [:body]]
                                             {:mode :xhtml :doctype? true})
                     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n<html"))))

(deftest unserializable-node-throws-structured-error
  (let [d (try (html/serialize [:p {:x "y"} #inst "2020"])
               nil
               (catch Exception e (error/data e)))]
    (is (= :smia.html.serialize/unserializable (:error/type d)))))

(deftest void-element-with-children-throws
  (let [d (try (html/serialize [:br "nope"])
               nil
               (catch Exception e (error/data e)))]
    (is (= :smia.html.serialize/void-element-children (:error/type d)))))
