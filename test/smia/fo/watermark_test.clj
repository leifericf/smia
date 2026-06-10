(ns smia.fo.watermark-test
  (:require
   [smia.fo.watermark :as watermark]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import (java.util Base64)))

(def a4 {:text "BETA" :width-pt 595 :height-pt 842})

(deftest svg-carries-the-text-and-the-svg-namespace
  (let [s (watermark/svg a4)]
    (is (str/includes? s "http://www.w3.org/2000/svg"))
    (is (str/includes? s ">BETA<"))
    (is (str/includes? s "<svg"))))

(deftest svg-is-a-faint-rotated-diagonal
  (let [s (watermark/svg a4)]
    (testing "low opacity keeps it unobtrusive"
      (is (re-find #"fill-opacity=\"0\.[0-1]" s)))
    (testing "rotated about the page centre"
      (is (str/includes? s "rotate("))
      (is (str/includes? s "text-anchor=\"middle\"")))
    (testing "sized to the page so it spans every page"
      (is (str/includes? s "595"))
      (is (str/includes? s "842")))))

(deftest font-size-auto-fits-shorter-text-larger
  (let [short-fs (-> (watermark/svg {:text "X" :width-pt 595 :height-pt 842})
                     (->> (re-find #"font-size=\"(\d+)")) second Integer/parseInt)
        long-fs  (-> (watermark/svg {:text "BETA — Ada Lovelace Reviewer"
                                     :width-pt 595 :height-pt 842})
                     (->> (re-find #"font-size=\"(\d+)")) second Integer/parseInt)]
    (is (> short-fs long-fs)
        "a longer watermark string gets a smaller font so it still fits")))

(deftest explicit-font-size-and-color-win
  (let [s (watermark/svg (assoc a4 :font-size 40 :color "#112233" :opacity 0.2))]
    (is (str/includes? s "font-size=\"40\""))
    (is (str/includes? s "#112233"))
    (is (str/includes? s "fill-opacity=\"0.2\""))))

(deftest svg-output-is-deterministic
  (is (= (watermark/svg a4) (watermark/svg a4))))

(deftest data-uri-base64-encodes-the-svg
  (let [s   (watermark/svg a4)
        uri (watermark/data-uri s)]
    (is (str/starts-with? uri "data:image/svg+xml;base64,"))
    (let [b64 (subs uri (count "data:image/svg+xml;base64,"))
          decoded (String. (.decode (Base64/getDecoder) b64) "UTF-8")]
      (is (= s decoded)))))

(deftest background-image-value-wraps-the-data-uri
  (let [v (watermark/background-image (watermark/svg a4))]
    (is (str/starts-with? v "url('data:image/svg+xml;base64,"))
    (is (str/ends-with? v "')"))))
