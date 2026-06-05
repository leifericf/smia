# Authoring in Hiccup

:::overview {:title "What this chapter covers"}
- The three concentric layers: HTML-flavored sugar, book extensions, and raw FO
- The everyday tags: paragraphs, lists, tables, and **description lists**
- Where to drop down to raw `:fo/*` when the sugar runs out
:::

Beneath the Markdown surface of [the previous chapter](#markdown) sits **author Hiccup**: the HTML-flavored data Clojure developers already know, arranged in three concentric layers. Markdown compiles to it, and a `.clj` chapter produces it directly, so this vocabulary is what both front-ends target. Everything below applies to both.

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
 [:dt "Manuscript"] [:dd "The normalized document structure."]
 [:dt "Profile"]    [:dd "A layout variant such as " [:code ":screen"] " or " [:code ":print"] "."]]
```

Table columns are equal width by default. Give `:table` a `:cols` vector of positive numbers, one relative weight per column, to size them: `[:table {:cols [3 1 1]} …]` makes the first column three times as wide. FOP supports only fixed table layout, so weights are how you make room for wide, unbreakable cell content. In Markdown, a bare EDN map on the line directly above a table supplies the same `:cols`, and colons in the header separator row set cell alignment in the usual way.

`:cols :auto` sizes the columns to their content instead: each column's weight is its widest cell, measured in characters and clamped to a floor and a ceiling so one long cell cannot squash the rest into slivers and a short column keeps a usable minimum. The PDF is where this matters; the site already fits columns to content in the browser. The proportional weights always sum to the table width, so the table never runs off the page, and long text wraps. A single unbreakable token wider than its column (a long monospace identifier) still overflows the cell, so reach for explicit `:cols` when one column must be guaranteed wide.

A cell may span columns or rows: `[:td {:colspan 2} …]` and `[:th {:rowspan 3} …]` carry through to both the HTML and the PDF. Markdown's table grammar has no span syntax, so a table with merged cells is written in Hiccup (directly in a `.clj` chapter or through a `{=hiccup}` escape).

A cell carries its own alignment too: `[:td {:align "right"} …]` sets the horizontal alignment (`"left"`, `"center"`, `"right"`) and `[:td {:valign "top"} …]` the vertical (`"top"`, `"middle"`, `"bottom"`). Both render in every edition. The colons in a Markdown separator row (`|:--|:-:|--:|`) set a column's horizontal alignment the same way, applied to every cell in the column.

## Layer 2: book extensions

Some things HTML cannot name. Smia adds them:

| Tag | Purpose |
|---|---|
| `:chapter` | a chapter (page sequence + bookmark) |
| `:overview` | a panel at the head of a chapter summarizing what it covers |
| `:example` | a titled worked-example callout |
| `:details` / `:open` | a disclosure that folds away on the site (`:open` starts expanded) |
| `:xref` | a cross-reference resolved to a page number |
| `:footnote` | a footnote |
| `:admonition` | a called-out note, tip, or warning |

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

From Markdown, a `` ```{=hiccup} `` fence splices author Hiccup, which re-expands, and a `` ```{=fo} `` fence splices raw FO verbatim. A `.clj` chapter is also a **program**, so it can `slurp` a real source file or generate repetitive content with ordinary Clojure.
