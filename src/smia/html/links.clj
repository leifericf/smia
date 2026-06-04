(ns smia.html.links
  "Pure core: link resolution for HTML editions.

   The numbering pass already resolves what a cross-reference points at;
   in paged output the page number does the locating, but in HTML the
   link must name a file and fragment. This namespace builds the
   `{anchor-id → file}` table from the assembled pages and produces the
   resolver the HTML expander uses: `(resolve id current-file)` returns
   `\"file#id\"`, or a bare `\"#id\"` for a same-file target. No IO."
  (:require
   [smia.error :as error]))

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
  "A resolve fn over an id `table`: `(resolve id current-file)` returns
   the href for `id` as seen from `current-file`. Unknown ids are a
   structured error — the numbering pass guarantees every reference
   target exists, so a miss here is an assembly bug, not author error."
  [table]
  (fn [id current-file]
    (let [file (get table id)]
      (when-not file
        (throw (error/ex :smia.html.links/unknown-id
                         (str "No page declares anchor id " (pr-str id) ".")
                         {:id id :current-file current-file})))
      (if (= file current-file)
        (str "#" id)
        (str file "#" id)))))
