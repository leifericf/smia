(ns smia.book.linkcheck
  "Pure core: a validate-time check for dead internal links.

   A cross-reference (`:xref`) is already resolved and hard-checked by the
   numbering pass. This catches the other case: a hand-written anchor link
   (`[:a {:href \"#id\"}]` or its `:html/*` form, e.g. from a `{=hiccup}`
   escape) whose target is no known anchor in the book. The valid targets
   are every numbered id (the numbering registry) plus every author-supplied
   `:id` anywhere in the chapters. Reported as warnings — the link still
   renders — so a generated anchor the check does not model never blocks a
   build. No IO."
  (:require
   [clojure.string :as str]))

(defn- anchor-link?
  "True for an `:a`/`:html/a` node whose href is an in-page `#id` reference."
  [n]
  (and (vector? n)
       (#{:a :html/a} (first n))
       (map? (second n))
       (let [h (:href (second n))]
         (and (string? h) (str/starts-with? h "#") (> (count h) 1)))))

(defn- id-of [n]
  (when (and (vector? n) (map? (second n)) (:id (second n)))
    (name (:id (second n)))))

(defn anchor-ids
  "The set of valid internal target ids: the numbering `registry` keys plus
   every author-supplied `:id` anywhere in `chapters`."
  [chapters registry]
  (into (set (keys registry))
        (comp (mapcat #(tree-seq vector? seq %)) (keep id-of))
        chapters))

(defn dead-links
  "A vector of warnings for internal `#id` links in `chapters` whose target
   is not among the known `anchor-ids`."
  [chapters registry]
  (let [ids (anchor-ids chapters registry)]
    (vec
      (for [chapter chapters
            node    (filter anchor-link? (tree-seq vector? seq chapter))
            :let    [target (subs (:href (second node)) 1)]
            :when   (not (contains? ids target))]
        {:warning/type   :smia.book.linkcheck/dead-internal-link
         :warning/target target
         :warning/note   (str "Anchor link #" target " targets no known id.")}))))
