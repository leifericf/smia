# clj-book Book-Production Affordances — Design

## Status

**Planned.** This document specifies the structural and visual apparatus a
long-form technical book needs and that clj-book does not yet express. It extends
the established `load → assemble → expand → serialize → render` pipeline and the
functional-core / imperative-shell discipline; it introduces no new intermediate
representation. The phased build-out lives in `docs/plans/implementation-plan.md`;
this document is the design and the decisions behind it.

It builds directly on the Hiccup superset (`technical-design.md`) and the Markdown
front-end (`markdown-frontend.md`): every affordance here gets a Hiccup form *and*
a Markdown form, both compiling to the same author Hiccup.

## Motivation

clj-book today renders a **flat list of chapters** with a title page, a
chapter-level table of contents, chapter-level PDF bookmarks, one running head
(the chapter title), and a centered page number. Code is plain monospace and a
cross-reference resolves only to a page number.

A study of professional technical-book form (long-form reference titles in the
same genre, studied for their *conceptual* apparatus only — never their setup or
styling) shows the structural and visual vocabulary clj-book cannot yet express:

- **Structure:** parts grouping chapters; numbered chapters and lettered
  appendices; named front matter (preface, foreword) and back matter
  (bibliography, index, colophon); roman-then-arabic page numbering; chapters
  starting on a recto.
- **Navigation:** a multi-level table of contents (parts, chapters, sections); a
  nested PDF outline; cross-references that read "Figure 3" or "Chapter 5", not a
  bare page number.
- **Block vocabulary:** numbered figures with captions; captioned and numbered
  tables; code listings with a filename header and a numbered caption; titled
  sidebars (a generalization of the admonition); epigraphs at chapter openers.
- **Code presentation:** syntax highlighting and optional line numbers.
- **Apparatus:** an index built from inline marks; a bibliography with inline
  citations.
- **Running content:** distinct verso/recto running heads and footers carrying the
  book, part, chapter, or section title by page parity.

This plan fills **all** of those holes — implemented our own way: data-oriented,
Clojure-idiomatic, additive and back-compatible.

## Design principles

- **Two surfaces, one model.** Every author-facing feature has a Hiccup form and a
  Markdown form (EDN attributes throughout), both compiling to the same author
  Hiccup. Markdown additions are a **true superset of CommonMark** — every
  CommonMark construct keeps working; extensions live only in the `:::name {edn}`
  directive container, fenced-code info maps, the bare-EDN attribute line, and
  inline escapes. No CommonMark syntax is repurposed.
- **Data-driven dispatch.** New dispatch mirrors the existing introspectable maps
  (`fo.expand/expanders`, `md.compile/compilers`, `eval.registry/evaluators`):
  numbering rules and highlight tokenizers are data maps, so adding a case is
  adding an entry, not editing a `cond`.
- **Functional core / imperative shell.** New transforms are pure cores,
  registered in `boundaries_test`'s `pure-core-nss` with the forbidden-import
  checks. Effects stay in the shell. Seams are validated with `schema/check`;
  errors are structured `error/ex`.
- **Determinism preserved.** Serialized FO stays byte-stable. Page-number
  resolution remains FO-native (`fo:page-number-citation`) so the pure numbering
  pass never needs layout results, and syntax highlighting is a pure tokenizer
  (no nondeterministic source).
- **Back-compatible.** A book with only a flat `:book/chapters` list and no new
  attributes renders exactly as today; every new affordance is additive and
  optional.

## Two keystones

Everything else depends on these two, built first.

### K1 — Typed document model and generalized assembly

Replace the hardwired "title page + TOC + flat chapters" assembly with a
data-driven model of **typed sections**: front matter, body (optionally grouped
into parts), appendices, and back matter. Each section carries its **role**, which
determines page-numbering format (roman front matter, arabic body, lettered
appendices) and page-master selection, plus blank-page parity (start a chapter on
a recto). The flat `:book/chapters` list remains the zero-config default and means
"one body, no parts, no named matter."

Structure is declared in `book.edn` (data, validated) — not in chapter files,
which stay self-describing and reorderable:

```clojure
{:book/slug  "the-manual"
 :book/title "The Manual"
 :book/front-matter [{:role :preface :file "front/preface.md"}]
 :book/parts [{:part/title "Foundations"
               :part/chapters ["chapters/01-intro.md" "chapters/02-model.md"]}
              {:part/title "Practice"
               :part/chapters ["chapters/03-build.md"]}]
 :book/appendices ["appendix/a-glossary.md"]
 :book/back-matter [{:role :bibliography} {:role :index}]
 :book/numbering {:chapters :arabic :parts :roman :appendices :letter
                  :sections false :start-chapters-on :recto}}
```

This unlocks parts, appendices, named matter, roman→arabic switching, and
recto-start parity.

### K2 — Numbering and cross-reference engine

A pure pass (`clj-book.book.number`) over the assembled author tree, inserted in
`render-profile!` between assembly and expansion:

```
(-> (assemble …) (number/assign …) (expand …) (serialize …))
```

mirroring where the code-validation pass hooks into the shell. It:

1. **numbers targets** per the policy — part, chapter, appendix, section
   (optional), figure, table, listing, footnote, citation, index entry — via a
   `kind → rule` data map;
2. **builds a registry** `{id → {:kind :number :label :title}}`;
3. **annotates** each target node with its computed label;
4. **rewrites** reference nodes (`:xref`, `:cite`) to their composed text, leaving
   the page reference to FO's `fo:page-number-citation`.

This unlocks all numbering and every rich reference; the TOC, outline, index, and
bibliography are all built from the registry.

## Resolved decisions

1. **Numbering (default, configurable per book via `:book/numbering`):** parts
   (I, II, …), chapters (1, 2, …), and appendices (A, B, …) are auto-numbered;
   **sections are not numbered** by default — they still appear in the TOC,
   outline, and running heads. Decimal section numbering (1.1, 1.1.1) is available
   behind a flag.
2. **Syntax highlighting** is a **pure tokenizer registry** (language → pure
   `tokenize` fn), mirroring the evaluator registry — Clojure first, then the
   other shipped JVM languages (Java, Kotlin, Groovy). Colors come from a `:code`
   token-class palette in `tokens.edn`. No new dependency; deterministic; optional
   (off → plain monospace). This **resolves the long-standing "code highlighting"
   open decision** (`technical-design.md` §Open decisions, `implementation-plan.md`
   open decision 5) in favor of the pure-JVM, no-subprocess path.
3. **Document structure is declared in `book.edn`** (data, validated). The flat
   `:book/chapters` list remains the zero-config default.
4. **Cross-references** extend the existing `:xref`. A reference to any labeled
   target with no children auto-composes "`<Kind> <number>`" (with `:style :full`
   adding the title, and an optional page citation); author-supplied children
   still override. A new `:cite` handles bibliography references.
5. **Floats** use `fo:float` (FOP supports `float="start|end"`), with a documented
   caveat and a table-based fallback for robust text wrap.
6. **No third-party, commercial, or brand names** appear anywhere in code, docs,
   plans, or commits. Inspiration is conceptual only; the implementation is our
   own.

## The affordances in detail

### Structure and matter (K1)

- `:book/parts` — ordered parts, each `{:part/title … :part/chapters […]}`. A part
  emits a part-divider page-sequence and groups its chapters under it in the
  outline.
- `:book/front-matter` / `:book/back-matter` — named sections carrying a `:role`
  (`:preface`, `:foreword`, `:colophon`, `:bibliography`, `:index`, …). Roles with
  generated content (`:bibliography`, `:index`) need no file.
- `:book/appendices` — body-role sections numbered with letters.
- `:book/numbering` — the policy: per-kind formats, whether sections are numbered,
  and recto-start parity.

The assembler walks the typed model, sets per-role page-number `:format` and
`:initial-page-number`, selects page-masters per section, inserts blank-page parity
pages, and emits a **nested** bookmark tree (part → chapter → section).

### Navigation (K2-derived)

- **Multi-level TOC** — generated from the registry as a front-matter section:
  parts, chapters, and sections, with numbers (per policy), dotted leaders, and
  page citations, indented by level.
- **Nested PDF outline** — part → chapter → section, from the registry.
- **Rich cross-references** — `[:xref {:to id}]` with no children renders
  "`<Kind> <number>`" (optionally "`, on page <n>`"); `:style :full` adds the
  title. Markdown `[](#id)` (empty link text to a `#id`) auto-composes; non-empty
  link text overrides, as today.

### Page mechanics and running content

- Heading expanders emit `fo:marker`s for the nearest section and chapter;
  assembly retrieves the right marker per region.
- **Distinct verso/recto** running heads/footers via the recto/verso masters, each
  with its own `region-before`/`region-after` static content. Slots (book title,
  part title, chapter title, current section, page number) are positioned by parity
  and declared in `:book/running-heads` (or the theme).
- Author page sugar: `:page-break` (break before) and `:keep-together` — thin sugar
  over existing FO keep/break properties.

### Block vocabulary

- `:figure` — an image plus a numbered caption, optionally floated. Markdown:
  `:::figure {:id … :caption …}` around an image.
- captioned/numbered `:table` — `:id`/`:caption` on the table (Markdown: on the
  existing bare-EDN line above the table).
- code listing — `:pre` gains `:file` (a filename header bar) and `:caption` (a
  numbered "Listing N" caption); referenceable. Markdown: the fence info EDN map
  already supports arbitrary keys.
- `:sidebar` — generalize `:admonition` to an arbitrary `:title` plus rich body,
  keeping the named `:kind` variants and adding an optional `:icon`. Markdown:
  `:::sidebar {:title …}`.
- `:epigraph` — a quotation plus attribution at a chapter or part opener.

### Code presentation

A pure tokenizer registry (`clj-book.highlight.*`): `language → tokenize` where
`tokenize` returns `[{:kind :text} …]`. When `:lang` is set and highlighting is
enabled, `:pre` expansion runs the tokenizer into runs of colored `fo:inline`
(whitespace preserved), with colors from the `:code` token-class palette; an
optional `:line-numbers` gutter is available. Off, or for an unregistered
language, it falls back to today's plain monospace, so determinism is intact.

### Back-matter apparatus

- **Bibliography** — an EDN references file (`key → entry`); `[:cite {:key …}]`
  inline renders a cite label linking to a generated, sorted Bibliography section
  (each entry an `:id` target via K2).
- **Index** — inline `[:index {:term …}]` marks (a Markdown inline directive)
  collected by K2 into an alphabetical Index section with page citations, set in
  multiple columns.

## Idioms

Pure, REPL-testable transforms (`(number/assign tree policy)` returns data with no
files; `(highlight/tokenize :clojure "(+ 1 2)")` returns token runs); data-driven
dispatch; errors-as-data; Malli validation at the seams; minimal macros. This is
clj-book continuing to dogfood the manuscript's own advice.

## Coexistence and migration

Raw `.clj` chapters and the existing flat-chapter books keep working unchanged: a
book that declares no parts, matter, appendices, or numbering renders byte-for-byte
as it does today (the serialized FO is the equivalence oracle). The dogfood manual
under `docs/manual/` is re-organized to exercise every new affordance and doubles
as the regression case.

## Deferred and non-goals (this design)

- Evaluate-and-capture of code output (still a determinism cost; unchanged).
- Non-JVM highlight tokenizers — the registry accepts them later with no
  architecture change.
- Automatic float placement heuristics beyond `fo:float` start/end and the
  table-based fallback.
- HTML/EPUB output — still out of scope; the typed model could feed them later.

## Details to settle during implementation

- `fo:float` robustness in FOP across page boundaries (hence the documented
  table-based fallback).
- Hyphenation/justification interaction with highlighted, non-wrapping code.
- Index page-citation deduplication when a term is marked many times on one page.
- Bibliography entry formatting vocabulary (author/title/year fields) kept minimal
  and data-driven.
