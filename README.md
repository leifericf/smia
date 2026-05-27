# clj-book

A PDF-first JVM Clojure publishing engine for technical books. You write
**Hiccup**; `clj-book` renders **PDF** — screen and print editions — entirely
on the JVM via [Apache FOP](https://xmlgraphics.apache.org/fop/). No Ruby, no
asciidoctor, no external binary, no subprocess.

## Documentation

The manual is itself a `clj-book` manuscript under `docs/manual/`. The only
prerequisites are a JDK and the Clojure CLI; build it with:

```bash
clojure -X clj-book.api/build :book-root '"docs/manual"'
```

Then read `build/clj-book-manual/pdf/`. It covers the quickstart, the Hiccup
authoring vocabulary, `book.edn` configuration, theming and profiles, the
commands, and the error catalog.

Design notes and the architecture live in `docs/plans/`.

## License

Eclipse Public License 2.0. See `LICENSE`.
