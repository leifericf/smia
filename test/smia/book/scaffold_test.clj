(ns smia.book.scaffold-test
  (:require
   [smia.api :as api]
   [smia.book.scaffold :as scaffold]
   [smia.error :as error]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(defn- tmp-target
  "A unique, not-yet-created directory path under the system tmp dir."
  [tag]
  (io/file (System/getProperty "java.io.tmpdir")
           (str "smia-scaffold-" tag "-" (System/nanoTime))))

(defn- catch-data [f] (try (f) nil (catch Exception e (error/data e))))

;; --- the pure templates -----------------------------------------------------

(deftest files-derive-slug-and-title-from-the-name
  (let [fs     (scaffold/files "my-first-book")
        config (edn/read-string (get fs "book.edn"))]
    (is (= #{"book.edn" "theme.edn" "chapters/01-introduction.md"}
           (set (keys fs))))
    (is (= "my-first-book" (:book/slug config)))
    (is (= "My First Book" (:book/title config)))
    (is (= ["chapters/01-introduction.md"] (:book/chapters config)))))

(deftest a-blank-name-still-yields-a-titled-book
  (testing "an empty or punctuation-only name falls back to a default"
    (doseq [nm ["" "   " "-" "_"]]
      (let [config (edn/read-string (get (scaffold/files nm) "book.edn"))]
        (is (seq (:book/slug config)) (str "slug non-blank for " (pr-str nm)))
        (is (seq (:book/title config)) (str "title non-blank for " (pr-str nm)))))))

(deftest theme-template-carries-the-required-groups
  (let [tokens (edn/read-string (get (scaffold/files "x") "theme.edn"))]
    (is (every? #(map? (get tokens %)) [:color :type :spacing :layout]))))

(deftest chapter-template-has-a-title
  (is (re-find #"^# " (get (scaffold/files "x") "chapters/01-introduction.md"))))

;; --- the shell ----------------------------------------------------------------

(deftest init-creates-and-fills-a-missing-directory
  (let [dir (tmp-target "new")
        out (scaffold/init! (.getPath dir))]
    (is (.exists (io/file dir "book.edn")))
    (is (.exists (io/file dir "theme.edn")))
    (is (.exists (io/file dir "chapters/01-introduction.md")))
    (is (= ["book.edn" "chapters/01-introduction.md" "theme.edn"]
           (:files out)))))

(deftest init-accepts-an-existing-empty-directory
  (let [dir (tmp-target "empty")]
    (.mkdirs dir)
    (scaffold/init! (.getPath dir))
    (is (.exists (io/file dir "book.edn")))))

(deftest init-refuses-a-non-empty-target
  (let [dir (tmp-target "occupied")]
    (.mkdirs dir)
    (spit (io/file dir "existing.txt") "already here")
    (let [d (catch-data #(scaffold/init! (.getPath dir)))]
      (is (= :smia.book.scaffold/target-not-empty (:error/type d)))
      (is (not (.exists (io/file dir "book.edn")))
          "nothing is written into an occupied directory"))))

;; --- the scaffold is a buildable book -----------------------------------------

(deftest scaffolded-book-validates-and-plans
  (testing "the templates pass validate and a dry-run build"
    (let [dir (tmp-target "buildable")]
      (scaffold/init! (.getPath dir))
      (is (= :ok (:status (api/validate {:book-root (.getPath dir)}))))
      (binding [*out* (java.io.StringWriter.)]
        (let [plan (api/build {:book-root   (.getPath dir)
                               :dry-run     true
                               :output-root (.getPath (io/file dir "build"))})]
          (is (= [:screen :print] (:editions plan))))))))
