(ns clj-book.theme.pdf-test
  (:require
   [clj-book.theme.load :as theme]
   [clj-book.theme.pdf :as theme-pdf]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def valid-root  "test/fixtures/synthetic/valid-book")
(def escape-root "test/fixtures/synthetic/escape-hatch-book")

(defn- read-tokens [root]
  (:tokens (theme/load-tokens {:book-root root})))

(defn- compile-root
  "Load a root's tokens and pdf extras, then compile the YAML the way the
   PDF target shell does."
  [root]
  (theme-pdf/compile-yaml {:tokens  (read-tokens root)
                           :extras  (theme/load-pdf-extras root)}))

(deftest tokens-compile-to-yaml
  (let [yml (theme-pdf/compile-yaml {:tokens (read-tokens valid-root)})]
    (is (string? yml))
    (is (str/includes? yml "base:"))
    (is (str/includes? yml "font_color: '#111111'"))))

(deftest yaml-is-deterministic
  (testing "Same input yields byte-identical output"
    (let [t (read-tokens valid-root)
          a (theme-pdf/compile-yaml {:tokens t})
          b (theme-pdf/compile-yaml {:tokens t})]
      (is (= a b)))))

(deftest pdf-extras-deep-merged
  (testing "tier-3 pdf-theme.edn deep-merges on top of token-derived theme"
    (let [yml    (compile-root escape-root)
          parsed (yaml/parse-string yml)]
      (is (= "#222222" (get-in parsed [:base :font_color]))
          "tier-3 overrides token-derived font_color")
      (is (= "logo.png" (get-in parsed [:extras :logo]))
          "tier-3 adds keys not present in token-derived theme"))))

(deftest absent-pdf-extras-leaves-yaml-unchanged
  (let [a (compile-root valid-root)]
    (is (str/includes? a "font_color: '#111111'"))
    (is (not (str/includes? a "logo")))))
