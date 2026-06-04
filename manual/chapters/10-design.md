# How it works

Smia is small because it leans on two ideas: everything is plain data, and the only effects live at the edges. This chapter explains the design and the reasoning behind it.

## The pipeline

A build is a short series of transforms from your Hiccup to PDF bytes:

:::deflist
**load**

Read `book.edn` and `theme.edn`; compile each Markdown or `.clj` chapter.

**assemble**

Chapters plus metadata plus theme become one `:fo/root` tree.

**expand**

HTML sugar and book extensions become FO; any `:fo/*` tag passes through.

**serialize**

The FO-Hiccup tree becomes XSL-FO XML.

**render**

Apache FOP turns XSL-FO into PDF, once per PDF edition.
:::

The middle three steps, assemble through serialize, are **pure functions** over plain Clojure data. The only effects are reading inputs at the start and FOP writing bytes at the end.

## Data all the way down

The manuscript, the theme, and the XSL-FO document are all ordinary Clojure data structures. XSL-FO is XML, and Hiccup is a generic XML-tree literal, so an FO element is a vector:

```clojure
[:fo/block {:space-before "12pt"} "text"]
;; serializes to
<fo:block space-before="12pt">text</fo:block>
```

Because the FO tree is data, it can be assembled, transformed, and inspected with the same tools as any other Clojure value.

## A superset per format, not a subset

Most engines give you a fixed vocabulary plus an escape hatch. Smia inverts that: for each output format the author vocabulary is a superset of that format's substrate. In a PDF edition any `:fo/*` tag passes straight through, so every XSL-FO construct is reachable; in an HTML edition `:html/*` does the same for HTML. The sugar in [the authoring chapter](#authoring) is the portable core that renders in every edition. Reaching for one format's hatch while building another is a structured error at build time, so a portable manuscript stays portable.

:::admonition {:kind :note}
Sugar nested inside a raw `:fo/*` or `:html/*` element still expands, so the layers compose freely in the same tree.
:::

## Functional core, imperative shell

Keeping assembly, expansion, and serialization pure means the hard part of the engine runs on in-memory data, with no files and no FOP. Reading inputs, evaluating chapters, and rendering are confined to a thin shell. Values that cross between the two are checked against schemas, so a malformed value fails at the boundary with a structured error.

## Why Apache FOP, in-process

FOP is a pure-JVM XSL-FO formatter. Smia calls it as a library, never as a subprocess, so a build needs only a JVM. One process also makes errors easy to surface: FOP's events become structured Smia errors and warnings.

## Determinism

Identical inputs should produce equivalent output. Smia serializes FO without pretty-printing, so `white-space="pre"` survives, emits attributes in sorted order, and pins FOP's document metadata. Two builds of the same manuscript agree on pages, text, and bookmarks.

## Styling without CSS

XSL-FO has no CSS cascade: every block carries its own properties. Styling is therefore a **style map** from tag to FO properties, derived from the tokens and the edition's page layout and applied as the sugar expands. There is no stylesheet language to learn beyond the tokens in [the theming chapter](#theming).

## Chapters are programs

A `.clj` chapter is a file whose value is its last form, so a chapter can compute its content: read a real source file, build a table from data, or factor out helpers. The trade-off is explicit. Building such a book runs the author's code, so you build only manuscripts you trust. A Markdown chapter, by contrast, is read as data.
