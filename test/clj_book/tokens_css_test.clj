(ns clj-book.tokens-css-test
  (:require
   [clj-book.tokens :as tokens]
   [clj-book.tokens.css :as tokens-css]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def escape-root "test/fixtures/synthetic/escape-hatch-book")

(defn- read-tokens [root]
  (:tokens (tokens/load-tokens {:book-root root})))

(deftest tokens-compile-to-css
  (let [css (tokens-css/token-css (read-tokens valid-root))]
    (is (string? css))
    (is (str/includes? css ":root"))
    (is (str/includes? css "--color-bg"))
    (is (str/includes? css "#ffffff"))))

(deftest token-css-is-deterministic
  (testing "Same input yields byte-identical output"
    (let [t   (read-tokens valid-root)
          one (tokens-css/token-css t)
          two (tokens-css/token-css t)]
      (is (= one two)))))

(deftest site-clj-extras-appended
  (let [css (tokens-css/compile-css
              {:book-root escape-root :tokens (read-tokens escape-root)})]
    (is (str/includes? css "--color-bg") "token-derived rules present")
    (is (str/includes? css "rebeccapurple") "Garden tier-3 rules present")
    (let [bg-idx    (.indexOf css "--color-bg")
          extra-idx (.indexOf css "rebeccapurple")]
      (is (< bg-idx extra-idx)
          "tier-3 rules appear after token-derived rules"))))

(deftest absent-site-clj-leaves-css-unchanged
  (let [base  (tokens-css/token-css (read-tokens valid-root))
        full  (tokens-css/compile-css
                {:book-root valid-root :tokens (read-tokens valid-root)})]
    (is (= base full))))
