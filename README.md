# Smia

Smia builds technical books. From one Markdown manuscript it produces screen and
print PDFs, a press-ready PDF/X, an EPUB, and a static website. Manuscripts can
also be written in Hiccup, the Clojure data that Markdown compiles to. The name
is Norwegian for a smithy.

Smia is written in Clojure and renders PDFs with
[Apache FOP](https://xmlgraphics.apache.org/fop/). A JDK and the Clojure CLI are
the only prerequisites; everything else arrives as a Maven dependency, and the
whole build runs inside one JVM process.

**[Read the manual online](https://smia.leifericf.com)**. Smia builds and
publishes its own manual. It walks from the quickstart to a finished, published
book, covers every feature on the way, and its design chapter explains how Smia
works inside. Download any edition from the site's Downloads page or from the
repository's [Releases](https://github.com/leifericf/smia/releases/latest).

## Quick start

```bash
clojure -M:run init my-book      # scaffold a minimal, buildable book
clojure -M:run build my-book     # write the screen and print PDFs
clojure -M:run preview my-book   # rebuild on every save
```

A book is a `book.edn`, its chapter sources, and a `theme.edn`. Output lands
under `build/<slug>/`, and the repeatable `--edition` flag selects the site,
the EPUB, and the press-ready PDF/X beyond the default PDFs.

Optional capabilities sit behind deps aliases composed with the command:
`:math` and `:diagrams` for the build-time SVG renderers, `:cljs` for the
site's opt-in script islands. The manual that ships in this repository uses
all three:

```bash
clojure -M:run:cljs:math:diagrams build manual --edition site
```

See `clojure -M:run build --help` for the full option list, or
[the commands chapter](https://smia.leifericf.com/manual/part-2/commands/)
for the whole command set.

## License

Eclipse Public License 2.0. See `LICENSE`.
