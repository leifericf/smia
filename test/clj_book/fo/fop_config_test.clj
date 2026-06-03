(ns clj-book.fo.fop-config-test
  (:require
   [clj-book.fo.fop-config :as fop-config]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private print-x
  {:output-intent {:icc "assets/icc/sRGB2014.icc" :profile-name "sRGB2014"}
   :fonts [{:family "Test Serif"
            :normal "assets/fonts/TestSerif-Regular.ttf"
            :bold   "assets/fonts/TestSerif-Bold.ttf"
            :italic "assets/fonts/TestSerif-Italic.ttf"
            :bold-italic "assets/fonts/TestSerif-BoldItalic.ttf"}
           {:family "Test Mono"
            :normal "assets/fonts/TestMono-Regular.ttf"}]})

(deftest pdf-x-configuration-carries-mode-and-output-intent
  (let [xml (fop-config/xconf print-x {:pdf-x? true})]
    (is (str/includes? xml "<pdf-x-mode>PDF/X-4</pdf-x-mode>"))
    (is (str/includes? xml
                       "<output-profile>assets/icc/sRGB2014.icc</output-profile>"))))

(deftest fonts-register-one-triplet-per-style
  (let [xml (fop-config/xconf print-x {:pdf-x? true})]
    (is (str/includes? xml "embed-url=\"assets/fonts/TestSerif-Regular.ttf\""))
    (is (str/includes? xml
                       "<font-triplet name=\"Test Serif\" style=\"normal\" weight=\"normal\"/>"))
    (is (str/includes? xml
                       "<font-triplet name=\"Test Serif\" style=\"normal\" weight=\"bold\"/>"))
    (is (str/includes? xml
                       "<font-triplet name=\"Test Serif\" style=\"italic\" weight=\"normal\"/>"))
    (is (str/includes? xml
                       "<font-triplet name=\"Test Serif\" style=\"italic\" weight=\"bold\"/>"))
    (testing "a single-style family registers only what it has"
      (is (str/includes? xml
                         "<font-triplet name=\"Test Mono\" style=\"normal\" weight=\"normal\"/>"))
      (is (= 5 (count (re-seq #"<font-triplet" xml)))))))

(deftest fonts-without-pdf-x-mode
  (testing "PDF editions embed the configured fonts without X conformance"
    (let [xml (fop-config/xconf print-x {:pdf-x? false})]
      (is (not (str/includes? xml "pdf-x-mode")))
      (is (not (str/includes? xml "output-profile")))
      (is (str/includes? xml "embed-url=\"assets/fonts/TestSerif-Regular.ttf\"")))))

(deftest configuration-is-deterministic
  (is (= (fop-config/xconf print-x {:pdf-x? true})
         (fop-config/xconf print-x {:pdf-x? true}))))
