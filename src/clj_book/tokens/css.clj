(ns clj-book.tokens.css
  "Compile design tokens into a CSS custom-properties stylesheet. The
   tier-3 `styles/site.clj` escape hatch (Garden) is appended after the
   token-derived rules when present."
  (:require
   [clj-book.error :as error]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [garden.core :as garden]))

(defn- token-name
  "Map a [group leaf] token path to a CSS custom-property name."
  [group leaf]
  (str "--" (name group) "-"
       (-> (name leaf)
           (str/replace #"\." "-")
           (str/replace #"/" "-"))))

(defn- ->css-value [v]
  (cond
    (keyword? v) (name v)
    (number? v)  (str v)
    (string? v)  v
    :else        (str v)))

(defn- tokens->custom-props
  "Flatten a token map into a sorted vector of [css-var value] pairs.
   Deterministic ordering is achieved by sorting group keys first, then
   leaf keys within each group."
  [tokens]
  (vec
    (for [g (sort (keys tokens))
          :let [m (get tokens g)]
          :when (map? m)
          k (sort (keys m))]
      [(token-name g k) (->css-value (get m k))])))

(defn token-css
  "Render the canonical token-derived CSS string (deterministic) using
   Garden. The result targets `:root` with custom properties. The
   selector is a string because Garden treats the `:root` keyword as an
   element selector rather than the pseudo-class."
  [tokens]
  (let [props (into {} (map (fn [[k v]] [(keyword k) v])
                            (tokens->custom-props tokens)))]
    (garden/css [":root" props])))

(defn- load-site-clj
  "Load `styles/site.clj` (if present) and return its Garden data
   structure. Returns nil if the file does not exist."
  [book-root]
  (let [f (io/file book-root "styles" "site.clj")]
    (when (.exists f)
      (try
        (load-file (.getPath f))
        (catch Exception e
          (throw (error/ex :clj-book.tokens.css/site-clj-eval-error
                           (str "Failed to evaluate styles/site.clj: "
                                (.getMessage e))
                           {:path (.getPath f)})))))))

(defn compile-css
  "Return a CSS string composed of the token-derived stylesheet followed
   by the tier-3 `styles/site.clj` rules (when present)."
  [{:keys [book-root tokens]}]
  (let [base    (token-css tokens)
        extra  (load-site-clj book-root)
        extra-css (when extra (garden/css extra))]
    (if (str/blank? extra-css)
      base
      (str base "\n" extra-css))))
