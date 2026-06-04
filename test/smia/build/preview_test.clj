(ns smia.build.preview-test
  (:require
   [smia.build.preview :as preview]
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

;; --- the polling loop, driven by scripted snapshots (no threads, no timing)

(defn- run-script
  "Run `poll-loop!` over a scripted vector of snapshots: the first seeds the
   loop, the rest arrive one per tick, and the script's exhaustion raises
   the stop flag. Returns [rebuild-count loop-result]."
  [script & {:keys [throw?]}]
  (let [remaining (atom script)
        rebuilds  (atom 0)
        stop?     (atom false)
        snapshot! (fn []
                    (let [[s & more] @remaining]
                      (if (seq more)
                        (reset! remaining (vec more))
                        (reset! stop? true))
                      s))
        rebuild!  (fn []
                    (swap! rebuilds inc)
                    (when throw? (throw (RuntimeException. "boom"))))
        result    (binding [*err* (java.io.StringWriter.)]
                    (preview/poll-loop! {:snapshot! snapshot!
                                         :rebuild!  rebuild!
                                         :sleep!    (fn [])
                                         :stop?     stop?}))]
    [@rebuilds result]))

(deftest poll-loop-rebuilds-once-per-changed-snapshot
  (testing "a changed snapshot triggers exactly one rebuild"
    (is (= [1 :stopped] (run-script [{"a" 1} {"a" 2} {"a" 2}]))))
  (testing "unchanged ticks trigger none"
    (is (= [0 :stopped] (run-script [{"a" 1} {"a" 1} {"a" 1}])))))

(deftest poll-loop-survives-a-throwing-rebuild
  (is (= [1 :stopped] (run-script [{"a" 1} {"a" 2} {"a" 2}] :throw? true))
    "the loop reports the error and keeps running"))

(deftest poll-loop-stops-on-the-stop-flag
  (is (= [0 :stopped] (run-script [{"a" 1}]))
      "a pre-exhausted script stops the loop before any tick"))

;; --- the real tree snapshot, smoke-tested on the synthetic fixture

(deftest snapshot-walks-the-fixture-tree
  (let [fixture (.getCanonicalPath (java.io.File. "test/fixtures/synthetic/valid-book"))
        snap    (preview/snapshot! {:book-root fixture :excluded-roots []})]
    (is (some #(.endsWith ^String % "book.edn") (keys snap)))
    (is (some #(.endsWith ^String % "01-intro.clj") (keys snap)))
    (is (every? number? (vals snap)))
    (is (not-any? #(preview/ignored-name? (.getName (java.io.File. ^String %)))
                  (keys snap)))))
