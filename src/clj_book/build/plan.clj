(ns clj-book.build.plan
  "Build context (pure core): turn a prepared build context into an
   inspectable plan value.

   The plan decides which targets to build, where each writes, which
   shared prerequisites to emit, and the manifest skeleton — all as data,
   without performing any IO. clj-book.build.execute performs it, and
   `--dry-run` prints it. Decision is thereby separated from execution."
  (:require
   [clj-book.schema :as schema]))

(defn- prereq-steps
  "The shared prerequisite emitted once before any target runs, as a
   deterministic descriptor derived purely from the resolved paths. Only
   the master adoc is shared; each target compiles its own theme."
  [{:keys [intermediate-dir]}]
  [{:kind :master-adoc :path (str intermediate-dir "/book.adoc")}])

(defn- target-step [target paths]
  {:target     target
   :output-dir (case target
                 :site (:site-output-dir paths)
                 :pdf  (:pdf-output-dir paths))})

(defn plan
  "Pure: produce the build Plan value from a prepared build context
   `{:request :manuscript :paths}`. Performs no IO."
  [{:keys [request manuscript paths]}]
  (let [targets (vec (:targets request))
        {:keys [config warnings]} manuscript]
    (schema/check
      schema/Plan
      {:book-root         (:book-root request)
       :targets           targets
       :manuscript        manuscript
       :paths             paths
       :prereqs           (prereq-steps paths)
       :target-steps      (mapv #(target-step % paths) targets)
       :manifest-skeleton {:book/slug     (:book/slug config)
                           :build/targets targets
                           :metadata      {:warnings warnings}}}
      :clj-book.build.plan/invalid-plan)))
