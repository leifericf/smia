[:chapter {:id :configuration :title "Configuring the book"}
 [:p "One " [:code "book.edn"] " describes the manuscript. It is an "
  [:strong "open map"] ": required keys are enforced, and any extra keys you "
  "add are preserved untouched."]

 [:h2 {:id :required-keys} "Required keys"]
 [:table
  [:thead [:tr [:th "Key"] [:th "Meaning"]]]
  [:tbody
   [:tr [:td [:code ":book/slug"]] [:td "output directory + file-name stem"]]
   [:tr [:td [:code ":book/title"]] [:td "title, shown on the title page"]]
   [:tr [:td [:code ":book/chapters"]]
    [:td "ordered vector of chapter file paths"]]]]

 [:h2 {:id :optional-metadata} "Optional metadata"]
 [:p [:code ":book/author"] " is written onto the title page and into the PDF "
  "metadata. Chapter paths are resolved relative to the book root; each must "
  "exist and must be unique."]

 [:h2 {:id :validation} "Validation"]
 [:p "A missing required key, a missing chapter file, or a duplicated chapter "
  "fails with a descriptive, structured error. See "
  [:xref {:to :errors} "the error catalog"] " for the full list."]

 [:admonition {:kind :note}
  [:p "There is no AsciiDoc header anymore: all metadata lives in "
   [:code "book.edn"] ", and all styling lives in "
   [:xref {:to :theming} "tokens.edn"] "."]]]
