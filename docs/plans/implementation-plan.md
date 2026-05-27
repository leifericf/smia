# clj-book v1.0.0-alpha Implementation Plan

This plan defines a progressive, step-by-step path from empty repository scaffolding to a finished `v1.0.0-alpha` product.

It is implementation-oriented, test-driven, and aligned with the locked product decisions:

- JVM Clojure (`deps.edn`) command surface
- explicit targets required (no defaults)
- AsciiDoc source; DocBook 5 as canonical intermediate for the site target (produced by Asciidoctor)
- `book.adoc` document header carries authorial metadata
- `book.edn` is the minimal build configuration map per manuscript and carries tier-2 layout keys
- `styles/tokens.edn`: EDN-native design tokens (conceptually modeled on W3C Design Tokens) per manuscript
- three-tier customization model: tokens (tier 1), layout config in `book.edn` (tier 2), per-target escape hatches `styles/site.clj` (Garden) and `styles/pdf-theme.edn` (tier 3)
- Hiccup for all internal HTML generation; Garden for all internal CSS generation and for the tier-3 site escape hatch
- `:site` (HTML + CSS only, no client-side JavaScript) and `:pdf` only in v1 alpha
- no Datomic in v1 alpha
- `clj-book` documentation is itself a manuscript built with `clj-book` (dogfooding)
- target user is a semi-technical author with programming experience (see `docs/plans/user-affordances.md` § Target User); non-technical authoring layers are out of scope for v1
- licensed under **EPL 2.0** (Eclipse Public License 2.0) — file-level copyleft consistent with the Clojure ecosystem; modifications to `clj-book` files come back upstream while downstream manuscript repos consuming `clj-book` as a dependency remain unaffected

## Traceability to Requirements and BDD

All implementation work must be anchored to these source documents:

- User-facing requirements: `docs/plans/user-affordances.md`
- BDD scenarios (Gherkin): `docs/plans/user-affordances.md` under `## BDD Scenarios (Gherkin)`
- Technical architecture constraints: `docs/plans/technical-design.md`
- Clojure coding standards (normative): `https://gist.github.com/leifericf/d90382db37f2deddafcdbfc0a8725942`

Coding standards enforcement rule:

- All Clojure/ClojureScript code and tests must follow the linked coding guidelines.
- During implementation, every PR/phase completion check should include a style/convention review against the gist (naming, namespace layout, functional core/imperative shell, REPL/TDD workflow, idiomatic collection/control-flow usage).
- If any plan step conflicts with the coding standards gist, the coding standards gist takes precedence.

Execution rule for every phase:

1. Identify the relevant requirement bullets and matching Gherkin scenarios.
2. Write failing tests that encode those scenarios before implementation.
3. Implement the minimal code to make those tests pass, following the coding standards gist.
4. Refactor while keeping tests green.
5. Update docs only if behavior/contract changed.

No phase is considered complete unless failing tests were written first and can be traced back to at least one requirement or Gherkin scenario.

---

## 0. Release Definition for v1.0.0-alpha

Before writing code, lock what alpha means.

### 0.1 Scope

Included:

- `validate`, `build`, `serve` entrypoints
- `:site` target (Stasis + Hiccup, HTML + CSS only, no client-side JavaScript)
- `:pdf` target (Asciidoctor PDF)
- three-tier customization: `styles/tokens.edn` (tier 1), tier-2 layout keys in `book.edn`, tier-3 escape hatches `styles/site.clj` (Garden) and `styles/pdf-theme.edn`
- Hiccup-based HTML generation; Garden-based CSS generation (internal and tier-3)
- canonical token compilation to CSS custom properties and PDF theme YAML
- deterministic output layout and artifact manifest
- synthetic fixture-based tests
- `clj-book` user manual built as a dogfood manuscript using `clj-book` itself

Excluded:

- default target behavior
- Datomic integration
- EPUB/eBook targets
- packaged binary CLI
- client-side JavaScript runtime (Node, ClojureScript, bundler)

### 0.2 Alpha Exit Criteria

`v1.0.0-alpha` is ready when:

1. All acceptance tests pass in CI.
2. `build` fails hard and descriptively when `:targets` are missing/invalid.
3. `build` succeeds for `[:site]`, `[:pdf]`, and `[:site :pdf]`.
4. Output paths are deterministic and artifact manifest is emitted.
5. Docs are sufficient for a new manuscript repo to adopt `clj-book`.
6. The bundled user manual at `docs/manual/` builds end-to-end via `clj-book` for both `:site` and `:pdf` targets in CI.

---

## 1. Scaffolding and Project Foundation

Goal: establish a clean standalone repo with predictable structure.

### 1.0 Create private GitHub repository

Create the repository as private from the start using `gh`:

```bash
gh repo create clj-book --private --clone
```

If creating from an existing local directory instead:

```bash
gh repo create clj-book --private --source=. --remote=origin --push
```

Acceptance:

- `clj-book` exists as a private GitHub repo under the intended owner.
- Local checkout has `origin` configured to the private repository.

### 1.1 Create repository skeleton

```text
clj-book/
  deps.edn
  README.md
  LICENSE              # EPL 2.0 full text
  src/clj_book/
  test/clj_book/
  resources/
  docs/
  .github/workflows/
```

Include the EPL 2.0 license text verbatim in `LICENSE`. Add a short license notice to `README.md` (`Distributed under the Eclipse Public License 2.0. See LICENSE.`).

### 1.2 Establish dependency baseline

- Add core deps in `deps.edn`:
  - `org.clojure/clojure`
  - `stasis/stasis`
  - `hiccup/hiccup` (server-side HTML generation)
  - `garden/garden` (CSS generation for tier-1 token compilation and tier-3 site escape hatch)
  - `clj-commons/clj-yaml` or equivalent (Asciidoctor PDF theme YAML emission)
- Add aliases:
  - `:test`
  - `:dev`
  - `:build`
  - `:serve`

### 1.3 Initial namespace scaffolding

Create empty/stub namespaces:

- `clj_book.api`
- `clj_book.request`
- `clj_book.error`
- `clj_book.pipeline`
- `clj_book.config`
- `clj_book.tokens`
- `clj_book.tokens.css`
- `clj_book.tokens.pdf`
- `clj_book.compose`
- `clj_book.docbook`
- `clj_book.targets.site`
- `clj_book.targets.pdf`
- `clj_book.artifacts`
- `clj_book.serve`

### 1.4 Add synthetic fixture layout for tests

Under `test/fixtures/`, create synthetic manuscript trees for valid/invalid cases.

### 1.5 Create traceability matrix and empty test inventory

Before implementation, add a mapping document in the new repo, e.g. `docs/traceability-matrix.md`:

- requirement bullet -> Gherkin scenario -> test namespace/test name
- include placeholder test identifiers for planned coverage

Start with failing placeholder tests that reference scenario IDs in test names or metadata.

Acceptance:

- Repo builds and tests run (even with placeholder failing tests).
- Traceability matrix exists and every planned behavior has at least one mapped failing test.

---

## 2. Contract-First TDD: Request and Error Model

Goal: enforce public behavior before implementation details.

BDD anchors:

- `Feature: Explicit target builds`
- `Feature: Artifact manifest and deterministic outputs` (request/metadata preconditions)

### 2.1 Write failing tests for request validation

Cover:

- missing `:book-root`
- missing `:targets` for `build`
- empty `:targets`
- unknown targets
- valid one-target and multi-target requests

### 2.2 Implement request normalization/validation

- normalize optional fields (`:config-path`, `:output-root`, `:profile`)
- reject invalid required fields

### 2.3 Standardize error data

Implement helper constructors in `clj_book.error` returning `ex-info` with structured data:

- `:error/type`
- `:error/message`
- `:error/context`

Acceptance:

- All request/error contract tests pass.
- Tests include references to mapped Gherkin scenarios.

---

## 3. Configuration Contract (`book.edn`) Validation

Goal: build robust input validation while preserving open-map flexibility.

BDD anchors:

- `Feature: Open-map manuscript configuration`

### 3.1 Define required config keys for alpha

Document and test the exact required keys (minimum viable contract). `book.edn` scope is build-orthogonal configuration only (chapter ordering, output paths, build profiles); authorial metadata belongs in the `book.adoc` AsciiDoc document header.

### 3.2 Write failing tests

Cover:

- missing required key
- wrong type for required key
- missing referenced file/path
- additional custom keys present (should pass)

### 3.3 Implement loader + validator

- read `book.edn`
- validate required keys/types
- preserve unknown keys in normalized output
- perform path existence checks for required references

Acceptance:

- Valid manifests pass; invalid manifests fail with descriptive errors.
- Tests explicitly cover both "missing required key fails" and "additional keys pass" scenarios.

---

## 4. Theme System Contract (`styles/tokens.edn`)

Goal: single canonical theme source compiled for both outputs.

BDD anchors:

- `Feature: Single cross-target theme file`

### 4.1 Define required token groups for alpha

Lock minimal but meaningful set of token groups (color, type, spacing, layout), authored as EDN and conceptually modeled on W3C Design Tokens.

### 4.2 Write failing tests for tokens validation

Cover:

- missing `styles/tokens.edn` file
- missing required token groups
- invalid value type
- extra custom token groups allowed

### 4.3 Implement tokens loader/validator

- read `styles/tokens.edn`
- validate required token groups
- preserve additional token groups

### 4.4 Implement token compilers

- `tokens -> Garden tree -> CSS custom properties` (using `garden/garden`)
- `tokens -> Asciidoctor PDF YAML` (intermediate Clojure map -> YAML emission)

### 4.5 Add parity fallback tests

Ensure deterministic fallback behavior for non-1:1 mapping cases.

### 4.6 Tier-2 layout configuration

Define and validate the v1 layout key surface inside `book.edn`:

- `:page-size`, `:page-margins`, `:chapter-opener`, `:toc-depth`, `:code-line-numbers`, `:admonition-style`

Cover with tests:

- valid layout keys are translated symmetrically to both targets where applicable
- unknown layout keys pass validation (open map) but emit a warning in build metadata

### 4.7 Tier-3 escape hatches

Implement and test optional inputs:

- `styles/site.clj`: load, evaluate top-level form, render via `garden.core/css`, append output after the token-derived CSS
- `styles/pdf-theme.edn`: load EDN, deep-merge into the token-derived PDF theme map before YAML emission

Cover with tests:

- when escape-hatch file is absent, build proceeds unchanged
- when present, site CSS contains token-derived rules followed by Garden-derived rules
- when present, PDF theme YAML reflects deep-merged user keys on top of token-derived keys

Acceptance:

- Same tokens input consistently yields expected CSS and PDF theme outputs.
- Missing tokens file failure behavior is covered by failing-first tests.
- Tier-2 layout keys and tier-3 escape hatches are covered by failing-first tests, with explicit ordering/merge semantics verified.

---

## 5. Manuscript Composition Layer

Goal: deterministic master AsciiDoc assembly from chapter sources.

BDD anchors:

- `Feature: Artifact manifest and deterministic outputs` (deterministic behavior)

### 5.1 Write composition tests

Cover:

- chapter order obeys config order
- generated master `book.adoc` is stable for stable input
- duplicate/missing chapter references fail clearly

### 5.2 Implement composition

- generate a master `book.adoc` under intermediate build output containing `include::` directives in configured chapter order
- keep source manuscript files unchanged

Acceptance:

- Composition tests and snapshot assertions pass.
- Determinism checks are encoded as repeat-run tests.

---

## 6. Site Target Implementation (`:site`)

Goal: static HTML + CSS output with zero client-side JavaScript.

BDD anchors:

- `Feature: Explicit target builds` (valid single target)
- `Feature: Artifact manifest and deterministic outputs`

### 6.1 Design site target input model

- normalized config
- compiled CSS custom properties artifact
- DocBook 5 intermediate (`book.xml`)

### 6.2 Write integration tests first

Cover:

- build with `[:site]` emits expected core files
- generated pages are readable and navigable without JavaScript
- output contains no `<script>` tags and no client-side runtime
- deterministic output paths

### 6.3 Generate DocBook 5 intermediate

- invoke Asciidoctor's DocBook 5 backend on the composed master `book.adoc`
- write `book.xml` under the intermediate directory

### 6.4 Implement DocBook -> Hiccup transform

- transform DocBook elements (book, chapter, section, code, admonition, etc.) to Hiccup
- handle cross-references, anchors, and TOC generation

### 6.5 Implement Stasis adapter

- page map generation
- static export to target directory

Acceptance:

- Site target passes integration tests and the no-script assertion.
- Tests remain traceable to corresponding BDD scenarios.

---

## 7. PDF Target Implementation (`:pdf`)

Goal: professional PDF output with robust toolchain diagnostics.

BDD anchors:

- `Feature: Explicit target builds` (valid single target)
- `Feature: Artifact manifest and deterministic outputs`

### 7.1 Write integration tests and failure diagnostics tests

Cover:

- build with `[:pdf]` emits non-empty PDF artifact
- missing Asciidoctor tools produce descriptive failure
- missing theme artifact/path produces descriptive failure

### 7.2 Implement PDF adapter

- invoke Asciidoctor PDF CLI with generated intermediate/manuscript
- consume generated PDF theme YAML

### 7.3 Add preflight checks

- command availability
- required paths and files

Acceptance:

- PDF build works in supported environments and fails clearly otherwise.
- Failure diagnostics are captured by failing-first tests before adapter implementation.

---

## 8. Orchestration Pipeline and Multi-Target Execution

Goal: shared prerequisites run once; explicit target execution is deterministic.

BDD anchors:

- `Feature: Explicit target builds` (multi-target success)
- `Feature: Artifact manifest and deterministic outputs`

### 8.1 Write orchestration tests

Cover:

- `[:site]`
- `[:pdf]`
- `[:site :pdf]`
- shared step deduplication
- error propagation and stop behavior

### 8.2 Implement pipeline runner

Execution graph:

1. request validation
2. configuration validation
3. tokens validation
4. master `book.adoc` composition
5. token compilation (CSS + PDF YAML)
6. target builds (DocBook 5 generation + site adapter for `:site`; Asciidoctor PDF for `:pdf`)
7. artifact manifest write

Acceptance:

- All target combinations pass with deterministic behavior.
- Multi-target test cases are mapped to explicit Gherkin scenarios.

---

## 9. Artifact Manifest and Build Metadata

Goal: machine-readable output for CI and downstream tooling.

BDD anchors:

- `Feature: Artifact manifest and deterministic outputs`

### 9.1 Write tests for manifest shape

Ensure emitted manifest includes:

- book identifier
- requested targets
- output artifact paths
- timestamps
- relevant build metadata

### 9.2 Implement serializer

- emit EDN (and optionally JSON)
- stable keys and predictable path

Acceptance:

- CI can parse artifact manifest deterministically.
- Manifest schema tests include scenario-level expectations from the BDD doc.

---

## 10. Serve Mode

Goal: local dev preview for site output workflows.

BDD anchors:

- `Feature: Explicit target builds` (operational consistency for site workflows)

### 10.1 Write smoke tests

Cover:

- server starts
- expected route(s) respond
- invalid manuscript errors are surfaced clearly

### 10.2 Implement `serve`

- load/validate config and theme
- build site page map
- run local server for preview

Acceptance:

- local preview workflow is documented and stable.
- Smoke tests are written before serve implementation changes.

---

## 11. Documentation Pass (User + Technical)

Goal: adoption-ready docs for alpha users.

BDD anchors:

- All features in `docs/plans/user-affordances.md`

### 11.1 README quickstart

- install prerequisites
- minimal manuscript setup
- `validate`, `build`, `serve` examples

### 11.2 Contract docs

- `book.edn` required keys, tier-2 layout keys, and examples
- `styles/tokens.edn` required token groups and examples
- `styles/site.clj` (Garden) and `styles/pdf-theme.edn` tier-3 escape hatch examples
- AsciiDoc header attributes recognised by `clj-book`
- target selection rules (explicit only)

### 11.3 Error catalog

- common error types
- likely causes
- how to fix

### 11.4 User manual as dogfood manuscript

The user manual is authored under `docs/manual/` and structured exactly like an external manuscript repo:

- `docs/manual/book.adoc` (AsciiDoc document header with authorial metadata)
- `docs/manual/book.edn` (build configuration + tier-2 layout keys)
- `docs/manual/styles/tokens.edn` (design tokens)
- `docs/manual/styles/site.clj` (Garden; exercises the tier-3 site escape hatch)
- `docs/manual/styles/pdf-theme.edn` (exercises the tier-3 PDF escape hatch)
- `docs/manual/chapters/*.adoc` (chapter sources)

The manual covers everything from 11.1-11.3 (quickstart, contract docs, error catalog) plus reference material for `validate` / `build` / `serve`.

The manual is built by `clj-book` itself, both locally and in CI, for both `:site` and `:pdf` targets. This is the platform's primary real-manuscript regression case alongside the synthetic fixtures.

Acceptance:

- new user can adopt from docs alone.
- Docs include a requirement-to-command and scenario-to-test mapping summary.
- `docs/manual/` builds successfully for `[:site :pdf]` via `clj-book`.

---

## 12. CI Pipeline and Release Preparation

Goal: reproducible builds and confidence gates for alpha release.

BDD anchors:

- Entire BDD scenario set must run in CI (unit + integration split allowed)

### 12.1 CI workflow

- run tests on push/PR
- run integration target matrix
- build the bundled user manual under `docs/manual/` for `[:site :pdf]` as an end-to-end dogfood regression check
- verify deterministic output checks where feasible

### 12.2 Release checklist

- version bump to `v1.0.0-alpha`
- changelog/release notes
- tag/release process documented
- `LICENSE` file present and contains the full EPL 2.0 text
- `README.md` references the license

Acceptance:

- CI green; release checklist complete.
- CI output shows all mapped scenario test groups passing.

---

## 13. Final Hardening and Alpha Sign-Off

Goal: finalize behavior and prevent scope creep.

BDD anchors:

- Full regression against all requirement-linked tests

### 13.1 Regression pass

- rerun full suite
- run manual build flows against synthetic fixture repos

### 13.2 Non-goal enforcement

- verify no implicit targets introduced
- verify no Datomic dependency added
- verify site output contains no `<script>` tags and no client-side JavaScript runtime
- verify no third-party manuscript content added to the platform repo (the bundled user manual under `docs/manual/` is the platform's own dogfood manuscript and is permitted)

### 13.3 Alpha sign-off

Declare `v1.0.0-alpha` complete when all phase acceptance criteria are green.

Additional sign-off gate:

- every user-facing requirement and every Gherkin scenario has at least one passing automated test mapped in `docs/traceability-matrix.md`.

---

## Suggested Milestone Breakdown

- **Milestone A (Foundation):** Phases 1-3
- **Milestone B (Customization + Compose):** Phases 4-5
- **Milestone C (Targets):** Phases 6-7
- **Milestone D (Orchestration + Artifacts):** Phases 8-9
- **Milestone E (Dev + Docs + CI + Release):** Phases 10-13

---

## Implementation Discipline

- TDD for every new behavior.
- Keep pure logic in core namespaces; isolate effects at edges.
- Preserve structured errors and deterministic outputs as non-negotiable.
- Treat user-facing affordance and architecture docs as source-of-truth constraints.
