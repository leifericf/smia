;; This appendix is a program. The build evaluates this file, and the value
;; of its last expression is the chapter, expressed in the same author
;; vocabulary the Markdown front end compiles to. Everything else in the
;; file is ordinary Clojure preparing content for that final expression.
;;
;; The listings shown in the rendered appendix are pulled from this file
;; itself, between tag::name and end::name comment markers.

(require '[smia.fo.schema :as vocabulary]
         '[smia.theme.compile :as theme])

;; tag::vocab
(def tag-names
  (sort (map name vocabulary/sugar-tags)))

(def vocabulary-table
  (into [:table {:id :tbl-vocab
                 :caption "The author vocabulary, read from the running build"}]
        (for [row (partition-all 3 tag-names)]
          (into [:tr] (for [tag row] [:td [:code tag]])))))
;; end::vocab

;; tag::canon
(def canon-table
  (into [:table {:id :tbl-canon
                 :caption "Margins per trim, from the canon function"}]
        (cons [:tr [:th "Trim"]
               [:th "Inner"] [:th "Top"] [:th "Outer"] [:th "Bottom"]]
              (for [[trim {:keys [width]}] (sort-by first theme/page-sizes)]
                (let [margins (theme/canon-margins width 2/3)]
                  [:tr
                   [:td [:code (str trim)]]
                   [:td (:margin-inside margins)]
                   [:td (:margin-top margins)]
                   [:td (:margin-outside margins)]
                   [:td (:margin-bottom margins)]])))))
;; end::canon

;; tag::colophon
(def pdf-editions
  {:any-of [{:equals [:edition :screen]}
            {:equals [:edition :print]}
            {:equals [:edition :print-x]}]})

(def colophon
  [:when pdf-editions
   [:fo/block {:text-align "center" :letter-spacing "0.15em"
               :font-size "9pt" :color "#666666" :space-before "24pt"}
    "SET IN SMIA"]])
;; end::colophon

[:chapter {:id :code-authoring :title "A Chapter Written in Code"}
 [:overview {:title "What this appendix covers"}
  [:ul
   [:li "A chapter file that is a program, with its full source on display"]
   [:li "Tables generated from the build itself, so they cannot drift"]
   [:li "Per-edition fine-tuning down to a single formatting object"]]]

 [:p "Every other chapter in this manual is Markdown. This one is a "
  [:code ".clj"] " file: the build runs it and uses the value of its last "
  "expression as the chapter. The result is the same author Hiccup "
  "described in " [:xref {:to :authoring} "the authoring chapter"]
  ", so everything downstream is identical. What changes is that the "
  "content can be computed."]

 [:p "You do not need prior Clojure to follow along, and you can treat "
  "this file as a template to copy. Every form it uses is introduced in "
  [:xref {:to :clojure-for-authors} "the Clojure appendix"]
  ": the four data shapes, plus " [:code "def"] " to name a value, "
  [:code "for"] " to build rows, and " [:code "into"]
  " to pour them into an element. Each section below shows the lines of "
  "this file that produce it, included from the file itself, so the "
  "source you read is the source that ran."]

 [:h2 {:id :generated-vocabulary} "A table the book writes for itself"]

 [:p "The authoring chapter lists the author vocabulary by hand. This "
  "appendix asks the build instead: the tag set lives in a var the "
  "expansion engine itself uses, and the chapter turns it into rows."]

 [:pre {:lang :clojure :include "chapters/13-code-authoring.clj" :tag "vocab"
        :caption "Generating a table from the build's own tag registry"}]

 [:p "Line by line: " [:code "tag-names"] " takes the tag set, turns each "
  "tag into its name, and sorts them. " [:code "partition-all"]
  " slices the sorted names into rows of three, the " [:code "for"]
  " wraps each name in a table cell, and " [:code "into"]
  " pours the rows into a captioned table. The result renders as "
  [:xref {:to :tbl-vocab}] ":"]

 vocabulary-table

 [:p "When a tag joins the vocabulary, the next build updates the table. "
  "There is no copy to forget."]

 [:h2 {:id :computed-values} "Documentation that cannot drift"]

 [:p "The theming chapter explains that PDF margins default to the "
  "classical 2:3:4:6 construction. Prose can describe the rule; a chapter "
  "written in code can run it. The table below calls the same function "
  "the PDF compiler calls, once per named trim:"]

 [:pre {:lang :clojure :include "chapters/13-code-authoring.clj" :tag "canon"
        :caption "Margin values computed by calling the implementation"}]

 canon-table

 [:p "The table lists " (str (count theme/page-sizes)) " trims because "
  "that is how many the build knows about; the count in this sentence is "
  "computed too. If the canon derivation ever changes, "
  [:xref {:to :tbl-canon}] " follows it on the next build, and a stale "
  "number here would be a bug in Smia, not in the manual."]

 [:h2 {:id :fo-flourish} "Down to a single formatting object"]

 [:p "The same file can reach below the sugar. The end of this appendix "
  "carries a small letterspaced colophon line in the PDF editions, "
  "written directly as a formatting object and wrapped in a condition so "
  "the web editions skip it:"]

 [:pre {:lang :clojure :include "chapters/13-code-authoring.clj" :tag "colophon"
        :caption "A print-only flourish, one element deep"}]

 [:p "This is the escape-hatch matrix at its most granular: one element, "
  "in one place, in some editions, with no stylesheet layer involved. "
  "The condition map is the same " [:code ":when"]
  " vocabulary Markdown uses for conditional content."]

 [:h2 {:id :when-to-use-code} "When to reach for code"]

 [:ul
  [:li "A reference table whose rows already live in your project: a "
   "configuration registry, an error catalog, a benchmark output file."]
  [:li "Values the text must state and the implementation already knows: "
   "defaults, limits, version-dependent behavior."]
  [:li "Structure Markdown cannot express, such as merged table cells."]
  [:li "Bulk content that follows a pattern: fifty near-identical "
   "sections want a " [:code "for"] ", not fifty files."]
  [:li "Production at scale: a publisher whose pipeline already holds "
   "the content as data can emit chapters from it directly, and the "
   "build stays deterministic, scriptable, and headless."]]

 [:p "Prose-heavy chapters stay nicer in Markdown; this manual keeps "
  "every other chapter there. The two front ends share one vocabulary, "
  "so a book can mix them freely, chapter by chapter."]

 [:when {:not {:any-of [{:equals [:edition :screen]}
                        {:equals [:edition :print]}
                        {:equals [:edition :print-x]}]}}
  [:p {} "In the PDF editions a small letterspaced colophon closes this "
   "page, set directly in FO. This paragraph is its web-edition "
   "replacement, chosen by the same condition."]]

 colophon]
