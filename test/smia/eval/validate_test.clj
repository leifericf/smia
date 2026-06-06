(ns smia.eval.validate-test
  (:require
   [smia.error :as error]
   [smia.eval.validate :as validate]
   [clojure.test :refer [deftest is]]))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest passing-blocks-validate-ok
  (let [ch [:chapter {:id :x :title "X"}
            [:pre {:lang :clojure :test true} "(+ 1 2)"]
            [:pre {:lang :clojure :test true} "(map inc [1 2 3])"]]
        r  (validate/validate-chapters! [ch])]
    (is (= :ok (:status r)))
    (is (= 2 (:validated r)))))

(deftest a-failing-block-aborts-with-aggregated-error
  (let [ch [:chapter {:id :x :title "X"}
            [:pre {:lang :clojure :test true} "(+ 1 2)"]
            [:pre {:lang :clojure :test true} "(/ 1 0)"]]
        d  (catch-data #(validate/validate-chapters! [ch]))]
    (is (= :smia.eval/validation-failed (:error/type d)))
    (is (= 1 (count (get-in d [:error/context :failures]))))))

(deftest unsupported-language-is-a-clear-error
  (let [ch [:chapter {} [:pre {:lang :ruby :test true} "puts 1"]]
        d  (catch-data #(validate/validate-chapters! [ch]))]
    (is (= :smia.eval/unsupported-language (:error/type d)))))

(deftest test-block-without-a-language-is-an-error
  (let [ch [:chapter {} [:pre {:test true} "(+ 1 2)"]]
        d  (catch-data #(validate/validate-chapters! [ch]))]
    (is (= :smia.eval/missing-language (:error/type d)))))

(deftest nothing-to-validate-is-ok
  (let [ch [:chapter {:id :x :title "X"} [:p "prose only"]]
        r  (validate/validate-chapters! [ch])]
    (is (= :ok (:status r)))
    (is (= 0 (:validated r)))))

(deftest unknown-level-is-an-error-not-a-run
  (let [marker (str (System/getProperty "java.io.tmpdir")
                    "/smia-eval-level-" (System/nanoTime))
        ch [:chapter {} [:pre {:lang :clojure :test true :level :prase}
                         (str "(spit " (pr-str marker) " \"ran\")")]]
        d  (catch-data #(validate/validate-chapters! [ch]))]
    (is (= :smia.eval/invalid-level (:error/type d)))
    (is (= :prase (get-in d [:error/context :level])))
    (is (not (.exists (java.io.File. marker))) "the block never executes")))

(deftest every-known-level-is-accepted
  (let [ch [:chapter {}
            [:pre {:lang :clojure :test true :level :parse} "(+ 1 2)"]
            [:pre {:lang :clojure :test true :level :compile} "(+ 1 2)"]
            [:pre {:lang :clojure :test true :level :run} "(+ 1 2)"]
            [:pre {:lang :clojure :test true :level :assert} "(= 3 (+ 1 2))"]]]
    (is (= :ok (:status (validate/validate-chapters! [ch]))))))
