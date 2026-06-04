(ns smia.html.links
  "Pure core: link resolution for HTML editions.

   The numbering pass already resolves what a cross-reference points at;
   in paged output the page number does the locating, but in HTML the
   link must name a file and fragment. This namespace builds the
   `{anchor-id → file}` table from the assembled pages and produces the
   resolver the HTML expander uses: `(resolve id current-url)` returns a
   relative href to the target page plus `#id`, or a bare `\"#id\"` for a
   same-page target. Page urls are either a flat file (`\"chapter-01.html\"`,
   the page lives at the site root) or a directory (`\"part-1/quickstart/\"`,
   the page is that directory's `index.html`); `relativize` bridges the two
   so links work whatever the depth. No IO."
  (:require
   [smia.error :as error]
   [clojure.string :as str]))

(defn page-dir
  "The directory a page url lives in: a directory url (`\"a/b/\"`, or the
   empty root) is its own directory; a flat file url (`\"a.html\"`) lives
   in the directory above it (`\"\"` at the root)."
  [url]
  (cond
    (str/blank? url)            ""
    (str/ends-with? url "/")    url
    :else                       (or (re-find #"^.*/" url) "")))

(defn relativize
  "A relative href from the page at `from-url` to the absolute-from-root
   `target` (a url, optionally carrying a `#fragment`). Ascends out of the
   from-page's directory, then descends into the target. A flat from-url
   sits at the root, so the result is just `target` — preserving the
   single-directory behavior the EPUB and standalone editions rely on."
  [from-url target]
  (let [depth (count (remove str/blank? (str/split (page-dir from-url) #"/")))]
    (str (apply str (repeat depth "../")) target)))

(defn anchor-ids
  "Every anchor id (string) declared by an `:id` attribute anywhere in a
   Hiccup `tree`."
  [tree]
  (->> (tree-seq vector? seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (when-let [id (:id (second node))]
                   (if (keyword? id) (name id) (str id))))))
       set))

(defn table
  "Build `{anchor-id → file}` from `pages`, each
   `{:file <name> :content <hiccup>}` and/or `{:file <name> :ids [..]}`
   for anchors with no Hiccup source (generated back matter). An id
   declared by two files is a structured error — the link would be
   ambiguous."
  [pages]
  (reduce
    (fn [acc {:keys [file content ids]}]
      (reduce
        (fn [acc id]
          (when-let [other (get acc id)]
            (when (not= other file)
              (throw (error/ex :smia.html.links/duplicate-id
                               (str "Anchor id " (pr-str id) " is declared by both "
                                    other " and " file ".")
                               {:id id :files [other file]}))))
          (assoc acc id file))
        acc
        (concat (when content (anchor-ids content)) ids)))
    {}
    pages))

(defn resolver
  "A resolve fn over an id `table` (`{id → page-url}`):
   `(resolve id current-url)` returns the href for `id` as seen from the
   page at `current-url` — a bare `\"#id\"` for a same-page target, else a
   relative href to the target page plus `#id`. Unknown ids are a
   structured error — the numbering pass guarantees every reference target
   exists, so a miss here is an assembly bug, not author error."
  [table]
  (fn [id current-url]
    (let [url (get table id)]
      (when-not url
        (throw (error/ex :smia.html.links/unknown-id
                         (str "No page declares anchor id " (pr-str id) ".")
                         {:id id :current-file current-url})))
      (if (= url current-url)
        (str "#" id)
        (str (relativize current-url url) "#" id)))))
