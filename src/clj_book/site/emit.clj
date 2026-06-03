(ns clj-book.site.emit
  "Site context (shell): write an assembled page map to disk.

   All site IO lives here so `site.assemble` stays pure: each page-map
   entry becomes a file under the output directory, and every referenced
   resource (images) is copied from the book root. A missing resource is
   a warning, not a failure — the site is still browsable without it."
  (:require
   [clojure.java.io :as io]))

(defn emit!
  "Write `{:pages {path → content-string} :resources [{:src} …]}` under
   `out-dir`, copying resources from `book-root`. Returns
   `{:warnings [{:warning/type :path} …]}`."
  [{:keys [out-dir book-root pages resources]}]
  (doseq [[path content] (sort-by key pages)]
    (let [f (io/file out-dir path)]
      (io/make-parents f)
      (spit f content)))
  (let [warnings
        (vec (keep (fn [{:keys [src]}]
                     (let [from (io/file book-root src)
                           to   (io/file out-dir src)]
                       (if (.exists from)
                         (do (io/make-parents to)
                             (io/copy from to)
                             nil)
                         {:warning/type :clj-book.site.emit/missing-resource
                          :path         src})))
                   resources))]
    {:warnings warnings}))
