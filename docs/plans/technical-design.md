# clj-book Technical Design and Architecture

## Status — PDF-first redesign

This document supersedes the earlier AsciiDoc / multi-format design. `clj-book`
is repositioned as a **PDF-first** publishing engine: authors write Clojure data
(Hiccup), and the engine renders **PDF** (screen and print editions) entirely on
the JVM via **Apache FOP**.

The previously implemented `:site` / AsciiDoc / DocBook pipeline — and its
Ruby/asciidoctor dependency — is retired by this design. The parts that carry
over are the spine: the document model as a data contract, malli schemas, the
structured `error` kernel, deterministic output, and the functional-core /
imperative-shell discipline.

## Purpose

`clj-book` turns a Clojure-data manuscript into publication-quality **PDF** —
screen and print editions — using only the JVM. There is no Ruby, no asciidoctor,
no external binary, and **no subprocess**: Apache FOP runs in-process.

Authors write **bog-standard Hiccup** (the HTML-flavored data the Clojure
ecosystem already produces). The engine treats that as the convenient surface of
a **true superset** that reaches the entire XSL-FO formatting model — so common
content is easy and the full power of FO is always available.

## Target user

Authors comfortable with programming and Clojure data: they edit `.edn`/`.clj`
files, read structured `ex-info` errors, and are content expressing prose and
structure as Hiccup. `clj-book` is the engine, not a WYSIWYG editor. Because
Hiccup is the contract, friendlier front-ends (e.g. Markdown → Hiccup) can be
layered on top later as separate concerns.

## Architectural principles

- **Data-oriented.** Inputs, the document model, the theme, and the FO tree are
  all plain Clojure data.
- **Functional core, imperative shell.** Expand / assemble / serialize are pure;
  the only effects are reading inputs and FOP writing PDF bytes.
- **Superset, not subset.** The author vocabulary is a true superset that can
  express *any* XSL-FO construct (raw FO is first-class) — not a bounded subset
  with an escape hatch.
- **Explicitness and open maps.** No implicit behavior; required keys enforced,
  extra keys preserved.
- **Determinism.** Byte-reproducible PDFs from identical inputs.
- **In-process.** FOP is a library call; there is no CLI subprocess anywhere.

## Authoring model — the three-layer Hiccup superset

Hiccup (`[tag attrs? & children]`) is a generic XML-tree literal, and XSL-FO is
XML — so FO is representable directly as Hiccup. The author vocabulary is three
concentric layers in one syntax:

1. **Raw FO floor** — `:fo/*` tags serialize directly to `fo:*` elements. 100% of
   XSL-FO is reachable by construction.
2. **HTML/semantic sugar** — `:p :h1`–`:h6 :ul :ol :li :strong :em :code :pre :a
   :table :thead :tbody :tr :td :blockquote :img` … expand to FO. This is the
   "bog-standard Hiccup" surface; anything that already emits HTML hiccup works.
3. **Book extensions** — `:chapter :xref :cite :footnote :admonition :sidebar
   :figure :epigraph :index :page-break :keep-together` … expand to the FO that
   HTML can't name (page-sequences, `fo:page-number-citation`, `fo:footnote`,
   bordered blocks, numbered captions, floats).

So `bog-standard HTML hiccup ⊂ (HTML sugar + book extensions + raw FO) = the
superset`.

```clojure
[:chapter {:id :intro :title "Introduction"}        ; book sugar  → page-sequence + heading + bookmark
 [:p "Mostly you write " [:strong "ordinary"] " hiccup."]   ; HTML sugar → fo:block / fo:inline
 [:p "See " [:xref {:to :ch-config}] "."]                  ; book sugar → fo:basic-link + page-number-citation
 [:fo/block {:keep-together.within-page "always"           ; RAW FO — full power, same syntax
             :space-before "12pt"}
  "Anything FO can do, written directly."]]
```

**Namespace scheme (proposed; to finalize):** raw FO under `:fo/…`; HTML sugar as
plain keywords; book extensions as plain keywords (or `:book/…`); embedded SVG
under `:svg/…`. FO properties live in the attribute map as keywords, including
compound names (`:keep-together.within-page`) with unit values as strings.

## Pipeline

```
author hiccup  (sugar + book ext + raw fo)
  → expand     known tags → FO hiccup; identity pass-through for :fo/*   [pure]
  → assemble   wrap chapters into page-sequences with profile masters    [pure]
  → serialize  FO hiccup → FO XML (fo: namespace)                        [pure]
  → FOP        FO XML → PDF (per profile)                                [shell]
```

The core is "expand, then serialize." `:fo/*` nodes need no mapping. The engine
emits the FO machinery authors don't touch: regions, running heads (via
`fo:marker`/`retrieve-marker`), TOC with leaders + page numbers, PDF bookmarks,
and keep/break control.

A pure **numbering pass** (`clj-book.book.number`) runs between assembly and
expansion — `assemble → number → expand → serialize → FOP` — assigning numbers to
numbered targets (parts, chapters, appendices, figures, tables, listings) and
building the registry the TOC, outline, cross-references, index, and bibliography
draw from. It is pure and layout-free; page numbers stay FO-native
(`fo:page-number-citation`), so determinism holds. See
`docs/plans/book-production.md`.

## Layered architecture

- **L1 — renderer:** a general `hiccup → XSL-FO → PDF` compiler (sugar expansion +
  raw-FO passthrough + serialization + FOP), themed by a profile. Independently
  useful to anyone generating PDFs from Clojure data.
- **L2 — `clj-book` book layer:** `book.edn` + ordered chapter hiccup + `tokens.edn`
  → assemble → render via L1, adding the book concerns: per-chapter
  page-sequences, TOC, running heads, cross-reference page numbers, bookmarks.

## Theme and profiles

- The theme/profile is itself **FO-Hiccup** (page-masters, regions). `tokens.edn`
  compiles to FO-Hiccup fragments plus FO properties (fonts, sizes, spacing,
  colors).
- **Profiles**: `:screen` and `:print` are two layout configurations over the same
  document model — page size/margins (print adds gutter/recto-verso), color,
  live links vs page-number citations, running heads.
- Styling comes from **tokens + profile + a small class vocabulary** — *not* from
  interpreting arbitrary CSS or `:style`. FOP is not a CSS engine, and we will not
  pretend it is.

## Validation

- malli validates the sugar/book vocabulary, giving clear authoring errors.
- Raw `:fo/*` is passed through and validated by FOP; the engine surfaces FOP's
  diagnostics rather than re-implementing an FO schema.
- `book.edn` and `tokens.edn` are validated as data (required keys, open maps).

## Internal modules (conceptual)

- `clj_book.api` — public entrypoints (`validate`, `build`, optional `preview`).
- `clj_book.request` — request normalization and argument checks.
- `clj_book.error` — structured `ex-info` constructors (shared kernel).
- `clj_book.schema` — malli schemas (manuscript, tokens, profile, plan, and the
  sugar/book vocabulary).
- `clj_book.config` — `book.edn` load/validate (build config + metadata + chapter
  list).
- `clj_book.theme` — tokens → FO-Hiccup theme fragments + properties; profiles.
- `clj_book.fo` — **L1 core**: sugar/book expansion, raw-FO passthrough, and
  Hiccup → FO-XML serialization.
- `clj_book.book` — **L2**: assemble chapters, TOC, running heads, xref/bookmark
  resolution.
- `clj_book.fop` — FOP invocation (FO XML → PDF bytes); the only effectful renderer.
- `clj_book.artifacts` — manifest generation.

Retired from the prior design: `compose`, `docbook`, `docbook.parse`,
`targets.site`, `theme.css`, `theme.pdf`, `proc`, and the site preview `serve`.

## Output layout

```text
build/<book-slug>/
  intermediate/
    book-screen.fo     # serialized XSL-FO (per profile)
    book-print.fo
  pdf/
    <slug>-screen.pdf
    <slug>-print.pdf
  artifacts.edn
```

## Determinism

PDFs must be byte-reproducible: pin FOP's creation date and producer string and
avoid embedded timestamps, so identical inputs yield identical bytes.

## Runtime and toolchain

- JVM Clojure, `deps.edn`, `clojure -X`.
- Apache FOP (+ Batik, XML Graphics Commons) — all Apache-2.0, compatible with the
  project's EPL-2.0 license.
- No Ruby, no asciidoctor, no Node/ClojureScript, no bundler, **no external
  process**.
- No Datomic.

## Non-goals (this phase)

- No HTML/static-site output and no EPUB. (The format-neutral document model could
  feed them later, but they are out of scope now.)
- No interpretation of arbitrary CSS / `:style`.
- No external binaries or subprocesses.
- No WYSIWYG/browser authoring; `clj-book` is the engine.

## Open design decisions

1. **Chapter files: `.edn` vs `.clj`.** `.edn` is pure data (safe, no eval); `.clj`
   is programmable (slurp real code samples, generate content) at the cost of
   running author code at build time.
2. **Profiles: one or two.** Emit `:screen` + `:print` both by default, or select
   per build; one edition vs two.
3. **Final namespace scheme and FO property representation** (compound properties,
   units, SVG).
4. **v1 sugar element set + unknown-tag policy** (warn / error / treat as raw FO).
5. **Code highlighting** — *resolved*: a **pure tokenizer registry**
   (`clj-book.highlight.*`, language → pure `tokenize`), colors from `tokens.edn`;
   no subprocess, deterministic, optional. See `docs/plans/book-production.md`.
6. **Markdown author front-end** — *resolved (promoted)*: specified in
   `docs/plans/markdown-frontend.md` (CommonMark + curated extensions → author
   Hiccup; Hiccup stays the IR).
7. **Book-production apparatus** — *resolved (promoted)*: typed document model
   (parts, appendices, named matter), numbering/cross-reference engine,
   multi-level TOC/outline, figures/captions/listings/sidebars/epigraphs,
   distinct verso/recto running heads, index, and bibliography. Specified in
   `docs/plans/book-production.md`.

## Risk areas and mitigations

- **FO learning curve** for raw-FO usage — mitigated by good sugar coverage and
  sensible theme defaults so raw FO is rare.
- **PDF non-determinism** — mitigated by pinning FOP metadata.
- **Scope creep into "CSS-in-FO"** — mitigated by the styling discipline: layout
  and type come from tokens/profile/class vocabulary only.
