# clj-book User-Facing Functionality and Affordances

## Status — PDF-first redesign

This reflects the repositioned product: author in **Hiccup**, output **PDF**
(screen and print) on the JVM. It supersedes the earlier AsciiDoc / static-site
affordances.

## Target user

`clj-book` is built for **programming-comfortable, Clojure-friendly authors** —
developers writing technical books, or technical writers at home in a terminal
and in Clojure data. The user is expected to:

- Use `git` and a command-line build tool.
- Edit `.edn`/`.clj` files in their editor of choice (structural editing helps).
- Author content as **Hiccup** (the HTML-flavored data Clojure devs already know).
- Diagnose issues from descriptive `ex-info` errors.

It is **not** for non-technical authors expecting WYSIWYG or a zero-CLI workflow.
`clj-book` is the engine, not the editor; because Hiccup is the contract, a
friendlier prose front-end (e.g. Markdown → Hiccup) could be layered on top later
as a separate project.

## Core user experience

- Write each chapter as **Hiccup data**.
- Configure the book in one `book.edn` (metadata + chapter order + build config).
- Define the theme once in `tokens.edn` (compiled to the FO theme).
- Run one command to render **PDF** in the requested profile(s).
- Get deterministic output paths and a machine-readable artifact manifest.
- Get hard, descriptive errors for missing or malformed inputs.

## Commands

- `validate` — manuscript config and chapter data are well-formed.
- `build` — render the requested profile(s) to PDF.
- `preview` *(optional)* — rebuild and open the PDF; replaces the old site server.

Canonical invocation is standard JVM Clojure (`clojure -X …`).

## Authoring surface — the Hiccup superset

One syntax, three layers, no ceiling:

- **Ordinary HTML-flavored Hiccup** for the common case: `[:p]`, `[:h1]`, `[:ul]`,
  `[:code]`, `[:pre]`, `[:a]`, `[:table]`, `[:blockquote]`, `[:img]`, …
- **Book extensions** for things HTML can't name: `[:chapter {…}]`,
  `[:xref {:to :ch-config}]`, `[:cite {:key …}]`, `[:footnote …]`,
  `[:admonition {:kind :note}]`, `[:sidebar {:title …}]`, `[:figure {…}]`,
  `[:epigraph {…}]`, `[:index {:term …}]`, `[:page-break]`, `[:keep-together …]`.
- **Raw FO** for full power, in the same data: `[:fo/block {…} …]` reaches any
  XSL-FO construct.

```clojure
[:chapter {:id :intro :title "Introduction"}
 [:p "Plain prose with " [:strong "emphasis"] " and " [:code "inline code"] "."]
 [:admonition {:kind :note} [:p "Worth knowing."]]
 [:p "See " [:xref {:to :ch-config}] " to configure."]
 [:fo/block {:space-before "12pt"} "Drop to FO only when you need to."]]
```

Most authors stay in the first two layers and let the theme handle layout; raw FO
is opt-in.

## Book-production affordances

Beyond single chapters, clj-book expresses the structural and visual apparatus of
a long-form book (designed in `docs/plans/book-production.md`):

- **Structure** — group chapters into **parts**; add **appendices** (lettered) and
  named **front/back matter** (preface, bibliography, index, colophon) in
  `book.edn`. Front matter is numbered in roman, the body in arabic; chapters can
  start on a recto. A flat `:book/chapters` list still works with zero extra
  config.
- **Numbering** — parts, chapters, and appendices are auto-numbered; sections are
  unnumbered by default (decimal numbering is available via `:book/numbering`).
- **Navigation** — a multi-level table of contents (parts → chapters → sections), a
  nested PDF outline, and cross-references that read "Figure 3" or "Chapter 5"
  (`[:xref {:to id}]`; `:style :full` adds the title).
- **Block vocabulary** — numbered **figures** with captions, captioned/numbered
  **tables**, **code listings** with a filename header and a numbered caption,
  titled **sidebars** (a richer admonition), and **epigraphs** at chapter openers.
- **Code presentation** — optional **syntax highlighting** (a pure JVM tokenizer;
  colors from `tokens.edn`) and **line numbers**; off by default, plain monospace
  otherwise.
- **Apparatus** — an **index** built from inline `[:index {:term …}]` marks and a
  **bibliography** with inline `[:cite {:key …}]` citations resolving to a sorted
  back-matter section.
- **Running content** — distinct verso/recto running heads and footers carrying the
  book/part/chapter/section title and page number, configured per book.

Every one of these has both a Hiccup form and a Markdown form; the Markdown
additions are a true superset of CommonMark.

## Editions / profiles

The same manuscript renders into layout profiles:

- `:screen` — on-screen reading: comfortable margins, full color, live hyperlinks.
- `:print` — print/binding: trim size, gutter/recto-verso margins, page-number
  cross-references, running heads.

## Configuration affordances

- **`book.edn`** — open map: title/author metadata (there is no AsciiDoc header
  anymore), chapter list and order, output config, profile selection. Required
  keys enforced; extra keys preserved.
- **`tokens.edn`** — design tokens (color, type, spacing, page) compiled into the
  FO theme.

## Customization model

Replaces the old three tiers with a simpler, more powerful stack:

1. **Tokens** — the cross-profile theme (`tokens.edn` → FO theme).
2. **Profiles** — `:screen` / `:print` layout differences.
3. **Raw FO** — the first-class, full-power escape: drop to `:fo/*` anywhere in
   content or theme. No separate escape-hatch file or language.

## Output affordances

- One PDF per requested profile, under deterministic paths.
- An `artifacts.edn` manifest listing profiles, produced paths, and build metadata.

## Error and validation affordances

- Missing required `book.edn` keys or chapter files → hard error.
- Malformed sugar/book Hiccup → malli validation error with actionable context.
- Invalid raw `:fo/*` → surfaced FOP diagnostics.

## Content and repository affordances

- Third-party manuscripts live in separate repos consuming `clj-book` as a
  dependency.
- The `clj-book` user manual lives under `docs/manual/` as a dogfood manuscript —
  to be re-authored in Hiccup — and is the platform's primary real-manuscript
  regression case.

## Scope of v1

- **Included:** build PDF (`:screen` + `:print`) from Hiccup; `validate`; `tokens`
  theme; deterministic output + manifest.
- **Excluded:** HTML/static site, EPUB, arbitrary CSS interpretation, external
  binaries/subprocesses, Datomic, WYSIWYG.

## Open decisions

- Chapter files `.edn` (pure data) vs `.clj` (programmable, runs at build).
- One PDF edition vs two profiles emitted by default.
- v1 sugar element set + unknown-tag policy.
- Markdown prose front-end — *promoted*; see `docs/plans/markdown-frontend.md`.
- Book-production apparatus (parts, numbering, figures, index, bibliography,
  running heads) — *promoted*; see `docs/plans/book-production.md`.
- Code highlighting — *resolved*: pure JVM tokenizer registry; see
  `docs/plans/book-production.md`.

## BDD Scenarios (Gherkin)

```gherkin
Feature: Render a manuscript to PDF
  As a manuscript maintainer
  I want to build my Hiccup manuscript into a PDF
  So that I can publish a screen or print edition

  Scenario: Build a PDF from a valid manuscript
    Given a manuscript repo with a valid "book.edn"
    And valid Hiccup chapter sources
    And a valid "tokens.edn"
    When I run the build command for the ":print" profile
    Then the command succeeds
    And a PDF artifact is written to a deterministic output path
    And an artifact manifest is emitted

  Scenario: Build both editions
    Given a valid manuscript
    When I run the build command for ":screen" and ":print"
    Then a screen PDF and a print PDF are written to deterministic paths
    And one artifact manifest lists both profiles

Feature: Hiccup superset authoring
  As an author
  I want bog-standard Hiccup to work, with full FO available when needed
  So that the common case is easy and nothing is out of reach

  Scenario: Ordinary HTML hiccup renders
    Given a chapter using ":p", ":h2", ":ul", ":code", and ":a" elements
    When I build the manuscript
    Then those elements are rendered to the corresponding FO and appear in the PDF

  Scenario: Raw FO passes through
    Given a chapter containing a ":fo/block" with FO properties
    When I build the manuscript
    Then the raw FO is emitted verbatim into the FO document
    And the PDF reflects it

  Scenario: Cross-references resolve to page numbers in print
    Given a chapter with "[:xref {:to :ch-config}]"
    And a chapter with id ":ch-config"
    When I build the ":print" profile
    Then the cross-reference renders as a link with the target's page number

Feature: Open-map manuscript configuration
  As a maintainer
  I want required keys enforced while extra keys are preserved

  Scenario: Validation fails when a required key is missing
    Given a "book.edn" missing a required key
    When I run the validate command
    Then the command fails and the error identifies the missing key

  Scenario: Validation accepts additional keys
    Given a "book.edn" with all required keys plus custom keys
    When I run the validate command
    Then the command succeeds

  Scenario: Malformed chapter content is rejected with context
    Given a chapter whose Hiccup violates the vocabulary schema
    When I run the validate command
    Then the command fails with a humanized schema error identifying the element

Feature: Single cross-profile theme
  As a maintainer
  I want one theme definition compiled into the FO output

  Scenario: Tokens drive the PDF theme
    Given a valid "tokens.edn"
    When I build the manuscript
    Then fonts, sizes, spacing, and colors in the PDF derive from the tokens

  Scenario: Build fails when the tokens file is missing
    Given a valid "book.edn" and no "tokens.edn"
    When I run the build command
    Then the command fails and the error identifies the missing tokens path

Feature: Deterministic outputs and manifest
  As a maintainer
  I want reproducible PDFs and machine-readable metadata

  Scenario: Same input produces the same bytes
    Given identical manuscript inputs and the same build request
    When I run the build twice
    Then the produced PDF bytes are identical across both runs

  Scenario: Manifest lists profiles and paths
    Given a successful build for ":screen" and ":print"
    When I inspect the artifact manifest
    Then it lists both profiles, their output paths, and build metadata

Feature: Repository and content boundaries
  As a platform maintainer
  I want clj-book and manuscript content kept separate

  Scenario: Platform tests use synthetic manuscripts
    Given the clj-book repository
    When I run the test suite
    Then it uses synthetic Hiccup fixtures and no third-party manuscript text

  Scenario: The user manual is a dogfood manuscript
    Given a manuscript at "docs/manual/" authored in Hiccup
    When I build it with clj-book
    Then the manual PDF is produced for the requested profiles
```
