# clj-book Technical Design and Architecture (v1)

## Purpose

`clj-book` is a reusable JVM Clojure publishing engine for technical books.

The target user is a semi-technical author with programming experience (see `docs/plans/user-affordances.md` § Target User). API surface, error messages, and documentation tone all assume this audience: descriptive `ex-info` errors are appropriate, Garden/EDN are appropriate customization surfaces, and CLI invocation is the canonical entrypoint. Non-technical authoring layers, if ever built, will sit on top of `clj-book` as separate projects — they do not constrain the platform's design.

It separates:

- platform concerns (pipeline, rendering adapters, validation, orchestration), and
- manuscript concerns (book text, structure metadata, design tokens, customization, assets).

All Clojure-side generation is data-oriented: HTML via Hiccup and CSS via Garden. AsciiDoc remains the author input language; DocBook 5 exists as an internal intermediate only.

Primary v1 outputs:

- `:site` (Stasis + Hiccup, HTML + CSS only, no client-side JavaScript)
- `:pdf` (Asciidoctor PDF)

## Architectural Principles

- Data-oriented design: all pipeline inputs/outputs are plain Clojure data.
- Functional core, imperative shell: pure transforms in core; I/O in adapters.
- Explicitness over defaults: no implicit build targets.
- Open maps for user config: enforce required keys, permit additional keys.
- Determinism: identical inputs produce stable output layout and metadata shape.

## High-Level System Context

Inputs (required):

- Manuscript repository root (`:book-root`)
- `book.adoc` (AsciiDoc document header carries authorial metadata)
- `book.edn` (minimal build configuration map; also carries tier-2 layout configuration)
- `styles/tokens.edn` (EDN-native design tokens, conceptually modeled on W3C Design Tokens)
- chapter source files (`.adoc`) referenced from the configuration

Inputs (optional, tier-3 escape hatches):

- `styles/site.clj` (Garden source; appended to the token-derived CSS for the site target)
- `styles/pdf-theme.edn` (EDN extras merged into the token-derived Asciidoctor PDF theme YAML)

Engine:

- `clj-book` library functions (`clojure -X` entry points)
- optional local dev preview server for site

Outputs:

- site artifacts under deterministic build path
- PDF artifact under deterministic build path
- artifact manifest with metadata and produced paths

## Repository Boundaries

- `clj-book` repo contains engine code, developer docs, synthetic fixtures, and the user manual.
- The user manual lives under `docs/manual/` and is structured as a manuscript repo (its own `book.adoc`, `book.edn`, `styles/tokens.edn`, and chapter sources). It is built by `clj-book` itself as a dogfood manuscript and acts as the platform's primary real-manuscript regression case.
- Third-party manuscript content lives in separate manuscript repos.
- Manuscript repos consume `clj-book` via `deps.edn` dependency.

## Runtime and Toolchain

- Runtime: JVM Clojure with `deps.edn`.
- Command interface: `clojure -X ...`.
- External tooling:
  - Asciidoctor CLI (HTML and DocBook 5 backends)
  - Asciidoctor PDF CLI
  - Rouge (syntax highlighting for Asciidoctor)
- No Node.js, no ClojureScript toolchain, no bundler.

No Datomic dependency in v1.

## Public API Surface (Conceptual)

- `clj-book.api/validate`
- `clj-book.api/build`
- `clj-book.api/serve`

`build` requires explicit targets. No defaults.

## Request/Response Contracts

### Build Request Map

```clojure
{:book-root "."                 ;; required
 :config-path "book.edn"        ;; optional
 :targets [:site :pdf]           ;; required, non-empty
 :output-root "build"           ;; optional
 :profile :prod}                 ;; optional
```

### Validation Policy

- Required keys: missing/invalid => hard error.
- Optional keys: validated if present.
- Unknown keys: accepted and preserved.

### Error Contract

Errors are thrown as `ex-info` with structured `ex-data`.

```clojure
{:error/type :clj-book.validation/missing-targets
 :error/message "Build requires :targets with one or more values."
 :error/context {:targets nil}}
```

### Artifact Manifest Contract

```clojure
{:book/slug "example-book"
 :build/started-at "2026-05-27T12:00:00Z"
 :build/finished-at "2026-05-27T12:00:08Z"
 :build/targets [:site :pdf]
 :artifacts [{:target :site :path "build/example-book/site/index.html"}
             {:target :pdf  :path "build/example-book/pdf/example-book.pdf"}]}
```

## Internal Module Architecture (Conceptual)

- `clj_book.api`
  - Public entrypoints (`validate`, `build`, `serve`).
- `clj_book.request`
  - Request normalization and top-level argument checks.
- `clj_book.error`
  - Error constructors and common error shapes.
- `clj_book.config`
  - `book.edn` loading, required-key validation, path checks.
- `clj_book.tokens`
  - `styles/tokens.edn` loading and schema validation (EDN-native design tokens).
- `clj_book.tokens.css`
  - Tokens -> Garden tree -> CSS custom properties compilation; appends user `styles/site.clj` Garden output if present.
- `clj_book.tokens.pdf`
  - Tokens -> Asciidoctor PDF YAML compilation; merges user `styles/pdf-theme.edn` on top if present.
- `clj_book.compose`
  - Generate master `book.adoc` with `include::` directives in configured chapter order.
- `clj_book.docbook`
  - Drive Asciidoctor's DocBook 5 backend to produce `book.xml` from the master `book.adoc`.
- `clj_book.pipeline`
  - Target graph execution and orchestration.
- `clj_book.targets.site`
  - Site target adapter (DocBook 5 -> Hiccup -> Stasis static export). HTML + CSS only.
- `clj_book.targets.pdf`
  - PDF target adapter (Asciidoctor PDF invocation on the master `book.adoc`).
- `clj_book.serve`
  - Local preview server for the site target.
- `clj_book.artifacts`
  - Artifact manifest generation/serialization.

## Pipeline Architecture

Execution model is step-based with explicit dependencies.

Base steps:

1. Validate request
2. Load/validate `book.edn` configuration (including tier-2 layout keys)
3. Load/validate `styles/tokens.edn`
4. Compose master `book.adoc` from chapter order
5. Compile design tokens to target artifacts via Garden (CSS custom properties) and YAML emission (PDF theme), then apply tier-3 escape hatches:
   - if `styles/site.clj` exists, load + eval, render via Garden, append to the site CSS
   - if `styles/pdf-theme.edn` exists, merge into the PDF theme map before YAML emission
6. Build requested targets:
   - `:site` -> Asciidoctor DocBook 5 backend -> Hiccup -> Stasis static export
   - `:pdf`  -> Asciidoctor PDF (consumes master `book.adoc` + PDF theme YAML)
7. Emit artifact manifest

Target-specific dependency behavior:

- `:site` requires steps 1-5, then DocBook generation and site adapter execution.
- `:pdf` requires steps 1-5, then PDF adapter execution.
- multi-target run executes shared prerequisites once.

## Customization System Design

User-controlled presentation is layered in three tiers. Higher tiers are cross-target by design; the tier-3 escape hatches are per-target by definition.

### Tier 1 — Design tokens (style, symmetric)

Single canonical source per manuscript: `styles/tokens.edn`. EDN-native design tokens (conceptually modeled on W3C Design Tokens / DTCG).

Compilation strategy:

- site CSS is generated by transforming the token map into a Garden data structure and rendering Garden -> CSS custom properties
- PDF theme YAML is generated by transforming the token map into the Asciidoctor PDF theme shape and emitting YAML

Parity policy:

- preserve shared design intent (color, type scale, spacing)
- where exact parity is impossible, apply deterministic fallback and expose warning metadata

### Tier 2 — Layout configuration (symmetric)

A deliberately small set of layout keys in `book.edn` translated to both targets. Initial v1 surface: `:page-size`, `:page-margins`, `:chapter-opener`, `:toc-depth`, `:code-line-numbers`, `:admonition-style`.

### Tier 3 — Per-target escape hatches (asymmetric)

Optional inputs activated when present:

- `styles/site.clj` (Garden source) — the engine loads the file, evaluates the top-level form to obtain a Garden data structure, renders it with `garden.core/css`, and appends the result to the token-derived stylesheet.
- `styles/pdf-theme.edn` (EDN) — the engine deep-merges this map into the token-derived Asciidoctor PDF theme map before YAML emission.

The platform does not validate semantic correctness of tier-3 contents; users accepting tier 3 also accept loss of automatic cross-target parity for the affected area.

## Site Target Design (`:site`)

- Rendering model: static HTML export via Stasis page map.
- HTML generation: DocBook 5 intermediate -> Hiccup -> HTML string per route.
- CSS generation: tokens -> Garden tree -> CSS; tier-3 `styles/site.clj` (Garden) appended if present.
- No client-side JavaScript runtime in v1. Site is HTML + CSS only.
- Interactivity is delivered via native HTML/CSS only (`<details>`, `:target`, `prefers-color-scheme`, etc.).
- Core outputs:
  - `index.html`
  - chapter pages
  - TOC page(s)
  - CSS asset (compiled from design tokens)
  - image assets copied from the manuscript
  - optional `sitemap.xml` and `robots.txt`

## PDF Target Design (`:pdf`)

- Rendering model: Asciidoctor PDF CLI invocation on the composed master `book.adoc`.
- Theme source: compiled YAML from canonical tokens, deep-merged with optional tier-3 `styles/pdf-theme.edn`.
- Preflight checks:
  - required CLI tools available
  - theme artifact exists
  - required source files exist
- Output: deterministic PDF path under output root.

## Output Layout

Deterministic layout rooted at `:output-root` (default `build`).

Example:

```text
build/
  <book-slug>/
    intermediate/
      book.adoc           # composed master with include:: directives
      book.xml            # DocBook 5 (site path only)
      tokens/
        site.css          # CSS custom properties
        pdf-theme.yml     # Asciidoctor PDF YAML
    site/
      index.html
      ...
    pdf/
      <book-slug>.pdf
    artifacts.edn
```

## Serve Mode Design

- `serve` runs local preview for site target only.
- It reuses manifest/theme loading and route generation.
- It is intentionally decoupled from PDF rendering.

## Testing Architecture (TDD)

- Unit tests for pure modules:
  - request validation
  - configuration validation
  - tokens validation + compilation
  - composition logic
  - artifact manifest generation
- Integration tests for adapters:
  - site target build output assertions
  - PDF target build behavior (including toolchain failure diagnostics)
- Fixtures:
  - synthetic manuscripts under `test/fixtures/` for unit + integration coverage
  - no third-party manuscript text in the platform repo
- End-to-end dogfood build:
  - the user manual at `docs/manual/` is built by `clj-book` for both `:site` and `:pdf` in CI
  - acts as a real-manuscript regression case complementing the synthetic fixtures

## Extensibility and Future Targets

- New targets are added via target registry + adapter namespace.
- v1 explicitly supports `:site` and `:pdf` only.
- Ebook formats (e.g. `:epub`) can be added later by:
  - defining target contract
  - reusing shared prerequisites
  - implementing adapter and tests

## Non-Goals (v1)

- No implicit/default target behavior.
- No client-side JavaScript runtime; no Node, no ClojureScript toolchain in v1.
- No Datomic dependency.
- No content hosting or manuscript storage inside `clj-book` repo.
- No standalone custom CLI binary (may come later).

## Risk Areas and Mitigations

- Toolchain variability (Asciidoctor installation differences)
  - mitigation: preflight checks + descriptive diagnostics.
- Token parity differences between CSS and PDF YAML
  - mitigation: explicit fallback rules and warning metadata.
- Configuration drift across manuscript repos
  - mitigation: strict required-key validation + documented contract.

## Adoption Path

1. Manuscript repo adds `clj-book` dependency.
2. Manuscript repo defines `book.adoc` (authorial metadata in the AsciiDoc header), `book.edn` (build configuration + tier-2 layout keys), and `styles/tokens.edn` (design tokens). Optionally adds `styles/site.clj` (Garden) and/or `styles/pdf-theme.edn` for tier-3 escape hatches.
3. User runs explicit `validate` and `build` commands with `:targets`.
4. CI consumes artifact manifest for downstream publication/deployment.
