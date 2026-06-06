(ns smia.md.parse-test
  (:require
   [smia.error :as error]
   [smia.md.parse :as parse]
   [clojure.test :refer [deftest is testing]]))

(defn- block-types [doc] (map :type (:children doc)))

(defn- catch-data [f]
  (try (f) nil
       (catch Exception e (error/data e))))

(deftest unclosed-directive-is-rejected
  (testing "a missing closing fence would silently swallow the chapter"
    (let [d (catch-data
              #(parse/parse ":::admonition {:kind :note}\n\nbody\n" "d.md"))]
      (is (= :smia.md.parse/unclosed-directive (:error/type d)))
      (is (= "admonition" (:directive (:error/context d))))
      (is (= 1 (:line (:error/context d))))
      (is (= "d.md" (:source-name (:error/context d))))))
  (testing "an unclosed outer directive is flagged even when the inner closes"
    (let [d (catch-data
              #(parse/parse
                 ":::example {:title \"t\"}\n:::admonition {:kind :note}\nbody\n:::\n"
                 "d.md"))]
      (is (= :smia.md.parse/unclosed-directive (:error/type d)))
      (is (= "example" (:directive (:error/context d))))))
  (testing "a closed directive parses as before"
    (is (= :directive
           (-> (parse/parse ":::admonition {:kind :note}\nbody\n:::\n" "d.md")
               :children first :type)))))

(deftest malformed-directive-line-is-rejected
  (testing "an opener whose attribute region does not close"
    (let [d (catch-data
              #(parse/parse ":::admonition {:kind :note\nBody.\n:::\n" "d.md"))]
      (is (= :smia.md.parse/malformed-directive (:error/type d)))
      (is (= 1 (:line (:error/context d))))
      (is (= "d.md" (:source-name (:error/context d))))))
  (testing "an opener with trailing content after the attribute map"
    (let [d (catch-data
              #(parse/parse ":::admonition {:kind :note} junk\nBody.\n:::\n" "d.md"))]
      (is (= :smia.md.parse/malformed-directive (:error/type d)))))
  (testing "a closing fence with no open directive"
    (let [d (catch-data #(parse/parse "Body.\n\n:::\nmore\n" "d.md"))]
      (is (= :smia.md.parse/malformed-directive (:error/type d)))))
  (testing "a ::: line inside a fenced code block stays content"
    (is (= :fenced-code-block
           (-> (parse/parse "```\n:::not-a-directive {\n```\n" "d.md")
               :children first :type))))
  (testing "an indented ::: line is code, not a directive"
    (is (= :indented-code-block
           (-> (parse/parse "    :::name {\n" "d.md")
               :children first :type)))))

(deftest parses-into-a-document-of-blocks
  (let [doc (parse/parse "# H\n\npara\n\n- a\n- b\n" "d.md")]
    (is (= :document (:type doc)))
    (is (= "d.md" (:source-name doc)))
    (is (= [:heading :paragraph :bullet-list] (block-types doc)))))

(deftest headings-carry-their-level
  (let [doc (parse/parse "# one\n\n### three\n" "d.md")
        [h1 h3] (:children doc)]
    (is (= 1 (:level h1)))
    (is (= 3 (:level h3)))))

(deftest attaches-one-based-positions
  (testing "block and inline nodes carry 1-based :line/:col source spans"
    (let [doc (parse/parse "# T\n\nhello **x**\n" "d.md")
          para (second (:children doc))
          strong (first (filter #(= :strong (:type %)) (:children para)))]
      (is (= {:line 1 :col 1} (:pos (first (:children doc)))))
      (is (= {:line 3 :col 1} (:pos para)))
      (is (= 3 (:line (:pos strong)))))))

(deftest inline-structure-is-normalized
  (let [doc   (parse/parse "a **b** `c` *d*\n" "d.md")
        para  (first (:children doc))
        types (map :type (:children para))]
    (is (= [:text :strong :text :code :text :emphasis] types))
    (is (= "c" (:literal (first (filter #(= :code (:type %)) (:children para))))))))

(deftest fenced-code-keeps-info-and-literal
  (let [doc  (parse/parse "```clojure {:test true}\n(+ 1 2)\n```\n" "d.md")
        fence (first (:children doc))]
    (is (= :fenced-code-block (:type fence)))
    (is (= "clojure {:test true}" (:info fence)))
    (is (= "(+ 1 2)\n" (:literal fence)))))
