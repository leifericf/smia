(ns smia.eval.clojure-test
  (:require
   [smia.eval.clojure :as ec]
   [clojure.test :refer [deftest is testing]]))

(defn- run [source & {:as attrs}]
  (ec/evaluate {:lang :clojure :source source :attrs (or attrs {:test true})}))

(deftest evaluates-a-valid-block
  (let [r (run "(defn sq [x] (* x x)) (sq 4)")]
    (is (= :ran (:status r)))
    (is (= 16 (:value r)))))

(deftest captures-runtime-failure-as-data
  (let [r (run "(/ 1 0)")]
    (is (= :failed (:status r)))
    (is (= "java.lang.ArithmeticException" (-> r :diagnostics first :exception)))))

(deftest captures-read-failure-as-data
  (let [r (run "(unbalanced")]
    (is (= :failed (:status r)))))

(deftest parse-level-does-not-evaluate
  (testing ":parse reads only, so a form that would throw still parses"
    (let [r (run "(/ 1 0)" :test true :level :parse)]
      (is (= :parsed (:status r))))))

(deftest assert-level-checks-truthiness
  (is (= :matched (:status (run "(= 4 (+ 2 2))" :test true :level :assert))))
  (is (= :failed (:status (run "(= 5 (+ 2 2))" :test true :level :assert)))))

(deftest blocks-do-not-leak-into-one-another
  (testing "a def in one block is not visible in the next"
    (run "(def leaked 1)")
    (let [r (run "leaked")]
      (is (= :failed (:status r)) "the symbol is unresolved in a fresh namespace"))))

(deftest read-time-eval-is-disabled
  (let [r (run "#=(System/getProperty \"user.home\")")]
    (is (= :failed (:status r)) "#= read-eval must not fire")))
