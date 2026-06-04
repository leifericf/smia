# Smia

Smia builds technical books. From one Markdown manuscript it produces screen and
print PDFs, a press-ready PDF/X, an EPUB, and a static website. Manuscripts can
also be written in Hiccup, the Clojure data that Markdown compiles to. The name
is Norwegian for a smithy.

Smia is written in Clojure and renders PDFs with
[Apache FOP](https://xmlgraphics.apache.org/fop/). A JDK and the Clojure CLI are
the only prerequisites. Everything else arrives as a Maven dependency, and the
whole build runs inside one JVM process.

**[Read the manual online](https://smia.leifericf.com)**. The manual is written
and published with Smia, and every edition can be downloaded from the site's
Downloads page or from the repository's
[Releases](https://github.com/leifericf/smia/releases/latest).

A book is a `book.edn` file, its chapter sources, and a `theme.edn`. From that,
Smia produces parts and numbered chapters, appendices, named front and back
matter, captioned figures and code listings, cross-references that read
"Figure 1" rather than a bare page number, syntax highlighting, running heads,
an index, and a bibliography.

## Building a book

To build the manual that ships with this repository:

```bash
clojure -M:run build manual
```

With no `--edition` option, the screen and print PDFs are written under
`build/smia-manual/pdf/`, next to an `artifacts.edn` manifest. Use
`clojure -M:run validate manual` to check a manuscript without rendering it,
and `clojure -M:run build --help` for the full option list. Scripts can call
the same engine through the `-X` map API:
`clojure -X smia.api/build :book-root '"manual"'`.

While writing, `clojure -M:run preview manual` rebuilds the screen edition on
every save. The JVM stays warm between rebuilds, so a save takes about 150 ms.
A save that fails to build prints the error and the watcher keeps running; stop
it with Ctrl-C. Previewing the site edition (`preview manual --edition site`)
also serves it at `http://localhost:8000/`, because the site's directory URLs
need a web server to browse.

## Editions

One manuscript builds into several deliverable forms, selected with the
repeatable `--edition` flag:

- `screen`: a PDF with symmetric margins for on-screen reading (default).
- `print`: a PDF with mirrored recto/verso margins and a binding gutter
  (default).
- `print-x`: the print layout as PDF/X-4 for press submission, with embedded
  fonts, an ICC output intent, and no link annotations. Requires a
  `:book/print-x` map in `book.edn` naming the fonts and profile.
- `site`: a static HTML site under `build/<slug>/site/`, with one page per
  chapter and a stylesheet generated from the same `theme.edn`. Each page is
  the `index.html` of its own directory, cross-references become relative
  links, and footnotes collect at each chapter's end. The page framing is
  selectable in `theme.edn`: a plain reading column or a sidebar layout with a
  table of contents. The output contains no JavaScript and needs nothing but a
  static file host.
- `epub`: an EPUB3 package at `build/<slug>/epub/<slug>.epub`, validated with
  epubcheck. The same pages as the site, packaged for e-readers.

Numbering and cross-reference resolution run once, before any format-specific
rendering. Every edition therefore agrees on the book's structure.

## Build output

Every edition is written under one gitignored directory, `build/<slug>/`
(override with `--output-root`). Rebuilds overwrite in place. The site edition
also removes pages whose chapters no longer exist, while files you add
yourself, such as a `CNAME`, are kept. Pass `--clean` to delete `build/<slug>/`
before building; it is a no-op under `--dry-run`.

`--licensee "Name <email>"` stamps a "Licensed to" line in the footer of every
PDF page, for distributing personalized copies. It affects the PDF editions
only and is not part of the manuscript.

## Documentation

The manual lives under `manual/`. Build it with the command above and read the
PDFs in `build/smia-manual/pdf/`, or read it
[online](https://smia.leifericf.com). It covers the quickstart, the Markdown
and Hiccup authoring vocabulary, `book.edn` configuration, theming and
editions, the build commands, book production, distribution, and the error
catalog. The design is covered in the manual's own design chapter.

## License

Eclipse Public License 2.0. See `LICENSE`.
