# clj-book

clj-book builds technical books as PDF on the JVM. You write your manuscript in
Markdown (or in Hiccup, the Clojure data it compiles to) and clj-book renders the
screen and print editions through [Apache FOP](https://xmlgraphics.apache.org/fop/).
It runs on nothing more than a JDK and the Clojure CLI. There is no Ruby toolchain
or asciidoctor underneath, and the renderer never shells out to an external binary,
so a build stays self-contained and reproducible.

A book is a `book.edn` file together with its chapter sources and a
`styles/tokens.edn`. From that, clj-book gives you parts and numbered chapters,
appendices, named front and back matter, figures and code listings with captions,
cross-references that read "Figure 1" instead of a bare page number, in-process
syntax highlighting, running heads, an index, and a bibliography.

## Building a book

The only prerequisites are a JDK and the Clojure CLI; everything else arrives as a
Maven dependency. To build the manual that ships with the repo:

```bash
clojure -X clj-book.api/build :book-root '"docs/manual"'
```

Without a `:profiles` option, both the screen and print editions are produced under
`build/clj-book-manual/pdf/`, next to a machine-readable `artifacts.edn` manifest.
Use `clj-book.api/validate` to check a manuscript without rendering anything.

## Documentation

The manual is itself a clj-book manuscript, under `docs/manual/`. Build it (the
command above) and read the PDFs in `build/clj-book-manual/pdf/`. It walks through
the quickstart, the Markdown and Hiccup authoring vocabulary, `book.edn`
configuration, theming and profiles, the build commands, producing a finished
book, and the error catalog.

Design notes and the architecture live in `docs/plans/`.

## License

Eclipse Public License 2.0. See `LICENSE`.
