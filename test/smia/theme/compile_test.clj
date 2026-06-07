(ns smia.theme.compile-test
  (:require
   [smia.theme.compile :as theme]
   [clojure.test :refer [deftest is testing]]))

(def tokens
  {:color   {:text "#222222" :code-background "#eeeeee" :rule "#777777"}
   :type    {:body-family "Georgia" :heading-family "Helvetica"
             :mono-family "Menlo" :base-size "12pt" :h1-size "28pt"}
   :spacing {:paragraph "8pt"}
   :layout  {:page-size :letter :margin-inside "30mm" :margin-outside "18mm"}})

(deftest tokens-drive-the-style
  (let [{:keys [style]} (theme/compile-theme tokens :screen)]
    (testing "body typography comes from tokens"
      (is (= "Georgia" (-> style :body :font-family)))
      (is (= "12pt" (-> style :body :font-size)))
      (is (= "#222222" (-> style :body :color))))
    (testing "headings and code adopt token families/sizes"
      (is (= "Helvetica" (-> style :h1 :font-family)))
      (is (= "28pt" (-> style :h1 :font-size)))
      (is (= "Menlo" (-> style :code :font-family)))
      (is (= "#eeeeee" (-> style :pre :background-color))))
    (testing "paragraph spacing is token-driven"
      (is (= "8pt" (-> style :p :space-after))))))

(deftest code-size-token-sizes-block-code
  (testing "the default block-code size is preserved when no token is set"
    (is (= "9.5pt" (-> (theme/compile-theme tokens :screen) :style :pre :font-size))))
  (testing ":type {:code-size} sets the block-code font size"
    (let [themed (assoc-in tokens [:type :code-size] "8pt")]
      (is (= "8pt" (-> (theme/compile-theme themed :screen) :style :pre :font-size))))))

(deftest unknown-page-size-is-an-error
  (testing "an unknown trim name must not silently fall back to A4"
    (let [bad (assoc-in tokens [:layout :page-size] :a5)
          d   (try (theme/compile-theme bad :screen) nil
                   (catch Exception e (ex-data e)))]
      (is (= :smia.theme.compile/unknown-page-size (:error/type d)))
      (is (= :a5 (:page-size (:error/context d)))))))

(deftest screen-layout-has-one-symmetric-master
  (let [{:keys [masters master-reference]} (theme/compile-theme tokens :screen)
        spm (filter #(= :fo/simple-page-master (first %)) masters)]
    (is (= "book" master-reference))
    (is (= 1 (count spm)))
    (let [attrs (second (first spm))]
      (is (= "18mm" (:margin-left attrs)))
      (is (= "18mm" (:margin-right attrs)))
      (is (= "8.5in" (:page-width attrs)) "letter trim size from tokens"))))

(deftest print-layout-mirrors-recto-and-verso
  (let [{:keys [masters]} (theme/compile-theme tokens :print)
        by-name (into {} (for [m masters
                               :when (= :fo/simple-page-master (first m))]
                           [(:master-name (second m)) (second m)]))]
    (testing "recto binds on the left, verso on the right"
      (is (= "30mm" (:margin-left (by-name "book-recto"))))
      (is (= "18mm" (:margin-right (by-name "book-recto"))))
      (is (= "18mm" (:margin-left (by-name "book-verso"))))
      (is (= "30mm" (:margin-right (by-name "book-verso")))))
    (testing "a page-sequence-master selects them by odd/even"
      (is (some #(= :fo/page-sequence-master (first %)) masters)))))

(deftest fo-override-group-merges-over-the-compiled-style
  (let [{:keys [style]} (theme/compile-theme
                          (assoc tokens :fo {:h1 {:space-before "99pt"}
                                             :p  {:color "#bada55"}})
                          :screen)]
    (testing "the override wins"
      (is (= "99pt" (-> style :h1 :space-before)))
      (is (= "#bada55" (-> style :p :color))))
    (testing "compiled properties not overridden survive"
      (is (= "28pt" (-> style :h1 :font-size)))
      (is (= "8pt" (-> style :p :space-after))))))

;; --- justified, hyphenated body text ---------------------------------------

(def ^:private sparse {:color {} :type {} :spacing {} :layout {}})

(deftest body-text-is-justified-and-hyphenated-by-default
  (let [{:keys [style]} (theme/compile-theme sparse :print)]
    (doseq [tag [:p :li :dd :blockquote]]
      (testing (str tag " carries the canon text properties")
        (is (= "justify" (-> style tag :text-align)))
        (is (= "true" (-> style tag :hyphenate)))
        (is (= "2" (-> style tag :hyphenation-ladder-count)))))
    (testing "headings keep their own alignment"
      (is (nil? (-> style :h1 :text-align)))
      (is (nil? (-> style :h2 :text-align))))))

(deftest justify-token-turns-justification-off
  (let [{:keys [style]} (theme/compile-theme
                          (assoc-in sparse [:type :justify] false) :print)]
    (is (= "start" (-> style :p :text-align)))
    (is (= "true" (-> style :p :hyphenate))
        "ragged-right text still hyphenates unless told otherwise")))

(deftest hyphenate-token-turns-hyphenation-off
  (let [{:keys [style]} (theme/compile-theme
                          (assoc-in sparse [:type :hyphenate] false) :print)]
    (is (= "false" (-> style :p :hyphenate)))
    (is (= "justify" (-> style :p :text-align)))))

(deftest hyphenation-ladder-token-tunes-consecutive-hyphens
  (let [{:keys [style]} (theme/compile-theme
                          (assoc-in sparse [:type :hyphenation-ladder] 3) :print)]
    (is (= "3" (-> style :p :hyphenation-ladder-count)))))

(deftest defaults-apply-when-tokens-are-sparse
  (let [{:keys [style masters]} (theme/compile-theme
                                  {:color {} :type {} :spacing {} :layout {}}
                                  :screen)]
    (is (= "serif" (-> style :body :font-family)))
    (is (= "210mm" (:page-width (second (first masters))))
        "defaults to A4 when no page-size token is given")))
