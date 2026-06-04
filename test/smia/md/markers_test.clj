(ns smia.md.markers-test
  "The inline raw-escape marker registry: every registered marker builds its
   author node, the regex names are derived from the registry, and an
   unregistered marker is a structured error."
  (:require
   [smia.error :as error]
   [smia.md.markers :as markers]
   [clojure.test :refer [deftest is]]))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest each-marker-builds-its-node
  (is (= [:strong "x"] (markers/marker-form :hiccup "[:strong \"x\"]")))
  (is (= [:fo/block "raw"] (markers/marker-form :fo "[:fo/block \"raw\"]")))
  (is (= [:cite {:key :smith2020}] (markers/marker-form :cite " smith2020 ")))
  (is (= [:index {:term "Determinism"}] (markers/marker-form :index "Determinism")))
  (is (= [:math {:notation "e^{i\\pi}"}] (markers/marker-form :math "e^{i\\pi}"))))

(deftest a-string-kind-resolves-like-a-keyword
  (is (= [:cite {:key :x}] (markers/marker-form "cite" "x"))))

(deftest marker-names-are-the-sorted-registry-keys
  (is (= (->> (keys markers/inline-markers) (map name) sort vec)
         markers/marker-names))
  (is (= markers/marker-names (sort markers/marker-names))
      "the names are sorted so the derived regex is deterministic"))

(deftest unreadable-edn-payload-is-a-structured-error
  (let [d (catch-data #(markers/marker-form :hiccup "[:p \"unterminated"))]
    (is (= :smia.md.compile/invalid-raw-escape (:error/type d)))))

(deftest an-unregistered-marker-is-a-structured-error
  (let [d (catch-data #(markers/marker-form :bogus "x"))]
    (is (= :smia.md.compile/unknown-marker (:error/type d)))
    (is (= :bogus (get-in d [:error/context :marker])))))
