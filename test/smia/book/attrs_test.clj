(ns smia.book.attrs-test
  (:require
   [smia.book.attrs :as attrs]
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

;; --- the per-chapter context -----------------------------------------------

(deftest context-precedence-book-then-front-matter-then-build
  (let [ctx (attrs/resolve-context
              {:version "1.0" :title "Book" :author "A"}
              {:title "Chapter" :note "fm"}
              {:licensee "ACME" :language nil})]
    (is (= "1.0" (:version ctx)) "a book attribute survives")
    (is (= "Chapter" (:title ctx)) "front-matter overrides the book")
    (is (= "fm" (:note ctx)) "front-matter adds keys")
    (is (= "ACME" (:licensee ctx)) "build context is highest")
    (is (not (contains? ctx :language)) "a nil build value never shadows")))

;; --- single-form substitution ----------------------------------------------

(deftest substitute-resolves-string-and-hiccup-values
  (let [ctx {:version "1.0" :brand [:strong "Smia"]}]
    (is (= [:p "Version " "1.0" "."]
           (attrs/substitute-form [:p "Version " [:attr :version] "."] ctx)))
    (is (= [:p "Built with " [:strong "Smia"]]
           (attrs/substitute-form [:p "Built with " [:attr :brand]] ctx)))))

(deftest an-unknown-attribute-is-a-structured-error
  (let [d (catch-data #(attrs/substitute-form [:p [:attr :missing]] {:version "1"}))]
    (is (= :smia.book.attrs/unknown-attribute (:error/type d)))
    (is (= :missing (get-in d [:error/context :attribute])))))

(deftest a-malformed-attribute-reference-is-a-structured-error
  (let [d (catch-data #(attrs/substitute-form [:p [:attr "version"]] {:version "1"}))]
    (is (= :smia.book.attrs/invalid-attribute (:error/type d)))))

(deftest attribute-values-may-reference-other-attributes
  (testing "a value carrying [:attr …] resolves transitively"
    (is (= [:p "deep"]
           (attrs/substitute-form [:p [:attr :x]] {:x [:attr :y] :y "deep"})))
    (is (= [:p [:span "v" "1.0"]]
           (attrs/substitute-form [:p [:attr :badge]]
                                  {:badge [:span "v" [:attr :version]]
                                   :version "1.0"}))))
  (testing "an unknown reference inside an attribute value is flagged"
    (let [d (catch-data #(attrs/substitute-form [:p [:attr :x]]
                                                {:x [:attr :typo]}))]
      (is (= :smia.book.attrs/unknown-attribute (:error/type d)))))
  (testing "a reference cycle is a structured error, not a hang"
    (let [d (catch-data #(attrs/substitute-form [:p [:attr :x]]
                                                {:x [:attr :y] :y [:attr :x]}))]
      (is (= :smia.book.attrs/circular-attribute (:error/type d))))))

(deftest references-inside-element-attribute-maps-resolve
  (testing "an [:attr …] in an element's attribute map is substituted"
    (is (= [:img {:alt "A photo"}]
           (attrs/substitute-form [:img {:alt [:attr :name]}] {:name "A photo"})))
    (is (= [:p "X" [:img {:alt "A photo"}]]
           (attrs/substitute-form [:p [:attr :x] [:img {:alt [:attr :name]}]]
                                  {:x "X" :name "A photo"}))))
  (testing "an unknown key in an attribute-map value is a structured error, not an m1p marker"
    (let [d (catch-data #(attrs/substitute-form [:img {:alt [:attr :typo]}] {}))]
      (is (= :smia.book.attrs/unknown-attribute (:error/type d)))))
  (testing "a cycle reached through an attribute-map value still terminates"
    (let [d (catch-data #(attrs/substitute-form [:img {:alt [:attr :x]}]
                                                {:x [:attr :y] :y [:attr :x]}))]
      (is (= :smia.book.attrs/circular-attribute (:error/type d))))))

;; --- manuscript-wide substitution -------------------------------------------

(defn- manuscript [body]
  (let [content (into [:chapter {:id :intro :title "Intro"}] body)]
    {:numbering structure/default-numbering
     :sections  [{:kind :chapter :content content}]
     :chapters  [content]}))

(deftest substitute-rewrites-sections-and-chapters
  (let [out (attrs/substitute (manuscript [[:p [:attr :v]]])
                              {:v "9"} {})]
    (is (= [:chapter {:id :intro :title "Intro"} [:p "9"]]
           (:content (first (:sections out)))))
    (is (= [:chapter {:id :intro :title "Intro"} [:p "9"]]
           (first (:chapters out))))))

(deftest a-manuscript-with-no-attr-reference-is-untouched
  (let [m (manuscript [[:p "plain"]])]
    (is (identical? m (attrs/substitute m {:v "9"} {})))))

(deftest front-matter-overrides-per-chapter
  ;; the chapter's own attrs (front-matter) win over the book context
  (let [content [:chapter {:id :intro :title "Intro" :v "local"} [:p [:attr :v]]]
        m       {:numbering structure/default-numbering
                 :sections  [{:kind :chapter :content content}]
                 :chapters  [content]}
        out     (attrs/substitute m {:v "book"} {})]
    (is (= [:chapter {:id :intro :title "Intro" :v "local"} [:p "local"]]
           (first (:chapters out))))))

;; --- ordering guarantee: substitution precedes numbering --------------------

(deftest an-attribute-value-with-a-numbered-float-numbers-correctly
  ;; The attribute value is a whole figure; because substitution runs before
  ;; numbering, the inlined figure is numbered like any other float.
  (let [fig  [:figure {:id :widget :caption "Widget"} [:img {:src "w.png"}]]
        out  (-> (manuscript [[:attr :fig]])
                 (attrs/substitute {:fig fig} {})
                 (number/assign))]
    (is (= "Figure 1" (:label (get (:registry out) "widget"))))))
