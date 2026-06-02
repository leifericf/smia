(ns clj-book.dogfood-test
  "Lightweight checks that the dogfood manual resolves. The full
   render-to-PDF regression lives in clj-book.characterization-test."
  (:require
   [clj-book.config :as config]
   [clj-book.theme.load :as theme]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def manual-root "docs/manual")

(deftest manual-fixture-resolves
  (let [{:keys [config warnings]} (config/load-config
                                    {:book-root manual-root
                                     :config-path "book.edn"})
        {:keys [tokens]}          (theme/load-tokens
                                    {:book-root manual-root})]
    (is (= "clj-book-manual" (:book/slug config)))
    (is (every? #(.exists (io/file manual-root %))
                (:book/chapters config)))
    (is (every? #(or (str/ends-with? % ".md") (str/ends-with? % ".clj"))
                (:book/chapters config))
        "chapters are Markdown or Clojure Hiccup files")
    (is (every? #(contains? tokens %) theme/required-groups))
    (is (vector? warnings))))
