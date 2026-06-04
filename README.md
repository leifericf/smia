# Smia

Smia builds technical books. From one Markdown manuscript it produces screen and
print PDFs, a press-ready PDF/X, an EPUB, and a static website. Manuscripts can
also be written in Hiccup, the Clojure data that Markdown compiles to. The name
is Norwegian for a smithy.

Smia is written in Clojure and renders PDFs with
[Apache FOP](https://xmlgraphics.apache.org/fop/). A JDK and the Clojure CLI are
the only prerequisites. Everything else arrives as a Maven dependency, and the
whole build runs inside one JVM process.

**[Read the manual online](https://smia.leifericf.com)**. Smia builds and
publishes its own manual; download any edition from the site's Downloads page
or from the repository's
[Releases](https://github.com/leifericf/smia/releases/latest).

A book is a `book.edn` file, its chapter sources, and a `theme.edn`. From that,
Smia produces parts and numbered chapters, appendices, named front and back
matter, captioned figures and code listings, cross-references that read
"Figure 1" rather than a bare page number, typographic punctuation, syntax
highlighting for the common book languages, running heads, an index, and a
bibliography. LaTeX math and PlantUML
diagrams render at build time into self-contained SVG, identical in every
edition, with no JavaScript and no math font needed at read time.

## Building a book

Start a new book with `clojure -M:run init my-book`: it scaffolds a minimal,
buildable manuscript — `book.edn`, `theme.edn`, and a first chapter — into a
fresh directory. To build the manual that ships with this repository:

```bash
clojure -M:run:math:diagrams build manual
```

The `:math` and `:diagrams` aliases pull the optional math and diagram
renderers; the manual uses live math and a live diagram. A book using neither
builds with `clojure -M:run build` alone.

With no `--edition` option, Smia writes the screen and print PDFs under
`build/smia-manual/pdf/`, next to an `artifacts.edn` manifest. Use
`clojure -M:run validate manual` to check a manuscript without rendering it,
and `clojure -M:run build --help` for the full option list. Scripts can call
the same engine through the `-X` map API:
`clojure -X smia.api/build :book-root '"manual"'`.

While you write, `clojure -M:run:math:diagrams preview manual` rebuilds the
screen edition on every save (preview renders the book, so it needs the same
renderer aliases as build). The JVM stays warm between rebuilds, so a save
takes about 150 ms. A save that fails to build prints the error and the watcher
keeps running; stop it with Ctrl-C. Previewing the site edition
(`preview manual --edition site` with the same aliases) also serves it at
`http://localhost:8000/`, because the site's directory URLs need a web server
to browse.

## Editions

One manuscript builds into several deliverable forms, selected with the
repeatable `--edition` flag:

- `screen`: a PDF with symmetric margins for on-screen reading (default).
- `print`: a PDF with mirrored recto/verso margins and a binding gutter
  (default).
- `print-x`: the print layout as PDF/X-4 for press submission, with embedded
  fonts, an ICC output intent, and no link annotations. Requires a
  `:book/print-x` map in `book.edn` naming the fonts and profile.
- `site`: a static HTML site under `build/<slug>/site/`, one page per chapter,
  styled by the same `theme.edn`. The output contains no JavaScript by default
  and needs nothing but a static file host; an opt-in search box is added as
  progressive enhancement, and every page works with JavaScript disabled.
- `epub`: an EPUB3 package at `build/<slug>/epub/<slug>.epub`, validated with
  epubcheck. The same pages as the site, packaged for e-readers.

Numbering and cross-reference resolution run once, before any format-specific
rendering. Every edition therefore agrees on the book's structure.

## Build output

Smia writes every edition under one gitignored directory, `build/<slug>/`;
`--output-root` overrides it. Rebuilds overwrite in place. The site edition
also removes pages whose chapters no longer exist, while files you add
yourself, such as a `CNAME`, are kept. Pass `--clean` to delete `build/<slug>/`
before building; it does nothing under `--dry-run`.

`--licensee "Name <email>"` stamps a "Licensed to" line in the footer of every
PDF page, for distributing personalized copies. It affects the PDF editions
only and is not part of the manuscript.

## Documentation

The manual lives under `manual/`. Build it with the command above and read the
PDFs in `build/smia-manual/pdf/`, or read it
[online](https://smia.leifericf.com). It walks from the quickstart to a
finished, published book, and its design chapter explains how Smia works
inside.

## License

Eclipse Public License 2.0. See `LICENSE`.
