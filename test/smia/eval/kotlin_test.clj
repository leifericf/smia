(ns smia.eval.kotlin-test
  "Gated on the optional Kotlin scripting dependency; run with
   `-A:eval-kotlin` to exercise. Without it these tests skip so the default
   suite stays green."
  (:require
   [clojure.test :refer [deftest is]])
  (:import
   (javax.script ScriptEngineManager)))

(def ^:private available?
  (try (some? (.getEngineByName (ScriptEngineManager.) "kotlin"))
       (catch Throwable _ false)))

(defn- ev [input] ((requiring-resolve 'smia.eval.kotlin/evaluate) input))

(deftest kotlin-evaluates-when-present
  (if available?
    (let [r (ev {:lang :kotlin :source "val sq = { x: Int -> x*x }; sq(6)" :attrs {:test true}})]
      (is (= :ran (:status r)))
      (is (= 36 (:value r))))
    (is true "Kotlin engine not on the classpath (run with -A:eval-kotlin); skipped")))

(deftest kotlin-failure-is-captured
  (when available?
    (let [r (ev {:lang :kotlin :source "throw RuntimeException(\"boom\")" :attrs {:test true}})]
      (is (= :failed (:status r))))))
