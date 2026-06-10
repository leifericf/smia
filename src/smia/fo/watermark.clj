(ns smia.fo.watermark
  "Pure core: build the beta-review watermark as a page-sized SVG and embed
   it as a base64 `data:` URI.

   The watermark rides on every page as the body region's `background-image`
   (set in `smia.theme.compile`): FOP paints a region background behind the
   text flow and repeats it on every page of a sequence, so a single
   background value marks the whole book — confirmed against FOP 2.11, where
   an `absolute-position=\"fixed\"` block renders on the first page only. A
   `data:` URI keeps the image inline in the FO, so no asset file is written.

   The SVG is a single faint, rotated line of text centred on the trim. Its
   font size auto-fits the page diagonal to the text length, so a long
   licensee-stamped watermark still fits. All inputs are numbers and strings;
   output is a deterministic string. No IO."
  (:require
   [clojure.string :as str])
  (:import (java.util Base64)))

(def defaults
  "Watermark styling defaults: a muted grey, low opacity (unobtrusive behind
   body and code), and a -45° diagonal."
  {:color "#888888" :opacity 0.12 :angle -45})

(defn- fitted-font-size
  "A font size (pt, integer) that fits `text` across most of the page
   diagonal: the rotated line spans the trim corner-to-corner, and an
   average sans-serif glyph is about 0.6 em wide. Clamped so a one- or
   two-letter watermark stays a tasteful size rather than filling the page."
  [text width-pt height-pt]
  (let [diagonal (Math/sqrt (+ (* width-pt width-pt) (* height-pt height-pt)))
        len      (max 1 (count text))
        raw      (/ (* 0.8 diagonal) (* 0.6 len))]
    (long (Math/round (min raw (* 0.22 (min width-pt height-pt)))))))

(defn- fmt
  "A locale-independent decimal string with trailing zeros trimmed, so the
   SVG serializes to identical bytes on any machine."
  [x]
  (let [s (String/format java.util.Locale/ROOT "%.3f" (object-array [(double x)]))]
    (-> s (str/replace #"0+$" "") (str/replace #"\.$" ""))))

(defn svg
  "The watermark SVG XML string for a page `width-pt` × `height-pt` (points).
   `:text` is the watermark line; `:color`, `:opacity`, and `:angle` override
   the styling `defaults`; `:font-size` (pt) overrides the auto-fit size."
  [{:keys [text width-pt height-pt color opacity angle font-size]}]
  (let [color   (or color (:color defaults))
        opacity (if (nil? opacity) (:opacity defaults) opacity)
        angle   (if (nil? angle) (:angle defaults) angle)
        cx (/ width-pt 2.0)
        cy (/ height-pt 2.0)
        fs (or font-size (fitted-font-size text width-pt height-pt))]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\""
         " width=\"" (fmt width-pt) "pt\" height=\"" (fmt height-pt) "pt\""
         " viewBox=\"0 0 " (fmt width-pt) " " (fmt height-pt) "\">"
         "<text x=\"" (fmt cx) "\" y=\"" (fmt cy) "\""
         " transform=\"rotate(" (fmt angle) " " (fmt cx) " " (fmt cy) ")\""
         " text-anchor=\"middle\""
         " font-family=\"sans-serif\""
         " font-size=\"" fs "\""
         " fill=\"" color "\" fill-opacity=\"" (fmt opacity) "\">"
         (-> text
             (str/replace "&" "&amp;")
             (str/replace "<" "&lt;")
             (str/replace ">" "&gt;"))
         "</text></svg>")))

(defn data-uri
  "Embed an SVG XML string as a base64 `data:image/svg+xml` URI."
  [svg-xml]
  (str "data:image/svg+xml;base64,"
       (.encodeToString (Base64/getEncoder) (.getBytes ^String svg-xml "UTF-8"))))

(defn background-image
  "The FO/CSS `background-image` value for an SVG XML string: a `url(...)`
   wrapping its `data:` URI."
  [svg-xml]
  (str "url('" (data-uri svg-xml) "')"))
