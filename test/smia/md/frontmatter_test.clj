(ns smia.md.frontmatter-test
  (:require
   [smia.error :as error]
   [smia.md.frontmatter :as fm]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

(deftest no-frontmatter-when-source-does-not-start-with-map
  (let [src "# Heading\n\nProse.\n"
        {:keys [attrs body]} (fm/split src)]
    (is (nil? attrs))
    (is (= src body) "body is returned unchanged")))

(deftest splits-a-leading-edn-map
  (let [{:keys [attrs body]} (fm/split "{:id :intro :draft true}\n# T\n\nHi\n")]
    (is (= {:id :intro :draft true} attrs))
    (is (not (str/includes? body ":draft")) "front-matter removed from body")
    (is (str/includes? body "# T"))))

(deftest preserves-line-numbering
  (testing "the consumed front-matter is replaced by blank lines so the H1
            keeps its original line number"
    (let [{:keys [body]} (fm/split "{:id :x}\n\n# Title\n")]
      ;; "# Title" is on line 3 of the source; it must remain on line 3.
      (is (= "# Title" (nth (str/split-lines body) 2))))))

(deftest multiline-frontmatter-preserves-line-numbering
  (let [src "{:id :x\n :title \"T\"}\n# H\n"
        {:keys [attrs body]} (fm/split src)]
    (is (= {:id :x :title "T"} attrs))
    (is (= "# H" (nth (str/split-lines body) 2)))))

(deftest leading-whitespace-before-map-is-allowed
  (let [{:keys [attrs]} (fm/split "\n  {:id :x}\n# H\n")]
    (is (= {:id :x} attrs))))

(deftest non-map-leading-form-is-an-error
  (let [d (catch-data #(fm/split "{:id :x :y}\n"))]
    ;; ":id :x :y" is an odd-count map literal -> unreadable EDN.
    (is (= :smia.md.frontmatter/invalid-front-matter (:error/type d)))))

(deftest unreadable-edn-is-an-error
  (let [d (catch-data #(fm/split "{:id :x\n# never closed\n"))]
    (is (= :smia.md.frontmatter/invalid-front-matter (:error/type d)))))
