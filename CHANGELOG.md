# Changelog

All notable changes to clj-book are recorded in this file. Format
follows the spirit of [Keep a Changelog](https://keepachangelog.com/).

## Unreleased — PDF-first redesign

clj-book is being repositioned as a **PDF-first** publishing engine: authors
write Clojure **Hiccup** and the engine renders **PDF** (screen and print
editions) entirely on the JVM via **Apache FOP** — no Ruby, no asciidoctor, no
external binary, no subprocess. This supersedes the earlier AsciiDoc /
static-site direction. The work is in progress; the bullets below track what has
landed on the redesign branch.

### Removed

- The static-site (`:site`) target (Stasis + Hiccup HTML + Garden CSS) and the
  local preview server (`clj-book.api/serve`), along with the Ring/Jetty
  dependency.
- The DocBook bridge (`clj-book.docbook`, `clj-book.docbook.parse`), the HTML
  document model (`clj-book.document`), the master composer
  (`clj-book.compose`), and the CSS theme compiler (`clj-book.theme.css`).
- The Asciidoctor PDF target (`clj-book.targets.pdf`), the PDF theme YAML
  compiler (`clj-book.theme.pdf`), and the subprocess kernel
  (`clj-book.proc`) — with them the Ruby / asciidoctor toolchain dependency
  and the `clj-yaml` dependency. The engine no longer shells out to any
  external process.

### Added

- The L1 renderer core (pure, no IO, no FOP): `clj-book.fo.attrs`
  (FO attribute name/value mapping, sorted for reproducibility),
  `clj-book.fo.serialize` (hand-rolled FO-Hiccup -> XSL-FO XML; XML
  escaping; single `xmlns:fo` on `fo:root`; no pretty-printing so
  `white-space="pre"` survives), `clj-book.fo.expand` (the three-layer
  Hiccup superset: HTML sugar + book extensions expand to FO, raw `:fo/*`
  passes through with nested sugar still expanded, unknown tags are a
  hard error), and `clj-book.fo.schema` (malli vocabulary with humanized
  errors; `:fo/*` accepted and left to FOP).

### Changed

- The build vocabulary moved from format `:targets` (`#{:site :pdf}`) to
  layout `:profiles` (`#{:screen :print}`). A build with no `:profiles`
  now renders both editions by default; a subset may be selected.
- `book.edn` no longer interprets the AsciiDoc-PDF layout keys
  (`:page-size`, `:page-margins`, `:chapter-opener`, `:toc-depth`,
  `:code-line-numbers`, `:admonition-style`); styling comes from
  `tokens.edn` and the profile instead. Required keys, types, and chapter
  existence/uniqueness are still enforced; extra keys are preserved.

### Retained spine

- `clj-book.api/validate` and `clj-book.api/build` entrypoints (`build`
  rendering is temporarily unavailable while the FOP pipeline is wired in).
- `book.edn` open-map configuration and `styles/tokens.edn` loading/validation.
- Deterministic output layout under `<output-root>/<book-slug>/` and the
  machine-readable `artifacts.edn` manifest.
- Structured `ex-info` errors with `:error/type`, `:error/message`,
  `:error/context`.
- `:dry-run` option for `clj-book.api/build`: returns the build plan without
  rendering or writing artifacts.

## v1.0.0-alpha — superseded

The pre-release described below was the AsciiDoc / multi-format design now
replaced by the PDF-first redesign above. Recorded for history.

### Added

- `clj-book.api/validate`, `clj-book.api/build`, `clj-book.api/serve` entrypoints.
- `:site` target (Stasis + Hiccup, HTML + CSS only, no client-side JavaScript).
- `:pdf` target (Asciidoctor PDF).
- Three-tier customization model:
  - `styles/tokens.edn` (tier 1) compiled to CSS custom properties and PDF theme YAML
  - `book.edn` layout keys (tier 2): `:page-size`, `:page-margins`, `:chapter-opener`, `:toc-depth`, `:code-line-numbers`, `:admonition-style`
  - `styles/site.clj` and `styles/pdf-theme.edn` (tier 3 escape hatches)
- Deterministic output layout under `<output-root>/<book-slug>/`.
- Machine-readable artifact manifest at `artifacts.edn`.
- Structured `ex-info` errors with `:error/type`, `:error/message`, `:error/context`.
- Synthetic fixture-based test suite plus dogfood `docs/manual/` manuscript.
- `:dry-run` option for `clj-book.api/build`: prints and returns the build
  plan without rendering targets or writing artifacts.

### Changed

- Reorganized the platform into bounded contexts with a functional core /
  imperative shell split. Pure transforms (validation, theme compilation,
  the DocBook to HTML document model, build planning) are separated from
  the IO shell. New namespaces: `clj-book.theme.{load,css,pdf}`,
  `clj-book.document`, `clj-book.build.{plan,execute}`, `clj-book.schema`.
  The `clj-book.pipeline` and `clj-book.tokens.*` namespaces were retired.
- Added malli schemas for values that cross context seams; they are
  checked in the shell, never inside a pure core.
- Renamed Theme error types from `:clj-book.tokens*` to
  `:clj-book.theme.load/*` (for example `:clj-book.theme.load/missing`).
- Centralized all subprocess execution in a single `clj-book.proc`
  kernel; `docbook` and the PDF target no longer construct their own
  `ProcessBuilder`s.
- Split the DocBook bridge: `clj-book.docbook` now only produces XML
  (shell), and the pure XML-to-Hiccup parser moved to
  `clj-book.docbook.parse`.
- Consolidated chapter validation (existence and uniqueness) in the
  Manuscript context; `compose` now only assembles. The duplicate-chapter
  error moved from `:clj-book.compose/duplicate-chapter` to
  `:clj-book.config/duplicate-chapter`, and `:clj-book.compose/missing-chapter`
  is gone (use `:clj-book.config/missing-chapter`).
- Made each target self-contained: it compiles its own theme (site -> CSS,
  pdf -> YAML). The only shared build prerequisite is now the master adoc,
  so a site-only build no longer emits a PDF theme (and vice versa).

### Removed

- The unused `:profile` request field, which was normalized but never read.
- The unused intermediate `intermediate/tokens/site.css`, which was
  written but never consumed (the site target compiles its own CSS).

### Fixed

- Multi-chapter builds now resolve their chapter includes. Both the
  DocBook (site) and asciidoctor-pdf (PDF) invocations run with
  `--base-dir` (the manuscript root) and `--doctype book`, so the
  generated master's relative `include::` paths resolve and level-1
  headings become chapters. Previously the master was processed from the
  intermediate dir, yielding a one-page/one-section result full of
  unresolved-directive text instead of a multi-chapter book.

### Excluded (not in v1 alpha)

- Default target behavior (explicit targets only).
- Datomic dependency.
- EPUB / ebook targets.
- Client-side JavaScript runtime.
- Packaged binary CLI.
