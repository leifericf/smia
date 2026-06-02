[:chapter {:id :authoring :title "Authoring in Hiccup"}
 [:p "You write content as " [:strong "Hiccup"] " — the HTML-flavored data "
  "Clojure developers already produce. The vocabulary is a true superset with "
  "three concentric layers, all in one syntax."]

 [:h2 {:id :html-sugar} "Layer 1: HTML-flavored sugar"]
 [:p "The common case looks like ordinary markup. Paragraphs, headings, lists, "
  "emphasis, inline " [:code "code"] ", code blocks, block quotes, tables, "
  "images, and links all work:"]
 [:ul
  [:li [:p [:code ":p"] ", " [:code ":h1"] "–" [:code ":h6"] ", "
        [:code ":blockquote"] ", " [:code ":hr"]]]
  [:li [:p [:code ":ul"] " / " [:code ":ol"] " / " [:code ":li"]]]
  [:li [:p [:code ":strong"] ", " [:code ":em"] ", " [:code ":code"] ", "
        [:code ":a"]]]
  [:li [:p [:code ":table"] " / " [:code ":thead"] " / " [:code ":tbody"]
        " / " [:code ":tr"] " / " [:code ":td"] " / " [:code ":th"]]]]
 [:p "Table columns are equal width by default. Give " [:code ":table"] " a "
  [:code ":cols"] " vector of positive numbers — one relative weight per "
  "column — to size them: " [:code "[:table {:cols [3 1 1]} …]"] " makes the "
  "first column three times as wide. FOP supports only fixed table layout, so "
  "weights are how you make room for wide, unbreakable cell content."]

 [:h2 {:id :book-extensions} "Layer 2: book extensions"]
 [:p "Some things HTML cannot name. clj-book adds them:"]
 [:table
  [:thead [:tr [:th "Tag"] [:th "Purpose"]]]
  [:tbody
   [:tr [:td [:code ":chapter"]] [:td "a chapter (page sequence + bookmark)"]]
   [:tr [:td [:code ":xref"]] [:td "a cross-reference resolved to a page number"]]
   [:tr [:td [:code ":footnote"]] [:td "a footnote"]]
   [:tr [:td [:code ":admonition"]] [:td "a called-out note, tip, or warning"]]]]
 [:p "For example, this sentence links to "
  [:xref {:to :theming} "the theming chapter"] " by id."]

 [:admonition {:kind :tip}
  [:p "An admonition takes a " [:code ":kind"]
   " — one of " [:code ":note"] ", " [:code ":tip"] ", or "
   [:code ":warning"] "."]]

 [:h2 {:id :raw-fo} "Layer 3: raw FO"]
 [:p "When you need something the sugar does not cover, drop to raw XSL-FO in "
  "the same data. Any " [:code ":fo/*"] " tag passes straight through:"]
 [:pre "[:fo/block {:space-before \"12pt\" :text-align \"center\"}\n"
  " \"Anything FO can do, written directly.\"]"]
 [:p "Because chapters are " [:code ".clj"] " files, they are also "
  [:strong "programs"] ": a chapter may " [:code "slurp"] " a real source file "
  "or generate repetitive content with ordinary Clojure."]]
