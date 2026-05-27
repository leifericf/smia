(ns clj-book.dogfood-test
  (:require
   [clj-book.config :as config]
   [clj-book.theme.load :as theme]
   [clojure.java.io :as io]
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
    (is (every? #(contains? tokens %) theme/required-groups))
    (is (vector? warnings))))

(deftest tier-3-files-present
  (is (.exists (io/file manual-root "styles" "site.clj"))
      "site.clj is shipped to dogfood the tier-3 site hatch")
  (is (.exists (io/file manual-root "styles" "pdf-theme.edn"))
      "pdf-theme.edn is shipped to dogfood the tier-3 PDF hatch"))
