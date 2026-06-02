# How it works

clj-book is small because it leans on two ideas: everything is plain data, and the only effects live at the edges. This chapter explains the design and the reasoning behind it.

## The pipeline

A build is a short series of transforms from your Hiccup to PDF bytes:

```
load       read book.edn + tokens.edn; compile each Markdown or .clj chapter
assemble   chapters + metadata + theme -> one :fo/root tree
expand     HTML sugar + book extensions -> FO; :fo/* passes through
serialize  FO-hiccup -> XSL-FO XML
render     Apache FOP: XSL-FO -> PDF, once per profile
```

The middle three steps — assemble, expand, serialize — are **pure functions** over plain Clojure data. The only effects are reading inputs at the start and FOP writing bytes at the end.

## Data all the way down

The manuscript, the theme, and the XSL-FO document are all ordinary Clojure data structures. XSL-FO is XML, and Hiccup is a generic XML-tree literal, so an FO element is just a vector:

```clojure
[:fo/block {:space-before "12pt"} "text"]
;; serializes to
<fo:block space-before="12pt">text</fo:block>
```

Because the FO tree is data, it can be assembled, transformed, and inspected with the same tools you use for any other Clojure value.

## A superset, not a subset

Most engines give you a fixed vocabulary plus an escape hatch. clj-book inverts that: the author vocabulary is a true superset of XSL-FO. Since any `:fo/*` tag passes straight through, every XSL-FO construct is reachable by construction — the sugar in [the authoring chapter](#authoring) is convenience layered on top, not a ceiling.

:::admonition {:kind :note}
Sugar nested inside raw FO still expands, so the two layers compose freely in the same tree.
:::

## Functional core, imperative shell

Keeping assembly, expansion, and serialization pure means the hard part of the engine is exercisable on in-memory data, with no files and no FOP. Reading inputs, evaluating chapters, and rendering are confined to a thin shell. Values that cross between the two are checked against schemas so a malformed value fails at the boundary with a clear, structured error.

## Why Apache FOP, in-process

FOP is a pure-JVM XSL-FO formatter. Running it as a library call — never as a subprocess — means a build needs only a JVM: no Ruby, no native binary, no toolchain to install. One process also makes errors easy to surface: FOP's events become structured clj-book errors and warnings.

## Determinism

Identical inputs should produce equivalent output. clj-book serializes FO without pretty-printing (so `white-space="pre"` survives), emits attributes in sorted order, and pins FOP's document metadata. Two builds of the same manuscript agree on pages, text, and bookmarks.

## Styling without CSS

XSL-FO has no CSS cascade: every block carries its own properties. So styling is a **style map** — tag to FO properties — derived from your tokens and the active profile, applied as the sugar expands. There is no CSS to interpret and no stylesheet language to learn beyond the tokens described in [the theming chapter](#theming).

## Chapters are programs

A `.clj` chapter is a file whose value is its last form, so a chapter can compute its content — read a real source file, build a table from data, or factor out helpers. The trade-off is explicit: building such a book runs the author's code, so you build only manuscripts you trust. A Markdown chapter, by contrast, is read as data.
