(ns clj-book.document
  "Document context (pure core): transform a parsed DocBook 5 hiccup tree
   into a semantic HTML hiccup model.

   No IO and no shelling out: given the DocBook data this is a
   referentially transparent transform, shared by every render target so
   the site and the preview server agree on one HTML model."
  (:require
   [clojure.string :as str]))

(declare ->hiccup)

(defn- tag-of [node]
  (when (vector? node) (first node)))

(defn- attrs-of [node]
  (let [a (and (vector? node) (second node))]
    (if (map? a) a {})))

(defn- content-of [node]
  (if (vector? node)
    (let [tail (drop 2 node)]
      (if (map? (second node)) tail (rest node)))
    nil))

(defn- inline->hiccup [c]
  (cond
    (string? c) c
    (vector? c)
    (case (tag-of c)
      :emphasis      (into [:em] (map inline->hiccup (content-of c)))
      :literal       (into [:code] (map inline->hiccup (content-of c)))
      :code          (into [:code] (map inline->hiccup (content-of c)))
      :link          (let [a (attrs-of c)]
                       (into [:a {:href (or (:xlink:href a) (:href a) "#")}]
                             (map inline->hiccup (content-of c))))
      :xref          (let [a (attrs-of c)]
                       [:a {:href (str "#" (or (:linkend a) ""))}
                        (or (:linkend a) "")])
      :phrase        (into [:span] (map inline->hiccup (content-of c)))
      (->hiccup c))
    :else (str c)))

(defn- title-of [node]
  (some (fn [c]
          (when (and (vector? c) (= :title (tag-of c)))
            (->> (content-of c)
                 (map inline->hiccup)
                 (apply str)
                 str/trim)))
        (content-of node)))

(defn- heading [level node]
  (let [t (title-of node)
        a (attrs-of node)
        id (:xml:id a)]
    (when t
      [(keyword (str "h" level))
       (cond-> {} id (assoc :id id))
       t])))

(defn- non-title-children [node]
  (->> (content-of node)
       (remove (fn [c]
                 (and (vector? c) (= :title (tag-of c)))))))

(defn- sectioning
  "Render a DocBook sectioning element (chapter/section) to an HTML
   `<section>` carrying `class`, an optional id from `xml:id`, a heading
   at `level`, and the recursively transformed non-title children."
  [node class level]
  (let [id (:xml:id (attrs-of node))]
    (into [:section (cond-> {:class class} id (assoc :id id))]
          (concat (when-let [h (heading level node)] [h])
                  (map ->hiccup (non-title-children node))))))

(defn- ->hiccup [node]
  (cond
    (string? node) node
    (vector? node)
    (case (tag-of node)
      :book
      (into [:article {:class "book"}]
            (concat (when-let [h (heading 1 node)] [h])
                    (map ->hiccup (non-title-children node))))
      :info
      (into [:header {:class "book-info"}]
            (map inline->hiccup (content-of node)))
      :chapter (sectioning node "chapter" 2)
      :section (sectioning node "section" 3)
      (:simpara :para) (into [:p] (map inline->hiccup (content-of node)))
      :literallayout (into [:pre] (map inline->hiccup (content-of node)))
      :programlisting (into [:pre [:code]] (map inline->hiccup (content-of node)))
      :itemizedlist (into [:ul]
                          (for [c (content-of node)
                                :when (and (vector? c) (= :listitem (tag-of c)))]
                            (into [:li] (map ->hiccup (content-of c)))))
      :orderedlist (into [:ol]
                         (for [c (content-of node)
                               :when (and (vector? c) (= :listitem (tag-of c)))]
                           (into [:li] (map ->hiccup (content-of c)))))
      :title nil
      (into [:div {:class (str "db-" (name (tag-of node)))}]
            (map inline->hiccup (content-of node))))
    :else (str node)))

(defn ->html-model
  "Transform a parsed DocBook 5 hiccup tree into a semantic HTML hiccup
   model. The public entry point of the Document context."
  [docbook-hiccup]
  (->hiccup docbook-hiccup))
