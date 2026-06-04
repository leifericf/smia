(ns smia.eval.java-test
  "JShell is part of the JDK, so these normally run in the default suite.
   They still gate on availability so an unusual JDK/module setup degrades
   gracefully rather than erroring."
  (:require
   [clojure.test :refer [deftest is]]))

(def ^:private available?
  (try (Class/forName "jdk.jshell.JShell") true (catch Throwable _ false)))

(defn- ev [input] ((requiring-resolve 'smia.eval.java/evaluate) input))

(deftest java-compiles-and-runs
  (if available?
    (let [r (ev {:lang :java :source "int x = 21;\nx * 2;" :attrs {:test true}})]
      (is (= :ran (:status r)))
      (is (= "42" (:value r))))
    (is true "JShell unavailable; skipped")))

(deftest java-compile-error-is-captured
  (when available?
    (let [r (ev {:lang :java :source "int x = ;" :attrs {:test true}})]
      (is (= :failed (:status r)))
      (is (seq (:diagnostics r))))))

(deftest java-runtime-exception-is-captured
  (when available?
    (let [r (ev {:lang :java :source "Object o = null;\no.toString();" :attrs {:test true}})]
      (is (= :failed (:status r)))
      (is (= "jdk.jshell.EvalException" (-> r :diagnostics first :exception))))))

(deftest java-assert-level-checks-truthiness
  (when available?
    (is (= :matched (:status (ev {:lang :java :source "2 + 2 == 4;" :attrs {:test true :level :assert}}))))
    (is (= :failed  (:status (ev {:lang :java :source "2 + 2 == 5;" :attrs {:test true :level :assert}}))))))
