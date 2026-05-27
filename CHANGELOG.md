# Changelog

All notable changes to clj-book are recorded in this file. Format
follows the spirit of [Keep a Changelog](https://keepachangelog.com/).

## v1.0.0-alpha — Unreleased

Initial pre-release.

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

### Excluded (not in v1 alpha)

- Default target behavior (explicit targets only).
- Datomic dependency.
- EPUB / ebook targets.
- Client-side JavaScript runtime.
- Packaged binary CLI.
