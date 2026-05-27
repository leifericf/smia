# clj-book

A PDF-first JVM Clojure publishing engine for technical books.

`clj-book` turns a Clojure-data manuscript into publication-quality **PDF** —
screen and print editions — entirely on the JVM via [Apache FOP](https://xmlgraphics.apache.org/fop/).
There is no Ruby, no asciidoctor, no external binary, and **no subprocess**:
FOP runs in-process. Authors write **Hiccup** (the HTML-flavored data Clojure
already produces); the engine treats it as the convenient surface of a true
superset that reaches the entire XSL-FO formatting model.

## Status

Pre-release. APIs and contracts may change.

## Pipeline

```
author hiccup  (HTML sugar + book extensions + raw :fo/*)
  → assemble   chapters + metadata + theme → :fo/root          [pure]
  → expand     known tags → FO; identity pass-through for :fo/* [pure]
  → serialize  FO-hiccup → XSL-FO XML                           [pure]
  → render     Apache FOP: FO XML → PDF, per profile            [shell]
```

## Quickstart

Prerequisites: a JDK and the Clojure CLI. That's all — Apache FOP arrives as a
Maven dependency (it pulls Batik and XML Graphics Commons; all Apache-2.0).

A minimal manuscript repo:

```
my-book/
  book.edn             # metadata + chapter order (open map)
  styles/tokens.edn    # design tokens → the FO theme
  chapters/*.clj        # chapter sources: Clojure that evaluates to Hiccup
```

A chapter file evaluates to a `[:chapter …]` form:

```clojure
[:chapter {:id :intro :title "Introduction"}
 [:p "Plain prose with " [:strong "emphasis"] " and " [:code "inline code"] "."]
 [:admonition {:kind :note} [:p "Worth knowing."]]
 [:p "See " [:xref {:to :config}] " to configure."]
 [:fo/block {:space-before "12pt"} "Drop to raw FO only when you need to."]]
```

Validate:

```bash
clojure -X clj-book.api/validate :book-root '"my-book"'
```

Build (both editions by default; pass `:profiles` to select a subset):

```bash
clojure -X clj-book.api/build :book-root '"my-book"' :profiles '[:screen :print]'
```

Output lands under `build/<slug>/pdf/` with deterministic names
(`<slug>-screen.pdf`, `<slug>-print.pdf`) plus an `artifacts.edn` manifest.

## The Hiccup superset

One syntax, three concentric layers:

1. **HTML-flavored sugar** — `:p`, `:h1`–`:h6`, `:ul`/`:ol`/`:li`, `:strong`,
   `:em`, `:code`, `:pre`, `:a`, `:blockquote`, `:img`, `:hr`, and tables.
2. **Book extensions** — `:chapter`, `:xref`, `:footnote`, `:admonition`.
3. **Raw FO** — any `:fo/*` tag passes straight through, so 100% of XSL-FO is
   reachable. Sugar nested inside raw FO still expands.

Styling comes from `tokens.edn` plus the layout profile — never from arbitrary
CSS. The two profiles, `:screen` (symmetric margins) and `:print` (mirrored
recto/verso with a binding gutter), are two layouts over one document model.
The default theme uses the PDF base-14 fonts, so output is zero-config and
reproducible.

## Architecture

A **functional core behind an imperative shell**. Assembly, expansion, and
serialization are pure transforms over plain data; the only effects are
reading inputs, evaluating chapters, and FOP writing PDF bytes. Values that
cross context seams are checked against malli schemas (`clj_book/schema.clj`).

| Context       | Namespaces                                              | Role                                            |
|---------------|---------------------------------------------------------|-------------------------------------------------|
| Manuscript    | `config`                                                | load + validate `book.edn`                      |
| Theme         | `theme.load`, `book.theme`                              | tokens → FO style + page masters                |
| Renderer (L1) | `fo.attrs`, `fo.serialize`, `fo.expand`, `fo.schema`    | Hiccup superset → XSL-FO (pure)                 |
| Renderer (L1) | `fo.render`                                             | XSL-FO → PDF via Apache FOP (shell)             |
| Book (L2)     | `book.assemble`, `book.load`                            | chapters → one `:fo/root`; load `.clj` chapters |
| Build         | `build.plan` (pure), `build.execute` (shell)            | plan the build, then perform it                 |
| Interface     | `api`, `request`                                        | `-X` entry points                               |
| Shared kernel | `error`, `schema`, `artifacts`                          | structured errors, value contracts, manifest    |

The interface routes through `build.execute` only; the pure cores do no IO and
never touch FOP. Both invariants are enforced in `clj-book.boundaries-test`.

> **Trust boundary:** chapters are `.clj` files, so building a book runs the
> author's own Clojure code (the same model as Pollen). Only build manuscripts
> you trust.

## Documentation

The full user manual is itself a Hiccup manuscript under `docs/manual/`, built
by `clj-book` as a dogfood and the platform's real-manuscript regression case.

## License

Distributed under the Eclipse Public License 2.0. See `LICENSE`.
