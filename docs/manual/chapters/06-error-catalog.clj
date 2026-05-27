[:chapter {:id :errors :title "Error catalog"}
 [:p "Every failure is a structured " [:code "ex-info"] " carrying "
  [:code ":error/type"] ", " [:code ":error/message"] ", and "
  [:code ":error/context"] ". The most useful types, grouped by where they "
  "arise:"]

 [:h2 {:id :request-errors} "Request"]
 [:ul
  [:li [:p [:code ":clj-book.request/missing-book-root"] " — no "
        [:code ":book-root"] " was given."]]
  [:li [:p [:code ":clj-book.request/unknown-profile"] " — a profile other "
        "than " [:code ":screen"] " or " [:code ":print"] " was requested."]]
  [:li [:p [:code ":clj-book.request/invalid-profiles"] " — "
        [:code ":profiles"] " was not a vector of keywords."]]]

 [:h2 {:id :config-errors} "Configuration and tokens"]
 [:ul
  [:li [:p [:code ":clj-book.config/missing"] " — no " [:code "book.edn"]
        " at the expected path."]]
  [:li [:p [:code ":clj-book.config/missing-required-key"] " — a required "
        [:code ":book/*"] " key is absent."]]
  [:li [:p [:code ":clj-book.config/missing-chapter"] " — a listed chapter "
        "file does not exist."]]
  [:li [:p [:code ":clj-book.config/duplicate-chapter"] " — a chapter is "
        "listed more than once."]]
  [:li [:p [:code ":clj-book.theme.load/missing"] " — no "
        [:code "styles/tokens.edn"] "."]]
  [:li [:p [:code ":clj-book.theme.load/missing-group"] " — a required token "
        "group is absent."]]]

 [:h2 {:id :authoring-errors} "Authoring"]
 [:ul
  [:li [:p [:code ":clj-book.book.load/chapter-eval-error"] " — a chapter "
        [:code ".clj"] " file failed to evaluate."]]
  [:li [:p [:code ":clj-book.book.assemble/missing-chapter-id"] " / "
        [:code ":clj-book.book.assemble/missing-chapter-title"]
        " — a " [:code ":chapter"] " is missing its " [:code ":id"] " or "
        [:code ":title"] "."]]
  [:li [:p [:code ":clj-book.book.assemble/unresolved-xref"] " — an "
        [:code ":xref"] " points at an unknown id."]]
  [:li [:p [:code ":clj-book.fo.expand/unknown-tag"] " — an element tag is "
        "neither known sugar nor a " [:code ":fo/*"] " tag."]]]

 [:h2 {:id :render-errors} "Rendering"]
 [:ul
  [:li [:p [:code ":clj-book.fo.render/fo-error"] " — FOP reported an error in "
        "the FO (often from invalid raw " [:code ":fo/*"] ")."]]
  [:li [:p [:code ":clj-book.fo.render/render-failed"] " — the FO could not be "
        "transformed to PDF."]]]

 [:admonition {:kind :tip}
  [:p "Run " [:xref {:to :commands} "validate"]
   " first: it surfaces the config, vocabulary, and cross-reference errors "
   "above without spending time on rendering."]]]
