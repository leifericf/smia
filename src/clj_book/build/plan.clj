(ns clj-book.build.plan
  "Build context (pure core): turn a prepared build context into an
   inspectable plan value.

   The plan decides which editions to build, where each writes its
   output files, and the manifest skeleton — all as data, without
   performing any IO. clj-book.build.execute performs it, and `--dry-run`
   prints it. Decision is thereby separated from execution."
  (:require
   [clj-book.schema :as schema]))

(declare edition-step)

(defn plan
  "Pure: produce the build Plan value from a prepared build context
   `{:request :manuscript :paths}`. Performs no IO."
  [{:keys [request manuscript paths]}]
  (let [editions (vec (:editions request))
        {:keys [config warnings]} manuscript
        slug (:book/slug config)]
    (schema/check
      schema/Plan
      {:book-root         (:book-root request)
       :editions          editions
       :manuscript        manuscript
       :paths             paths
       :edition-steps     (mapv #(edition-step % slug paths) editions)
       :manifest-skeleton {:book/slug      slug
                           :build/editions editions
                           :metadata       {:warnings warnings}}
       :validation        {:enabled (boolean (:validate-code request))}}
      :clj-book.build.plan/invalid-plan)))

;; --- private helpers -------------------------------------------------------

(defn- edition-step [edition slug paths]
  {:edition  edition
   :fo-path  (str (:intermediate-dir paths) "/book-" (name edition) ".fo")
   :pdf-path (str (:pdf-output-dir paths) "/" slug "-" (name edition) ".pdf")})
