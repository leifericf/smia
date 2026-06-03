(ns clj-book.build.plan
  "Build context (pure core): turn a prepared build context into an
   inspectable plan value.

   The plan decides which profiles to build, where each writes its
   intermediate FO and final PDF, and the manifest skeleton — all as
   data, without performing any IO. clj-book.build.execute performs it,
   and `--dry-run` prints it. Decision is thereby separated from
   execution."
  (:require
   [clj-book.schema :as schema]))

(declare profile-step)

(defn plan
  "Pure: produce the build Plan value from a prepared build context
   `{:request :manuscript :paths}`. Performs no IO."
  [{:keys [request manuscript paths]}]
  (let [profiles (vec (:profiles request))
        {:keys [config warnings]} manuscript
        slug (:book/slug config)]
    (schema/check
      schema/Plan
      {:book-root         (:book-root request)
       :profiles          profiles
       :manuscript        manuscript
       :paths             paths
       :profile-steps     (mapv #(profile-step % slug paths) profiles)
       :manifest-skeleton {:book/slug      slug
                           :build/profiles profiles
                           :metadata       {:warnings warnings}}
       :validation        {:enabled (boolean (:validate-code request))}}
      :clj-book.build.plan/invalid-plan)))

;; --- private helpers -------------------------------------------------------

(defn- profile-step [profile slug paths]
  {:profile  profile
   :fo-path  (str (:intermediate-dir paths) "/book-" (name profile) ".fo")
   :pdf-path (str (:pdf-output-dir paths) "/" slug "-" (name profile) ".pdf")})
