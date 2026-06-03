(ns clj-book.cli-test
  (:require
   [clj-book.cli :as cli]
   [clj-book.build.request :as request]
   [clojure.test :refer [deftest is testing]]))

(def ^:private fixture "test/fixtures/synthetic/valid-book")

(defn- run-code
  "Run the CLI with stdout/stderr captured, returning the exit code."
  [argv]
  (binding [*out* (java.io.StringWriter.)
            *err* (java.io.StringWriter.)]
    (cli/run argv)))

(def ^:private args->request #'cli/args->request)

(deftest args->request-sets-only-supplied-keys
  (testing "Absent flags are omitted so normalize owns the defaults"
    (is (= {:book-root "."} (args->request "." {})))
    (is (= {:book-root "manual"
            :editions  [:screen]
            :dry-run   true
            :validate-code true}
           (args->request "manual"
                          {:edition [:screen] :dry-run true :validate-code true})))))

(deftest args->request-output-is-accepted-by-normalize
  (testing "The translation layer stays in sync with the normalize seam"
    (let [req (args->request fixture {:edition [:print]})]
      (is (= [:print] (:editions (request/normalize req :build))))
      (is (= fixture (:book-root (request/normalize req :build)))))))

(deftest no-command-prints-help-and-succeeds
  (is (= 0 (run-code [])))
  (is (= 0 (run-code ["--help"])))
  (is (= 0 (run-code ["-h"]))))

(deftest unknown-command-is-a-usage-error
  (is (= 2 (run-code ["frobnicate"]))))

(deftest subcommand-help-succeeds
  (is (= 0 (run-code ["build" "--help"])))
  (is (= 0 (run-code ["validate" "--help"])))
  (is (= 0 (run-code ["preview" "--help"]))))

(deftest bad-option-is-a-usage-error
  (is (= 2 (run-code ["build" fixture "--no-such-flag"])))
  (is (= 2 (run-code ["preview" fixture "--no-such-flag"]))))

(deftest dry-run-build-succeeds
  (testing "A dry-run build returns 0 and renders nothing"
    (is (= 0 (run-code ["build" fixture "--dry-run"])))))

(deftest validate-succeeds
  (is (= 0 (run-code ["validate" fixture]))))

(deftest unknown-edition-is-a-runtime-error
  (testing "A structured pipeline error maps to exit 1, not a stack trace"
    (is (= 1 (run-code ["build" fixture "--edition" "bogus" "--dry-run"])))))
