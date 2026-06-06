(ns smia.site.search-snippet-test
  "Characterization of the folding-with-offsets algorithm the site search
   island uses to highlight a match inside a result snippet.

   The island itself is ClojureScript (`cljs/smia/site/search_client.cljs`)
   and has no ClojureScript unit harness, so this JVM test mirrors the
   island's pure `fold-with-offsets`/`snippet` logic to lock its contract:
   a query is matched against the accent-folded text, and its match
   boundaries are mapped back to the original text so the highlight lands
   on the right characters even when folding changed the string's length.
   Keep this in sync with the island."
  (:require
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.text Normalizer Normalizer$Form)
   (java.util Locale)))

(defn- normalize
  "The island's whole-string fold: lower-case, NFD-decompose, drop
   combining marks. (java.text NFD == JS String.prototype.normalize 'NFD';
   Locale/ROOT lower-casing matches JS's locale-independent toLowerCase.)"
  [s]
  (-> (.toLowerCase ^String s Locale/ROOT)
      (Normalizer/normalize Normalizer$Form/NFD)
      (.replaceAll "[\\u0300-\\u036f]" "")))

(defn- fold-with-offsets
  "Per-character fold returning [folded offsets], offsets[k] = index in s
   of the character the k-th folded character came from."
  [s]
  (loop [i 0, out "", offs []]
    (if (< i (count s))
      (let [f (normalize (subs s i (inc i)))]
        (recur (inc i) (str out f) (into offs (repeat (count f) i))))
      [out offs])))

(defn- marked
  "The original-text substring the island wraps in <mark> for query `q`
   (already normalized), or nil when `q` does not occur."
  [q text]
  (let [[tnorm offs] (fold-with-offsets text)
        i            (.indexOf ^String tnorm ^String q)]
    (when (>= i 0)
      (let [qn    (count q)
            start (nth offs i)
            end   (if (< (+ i qn) (count offs)) (nth offs (+ i qn)) (count text))]
        (subs text start end)))))

(deftest highlight-spans-the-matched-original-characters
  (testing "a plain ASCII match highlights exactly the matched word"
    (is (= "world" (marked (normalize "world") "hello world"))))
  (testing "a match after a decomposed accent lands on the right characters"
    ;; "école" with the acute accent as a standalone combining mark: the
    ;; fold drops the mark, so the folded text is one character shorter
    ;; than the original.
    (let [text (str "e" (char 0x0301) "cole")]
      (is (= "cole" (marked (normalize "cole") text)))
      (is (= (str "e" (char 0x0301)) (marked (normalize "e") text)))))
  (testing "a query that does not occur yields no highlight"
    (is (nil? (marked (normalize "zzz") "hello world")))))
