[:chapter {:id :theming :title "Theming and profiles"}
 [:p "Styling comes from design " [:strong "tokens"] " plus a layout "
  [:strong "profile"] " — never from arbitrary CSS. FOP is not a CSS engine, "
  "and clj-book does not pretend it is."]

 [:h2 {:id :tokens} "tokens.edn"]
 [:p "Define the theme once in " [:code "styles/tokens.edn"] ", grouped into "
  [:code ":color"] ", " [:code ":type"] ", " [:code ":spacing"] ", and "
  [:code ":layout"] ":"]
 [:pre "{:color  {:text \"#1c1c1c\" :link \"#2a52be\"}\n"
  " :type   {:body-family \"serif\" :base-size \"11pt\"}\n"
  " :spacing {:paragraph \"6pt\"}\n"
  " :layout {:page-size :a4 :margin-outside \"20mm\"}}"]
 [:p "The tokens compile into the FO properties carried by every block; "
  "missing tokens fall back to readable base-14 defaults."]

 [:h2 {:id :profiles} "Profiles"]
 [:p "The same manuscript renders into two layout profiles:"]
 [:ul
  [:li [:p [:code ":screen"] " — comfortable, symmetric margins for "
        "on-screen reading."]]
  [:li [:p [:code ":print"] " — mirrored recto/verso margins with a binding "
        "gutter on the inside edge."]]]
 [:p "Both are built by default; pass " [:code ":profiles '[:print]'"]
  " to select one. See " [:xref {:to :commands} "the commands chapter"] "."]

 [:h2 {:id :fonts} "Fonts"]
 [:p "The default theme uses the PDF base-14 font families, so output is "
  "zero-config and always reproducible. Authors who want their own fonts "
  "register them through configuration; clj-book bundles no fonts."]]
