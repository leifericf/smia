(ns smia.eval.registry-test
  (:require
   [smia.eval.registry :as registry]
   [clojure.test :refer [deftest is]]))

(deftest clojure-is-registered-as-data
  (is (registry/supported? :clojure))
  (is (symbol? (get-in registry/evaluators [:clojure :evaluate])))
  (is (not (registry/supported? :brainfuck))))

(deftest all-shipped-jvm-languages-are-addressable-as-data
  ;; Adding a language is adding a data entry, not editing a cond.
  (doseq [lang [:clojure :groovy :java :kotlin]]
    (is (registry/supported? lang) (str lang " has a registry entry"))
    (is (qualified-symbol? (get-in registry/evaluators [lang :evaluate]))
        (str lang " names its evaluate fn as a fully-qualified symbol"))))

(deftest result-constructors-shape-the-shared-contract
  (is (= {:status :parsed} (registry/parsed)))
  (is (= {:status :ran :value 9} (registry/ran 9)))
  (is (= {:status :matched :value true} (registry/matched true)))
  (is (= :failed (:status (registry/failed ["boom"]))))
  (is (= [{:message "boom"}] (:diagnostics (registry/failed ["boom"]))))
  (is (= :matched (:status (registry/from-value :assert true))))
  (is (= :failed (:status (registry/from-value :assert nil))))
  (is (= :ran (:status (registry/from-value :run 42)))))

(deftest collect-finds-only-test-marked-pre-blocks
  (let [ch [:chapter {:id :x :title "X"}
            [:p "prose"]
            [:pre {:lang :clojure :test true} "(+ 1 2)"]
            [:pre {:lang :clojure} "(not-marked)"]
            [:admonition {:kind :tip}
             [:pre {:lang :java :test true} "int x = 1;"]]]
        blocks (registry/collect-test-blocks ch)]
    (is (= 2 (count blocks)))
    (is (= #{:clojure :java} (set (map :lang blocks))))
    (is (= "(+ 1 2)" (:source (first blocks))))))

(deftest collect-concatenates-string-children
  (is (= "line1\nline2"
         (:source (first (registry/collect-test-blocks
                          [:pre {:test true :lang :clojure} "line1\n" "line2"]))))))

(deftest plan-validation-summarizes-by-language
  (let [chs [[:chapter {} [:pre {:lang :clojure :test true} "a"]]
             [:chapter {} [:pre {:lang :clojure :test true} "b"]
              [:pre {:lang :ruby :test true} "c"]]]
        plan (registry/plan-validation chs)]
    (is (= 3 (:total plan)))
    (is (= {:clojure 2 :ruby 1} (:by-language plan)))
    (is (= [:ruby] (:unsupported plan)) "languages without an evaluator are flagged")))
