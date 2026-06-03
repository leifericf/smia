# clj-book

clj-book builds technical books as PDF on the JVM. You write your manuscript in
Markdown (or in Hiccup, the Clojure data it compiles to) and clj-book renders the
screen and print editions through [Apache FOP](https://xmlgraphics.apache.org/fop/).
It runs on nothing more than a JDK and the Clojure CLI. There is no Ruby toolchain
or asciidoctor underneath, and the renderer never shells out to an external binary,
so a build stays self-contained and reproducible.

A book is a `book.edn` file together with its chapter sources and a
`theme.edn`. From that, clj-book gives you parts and numbered chapters,
appendices, named front and back matter, figures and code listings with captions,
cross-references that read "Figure 1" instead of a bare page number, in-process
syntax highlighting, running heads, an index, and a bibliography.

## Building a book

The only prerequisites are a JDK and the Clojure CLI; everything else arrives as a
Maven dependency. To build the manual that ships with the repo:

```bash
clojure -M:run build manual
```

With no `--edition` option, both the screen and print editions are produced under
`build/clj-book-manual/pdf/`, next to a machine-readable `artifacts.edn` manifest.
Run `clojure -M:run validate manual` to check a manuscript without rendering,
and `clojure -M:run build --help` for the full option list. Scripts and other tools
can call the same engine through the `-X` map API
(`clojure -X clj-book.api/build :book-root '"manual"'`).

For a live authoring loop, `clojure -M:run preview manual` builds the screen
edition and then rebuilds it on every save in the same warm JVM — around 150 ms
a save. A save that fails prints the error and keeps watching; stop with
Ctrl-C.

## Editions

The same manuscript builds into several deliverable forms, selected with the
repeatable `--edition` flag:

- `screen` — a PDF with symmetric margins for on-screen reading (default).
- `print` — a PDF with mirrored recto/verso margins and a binding gutter (default).
- `print-x` — the print layout hardened to PDF/X-4 for press submission:
  embedded fonts, an ICC output intent, and no link annotations. Gated on a
  `:book/print-x` map in `book.edn` naming the fonts and profile.
- `site` — a static HTML site under `build/<slug>/site/`: a table-of-contents
  home page, one page per chapter, a stylesheet generated from the same
  `theme.edn`, and copies of referenced images. Cross-references become links,
  footnotes collect at each chapter's end, and the output needs no server.
- `epub` — an accessible, byte-reproducible EPUB3 package at
  `build/<slug>/epub/<slug>.epub`, validated clean under epubcheck. The same
  pages as the site, packaged for e-readers and store pipelines.

Numbering and cross-reference resolution run once, before any format-specific
rendering, so every edition agrees on the book's structure.

## Documentation

The manual is itself a clj-book manuscript, under `manual/`. Build it (the
command above) and read the PDFs in `build/clj-book-manual/pdf/`. It walks through
the quickstart, the Markdown and Hiccup authoring vocabulary, `book.edn`
configuration, theming and editions, the build commands, producing a finished
book, and the error catalog. The design and architecture are covered in the
manual's own design chapter.

## License

Eclipse Public License 2.0. See `LICENSE`.
