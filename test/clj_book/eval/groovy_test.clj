(ns clj-book.eval.groovy-test
  "Gated on the optional Groovy dependency; run with `-A:eval-groovy` to
   exercise. Without it these tests skip (assert true) so the default suite
   stays green."
  (:require
   [clojure.test :refer [deftest is]]))

(def ^:private available?
  (try (Class/forName "groovy.lang.GroovyShell") true (catch Throwable _ false)))

(defn- ev [input] ((requiring-resolve 'clj-book.eval.groovy/evaluate) input))

(deftest groovy-evaluates-when-present
  (if available?
    (let [r (ev {:lang :groovy :source "def sq = { x -> x*x }; sq(5)" :attrs {:test true}})]
      (is (= :ran (:status r)))
      (is (= 25 (:value r))))
    (is true "Groovy not on the classpath (run with -A:eval-groovy); skipped")))

(deftest groovy-failure-is-captured
  (when available?
    (let [r (ev {:lang :groovy :source "throw new RuntimeException('boom')" :attrs {:test true}})]
      (is (= :failed (:status r)))
      (is (seq (:diagnostics r))))))

(deftest groovy-assert-level
  (when available?
    (is (= :matched (:status (ev {:lang :groovy :source "2 + 2 == 4" :attrs {:test true :level :assert}}))))
    (is (= :failed  (:status (ev {:lang :groovy :source "2 + 2 == 5" :attrs {:test true :level :assert}}))))))
