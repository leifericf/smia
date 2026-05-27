# clj-book

A reusable JVM Clojure publishing engine for technical books.

`clj-book` separates platform concerns (manuscript loading, theme compilation, document rendering, build orchestration) from manuscript concerns (book text, structure metadata, design tokens, customization, assets). Author input is AsciiDoc. HTML is generated server-side via Hiccup; CSS via Garden. DocBook 5 is an internal intermediate.

## Status

`v1.0.0-alpha` — pre-release. APIs and contracts may change.

## v1 targets

- `:site` — static HTML + CSS, no client-side JavaScript (Stasis + Hiccup)
- `:pdf`  — Asciidoctor PDF

## Quickstart

Prerequisites:

- JDK 17+
- Clojure CLI (`clojure -X`)
- Asciidoctor CLI (`asciidoctor`) for the `:site` target's DocBook intermediate
- Asciidoctor PDF CLI (`asciidoctor-pdf`) for the `:pdf` target

A minimal manuscript repo contains:

```
my-book/
  book.adoc            # AsciiDoc document header + chapter includes
  book.edn             # build configuration (open map)
  styles/tokens.edn    # canonical design tokens
  chapters/*.adoc      # chapter source files
```

Validate:

```bash
clojure -X clj-book.api/validate :book-root '"."'
```

Build:

```bash
clojure -X clj-book.api/build :book-root '"."' :targets '[:site :pdf]'
```

Serve (local preview, site target):

```bash
clojure -X clj-book.api/serve :book-root '"."'
```

## Customization tiers

1. **Tokens** — `styles/tokens.edn` (cross-target)
2. **Layout** — tier-2 keys in `book.edn`: `:page-size`, `:page-margins`, `:chapter-opener`, `:toc-depth`, `:code-line-numbers`, `:admonition-style`
3. **Escape hatches** — `styles/site.clj` (Garden, site only) and `styles/pdf-theme.edn` (PDF only)

## Architecture

The platform is a **functional core behind an imperative shell**. Pure
transforms (validation, theme compilation, the DocBook → HTML document
model, build planning) take and return plain Clojure data; all IO and
shelling out live in a thin shell. Values that cross context seams are
checked against malli schemas (`clj_book/schema.clj`).

Bounded contexts (DDD):

| Context       | Namespaces                                            | Role                                          |
|---------------|-------------------------------------------------------|-----------------------------------------------|
| Manuscript    | `config`                                              | load + validate `book.edn`                    |
| Theme         | `theme.load`, `theme.css`, `theme.pdf`                | tokens + escape hatches → CSS / PDF theme     |
| Document      | `compose`, `docbook`, `document`                      | master adoc → DocBook → semantic HTML model   |
| Render        | `targets.site`, `targets.pdf`                         | HTML model + theme → site / PDF artifacts     |
| Build         | `build.plan` (pure), `build.execute` (shell)          | plan the build, then perform it               |
| Interface     | `api`, `serve`, `request`                             | `-X` entry points + preview server            |
| Shared kernel | `error`, `schema`                                     | structured errors + value contracts           |

The interface layer routes through `build.execute` only; the pure cores
do no IO. Both invariants are enforced as tests in
`clj-book.boundaries-test`.

## Documentation

The full user manual is built by `clj-book` itself from `docs/manual/` as a dogfood manuscript.

## License

Distributed under the Eclipse Public License 2.0. See `LICENSE`.
