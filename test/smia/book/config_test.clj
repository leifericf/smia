(ns smia.book.config-test
  (:require
   [smia.book.config :as config]
   [smia.error :as error]
   [clojure.test :refer [deftest is testing]]))

(def valid-root "test/fixtures/synthetic/valid-book")
(def invalid-root "test/fixtures/synthetic/invalid-book")
(def missing-chapter-root "test/fixtures/synthetic/missing-chapter-book")

(defn- catch-data [f]
  (try (f) nil
       (catch Exception e
         (error/data e))))

(deftest missing-required-key-fails
  (let [d (catch-data
            #(config/load-config {:book-root invalid-root
                                  :config-path "book.edn"}))]
    (is (= :smia.book.config/missing-required-key (:error/type d)))
    (is (some #{:book/slug} (:missing (:error/context d))))))

(deftest additional-keys-pass
  (testing "open-map shape preserves custom keys"
    (let [{:keys [config]} (config/load-config
                             {:book-root valid-root
                              :config-path "book.edn"})]
      (is (contains? config :custom/extension)))))

(deftest required-keys-loaded
  (let [{:keys [config warnings]} (config/load-config
                                    {:book-root valid-root
                                     :config-path "book.edn"})]
    (is (= "tiny-book" (:book/slug config)))
    (is (string? (:book/title config)))
    (is (vector? (:book/chapters config)))
    (is (vector? warnings))))

(deftest missing-chapter-fails
  (let [d (catch-data
            #(config/load-config {:book-root missing-chapter-root
                                  :config-path "book.edn"}))]
    (is (= :smia.book.config/missing-chapter (:error/type d)))
    (is (some #{"chapters/does-not-exist.clj"}
              (:missing (:error/context d))))))

(deftest duplicate-chapter-rejected
  (testing "Manuscript validation owns chapter uniqueness (pure)"
    (let [d (catch-data
              #(config/validate {:book/slug "x" :book/title "t"
                                 :book/chapters ["a.adoc" "a.adoc"]}
                                "book.edn"))]
      (is (= :smia.book.config/duplicate-chapter (:error/type d)))
      (is (= ["a.adoc"] (:duplicates (:error/context d)))))))

(deftest valid-downloads-passes
  (is (vector? (config/validate
                 {:book/slug "x" :book/title "t" :book/chapters ["a.md"]
                  :book/downloads
                  {:base "https://example.com/dl"
                   :assets [{:label "Screen PDF" :file "s.pdf"
                             :note "For screen." :default true}
                            {:label "EPUB" :file "b.epub"}]}}
                 "book.edn"))))

(deftest malformed-downloads-rejected
  (testing "two assets flagged :default"
    (let [d (catch-data
              #(config/validate
                 {:book/slug "x" :book/title "t" :book/chapters ["a.md"]
                  :book/downloads {:base "u"
                                   :assets [{:label "a" :file "a" :default true}
                                            {:label "b" :file "b" :default true}]}}
                 "book.edn"))]
      (is (= :smia.book.config/invalid-downloads (:error/type d)))))
  (testing "an asset missing :file"
    (let [d (catch-data
              #(config/validate
                 {:book/slug "x" :book/title "t" :book/chapters ["a.md"]
                  :book/downloads {:base "u" :assets [{:label "a"}]}}
                 "book.edn"))]
      (is (= :smia.book.config/invalid-downloads (:error/type d)))))
  (testing "a non-string :base"
    (let [d (catch-data
              #(config/validate
                 {:book/slug "x" :book/title "t" :book/chapters ["a.md"]
                  :book/downloads {:base 5 :assets [{:label "a" :file "f"}]}}
                 "book.edn"))]
      (is (= :smia.book.config/invalid-downloads (:error/type d))))))

(deftest config-file-not-found
  (let [d (catch-data
            #(config/load-config {:book-root valid-root
                                  :config-path "nope.edn"}))]
    (is (= :smia.book.config/missing (:error/type d)))))

;; --- structured manuscripts (parts, matter, appendices) -------------------

(deftest parts-satisfy-the-body-requirement
  (testing "a book may declare its body as :book/parts instead of :book/chapters"
    (let [warnings (config/validate
                     {:book/slug "s" :book/title "t"
                      :book/parts [{:part/title "P" :part/chapters ["a.md" "b.md"]}]}
                     "book.edn")]
      (is (vector? warnings)))))

(deftest a-body-is-required
  (let [d (catch-data
            #(config/validate {:book/slug "s" :book/title "t"} "book.edn"))]
    (is (= :smia.book.config/missing-required-key (:error/type d)))))

(deftest chapters-and-parts-are-mutually-exclusive
  (let [d (catch-data
            #(config/validate {:book/slug "s" :book/title "t"
                               :book/chapters ["a.md"]
                               :book/parts [{:part/title "P" :part/chapters ["b.md"]}]}
                              "book.edn"))]
    (is (= :smia.book.config/ambiguous-body (:error/type d)))))

(deftest malformed-part-is-rejected
  (let [d (catch-data
            #(config/validate {:book/slug "s" :book/title "t"
                               :book/parts [{:part/title "P"}]}
                              "book.edn"))]
    (is (= :smia.book.config/invalid-type (:error/type d)))))

(deftest matter-without-file-needs-a-generated-role
  (testing ":preface has no generated content, so it must name a :file"
    (let [d (catch-data
              #(config/validate {:book/slug "s" :book/title "t"
                                 :book/chapters ["a.md"]
                                 :book/back-matter [{:role :preface}]}
                                "book.edn"))]
      (is (= :smia.book.config/invalid-matter (:error/type d)))))
  (testing ":bibliography and :index are generated, so they need no file"
    (is (vector? (config/validate
                   {:book/slug "s" :book/title "t"
                    :book/chapters ["a.md"]
                    :book/back-matter [{:role :bibliography} {:role :index}]}
                   "book.edn"))))
  (testing "the lists of figures, tables, and listings are generated too"
    (is (vector? (config/validate
                   {:book/slug "s" :book/title "t"
                    :book/chapters ["a.md"]
                    :book/front-matter [{:role :list-of-figures}
                                        {:role :list-of-tables}
                                        {:role :list-of-listings}]}
                   "book.edn")))))

(deftest duplicate-files-across-the-structure-are-rejected
  (let [d (catch-data
            #(config/validate {:book/slug "s" :book/title "t"
                               :book/front-matter [{:role :preface :file "x.md"}]
                               :book/chapters ["x.md"]}
                              "book.edn"))]
    (is (= :smia.book.config/duplicate-chapter (:error/type d)))
    (is (= ["x.md"] (:duplicates (:error/context d))))))
