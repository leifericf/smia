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

(deftest screen-layout-is-symmetric
  (let [{:keys [masters master-reference]} (theme/compile-theme tokens :screen)
        spm (filter #(= :fo/simple-page-master (first %)) masters)]
    (is (= "book" master-reference))
    (is (= 2 (count spm)) "the body master and the chapter-opener master")
    (doseq [m spm
            :let [attrs (second m)]]
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

;; --- paragraph style (indent vs space) --------------------------------------

(deftest indent-paragraphs-are-the-default
  (let [{:keys [style]} (theme/compile-theme sparse :print)]
    (testing "running paragraphs indent and drop the gap"
      (is (= "1em" (-> style :p :text-indent)))
      (is (= "0pt" (-> style :p :space-after))))
    (testing "the run opener sets flush but keeps the body text properties"
      (is (= "0" (-> style :p-first :text-indent)))
      (is (= "0pt" (-> style :p-first :space-after)))
      (is (= "justify" (-> style :p-first :text-align)))
      (is (= "true" (-> style :p-first :hyphenate))))
    (testing "list items never indent"
      (is (nil? (-> style :li :text-indent))))))

(deftest indent-width-token-sets-the-indent
  (let [{:keys [style]} (theme/compile-theme
                          (assoc-in sparse [:spacing :indent] "14pt") :print)]
    (is (= "14pt" (-> style :p :text-indent)))
    (is (= "0" (-> style :p-first :text-indent)))))

(deftest space-paragraph-style-restores-the-gap
  (let [{:keys [style]} (theme/compile-theme
                          (assoc-in sparse [:spacing :paragraph-style] :space)
                          :print)]
    (is (nil? (-> style :p :text-indent)))
    (is (= "6pt" (-> style :p :space-after)))
    (is (= "6pt" (-> style :p-first :space-after))
        "with gap paragraphs the opener renders like any other")))

(deftest explicit-paragraph-spacing-wins-in-either-mode
  (testing "indent mode honors an explicit gap"
    (let [{:keys [style]} (theme/compile-theme
                            (assoc-in sparse [:spacing :paragraph] "3pt") :print)]
      (is (= "3pt" (-> style :p :space-after)))
      (is (= "1em" (-> style :p :text-indent))))))

;; --- chapter-opener and blank-page masters -----------------------------------

(defn- spm-by-name [masters]
  (into {} (for [m masters
                 :when (= :fo/simple-page-master (first m))]
             [(:master-name (second m)) m])))

(defn- alternative-refs [masters]
  (let [psm  (first (filter #(= :fo/page-sequence-master (first %)) masters))
        alts (rest (last psm))]
    (mapv second alts)))

(deftest print-page-masters-select-blank-then-first-then-parity
  (let [refs (alternative-refs (:masters (theme/compile-theme sparse :print)))]
    (is (= ["book-blank" "book-first-recto" "book-first-verso"
            "book-recto" "book-verso"]
           (mapv :master-reference refs))
        "most specific first, in a fixed order")
    (is (= "blank" (:blank-or-not-blank (first refs))))
    (is (= "first" (:page-position (nth refs 1))))
    (is (= "odd" (:odd-or-even (nth refs 1))))
    (is (= "even" (:odd-or-even (nth refs 2))))))

(deftest first-page-masters-drop-the-header-but-keep-the-folio
  (let [by-name (spm-by-name (:masters (theme/compile-theme sparse :print)))
        regions (fn [m] (set (map first (drop 2 m))))]
    (testing "a chapter opener has no before-region to feed"
      (is (not (contains? (regions (by-name "book-first-recto"))
                          :fo/region-before)))
      (is (not (contains? (regions (by-name "book-first-verso"))
                          :fo/region-before))))
    (testing "its after-region reuses the parity footer name"
      (let [after (fn [m] (first (filter #(= :fo/region-after (first %))
                                         (drop 2 m))))]
        (is (= "foot-recto" (:region-name (second (after (by-name "book-first-recto"))))))
        (is (= "foot-verso" (:region-name (second (after (by-name "book-first-verso"))))))))
    (testing "the opener keeps the regular page geometry"
      (is (= (:margin-left (second (by-name "book-recto")))
             (:margin-left (second (by-name "book-first-recto"))))))))

(deftest blank-verso-master-has-only-a-body-region
  (let [by-name (spm-by-name (:masters (theme/compile-theme sparse :print)))
        blank   (by-name "book-blank")]
    (is (some? blank))
    (is (= [:fo/region-body] (mapv first (drop 2 blank)))
        "nothing to feed: an inserted verso renders truly empty")
    (testing "the blank page carries verso geometry"
      (is (= (:margin-left (second (by-name "book-verso")))
             (:margin-left (second blank)))))))

(deftest screen-gains-a-first-page-alternative
  (let [{:keys [masters master-reference]} (theme/compile-theme sparse :screen)
        refs (alternative-refs masters)]
    (is (= "book" master-reference))
    (is (= ["book-first" "book-page"] (mapv :master-reference refs)))
    (is (= "first" (:page-position (first refs))))
    (testing "no blank master: screen has no parity-inserted versos"
      (is (nil? (some :blank-or-not-blank refs))))))

;; --- canon page geometry (2:3:4:6) -------------------------------------------

(deftest canon-margins-derive-2-3-4-6-from-the-trim
  (testing "digest: the Van de Graaf construction at 2/3 coverage"
    (is (= {:margin-inside    "15.6mm"
            :margin-top       "23.3mm"
            :margin-outside   "31.1mm"
            :margin-bottom    "46.7mm"
            :margin-symmetric "23.3mm"}
           (theme/canon-margins "140mm" 2/3))))
  (testing "a4"
    (is (= {:margin-inside    "23.3mm"
            :margin-top       "35.0mm"
            :margin-outside   "46.7mm"
            :margin-bottom    "70.0mm"
            :margin-symmetric "35.0mm"}
           (theme/canon-margins "210mm" 2/3))))
  (testing "inch trims convert"
    (is (= "36.0mm" (:margin-top (theme/canon-margins "8.5in" 2/3))))))

(deftest canon-margin-errors-are-structured
  (testing "an unknown unit"
    (let [d (try (theme/canon-margins "140vw" 2/3) nil
                 (catch Exception e (ex-data e)))]
      (is (= :smia.theme.compile/invalid-length (:error/type d)))))
  (testing "a coverage outside (0, 1)"
    (let [d (try (theme/canon-margins "140mm" 1.2) nil
                 (catch Exception e (ex-data e)))]
      (is (= :smia.theme.compile/invalid-coverage (:error/type d))))))

(deftest print-masters-fall-back-to-canon-margins-per-key
  (let [{:keys [masters]} (theme/compile-theme sparse :print)
        recto (second (get (spm-by-name masters) "book-recto"))]
    (is (= "23.3mm" (:margin-left recto)) "inside = 2 units")
    (is (= "46.7mm" (:margin-right recto)) "outside = 4 units")
    (is (= "35.0mm" (:margin-top recto)) "top = 3 units")
    (is (= "70.0mm" (:margin-bottom recto)) "bottom = 6 units"))
  (testing "one explicit key wins; the rest stay canon"
    (let [{:keys [masters]} (theme/compile-theme
                              (assoc-in sparse [:layout :margin-top] "20mm")
                              :print)
          recto (second (get (spm-by-name masters) "book-recto"))]
      (is (= "20mm" (:margin-top recto)))
      (is (= "23.3mm" (:margin-left recto))))))

(deftest screen-canon-sides-are-symmetric-at-the-same-coverage
  (let [{:keys [masters]} (theme/compile-theme sparse :screen)
        page (second (get (spm-by-name masters) "book-page"))]
    (is (= "35.0mm" (:margin-left page)) "(1 - coverage)/2 per side = 3 units")
    (is (= "35.0mm" (:margin-right page)))
    (is (= "70.0mm" (:margin-bottom page)))))

(deftest text-coverage-token-tunes-the-canon
  (let [{:keys [masters]} (theme/compile-theme
                            (assoc-in sparse [:layout :text-coverage] 0.7)
                            :print)
        recto (second (get (spm-by-name masters) "book-recto"))]
    (is (= "21.0mm" (:margin-left recto)))
    (is (= "63.0mm" (:margin-bottom recto)))))

;; --- chapter drop -------------------------------------------------------------

(deftest chapter-drop-is-themed-with-a-canon-default
  (is (= "72pt" (:chapter-drop (theme/compile-theme sparse :print))))
  (is (= "50mm" (:chapter-drop (theme/compile-theme
                                 (assoc-in sparse [:layout :chapter-drop] "50mm")
                                 :print)))))

;; --- vertical rhythm ----------------------------------------------------------

(deftest heading-spaces-are-multiples-of-the-body-leading
  ;; default leading: 11pt base x 1.4 line-height = 15.4pt
  (let [{:keys [style]} (theme/compile-theme sparse :print)]
    (is (= "30.8pt" (-> style :h1 :space-before)) "2 leadings")
    (is (= "23.1pt" (-> style :h2 :space-before)) "1.5 leadings")
    (is (= "7.7pt"  (-> style :h2 :space-after))  "half a leading")
    (is (= "15.4pt" (-> style :h3 :space-before)) "one leading"))
  (testing "the rhythm follows the type tokens"
    (let [{:keys [style]} (theme/compile-theme
                            (update sparse :type assoc
                                    :base-size "12pt" :line-height "1.5")
                            :print)]
      (is (= "27.0pt" (-> style :h2 :space-before)) "1.5 x 18pt"))))

(deftest css-flavored-type-tokens-keep-the-rhythm
  (testing "a px base size is a valid CSS length the site shares"
    ;; 16px = 12pt, x 1.4 = 16.8pt leading; h2 sits at 1.5 leadings
    (let [{:keys [style]} (theme/compile-theme
                            (assoc-in sparse [:type :base-size] "16px")
                            :print)]
      (is (= "25.2pt" (-> style :h2 :space-before)))))
  (testing "a percentage line-height is a ratio of the base size"
    ;; 11pt x 140% = 15.4pt leading, as with 1.4
    (let [{:keys [style]} (theme/compile-theme
                            (assoc-in sparse [:type :line-height] "140%")
                            :print)]
      (is (= "23.1pt" (-> style :h2 :space-before))))))

(deftest heading-rhythm-token-overrides-multiples-per-level
  (let [{:keys [style]} (theme/compile-theme
                          (assoc-in sparse [:spacing :heading-rhythm]
                                    {:h2 [2 1]})
                          :print)]
    (is (= "30.8pt" (-> style :h2 :space-before)))
    (is (= "15.4pt" (-> style :h2 :space-after)))
    (testing "other levels keep the canon multiples"
      (is (= "15.4pt" (-> style :h3 :space-before))))))

(deftest widows-and-orphans-default-to-two
  (let [{:keys [style]} (theme/compile-theme sparse :print)]
    (is (= "2" (-> style :p :widows)))
    (is (= "2" (-> style :p :orphans)))
    (is (= "2" (-> style :p-first :widows)) "the run opener paginates alike"))
  (testing "the :type tokens tune them"
    (let [{:keys [style]} (theme/compile-theme
                            (update sparse :type assoc :widows 3 :orphans 3)
                            :print)]
      (is (= "3" (-> style :p :widows)))
      (is (= "3" (-> style :p :orphans))))))

;; --- running-head furniture ----------------------------------------------------

(deftest running-heads-are-uppercase-and-letterspaced
  ;; FOP has no font-variant small-caps; uppercase with letter tracking is
  ;; the closest classical running-head treatment it can render.
  (let [theme (theme/compile-theme sparse :print)]
    (is (= "uppercase" (-> theme :running-head :text-transform)))
    (is (= "0.08em" (-> theme :running-head :letter-spacing))))
  (testing "a :type :running-head map overrides per property"
    (let [theme (theme/compile-theme
                  (assoc-in sparse [:type :running-head]
                            {:letter-spacing "0.1em"})
                  :print)]
      (is (= "0.1em" (-> theme :running-head :letter-spacing)))
      (is (= "uppercase" (-> theme :running-head :text-transform))))))

(deftest defaults-apply-when-tokens-are-sparse
  (let [{:keys [style masters]} (theme/compile-theme
                                  {:color {} :type {} :spacing {} :layout {}}
                                  :screen)]
    (is (= "serif" (-> style :body :font-family)))
    (is (= "210mm" (:page-width (second (first masters))))
        "defaults to A4 when no page-size token is given")))
