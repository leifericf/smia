# clj-book

A reusable JVM Clojure publishing engine for technical books.

`clj-book` separates platform concerns (pipeline, rendering adapters, validation, orchestration) from manuscript concerns (book text, structure metadata, design tokens, customization, assets). Author input is AsciiDoc. HTML is generated server-side via Hiccup; CSS via Garden. DocBook 5 is an internal intermediate.

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

## Documentation

The full user manual is built by `clj-book` itself from `docs/manual/` as a dogfood manuscript.

## License

Distributed under the Eclipse Public License 2.0. See `LICENSE`.
