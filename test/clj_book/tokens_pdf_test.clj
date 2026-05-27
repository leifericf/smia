(ns clj-book.tokens-pdf-test
  (:require
   [clj-book.tokens :as tokens]
   [clj-book.tokens.pdf :as tokens-pdf]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def escape-root "test/fixtures/synthetic/escape-hatch-book")

(defn- read-tokens [root]
  (:tokens (tokens/load-tokens {:book-root root})))

(deftest tokens-compile-to-yaml
  (let [yml (tokens-pdf/compile-yaml
              {:book-root valid-root :tokens (read-tokens valid-root)})]
    (is (string? yml))
    (is (str/includes? yml "base:"))
    (is (str/includes? yml "font_color: '#111111'"))))

(deftest yaml-is-deterministic
  (testing "Same input yields byte-identical output"
    (let [t (read-tokens valid-root)
          a (tokens-pdf/compile-yaml {:book-root valid-root :tokens t})
          b (tokens-pdf/compile-yaml {:book-root valid-root :tokens t})]
      (is (= a b)))))

(deftest pdf-extras-deep-merged
  (testing "tier-3 pdf-theme.edn deep-merges on top of token-derived theme"
    (let [yml    (tokens-pdf/compile-yaml
                   {:book-root escape-root :tokens (read-tokens escape-root)})
          parsed (yaml/parse-string yml)]
      (is (= "#222222" (get-in parsed [:base :font_color]))
          "tier-3 overrides token-derived font_color")
      (is (= "logo.png" (get-in parsed [:extras :logo]))
          "tier-3 adds keys not present in token-derived theme"))))

(deftest absent-pdf-extras-leaves-yaml-unchanged
  (let [a (tokens-pdf/compile-yaml
            {:book-root valid-root :tokens (read-tokens valid-root)})]
    (is (str/includes? a "font_color: '#111111'"))
    (is (not (str/includes? a "logo")))))
