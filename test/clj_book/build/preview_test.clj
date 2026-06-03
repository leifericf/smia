(ns clj-book.build.preview-test
  (:require
   [clj-book.build.preview :as preview]
   [clojure.test :refer [deftest is testing]]))

(deftest ignored-names-never-trigger-a-rebuild
  (testing "editor and OS cruft is ignored"
    (is (preview/ignored-name? ".DS_Store"))
    (is (preview/ignored-name? "chapter.md~"))
    (is (preview/ignored-name? ".#chapter.md"))
    (is (preview/ignored-name? "chapter.md.swp"))
    (is (preview/ignored-name? "#chapter.md#"))
    (is (preview/ignored-name? ".hidden")))
  (testing "book sources are not"
    (is (not (preview/ignored-name? "chapter.md")))
    (is (not (preview/ignored-name? "book.edn")))
    (is (not (preview/ignored-name? "tokens.edn")))
    (is (not (preview/ignored-name? "01-intro.clj")))))

(deftest changes-diffs-two-snapshots
  (let [old {"/b/a.md" 1 "/b/b.md" 2}]
    (testing "a modified file is a change"
      (is (= #{"/b/a.md"} (preview/changes old {"/b/a.md" 9 "/b/b.md" 2}))))
    (testing "a created file is a change"
      (is (= #{"/b/c.md"} (preview/changes old (assoc old "/b/c.md" 3)))))
    (testing "a deleted file is a change"
      (is (= #{"/b/b.md"} (preview/changes old (dissoc old "/b/b.md")))))
    (testing "identical snapshots yield no changes"
      (is (= #{} (preview/changes old old))))))

(deftest relevance-gates-ignored-and-excluded-paths
  (let [ctx {:excluded-roots ["/book/build"]}]
    (testing "a chapter under the book root is relevant"
      (is (preview/relevant? ctx "/book/chapters/01-intro.md")))
    (testing "an ignored filename is not"
      (is (not (preview/relevant? ctx "/book/chapters/.#01-intro.md"))))
    (testing "anything under an excluded root (the build output) is not"
      (is (not (preview/relevant? ctx "/book/build/slug/pdf/x.pdf")))
      (is (not (preview/relevant? ctx "/book/build"))))))
