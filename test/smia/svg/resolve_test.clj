(ns smia.svg.resolve-test
  "The render halves are gated on the optional dependencies; run with
   `-A:math` / `-A:diagrams` to exercise them. Without them those tests
   skip (assert true) and the error paths are exercised instead, so the
   default suite stays green."
  (:require
   [smia.error :as error]
   [smia.svg.resolve :as resolve]
   [clojure.test :refer [deftest is testing]]))

(def ^:private math-available?
  (try (Class/forName "org.scilab.forge.jlatexmath.TeXFormula") true
       (catch Throwable _ false)))

(def ^:private diagrams-available?
  (try (Class/forName "net.sourceforge.plantuml.SourceStringReader") true
       (catch Throwable _ false)))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(defn- manuscript-with [chapter]
  {:title    "T"
   :sections [{:role :body :content chapter}]
   :chapters [chapter]})

(def ^:private plain
  (manuscript-with [:chapter {:id :x :title "X"} [:p "nothing to render"]]))

(def ^:private mathful
  (manuscript-with [:chapter {:id :x :title "X"}
                    [:p "Euler: " [:math {:notation "x^2"}]]
                    [:math {:notation "\\frac{a}{b}" :display true}]]))

(def ^:private diagramful
  (manuscript-with [:chapter {:id :x :title "X"}
                    [:diagram {:source "A -> B" :alt "A calls B"}]]))

(defn- nodes-of [tag form]
  (filter #(and (vector? %) (= tag (first %)))
          (tree-seq vector? seq form)))

(defn- section-nodes [tag manuscript]
  (nodes-of tag (mapv :content (:sections manuscript))))

;; --- the pass is inert without renderable nodes -----------------------------

(deftest a-manuscript-without-renderables-is-untouched
  (is (= plain (resolve/attach-svg plain))
      "nothing to render, no renderer load, no change"))

(deftest a-mermaid-diagram-is-not-rendered-at-build-time
  ;; A client-rendered (:engine :mermaid) diagram is left untouched — no SVG
  ;; is stamped and no diagram renderer is loaded.
  (let [mermaidful (manuscript-with [:chapter {:id :x :title "X"}
                                     [:diagram {:engine :mermaid :source "graph TD"}]])]
    (is (= mermaidful (resolve/attach-svg mermaidful))
        "the engine marks it as client-rendered, so the pass skips it")))

(deftest rendered-svg-accessor-throws-without-a-stamp
  (testing "math names the :math alias"
    (let [d (catch-data #(resolve/rendered-svg :math {:notation "x^2"}))]
      (is (= :smia.math/renderer-unavailable (:error/type d)))
      (is (= ":math" (get-in d [:error/context :requires])))))
  (testing "diagrams name the :diagrams alias"
    (let [d (catch-data #(resolve/rendered-svg :diagram {:source "A -> B"}))]
      (is (= :smia.diagram/renderer-unavailable (:error/type d)))
      (is (= ":diagrams" (get-in d [:error/context :requires]))))))

;; --- math ---------------------------------------------------------------------

(deftest math-nodes-get-svg-stamped
  (if math-available?
    (let [out   (resolve/attach-svg mathful)
          nodes (section-nodes :math out)]
      (is (= 2 (count nodes)))
      (doseq [[_ a] nodes]
        (is (vector? (:svg a)))
        (is (= :svg (first (:svg a))))
        (is (map? (second (:svg a)))))
      (testing ":chapters and :sections agree"
        (is (= (section-nodes :math out)
               (nodes-of :math (:chapters out))))))
    (is true "JLaTeXMath not on the classpath (run with -A:math); skipped")))

(deftest math-rendering-is-deterministic
  (when math-available?
    (is (= (resolve/attach-svg mathful) (resolve/attach-svg mathful))
        "the same manuscript renders to identical SVG twice")))

(deftest identical-formulas-share-one-rendering
  (when math-available?
    (let [m   (manuscript-with [:chapter {:id :x :title "X"}
                                [:p [:math {:notation "x^2"}]]
                                [:p [:math {:notation "x^2"}]]])
          out (resolve/attach-svg m)
          [a b] (map second (section-nodes :math out))]
      (is (= (:svg a) (:svg b))))))

(deftest distinct-formulas-do-not-collide
  (when math-available?
    (let [out (resolve/attach-svg mathful)
          [a b] (map second (section-nodes :math out))]
      (is (not= (:svg a) (:svg b))))))

(deftest invalid-math-notation-is-a-structured-error
  (when math-available?
    (let [m (manuscript-with [:chapter {:id :x :title "X"}
                              [:math {:notation "\\nonsense{x}" :display true}]])
          d (catch-data #(resolve/attach-svg m))]
      (is (= :smia.math/render-failed (:error/type d))
          "a malformed formula surfaces a structured error, not a raw exception")
      (is (= "\\nonsense{x}" (get-in d [:error/context :notation]))
          "the error names the offending notation"))))

(deftest missing-math-renderer-is-a-structured-error
  (when-not math-available?
    (let [d (catch-data #(resolve/attach-svg mathful))]
      (is (= :smia.math/renderer-unavailable (:error/type d)))
      (is (= ":math" (get-in d [:error/context :requires]))))))

;; --- diagrams --------------------------------------------------------------------

(deftest diagram-nodes-get-svg-stamped
  (if diagrams-available?
    (let [out     (resolve/attach-svg diagramful)
          [[_ a]] (section-nodes :diagram out)]
      (is (vector? (:svg a)))
      (is (= :svg (first (:svg a))))
      (is (map? (second (:svg a)))))
    (is true "PlantUML not on the classpath (run with -A:diagrams); skipped")))

(deftest diagram-rendering-is-deterministic
  (when diagrams-available?
    (is (= (resolve/attach-svg diagramful) (resolve/attach-svg diagramful)))))

(deftest missing-diagram-renderer-is-a-structured-error
  (when-not diagrams-available?
    (let [d (catch-data #(resolve/attach-svg diagramful))]
      (is (= :smia.diagram/renderer-unavailable (:error/type d)))
      (is (= ":diagrams" (get-in d [:error/context :requires]))))))
