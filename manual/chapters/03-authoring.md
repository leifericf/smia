# Authoring in Hiccup

:::overview {:title "What this chapter covers"}
- The three concentric layers: HTML-flavored sugar, book extensions, and raw FO
- The everyday tags: paragraphs, lists, tables, and **description lists**
- Where to drop down to raw `:fo/*` when the sugar runs out
:::

Beneath the Markdown surface of [the previous chapter](#markdown) sits **author Hiccup**: the HTML-flavored data Clojure developers already know, arranged in three concentric layers. Both front ends target it. Markdown compiles to it, and a `.clj` chapter produces it directly. Everything below applies to both.

## Reading Hiccup without knowing Clojure

You do not need Clojure to read or write this vocabulary. Hiccup is markup written as plain data, and four shapes cover all of it:

:::deflist
**String**

Text in double quotes: `"a paragraph of prose"`. Strings are the content.

**Keyword**

A name with a leading colon: `:p`, `:table`, `:caption`. Keywords name tags and attributes; think of them as the identifiers between angle brackets and the attribute names in other markup.

**Vector**

Square brackets: `[:p "hello"]`. A vector is an element. Its first item is the tag, an optional map after it carries the attributes, and everything else is the children. Elements nest by putting vectors inside vectors.

**Map**

Curly braces of alternating keys and values: `{:id :intro :caption "Run times"}`. A map is an attribute set; commas are optional whitespace.
:::

So `[:p "See " [:em "this"] "."]` is the paragraph "See *this*.", and the HTML element `<td colspan="2">x</td>` is `[:td {:colspan 2} "x"]`. Anyone who has written HTML or JSON has seen this structure before; only the punctuation differs. To go from reading it to writing chapters with it, [the Clojure appendix](#clojure-for-authors) teaches the few forms authoring uses.

## Layer 1: HTML-flavored sugar

The common case looks like ordinary markup. Paragraphs, headings, lists, emphasis, inline `code`, code blocks, block quotes, tables, images, and links all work:

- `:p`, `:h1`–`:h6`, `:blockquote`, `:hr`
- `:ul` / `:ol` / `:li`
- `:dl` / `:dt` / `:dd`
- `:strong`, `:em`, `:code`, `:span`, `:a`
- `:kbd`, `:menu`, `:button`, `:mark`, `:sub`, `:sup`
- `:table` / `:thead` / `:tbody` / `:tr` / `:td` / `:th`

`:span` is a neutral inline wrapper. It groups inline content without styling of its own, so a phrase can be composed from text and other inline tags. `:code`, `:strong`, and `:em` style what they wrap. `:img` takes a `:src` and `:alt` text; the HTML editions require the alt text, and an optional `:width` and `:height` size the image.

The interface tags name parts of a user interface. `:kbd` boxes a key (`[:kbd "Enter"]`); set a chord as adjacent keys. `:menu` renders a path from its segments (`[:menu "File" "Export"]`). `:button` labels a control, `:mark` highlights, and `:sub` and `:sup` shift the baseline.

A **description list** pairs a term with its definition. Each `:dt` is set bold, each `:dd` indented beneath it:

```clojure
[:dl
 [:dt "Recto"] [:dd "The right-hand page of a spread."]
 [:dt "Verso"] [:dd "The left-hand page, recto's other half."]]
```

Table columns are equal width by default. Give `:table` a `:cols` vector of positive numbers, one relative weight per column, to size them: `[:table {:cols [3 1 1]} …]` makes the first column three times as wide. FOP (the PDF renderer Smia uses) supports only fixed table layout, so weights are how you make room for wide, unbreakable cell content. In Markdown, a bare EDN map on the line directly above a table supplies the same `:cols`, and colons in the header separator row set cell alignment in the usual way.

`:cols :auto` sizes the columns to their content instead: each column's weight is its widest cell, measured in characters and clamped to a floor and a ceiling. The clamp keeps one long cell from squashing the rest into slivers, and it keeps a short column at a usable minimum. The PDF is where this matters; the site already fits columns to content in the browser. The proportional weights always sum to the table width, so the table never runs off the page, and long text wraps. A single unbreakable token wider than its column (a long monospace identifier) still overflows the cell, so reach for explicit `:cols` when one column must be guaranteed wide.

A cell may span columns or rows: `[:td {:colspan 2} …]` and `[:th {:rowspan 3} …]` carry through to both the HTML and the PDF. Markdown's table grammar has no span syntax, so a table with merged cells is written in Hiccup, directly in a `.clj` chapter or through a `{=hiccup}` escape. This page does the latter. The fence below holds the Hiccup; the build splices it in as [](#tbl-formats), merged cells and all:

`````
```{=hiccup}
[:table {:id :tbl-formats :caption "The editions by page model" :cols [1 1 3]}
 [:tr [:th "Output"] [:th "Edition"] [:th "Page model"]]
 [:tr [:td {:rowspan 3} "PDF"] [:td [:code ":screen"]]
  [:td "symmetric margins, for reading on screen"]]
 [:tr [:td [:code ":print"]] [:td "mirrored margins and a binding gutter"]]
 [:tr [:td [:code ":print-x"]] [:td "the print layout as PDF/X-4"]]
 [:tr [:td {:rowspan 2} "HTML"] [:td [:code ":site"]]
  [:td "no pages; one reading column"]]
 [:tr [:td [:code ":epub"]] [:td "no pages; the reader reflows"]]]
```
`````

```{=hiccup}
[:table {:id :tbl-formats :caption "The editions by page model" :cols [1 1 3]}
 [:tr [:th "Output"] [:th "Edition"] [:th "Page model"]]
 [:tr [:td {:rowspan 3} "PDF"] [:td [:code ":screen"]]
  [:td "symmetric margins, for reading on screen"]]
 [:tr [:td [:code ":print"]] [:td "mirrored margins and a binding gutter"]]
 [:tr [:td [:code ":print-x"]] [:td "the print layout as PDF/X-4"]]
 [:tr [:td {:rowspan 2} "HTML"] [:td [:code ":site"]]
  [:td "no pages; one reading column"]]
 [:tr [:td [:code ":epub"]] [:td "no pages; the reader reflows"]]]
```

A cell carries its own alignment too: `[:td {:align "right"} …]` sets the horizontal alignment (`"left"`, `"center"`, `"right"`) and `[:td {:valign "top"} …]` the vertical (`"top"`, `"middle"`, `"bottom"`). Both render in every edition. The colons in a Markdown separator row (`|:--|:-:|--:|`) set a column's horizontal alignment the same way, applied to every cell in the column.

## Layer 2: book extensions

Some things HTML cannot name. A selection of what Smia adds:

| Tag | Purpose |
|---|---|
| `:chapter` | a chapter (page sequence + bookmark) |
| `:overview` | a panel at the head of a chapter summarizing what it covers |
| `:example` | a titled worked-example callout |
| `:details` / `:open` | a disclosure that folds away on the site (`:open` starts expanded) |
| `:xref` | a cross-reference resolved to a page number |
| `:footnote` | a footnote |
| `:admonition` | a called-out note, tip, or warning |

Figures, sidebars, epigraphs, citations, the index, and the page mechanics are book extensions too; they are treated in [the book-production chapter](#book-production).

An `:overview` panel opens a chapter with a short summary of what it covers; the panel at the top of this chapter is one. Its label is "Overview" unless you give it a `:title`. In Hiccup it is `[:overview [:ul …]]`.

For example, this sentence links to [the theming chapter](#theming) by id.

:::admonition {:kind :tip}
An admonition takes a `:kind`: one of `:note`, `:tip`, `:warning`, `:important`, or `:caution`. A `:title` replaces the kind's default label, and an `:icon` prefixes the title. A kind outside the known set works too; its label is the capitalized kind name.
:::

## Layer 3: raw FO

When you need something the sugar does not cover, drop to raw XSL-FO in the same data. Any `:fo/*` tag passes straight through:

```clojure
[:fo/block {:space-before "12pt" :text-align "center"}
 "Anything FO can do, written directly."]
```

From Markdown, a `` ```{=hiccup} `` fence splices author Hiccup, which re-expands, and a `` ```{=fo} `` fence splices raw FO verbatim. An inline form does the same: a code span carrying the payload, immediately followed by `{=hiccup}`, splices into the running text.

## Customization at every grain

Put together, the escape hatches form a ladder, and each rung is plain data in the manuscript or the theme:

:::deflist
**One word or phrase**

An inline `{=hiccup}` span in Markdown, or any inline element in a `.clj` chapter.

**One block, in one place**

A `{=hiccup}` or `{=fo}` fence; the merged-cell table above is one. Wrap it in `:::when` to scope it to particular editions.

**One tag, everywhere**

The theme's `:fo` group merges FO properties over every occurrence of a tag, and `:css` does the same for the HTML editions. Both live in `theme.edn`; [the theming chapter](#theming) covers them.

**A whole chapter**

A `.clj` file that computes its content. [The code-authoring appendix](#code-authoring) is one, with its source on display.
:::

Print toolchains built on XSL-FO traditionally pushed this kind of adjustment into XSLT stylesheets, a separate language in a separate file; [the design chapter](#design) tells that history. Here the adjustment sits in the manuscript or the theme, at exactly the scope it applies to, in the one syntax the whole book already uses.

## Chapters as programs

Everything so far needs no code, and a Markdown-only book uses none. The advanced track exists for authors who want control past what the directives expose, and for publishers feeding Smia from an automated production pipeline. A `.clj` chapter is evaluated by the build, and the value of its last expression is the chapter. That turns authoring problems into small programming problems:

- **Generated reference tables.** Rows that already exist as data, in a registry, a schema, or an exported file, become a table by iteration rather than transcription.
- **Documentation that cannot drift.** A default, a limit, or a computed value can be obtained by calling the very function that defines it, so the text is correct by construction.
- **One dataset, several views.** Define a value once and derive a table, a figure caption, and a sentence from it; all three agree.
- **Bulk structure.** Fifty near-identical sections are a loop over fifty data entries.
- **Real source on the page.** `slurp` reads any file the build can see, and the include mechanism from [the Markdown chapter](#markdown) works in `.clj` chapters too.

None of this requires fluency: [the Clojure appendix](#clojure-for-authors) teaches the handful of forms these patterns use, and [the code-authoring appendix](#code-authoring) is a complete chapter written this way, showing its own source.
