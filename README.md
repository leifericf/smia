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

With no `--profile` option, both the screen and print editions are produced under
`build/clj-book-manual/pdf/`, next to a machine-readable `artifacts.edn` manifest.
Run `clojure -M:run validate manual` to check a manuscript without rendering,
and `clojure -M:run build --help` for the full option list. Scripts and other tools
can call the same engine through the `-X` map API
(`clojure -X clj-book.api/build :book-root '"manual"'`).

For a live authoring loop, `clojure -M:run preview manual` builds the screen
edition and then rebuilds it on every save in the same warm JVM — around 150 ms
a save. A save that fails prints the error and keeps watching; stop with
Ctrl-C.

## Documentation

The manual is itself a clj-book manuscript, under `manual/`. Build it (the
command above) and read the PDFs in `build/clj-book-manual/pdf/`. It walks through
the quickstart, the Markdown and Hiccup authoring vocabulary, `book.edn`
configuration, theming and profiles, the build commands, producing a finished
book, and the error catalog. The design and architecture are covered in the
manual's own design chapter.

## License

Eclipse Public License 2.0. See `LICENSE`.
