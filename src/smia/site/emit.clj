(ns smia.site.emit
  "Site context (shell): write an assembled page map to disk.

   All site IO lives here so `site.assemble` stays pure: each page-map
   entry becomes a file under the output directory, and every referenced
   resource (images) is copied from the book root. A missing resource is
   a warning, not a failure — the site is still browsable without it.

   The site directory self-corrects: stale `*.html` from a prior build
   are swept before writing, so a removed chapter leaves no ghost page.
   Every `.html` in the site dir is smia's (authors never hand-write
   HTML), so the sweep is precise; non-HTML files a user adds for hosting
   (`CNAME`, `.nojekyll`, `favicon.ico`) are never touched. A full reset
   that also evicts orphaned images is `build --clean`."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- sweep-stale-html!
  "Delete `*.html` files anywhere under `out-dir` whose path (relative to
   `out-dir`, with `/` separators) is not in `keep` (the current build's
   HTML page set), then prune any directories left empty. Pages now nest
   in per-page directories, so the sweep recurses; non-HTML files a user
   adds for hosting (`CNAME`, `.nojekyll`, images) are never touched."
  [out-dir keep]
  (let [root (io/file out-dir)]
    (when (.isDirectory root)
      (let [base (.toPath root)]
        (doseq [^java.io.File f (file-seq root)
                :when (and (.isFile f)
                           (str/ends-with? (.getName f) ".html")
                           (not (contains? keep (str (.relativize base (.toPath f))))))]
          (.delete f))
        (doseq [^java.io.File d (->> (file-seq root)
                                     (filter #(.isDirectory ^java.io.File %))
                                     (sort-by #(- (count (.getPath ^java.io.File %)))))
                :when (and (not= d root) (zero? (alength (.listFiles d))))]
          (.delete d))))))

(defn emit!
  "Write `{:pages {path → content-string} :resources [{:src} …]}` under
   `out-dir`, copying resources from `book-root`. Stale `*.html` pages
   from a prior build are swept first. Returns
   `{:warnings [{:warning/type :path} …]}`."
  [{:keys [out-dir book-root pages resources]}]
  (sweep-stale-html! out-dir (set (filter #(str/ends-with? % ".html")
                                          (keys pages))))
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
                         {:warning/type :smia.site.emit/missing-resource
                          :path         src})))
                   resources))]
    {:warnings warnings}))
