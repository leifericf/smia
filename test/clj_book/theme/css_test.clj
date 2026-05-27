(ns clj-book.theme.css-test
  (:require
   [clj-book.theme.css :as theme-css]
   [clj-book.theme.load :as theme]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def escape-root "test/fixtures/synthetic/escape-hatch-book")

(defn- read-tokens [root]
  (:tokens (theme/load-tokens {:book-root root})))

(deftest tokens-compile-to-css
  (let [css (theme-css/token-css (read-tokens valid-root))]
    (is (string? css))
    (is (str/includes? css ":root"))
    (is (str/includes? css "--color-bg"))
    (is (str/includes? css "#ffffff"))))

(deftest token-css-is-deterministic
  (testing "Same input yields byte-identical output"
    (let [t   (read-tokens valid-root)
          one (theme-css/token-css t)
          two (theme-css/token-css t)]
      (is (= one two)))))

(deftest site-clj-extras-appended
  (let [extras (theme/load-site-extras escape-root)
        css    (theme-css/compile-css
                 {:tokens (read-tokens escape-root) :extras extras})]
    (is (str/includes? css "--color-bg") "token-derived rules present")
    (is (str/includes? css "rebeccapurple") "Garden tier-3 rules present")
    (let [bg-idx    (.indexOf css "--color-bg")
          extra-idx (.indexOf css "rebeccapurple")]
      (is (< bg-idx extra-idx)
          "tier-3 rules appear after token-derived rules"))))

(deftest absent-site-clj-leaves-css-unchanged
  (let [extras (theme/load-site-extras valid-root)
        base   (theme-css/token-css (read-tokens valid-root))
        full   (theme-css/compile-css
                 {:tokens (read-tokens valid-root) :extras extras})]
    (is (nil? extras) "valid-book ships no site.clj")
    (is (= base full))))
