(ns smia.assets-license-test
  "Licensing guard for the print assets the manual ships: the fonts are
   OFL (their license text must travel beside them — the OFL requires
   it, and they must never be relicensed under the project's EPL), and
   the ICC profile carries its public-domain notice. Every asset
   `manual/book.edn` references must exist."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private font-dirs
  ["manual/assets/fonts/crimson-text"
   "manual/assets/fonts/space-mono"])

(deftest every-shipped-font-family-carries-the-ofl
  (doseq [dir font-dirs]
    (let [license (io/file dir "OFL.txt")]
      (is (.exists license) (str dir " ships its license"))
      (is (str/includes? (slurp license) "SIL Open Font License")
          (str dir "/OFL.txt is the OFL")))
    (testing (str dir " actually contains fonts")
      (is (seq (filter #(str/ends-with? (.getName ^java.io.File %) ".ttf")
                       (.listFiles (io/file dir))))))))

(deftest icc-profile-carries-its-notice
  (is (.exists (io/file "manual/assets/icc/sRGB-v2.icc")))
  (let [notice (io/file "manual/assets/icc/NOTICE.txt")]
    (is (.exists notice))
    (is (str/includes? (slurp notice) "CC0"))))

(deftest manual-print-x-config-references-existing-assets
  (let [config  (edn/read-string (slurp "manual/book.edn"))
        print-x (:book/print-x config)]
    (is (map? print-x) "the manual configures the print-x edition")
    (is (.exists (io/file "manual" (get-in print-x [:output-intent :icc]))))
    (doseq [font (:fonts print-x)
            [style path] (dissoc font :family)]
      (is (.exists (io/file "manual" path))
          (str (:family font) " " (name style) " font file exists")))))
