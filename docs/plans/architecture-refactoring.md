# clj-book Architecture Refactoring — Plan

## Status

**Proposed.** This is a post-feature-complete, top-down review of clj-book's
technical design and a scoped plan to pay down structural debt. It changes no
behaviour: every item preserves the `load → number → assemble → expand →
serialize → render` pipeline and the functional-core / imperative-shell
discipline. The goal is sharper domain boundaries and lower local complexity,
not a rewrite.

## How this review was conducted

The conceptual map came from the knowledge graph (import graph, complexity and
smell ratings, and natural-language questions about the pipeline, naming,
complecting, and domain boundaries). **Every load-bearing claim was then
re-verified by reading the source**, because the graph's generative answers
included two errors that would otherwise have shaped this plan:

- It invented roughly a dozen non-existent "stub" files (`document.clj`,
  `pipeline.clj`, a whole `tokens/` tree, `targets/`, `docbook/`, and others)
  and proposed consolidating "vestigial duplicate concepts." The real `src/`
  tree is 36 files with no such stubs; those recommendations were discarded.
- It claimed `config/validate` braids filesystem IO into pure shape validation.
  In fact `validate` does no IO; the existence check lives in a separate
  `check-files-exist` called only from the `load-config` shell. The separation
  it asked for already exists.

Only findings that survived reading the code appear below.

## What the design gets right (and must be preserved)

- **Functional core, imperative shell.** IO is confined to `config`,
  `theme/load`, `book/load`, `fo/render`, and `artifacts`. Everything between is
  pure data transformation; `build/execute` is the single orchestrating shell.
- **`build/plan` ↔ `build/execute`** is the exemplar: a pure, inspectable Plan
  value (which powers `--dry-run`) cleanly separated from the IO that acts on
  it. It is the pattern the rest of the code should imitate.
- **No import cycles.** The dependency graph is a clean DAG.
- **The `fo/` stages** (`assemble → expand → serialize → render`) form a
  textbook staged transformation with documented data-shape boundaries, each
  independently testable.
- **`md/` is a self-contained front-end** with no outward coupling beyond
  `error`/`schema`. The `eval` and `highlight` registries are open for
  extension. Errors are structured and consistent via `error.clj`.

The seven subsystems (`book`, `build`, `eval`, `fo`, `highlight`, `md`, `theme`)
are coherent bounded contexts. This plan tightens them; it does not redraw them.

## The work

### P1 — Structural decomposition (highest leverage)

**1. A single book-outline value in `book/assemble`.**
`bookmark-tree`, `toc-entries`, and `section-sequences` each walk the prepared
section list independently with the same parts → chapters → sections dispatch.
Three near-duplicate traversals are the real source of the file's
"very-complex" rating — not the FO emission, which is straightforward.

Compute the outline **once** as a pure value — an ordered tree of
`{:kind :id :title :number :label :level :children}` — and have the bookmark,
TOC, and page-sequence builders consume *that*. This removes the duplication and
collapses the file's complexity without changing a byte of output.

**2. Move chapter-shape validation to the load boundary.**
`assemble/parse-chapter` throws on a missing `:id`/`:title` — input-shape
validation running during assembly. That contract belongs in `book/load`, where
chapters are produced. After the move, `assemble` may assume well-formed input.

### P2 — Naming and placement: the theme compiler

**3. `book/theme.clj` → `theme/compile.clj`.**
It is pure and *compiles* design tokens plus a layout profile into FO style and
page-masters (`compile-theme`), but it sits under `book/` (the document model).
Its IO sibling `theme/load.clj` already lives under `theme/`. Move the compiler
beside it. This is a rename plus `:require` updates; behaviour is unchanged. Its
dependency on `fo/expand` (for base-14 defaults) is a legitimate `theme → fo`
edge and remains.

### P3 — Cohesion moves

**4. `config.clj` → `book/config.clj`.** `book.edn` loading/validation is a
book-domain concern, not a root-level one.

**5. `artifacts.clj` → `build/artifacts.clj`.** The build manifest is a build
output, imported only by `build/execute`.

Both are pure relocations with `:require` updates.

### P4 — Remove the dead legacy assembly path

**6. Make typed `:sections` the sole assembly contract.**
`book/load/load-manuscript` — the only production producer — always emits typed
`:sections` (via `structure/normalize`). The `legacy-sections` fallback in
`assemble` (`(or sections (legacy-sections chapters))`) and the parallel flat
handling in `number` fire **only** for tests that pass a flat `{:chapters …}`
map directly. The path is dead in production.

Remove `legacy-sections` and the fallback, drop the parallel flat handling in
`number`, and migrate the affected tests to the typed `:sections` shape. This
simplifies the two most complex namespaces in the document model.

## Explicitly out of scope (avoiding over-engineering)

These were considered and rejected as churn or ceremony:

- **Threading explicit accumulators through `number/number-body`.** Its atoms
  and `volatile!` are created fresh per call and never escape; `assign` is
  referentially transparent. Local mutable accumulation in a pure function is
  idiomatic Clojure — five threaded accumulators would be less readable, not
  more. Leave it (a one-line comment noting the atoms are local accumulators is
  the most that's warranted).
- **Splitting IO out of `book/load/resolve-includes`.** It does `slurp`
  mid-walk, but it lives in a namespace documented as the imperative shell.
  A collect → load → substitute split is ceremony for an include feature.
- **Turning `assemble/resolve-xrefs!` into warnings-as-data.** For a build tool,
  an unresolved cross-reference must fail the build; no caller wants to
  re-decide that. The eager hard error is correct.
- **Wholesale root→subdirectory reorganization and abstract renames**
  (`schema` → `schema.contracts`, `request` → `request.normalize`, etc.). For
  36 files with coherent subsystems, this is motion without value.

## Sequencing

The items are independent and individually shippable behind the existing test
suite. Suggested order, each its own commit:

1. P1.1 — book-outline extraction in `assemble`.
2. P4.6 — remove the legacy path, migrate tests (shrinks `assemble`/`number`
   before further edits).
3. P1.2 — move chapter-shape validation to `book/load`.
4. P2.3 — `book/theme` → `theme/compile`.
5. P3.4 / P3.5 — `config` → `book/config`, `artifacts` → `build/artifacts`.

Nothing here touches the verified-good core: `build/plan`↔`execute`, the `fo/`
pipeline, or the `md/` front-end.
