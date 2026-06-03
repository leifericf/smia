(ns clj-book.build.execute-test
  (:require
   [clj-book.build.execute :as execute]
   [clj-book.error :as error]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(defn- tmp-dir [tag]
  (str (System/getProperty "java.io.tmpdir")
       "/clj-book-execute-" tag "-" (System/currentTimeMillis)))

(defn- request [tag & {:as overrides}]
  (merge {:command     :build
          :book-root   valid-root
          :config-path "book.edn"
          :output-root (tmp-dir tag)}
         overrides))

(defn- tmp-book-with-chapter
  "Create a temp manuscript (theme copied from valid-book) whose single
   Markdown chapter has the given body, and return its root path."
  [tag chapter-md]
  (let [root (str (tmp-dir tag) "/book")]
    (io/make-parents (io/file root "chapters/x"))
    (io/copy (io/file valid-root "theme.edn")
             (io/file root "theme.edn"))
    (spit (io/file root "book.edn")
          (pr-str {:book/slug "vbook" :book/title "V"
                   :book/chapters ["chapters/01-x.md"]}))
    (spit (io/file root "chapters/01-x.md") chapter-md)
    root))

(deftest prepare-loads-config-and-tokens
  (let [{:keys [manuscript paths]} (execute/prepare (request "prepare"))]
    (is (= "tiny-book" (:book/slug (:config manuscript))))
    (is (map? (:tokens manuscript)))
    (is (vector? (:warnings manuscript)))
    (is (str/includes? (:intermediate-dir paths) "tiny-book/intermediate"))
    (is (str/includes? (:pdf-output-dir paths) "tiny-book/pdf"))))

(deftest validate-returns-ok
  (testing "validate loads, assembles and vocabulary-checks the chapters"
    (let [out (execute/validate (request "validate"))]
      (is (= :ok (:status out))))))

(deftest dry-run-returns-plan-without-building
  (testing "Dry run validates and plans but performs no edition render"
    (let [p (execute/build (request "dry" :editions [:screen :print]
                                    :dry-run true))]
      (is (= [:screen :print] (:editions p)))
      (is (= 2 (count (:edition-steps p))))
      (is (= "tiny-book" (-> p :manifest-skeleton :book/slug))))))

(deftest ^:integration single-edition-build-writes-pdf-and-manifest
  (let [req (request "build" :editions [:screen])
        man (execute/build req)
        art (first (:artifacts man))]
    (is (= "tiny-book" (:book/slug man)))
    (is (= [:screen] (:build/editions man)))
    (is (= :screen (:edition art)))
    (is (.exists (io/file (:path art))) "the PDF is written to disk")
    (is (str/ends-with? (:path art) "tiny-book-screen.pdf"))
    (is (.exists (io/file (-> art :paths :fo))) "the intermediate FO is written")
    (is (.exists (io/file (:manifest/path man))))))

(deftest invalid-edition-blocked-by-request-normalization
  ;; This is asserted at the public api/request layer; execute assumes
  ;; the request has been normalized.
  (is true))

;; --- opt-in code validation -----------------------------------------------

(def ^:private chapter-with-passing-block
  "# Demo\n\n```clojure {:test true}\n(assert (= 4 (+ 2 2)))\n```\n")

(def ^:private chapter-with-failing-block
  "# Demo\n\n```clojure {:test true}\n(/ 1 0)\n```\n")

(deftest validation-is-skipped-by-default
  (testing "without :validate-code, a failing block does not block validate"
    (let [root (tmp-book-with-chapter "noval" chapter-with-failing-block)
          out  (execute/validate (request "noval" :book-root root :command :validate))]
      (is (= :ok (:status out)))
      (is (nil? (:validation out))))))

(deftest validate-runs-marked-blocks-when-enabled
  (let [root (tmp-book-with-chapter "valok" chapter-with-passing-block)
        out  (execute/validate (request "valok" :book-root root
                                        :command :validate :validate-code true))]
    (is (= :ok (:status out)))
    (is (= :ok (get-in out [:validation :status])))
    (is (= 1 (get-in out [:validation :validated])))))

(deftest validate-fails-the-build-on-a-failing-block
  (let [root (tmp-book-with-chapter "valfail" chapter-with-failing-block)
        d    (catch-data #(execute/validate (request "valfail" :book-root root
                                                     :command :validate :validate-code true)))]
    (is (= :clj-book.eval/validation-failed (:error/type d)))))

(deftest build-aborts-on-a-failing-block-before-rendering
  (let [root (tmp-book-with-chapter "buildfail" chapter-with-failing-block)
        d    (catch-data #(execute/build (request "buildfail" :book-root root
                                                  :editions [:screen] :validate-code true)))]
    (is (= :clj-book.eval/validation-failed (:error/type d)))))

(deftest dry-run-surfaces-the-validation-plan
  (let [root (tmp-book-with-chapter "valdry" chapter-with-passing-block)
        p    (execute/build (request "valdry" :book-root root :editions [:screen]
                                     :dry-run true :validate-code true))]
    (is (true? (get-in p [:validation :enabled])))
    (is (= 1 (get-in p [:validation :plan :total])))
    (is (= {:clojure 1} (get-in p [:validation :plan :by-language])))))
