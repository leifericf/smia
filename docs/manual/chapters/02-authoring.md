# Authoring in Hiccup

You can write a chapter two ways. **Markdown** (see [writing in Markdown](#markdown)) is the prose-first surface: it compiles to the same author Hiccup a `.clj` chapter produces, so everything below describes the vocabulary both front-ends target. **Hiccup** — the HTML-flavored data Clojure developers already produce — is that vocabulary: a true superset with three concentric layers, all in one syntax.

## Layer 1: HTML-flavored sugar

The common case looks like ordinary markup. Paragraphs, headings, lists, emphasis, inline `code`, code blocks, block quotes, tables, images, and links all work:

- `:p`, `:h1`–`:h6`, `:blockquote`, `:hr`
- `:ul` / `:ol` / `:li`
- `:strong`, `:em`, `:code`, `:a`
- `:table` / `:thead` / `:tbody` / `:tr` / `:td` / `:th`

Table columns are equal width by default. Give `:table` a `:cols` vector of positive numbers — one relative weight per column — to size them: `[:table {:cols [3 1 1]} …]` makes the first column three times as wide. FOP supports only fixed table layout, so weights are how you make room for wide, unbreakable cell content. In Markdown, a bare EDN map on the line directly above a table supplies the same `:cols`.

## Layer 2: book extensions

Some things HTML cannot name. clj-book adds them:

| Tag | Purpose |
|---|---|
| `:chapter` | a chapter (page sequence + bookmark) |
| `:xref` | a cross-reference resolved to a page number |
| `:footnote` | a footnote |
| `:admonition` | a called-out note, tip, or warning |

For example, this sentence links to [the theming chapter](#theming) by id.

:::admonition {:kind :tip}
An admonition takes a `:kind` — one of `:note`, `:tip`, or `:warning`.
:::

## Layer 3: raw FO

When you need something the sugar does not cover, drop to raw XSL-FO in the same data. Any `:fo/*` tag passes straight through:

```clojure
[:fo/block {:space-before "12pt" :text-align "center"}
 "Anything FO can do, written directly."]
```

From Markdown, a `` ```{=hiccup} `` fence splices author Hiccup (which re-expands) and a `` ```{=fo} `` fence splices raw FO verbatim. Because `.clj` chapters are also **programs**, a chapter may `slurp` a real source file or generate repetitive content with ordinary Clojure.
