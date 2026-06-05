(ns smia.book.scaffold
  "Scaffold a new book: a minimal, buildable manuscript.

   The templates are pure data — `files` maps relative paths to contents,
   deriving the slug and title from the target directory's name. Writing
   them is the shell (`init!`), which refuses a non-empty target so it can
   never overwrite an existing manuscript."
  (:require
   [smia.error :as error]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private default-slug
  "The fallback slug when a directory name has no usable characters."
  "my-book")

(defn- slugify
  "Normalize a directory name into a `:book/slug`: lower-case, with runs
   of spaces and underscores as single hyphens, trimmed of leading and
   trailing hyphens. A name with no usable characters falls back to a
   default, so the scaffold is always a buildable, titled book."
  [name]
  (let [slug (-> (or name "")
                 (str/lower-case)
                 (str/replace #"[\s_]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? slug) default-slug slug)))

(defn- title-from
  "Derive a starting `:book/title` from a slug: hyphens to spaces, each
   word capitalized. `my-first-book` -> `My First Book`."
  [slug]
  (->> (str/split slug #"-")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn- book-edn [slug title]
  (str "{:book/slug     " (pr-str slug) "\n"
       " :book/title    " (pr-str title) "\n"
       " :book/author   \"Your Name\"\n"
       " :book/chapters [\"chapters/01-introduction.md\"]}\n"))

(def ^:private theme-edn
  (str "{:color   {:text \"#1c1c1c\" :link \"#2a52be\"}\n"
       " :type    {:body-family \"serif\" :base-size \"11pt\"}\n"
       " :spacing {:paragraph \"6pt\"}\n"
       " :layout  {:page-size :digest}\n"
       "\n"
       " ;; The site edition's opt-in features live under :site, e.g.:\n"
       " ;; :site {:layout :sidebar :search true :dark {:toggle true}\n"
       " ;;        :reader true :keyboard true}\n"
       " }\n"))

(defn- introduction-md [title]
  (str "# Introduction\n"
       "\n"
       "This is the first chapter of *" title "*. Build the book with:\n"
       "\n"
       "```\n"
       "clojure -M:run build\n"
       "```\n"
       "\n"
       "The screen and print PDFs land under `build/`. Add chapters as\n"
       "Markdown files and list them in `book.edn`.\n"))

(defn files
  "Pure: the scaffold's relative path -> content map, derived from the
   target directory's `name`."
  [name]
  (let [slug  (slugify name)
        title (title-from slug)]
    {"book.edn"                    (book-edn slug title)
     "theme.edn"                   theme-edn
     "chapters/01-introduction.md" (introduction-md title)}))

(defn init!
  "Shell: write the scaffold into the directory at `target`, creating it
   when missing. A target that already has entries is refused — nothing is
   ever overwritten. Returns `{:target <path> :files [<rel-path> …]}`."
  [target]
  (let [dir (.getCanonicalFile (io/file target))]
    (when (and (.exists dir) (seq (.list dir)))
      (throw (error/ex :smia.book.scaffold/target-not-empty
                       (str "Target directory is not empty: " (.getPath dir))
                       {:target (.getPath dir)
                        :entries (vec (sort (.list dir)))})))
    (let [fs (files (.getName dir))]
      (doseq [[rel content] (sort-by key fs)]
        (let [f (io/file dir rel)]
          (io/make-parents f)
          (spit f content)))
      {:target (.getPath dir)
       :files  (vec (sort (keys fs)))})))
