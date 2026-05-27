(ns clj-book.tokens.pdf
  "Compile design tokens into Asciidoctor PDF theme YAML. The tier-3
   `styles/pdf-theme.edn` escape hatch is deep-merged on top when
   present."
  (:require
   [clj-book.error :as error]
   [clj-yaml.core :as yaml]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import (java.io PushbackReader)))

(defn- ->theme-key
  "Asciidoctor PDF theme files use underscored snake_case keys. Convert a
   Clojure keyword like :font-family to the string \"font_family\"."
  [k]
  (-> (name k) (.replace "-" "_")))

(defn- deep-merge [a b]
  (cond
    (and (map? a) (map? b))
    (merge-with deep-merge a b)
    :else b))

(defn- tokens->theme-map
  "Translate the canonical token map into the Asciidoctor PDF theme
   shape. The structure is a minimal but symmetric mapping suited for
   v1 alpha; richer mappings can be layered via `pdf-theme.edn`."
  [tokens]
  (let [color   (:color tokens)
        type-g  (:type tokens)
        spacing (:spacing tokens)
        layout  (:layout tokens)]
    (cond-> {}
      color   (assoc :base
                     (cond-> {}
                       (:bg color)    (assoc :background_color (name (:bg color)))
                       (:fg color)    (assoc :font_color       (name (:fg color)))))
      type-g  (assoc :font
                     (cond-> {}
                       (:family type-g) (assoc :family (name (:family type-g)))
                       (:size   type-g) (assoc :size   (:size type-g))))
      spacing (assoc :page
                     (cond-> {}
                       (:margin spacing) (assoc :margin (:margin spacing))))
      layout  (assoc :layout layout))))

(defn- load-pdf-extras [book-root]
  (let [f (io/file book-root "styles" "pdf-theme.edn")]
    (when (.exists f)
      (try
        (with-open [r (PushbackReader. (io/reader f))]
          (edn/read r))
        (catch Exception e
          (throw (error/ex :clj-book.tokens.pdf/extras-read-error
                           (str "Failed to read styles/pdf-theme.edn: "
                                (.getMessage e))
                           {:path (.getPath f)})))))))

(defn- map-keys-recursive [m f]
  (cond
    (map? m) (into {} (map (fn [[k v]] [(f k) (map-keys-recursive v f)]) m))
    (vector? m) (mapv #(map-keys-recursive % f) m)
    :else m))

(defn theme-map
  "Return the merged Asciidoctor PDF theme map (token-derived plus
   tier-3 extras)."
  [{:keys [book-root tokens]}]
  (let [base   (tokens->theme-map tokens)
        extras (load-pdf-extras book-root)]
    (deep-merge base (or extras {}))))

(defn compile-yaml
  "Return the YAML string representing the compiled PDF theme."
  [ctx]
  (let [m (theme-map ctx)
        snake (map-keys-recursive m ->theme-key)]
    (yaml/generate-string snake :dumper-options {:flow-style :block})))
