(ns smia.math.resolve-test
  "The render half is gated on the optional JLaTeXMath dependency; run with
   `-A:math` to exercise it. Without it those tests skip (assert true) and
   the error path is exercised instead, so the default suite stays green."
  (:require
   [smia.error :as error]
   [smia.math.resolve :as resolve]
   [clojure.test :refer [deftest is testing]]))

(def ^:private available?
  (try (Class/forName "org.scilab.forge.jlatexmath.TeXFormula") true
       (catch Throwable _ false)))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(defn- manuscript-with [chapter]
  {:title    "T"
   :sections [{:role :body :content chapter}]
   :chapters [chapter]})

(def ^:private mathless
  (manuscript-with [:chapter {:id :x :title "X"} [:p "no math here"]]))

(def ^:private mathful
  (manuscript-with [:chapter {:id :x :title "X"}
                    [:p "Euler: " [:math {:notation "x^2"}]]
                    [:math {:notation "\\frac{a}{b}" :display true}]]))

(defn- math-nodes [form]
  (filter #(and (vector? %) (= :math (first %)))
          (tree-seq vector? seq form)))

(defn- section-math [manuscript]
  (math-nodes (mapv :content (:sections manuscript))))

(deftest a-manuscript-without-math-is-untouched
  (is (= mathless (resolve/attach-svg mathless))
      "no math, no renderer load, no change"))

(deftest rendered-svg-accessor-throws-without-a-stamp
  (let [d (catch-data #(resolve/rendered-svg {:notation "x^2"}))]
    (is (= :smia.math/renderer-unavailable (:error/type d)))
    (is (= ":math" (get-in d [:error/context :requires])))))

(deftest math-nodes-get-svg-stamped
  (if available?
    (let [out   (resolve/attach-svg mathful)
          nodes (section-math out)]
      (is (= 2 (count nodes)))
      (doseq [[_ a] nodes]
        (is (vector? (:svg a)))
        (is (= :svg (first (:svg a))))
        (is (map? (second (:svg a)))))
      (testing ":chapters and :sections agree"
        (is (= (section-math out)
               (math-nodes (:chapters out))))))
    (is true "JLaTeXMath not on the classpath (run with -A:math); skipped")))

(deftest rendering-is-deterministic
  (when available?
    (is (= (resolve/attach-svg mathful) (resolve/attach-svg mathful))
        "the same manuscript renders to identical SVG twice")))

(deftest identical-formulas-share-one-rendering
  (when available?
    (let [m   (manuscript-with [:chapter {:id :x :title "X"}
                                [:p [:math {:notation "x^2"}]]
                                [:p [:math {:notation "x^2"}]]])
          out (resolve/attach-svg m)
          [a b] (map second (section-math out))]
      (is (= (:svg a) (:svg b))))))

(deftest distinct-formulas-do-not-collide
  (when available?
    (let [out (resolve/attach-svg mathful)
          [a b] (map second (section-math out))]
      (is (not= (:svg a) (:svg b))))))

(deftest missing-renderer-is-a-structured-error
  (when-not available?
    (let [d (catch-data #(resolve/attach-svg mathful))]
      (is (= :smia.math/renderer-unavailable (:error/type d)))
      (is (= ":math" (get-in d [:error/context :requires]))))))
