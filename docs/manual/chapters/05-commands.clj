[:chapter {:id :commands :title "Commands"}
 [:p "clj-book is invoked through the standard Clojure CLI with "
  [:code "clojure -X"] "."]

 [:h2 {:id :validate} "validate"]
 [:p "Check that the manuscript is well-formed without rendering anything — "
  "config, tokens, chapter vocabulary, and cross-references:"]
 [:pre "clojure -X clj-book.api/validate :book-root '\"docs/manual\"'"]

 [:h2 {:id :build} "build"]
 [:p "Render the requested profiles to PDF:"]
 [:pre "clojure -X clj-book.api/build \\\n"
  "  :book-root '\"docs/manual\"' \\\n"
  "  :profiles  '[:screen :print]'"]
 [:p "Outputs land under " [:code "build/<slug>/pdf/"] " with deterministic "
  "names like " [:code "<slug>-screen.pdf"] ", plus an " [:code "artifacts.edn"]
  " manifest listing the profiles, paths, and build metadata."]

 [:h2 {:id :dry-run} "Dry run"]
 [:p "Add " [:code ":dry-run true"] " to a build to print and return the "
  "inspectable build plan without rendering or writing anything."]

 [:admonition {:kind :warning}
  [:p "Building runs your chapter code. Chapters are Clojure programs, so only "
   "build manuscripts you trust — the same trust model as your own build."]]]
