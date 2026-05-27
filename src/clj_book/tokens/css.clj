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

(defn load-site-extras
  "Load `styles/site.clj` (the tier-3 escape hatch) and return its Garden
   data structure, or nil when the file is absent.

   TRUST BOUNDARY: this evaluates arbitrary Clojure from the manuscript
   directory via `load-file`. It is a deliberate deserialization seam;
   only run it against manuscripts you trust. Kept apart from the pure
   `compile-css` so the effectful eval is the only impure step here."
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
   by the already-loaded tier-3 Garden `extras` (when present). Pure: the
   `extras` are supplied as data; see `load-site-extras` for the IO."
  [{:keys [tokens extras]}]
  (let [base      (token-css tokens)
        extra-css (when extras (garden/css extras))]
    (if (str/blank? extra-css)
      base
      (str base "\n" extra-css))))
