# clj-book Markdown Front-End — Design

## Status

This document specifies a **prose-first author front-end** for clj-book. It
promotes the previously deferred "optional Markdown front-end" (open decision in
`technical-design.md`, `implementation-plan.md`, `user-affordances.md`) from
out-of-scope to a planned, specified feature, and realizes the extension those
docs anticipated: *"because Hiccup is the contract, friendlier front-ends (e.g.
Markdown → Hiccup) can be layered on top later as a separate concern."*

The engine, the intermediate representation, and the rendering pipeline are
**unchanged**. This adds a new front stage that compiles a curated CommonMark
dialect down to the existing author (sugar) Hiccup. The phased build-out lives in
the implementation plan; this document is the design and the decisions behind it.

## Motivation

Authoring book content directly in Hiccup is **code-first**, but a book is
**prose-first**. In Hiccup, prose becomes quoted, line-split strings; inline
markup is interleaved vectors (`[:p "Use " [:code "bean"] " here."]`); and —
most painfully — code samples must escape every `"` and `\` and hand-manage `\n`
inside `:pre`. For a programming book (roughly a third code), that string
escaping is a recurring **correctness footgun**, not merely verbosity. A
prose-first surface removes it while keeping Hiccup as the machine-facing
contract.

## Architectural placement — Hiccup stays the IR

clj-book already has two intermediate representations: **author Hiccup** (sugar +
book extensions + raw FO) and **FO-Hiccup**. `expand` is the compiler from the
former to the latter. The front-end is therefore not a new IR but a new *front
stage* that produces the existing author Hiccup:

```
front-end (Markdown -> author Hiccup)
  -> assemble -> expand -> serialize -> render        [all unchanged]
```

Author Hiccup is the **contract**, and the Malli vocabulary schema is the
conformance point every front-end targets. Keeping it as the contract:

- keeps front-ends interchangeable — Markdown now, others later, all emitting the
  same author Hiccup;
- keeps every bit of FO/styling knowledge inside `expand`, so front-ends stay
  FO-ignorant;
- preserves content/presentation decoupling — theming and the screen/print
  profiles live *below* the front-end, so styling changes need no front-end
  changes;
- leaves raw `.clj` chapters — which already *are* author Hiccup — working
  unchanged.

**Insertion point:** `clj-book.book.load` dispatches on the chapter file
extension — `.clj` via `load-file` (existing), `.md` via the new front-end. Both
yield a `[:chapter {…} …]` form.

## Format — CommonMark plus a curated, owned extension set

- **Base:** CommonMark, parsed by an existing **pure-JVM** library
  (commonmark-java leaning; flexmark considered). No Ruby, no Node, no
  subprocess — consistent with clj-book's pillars.
- **AsciiDoc is rejected.** Its real implementations are Ruby / JRuby
  (Asciidoctor), which would reintroduce exactly the toolchain weight clj-book
  was built to remove; pure-Java AsciiDoc parsers are not credible.
- **Extensions are a small, closed, publisher-owned set** (the PML / MyST /
  Markua spirit — *curate, do not accrete*), each mapping 1:1 onto an existing
  author-Hiccup node.
- **No third-party-renderer compatibility constraint.** These documents are only
  ever rendered by clj-book, so custom syntax is chosen purely for clean parsing
  and author ergonomics. The only external-grammar concern is not *colliding*
  with CommonMark's own constructs.

## Data language — EDN everywhere

One data language, from `book.edn` down to a single inline attribute:

- **Front-matter** is an optional **bare leading EDN map** — not YAML. (YAML's
  type coercion is a real hazard here: unquoted `NO` parses as boolean false,
  and the manuscripts use `"NO"`/`"SE"`/`"DK"` country codes.) The front-matter
  map *is* the `:chapter` attribute map — no translation, same schema.
- **Attributes** on directives, fences, and spans are **EDN maps**
  (`{:kind :tip}`, `{:cols [4 3 2 1 2]}`), read as data — not a Pandoc
  `key=val` mini-language. This is idiomatic for Clojure authors and the same
  literal they would write anywhere else.
- **Read as data, never eval.** All metadata and attributes are EDN (data only).
  Code *execution* is a separate, explicitly opt-in concern (below).

## Extension grammar

| Need | Surface syntax | -> author Hiccup |
|---|---|---|
| chapter metadata | bare leading EDN map | `[:chapter {…} …]` |
| admonition | `:::admonition {:kind :tip} … :::` | `[:admonition {:kind :tip} …]` |
| cross-reference | `[the theming chapter](#theming)` | `[:xref {:to :theming} "…"]` |
| footnote | `[^1]` + definition | `[:footnote …]` |
| code block | ` ```clojure {:test true} ` | `[:pre {:lang "clojure" :test true} "…"]` |
| include real source | ` ```clojure {:include "src/x.clj" :lines [1 20]} ` | `[:pre {:lang …} "<slurped>"]` |
| raw IR escape | ` ```{=hiccup} ` / `` `…`{=hiccup} `` | spliced author Hiccup (re-expands) |
| raw FO escape | ` ```{=fo} ` | spliced FO-Hiccup (verbatim) |

`:pre` gains `:lang` / `:test` / `:include` attributes in the IR — inert to
rendering, consumed by the validation pass. The `{=hiccup}` escape is strictly
more powerful than `{=fo}`: spliced author Hiccup re-enters `expand`, so sugar
nested inside it still expands, whereas raw FO is terminal.

## Metadata model — minimal and in-file

- `:title` comes from the first `# H1`.
- `:id` is derived from the filename (stable and unique; it is the xref target).
- An optional leading EDN map supplies overrides or extra keys.

Centralizing chapter metadata in `book.edn` was considered and rejected: keeping
it in-file makes chapters self-describing and reorderable.

## Compile — thin Java wrapper, data-driven dispatch

- `clj-book.md.parse` is the **only** namespace that touches the Java parser:
  invoke it, walk the AST once, and emit a **normalized Clojure-data node tree**
  carrying source positions. Get to data immediately.
- `clj-book.md.compile` is **pure**: Clojure-data AST -> author Hiccup via a
  `node-type -> fn` **dispatch map**, mirroring `expand`'s introspectable
  `expanders` map. Adding an extension is adding a data entry, not editing a
  `cond`.
- Principle: **as little on the Java side as we can get away with.** The parser
  handles lexical and block structure (including delimiting `:::` blocks, which
  needs its cooperation); all *semantics* happen in Clojure, on data.

## Executable code — design for all languages, ship JVM-family

Opt-in, build-time **validation** of code examples, via a language-keyed
**evaluator registry** (data-driven dispatch — the seam that keeps non-JVM
languages addable later with no architecture change).

- **Contract:** `(evaluate {:lang :source :attrs}) -> {:status
  :compiled|:ran|:matched|:failed :diagnostics […] :value? …}`. It supports
  *levels* (parse / compile / run / assert) so a future C or C++ evaluator can be
  "does it compile?" only.
- **Verify, not capture.** The rendered content stays author-fixed; only the
  *check* runs. This preserves determinism — evaluate-and-capture would inject
  nondeterminism (e.g. `(Instant/now)` rendering differently each build).
- **Shipped now (in-process, no subprocess):** Clojure (native JVM `eval`),
  Groovy (`GroovyShell`), Java (JDK compiler API / JShell), Kotlin (embeddable
  scripting compiler). Each language uses its authentic in-process engine, so a
  validated sample behaves as it will for a reader running the real toolchain.
  The dynamic-eval pair (Clojure, Groovy) is cheapest to wire; Kotlin is the
  heaviest. Clojure needs no extra dependency — it is already on the classpath —
  so Clojure validation works out of the box.
- **Designed-for, deferred:** Scala (heavy, version-pinned compiler; in-process
  is possible) and **non-JVM** languages (C, C++, …). Non-JVM requires shelling
  out to an external toolchain — an explicit opt-in tier *outside* the pure-JVM
  and determinism guarantees (CI would need that toolchain; output becomes
  non-hermetic). The registry accepts them with no plumbing changes.
- **Modular, optional dependencies.** A book carries only the evaluators it uses;
  a plain prose or Clojure-only book still needs nothing but a JVM.
- **Trust, tiered.** `.md` is read as data (no `load-file`), so a prose book is
  eval-free — the "building runs your code" caveat disappears — until a block
  opts into `{:test true}`. Validation is **not** sandboxed: an opted-in block
  runs with the full authority of the build JVM (the same trust model as a `.clj`
  chapter under `load-file`). This was a deliberate choice over a sandboxed
  interpreter (SCI was considered): authentic host semantics matter for a
  programming book — a validated sample must behave exactly as it will for the
  reader — and it keeps Clojure consistent with the Java/Kotlin/Groovy evaluators,
  which all use their real engines. Only validate manuscripts you trust.
- **Placement.** Execution is an effectful **shell** pass, never in the pure
  core; `boundaries_test` keeps `md.compile`, `expand`, and `assemble` pure.

## Errors and validation

Failures are structured `ex-info` (`:error/type`, `:error/context`) carrying
**source positions** — for parse errors, EDN-read errors, unknown directives,
malformed attributes, and failed `{:test true}` blocks. Malli validates the
front-matter map, attribute maps, and the vocabulary: one validation story across
the system. Source-position fidelity from the parser is the make-or-break for
good author errors.

## Idioms

Pure, REPL-testable transforms (`(md.compile/compile "## Hi")` returns data with
no files); data-driven dispatch; errors-as-data; Malli validation; minimal
macros. This is clj-book dogfooding the manuscript's own advice — *plain data
transformation, data-first before abstractions, avoid clever macros.*

## Coexistence and migration

Raw `.clj` chapters remain first-class (they are author Hiccup, and the
`{=hiccup}` escape re-injects it). The dogfood manual under `docs/manual/` is the
acceptance test: convert it to `.md` and diff the rendered output against the
current `.clj` build.

## Deferred and non-goals (this design)

- Evaluate-and-capture of code output (determinism cost).
- Scala and non-JVM evaluators — designed-for, not built.
- Additional front-ends — the IR supports them later, but only Markdown ships.

## Details to settle during implementation

- commonmark-java source-position fidelity (gates the quality of author errors).
- How to attach an EDN attribute map to a GFM table (the `:cols` case).
- `:id`-from-filename uniqueness and stability across xref targets.
- Native-eval isolation for `{:test true}` (fresh namespace per block to avoid
  cross-block leakage); Kotlin scripting startup cost.
