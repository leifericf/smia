(ns smia.error-test
  (:require
   [smia.error :as error]
   [clojure.test :refer [deftest is testing]]))

(deftest ex-builds-structured-ex-info
  (testing "Constructor produces ex-info with structured payload"
    (let [e (error/ex :sample/type "Sample message" {:context :value})]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= "Sample message" (.getMessage e)))
      (let [d (ex-data e)]
        (is (= :sample/type (:error/type d)))
        (is (= "Sample message" (:error/message d)))
        (is (= {:context :value} (:error/context d)))))))

(deftest data-returns-payload-or-nil
  (testing "data extracts the structured payload"
    (let [e (error/ex :a/b "msg" {:k :v})]
      (is (= :a/b (:error/type (error/data e))))))
  (testing "data returns nil for non-structured exceptions"
    (is (nil? (error/data (ex-info "plain" {}))))
    (is (nil? (error/data (RuntimeException. "boom"))))))

(deftest report-lines-renders-structured-and-fallback
  (testing "a structured error renders message, type, and context lines"
    (is (= ["error: boom"
            "       type: :a/b"
            "       context: {:k 1}"]
           (error/report-lines (error/ex :a/b "boom" {:k 1})))))
  (testing "an empty context drops the context line"
    (is (= ["error: boom"
            "       type: :a/b"]
           (error/report-lines (error/ex :a/b "boom")))))
  (testing "a non-structured throwable renders the fallback line"
    (is (= ["unexpected error: nope"]
           (error/report-lines (RuntimeException. "nope"))))))
