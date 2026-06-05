# Preface

Smia built every page of this manual from the Markdown sources under `manual/`.

Smia is written in Clojure, runs on the JVM, and builds one manuscript into
several editions at once. The same sources become screen and print PDFs, a
press-ready PDF/X, an EPUB, and a website. A few choices shape the tool.

- A single manuscript and one set of design tokens produce every edition, so a book's content and appearance are each described once and compiled to what each format needs.
- The table of contents, the cross-references, figure and table numbering, the index, the bibliography, and the lists of figures, tables, and listings are all generated from the sources.
- The same manuscript renders to equivalent output every time, with attributes in sorted order and document metadata pinned.
- The manuscript, the theme, and the rendered document are all plain data structures, and where the portable sugar runs short, a typed escape hatch reaches the full vocabulary of the format being built.
- Mathematics and diagrams render to SVG while the book builds, so a formula or a figure reads the same in print, in the EPUB, and on the web, with no script or font needed when the book is read.
- A default website ships no JavaScript, and the optional reading aids are added over plain HTML that still works when scripting is off.

:::sidebar {:title "About the name"}
*Smia* is the Norwegian word for a smithy. It also reads as a backronym,
*Structured Markup Into Apparatus*. Smia turns the structured markup you write
into the apparatus around a book's text.
:::

The manual is organized into parts. The first covers authoring, the second
covers configuration, theming, and the build commands, and the third covers the
structure of a long-form book. An appendix catalogs the error types. Front
matter like this preface is numbered in roman numerals, and the body switches to
arabic at the first chapter.

The manual exercises the platform that builds it, so it doubles as a regression
test. If a feature breaks, this book stops building.
