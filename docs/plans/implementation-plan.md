# clj-book Implementation Plan — PDF-first redesign

## Status

This plan supersedes the earlier AsciiDoc / multi-format plan. It describes
building the repositioned product: a **PDF-first** engine that renders Clojure
**Hiccup** manuscripts to PDF (screen and print) entirely on the JVM via **Apache
FOP**.

It is partly a rebuild and partly a reuse. The spine carries over from the current
codebase — `error`, `schema`, `request`, `config` (`book.edn`), the malli-checked
seams, deterministic output, and the functional-core / imperative-shell
discipline. The rendering half is replaced: AsciiDoc/DocBook/asciidoctor,
`targets.site`, `theme.css`/`theme.pdf`, `compose`, `docbook`, the `proc`
subprocess kernel, and the site `serve` are retired in favor of a
`hiccup → XSL-FO → FOP → PDF` pipeline.

Locked product decisions:

- JVM Clojure (`deps.edn`), `clojure -X` command surface.
- Authoring is **Hiccup** — a true superset: HTML-flavored sugar, book extensions,
  and first-class raw `:fo/*` (full XSL-FO reachable by construction).
- Output is **PDF** in `:screen` and `:print` profiles. No HTML site, no EPUB.
- Rendering is **100% JVM** via Apache FOP, in-process. No Ruby, no asciidoctor,
  no external binary, no subprocess.
- `book.edn` carries metadata + chapter order + build config; `tokens.edn` is the
  cross-profile theme compiled to FO.
- Styling comes from tokens/profile/class vocabulary, never arbitrary CSS.
- malli validates the sugar/book vocabulary; raw FO is trusted and validated by
  FOP, whose diagnostics are surfaced.
- Determinism: byte-reproducible PDFs (pinned FOP metadata).
- The `clj-book` manual is itself a Hiccup manuscript built by `clj-book`
  (dogfooding).
- Licensed under **EPL 2.0**; Apache FOP and its deps are Apache-2.0
  (EPL-compatible).

## Traceability and discipline

Anchor all work to:

- User-facing requirements + BDD scenarios: `docs/plans/user-affordances.md`
- Architecture constraints: `docs/plans/technical-design.md`
- Clojure coding standards (normative): the project coding-standards gist
  (functional core/imperative shell, naming, REPL/TDD workflow, idiomatic data
  usage). If a plan step conflicts with the standards, the standards win.

Per-phase execution rule:

1. Identify the requirement bullets and matching Gherkin scenarios.
2. Write failing tests encoding those scenarios first.
3. Implement the minimal code to pass, following the standards.
4. Refactor while green.
5. Update docs only if a contract changed.

No phase is complete without failing-first tests traceable to a requirement or
scenario.

## Open decisions to resolve before/within the relevant phase

1. **Chapter files: `.edn` vs `.clj`** (pure data vs programmable, eval-at-build).
   Gate for Phase 5.
2. **Profiles: one or two by default** (`:screen` and/or `:print`). Gate for
   Phase 7.
3. **Namespace scheme + FO property representation** (compound props, units, SVG).
   Gate for Phase 4.
4. **v1 sugar element set + unknown-tag policy** (warn / error / treat as raw FO).
   Gate for Phase 4.
5. **Code highlighting** (server-side JVM vs plain monospace). Gate for Phase 6.
6. **Optional `(markdown "…")` front-end**. Out of scope unless promoted.

---

## 0. Release definition

Included for the first PDF-first release:

- `validate`, `build`, and optional `preview` entrypoints.
- The Hiccup superset: HTML sugar + book extensions + raw `:fo/*`.
- `tokens.edn` → FO theme; `:screen` and `:print` profiles.
- Deterministic PDF output + artifact manifest.
- Synthetic Hiccup fixtures + a dogfood manual under `docs/manual/`.

Excluded: HTML/site output, EPUB, arbitrary CSS, external binaries, Datomic,
WYSIWYG.

Exit criteria:

1. All acceptance tests pass in CI.
2. `build` renders a valid manuscript to PDF for `:screen` and `:print`.
3. Raw `:fo/*` passes through; malformed sugar/book Hiccup fails with a humanized
   schema error.
4. PDFs are byte-reproducible; the manifest is emitted.
5. The `docs/manual/` Hiccup manuscript builds end-to-end via `clj-book` in CI.

---

## 1. Foundation and carry-over

Goal: a clean baseline reusing the spine, with the new dependency set.

- **Dependencies:** keep `org.clojure/clojure` and `metosin/malli`; add Apache FOP
  (`org.apache.xmlgraphics/fop`, pulling Batik + XML Graphics Commons) and an
  XML emitter (`org.clojure/data.xml` or equivalent). Remove `stasis`, `hiccup`
  (HTML), `garden`, `clj-commons/clj-yaml`, and the Ring/Jetty site server.
- **Carry over unchanged or lightly adapted:** `error`, `schema`, `request`,
  `config`.
- **Retire:** `compose`, `docbook`, `docbook.parse`, `document` (HTML model),
  `targets.site`, `targets.pdf` (asciidoctor-pdf), `theme.css`, `theme.pdf`,
  `proc`, site `serve`.
- Refresh `LICENSE`/`README` to the PDF-first framing; keep EPL-2.0.

Acceptance: project compiles and tests run with the new deps; retired namespaces
removed; non-goals updated.

## 2. Request and error contract

Goal: public behavior before internals (mostly reuse).

- Adapt `request` to the new shape: profiles instead of format targets; validate
  `:profiles` (subset of `#{:screen :print}`), `:book-root`, `:output-root`.
- Keep `error` constructors and the structured `ex-info` contract.

Acceptance: request/error tests pass and map to the relevant scenarios.

## 3. Configuration contract (`book.edn`)

Goal: robust open-map input validation (reuse + extend).

- `book.edn` now carries authorial metadata (title/author) — there is no AsciiDoc
  header — plus chapter list/order and build config.
- Validate required keys/types; preserve extras; check chapter files exist; reject
  duplicates (the Manuscript context owns chapter validation).

Acceptance: valid configs pass; missing key / missing chapter / duplicate chapter
fail with descriptive errors.

## 4. The Hiccup → XSL-FO compiler (L1 core)

Goal: the heart of the engine — a pure `hiccup → FO` transform.

- **Resolve open decisions 3 and 4** (namespace scheme, property representation,
  v1 sugar set, unknown-tag policy).
- **Schema:** malli schemas for the sugar/book vocabulary.
- **Expand:** a tree walk that maps HTML sugar and book extensions to FO hiccup;
  identity pass-through for `:fo/*`; defined unknown-tag policy.
- **Serialize:** generic Hiccup → FO XML with the `fo:` namespace, including
  compound properties and units.
- Tests: each supported element → expected FO; raw `:fo/*` verbatim; malformed
  input → humanized malli error; serialization is deterministic and
  referentially transparent.

Acceptance: the pure compiler is exercised on in-memory Hiccup with no files and
no FOP, producing expected FO.

## 5. Manuscript assembly (L2)

Goal: turn a book's chapters into one document model + FO body.

- **Resolve open decision 1** (`.edn` vs `.clj` chapter files); implement loading
  accordingly (and, if `.clj`, document the build-runs-author-code boundary).
- Assemble chapters in `book.edn` order; wrap each as a page-sequence; generate
  TOC (headings → entries with `fo:leader` + page-number-citation), running heads
  (`fo:marker`), PDF bookmarks, and resolve `[:xref]` to `fo:page-number-citation`.

Acceptance: a multi-chapter synthetic manuscript assembles into a single FO
document with TOC, bookmarks, and resolved cross-references (verified on the FO
tree, pre-FOP).

## 6. Theme and tokens → FO

Goal: one theme compiled to FO styling.

- **Resolve open decision 5** (code highlighting).
- `tokens.edn` → FO-Hiccup theme fragments + FO properties (fonts, sizes, spacing,
  color); a small class vocabulary for admonitions; font embedding configuration.
- Tests: tokens drive the FO properties; missing tokens fails clearly.

Acceptance: theme application is deterministic; fonts embed; no CSS interpretation.

## 7. FOP rendering + profiles (imperative shell)

Goal: FO XML → PDF, per profile, reproducibly.

- **Resolve open decision 2** (one or two profiles by default).
- `clj_book.fop`: invoke FOP in-process to render FO → PDF; pin creation
  date/producer for byte-reproducible output.
- `:screen` and `:print` profiles = different page-master sets over the same body.
- Tests: a PDF is produced; page count/structure sane; identical inputs → identical
  bytes; print profile applies gutter/recto-verso and page-number citations.

Acceptance: `build` produces PDFs for the configured profiles; determinism holds.

## 8. Artifact manifest

Goal: machine-readable build output (reuse).

- Emit `artifacts.edn` listing profiles, produced paths, timestamps (clock owned
  by the shell), and tool metadata.

Acceptance: CI can parse the manifest; shape is covered by tests.

## 9. Preview (optional)

Goal: a tight author loop without the old web server.

- `preview`: rebuild on change and (optionally) open the PDF. No HTTP server.

Acceptance: smoke test for rebuild; documented workflow.

## 10. Documentation and dogfood manual

Goal: adoption-ready docs; the manual proves the engine.

- README quickstart: prerequisites (just the JVM), minimal Hiccup manuscript,
  `validate`/`build` examples.
- Contract docs: `book.edn` keys, `tokens.edn` groups, the Hiccup superset (sugar
  elements, book extensions, raw FO), profiles, error catalog.
- **Re-author `docs/manual/` in Hiccup** (replacing the AsciiDoc chapters); build
  it via `clj-book` for the configured profiles in CI as the real-manuscript
  regression case.

Acceptance: a new author can adopt from docs; the manual builds end-to-end.

## 11. CI and hardening

Goal: reproducible builds and enforced non-goals.

- CI: run tests; build the `docs/manual/` Hiccup manuscript to PDF; verify
  determinism where feasible.
- Non-goal enforcement (as regression tests): no external-binary/subprocess
  dependency; no HTML/site output; no arbitrary-CSS interpretation; no Datomic;
  no third-party manuscript content in the repo (the dogfood manual is permitted);
  EPL-2.0 present and referenced.
- Boundary tests (carried over and adapted): interface routes through the build
  layer; pure cores (`fo` expansion/serialization, `theme`, `book` assembly,
  `config` validate) do no IO and shell out to nothing.

Acceptance: CI green; non-goals and FC/IS boundaries machine-checked.

---

## Suggested milestones

- **Milestone A — Foundation:** Phases 1–3 (deps swap, request/error, `book.edn`).
- **Milestone B — Renderer core:** Phases 4–5 (Hiccup→FO compiler + assembly).
- **Milestone C — Theme + PDF:** Phases 6–7 (tokens→FO, FOP, profiles).
- **Milestone D — Output + loop:** Phases 8–9 (manifest, preview).
- **Milestone E — Docs + CI:** Phases 10–11 (dogfood manual, CI, hardening).

## Implementation discipline

- TDD for every behavior; pure logic in cores, effects only at the FOP and
  file-IO edges.
- Preserve structured errors and byte-deterministic outputs as non-negotiable.
- Treat the affordance and architecture docs as source-of-truth constraints.
