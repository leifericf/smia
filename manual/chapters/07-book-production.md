# Producing a Book

:::overview {:title "What this chapter covers"}
- Declaring a book's shape in `book.edn`
- Figures, captioned tables, code listings, and the generated lists of each
- Annotating a listing line by line
- References, the index, and page mechanics
:::

A single chapter is only the start. A finished book has parts, numbered chapters
and appendices, named front and back matter, captioned figures and code
listings, cross-references that read "Figure 1" rather than a bare page number,
running heads, an index, and a bibliography. Smia produces all of these from
data, and this chapter both explains and exercises them.

:::epigraph {:attribution "C. Designer"}
Programs that manipulate plain data are easier to reason about than programs that
manipulate objects with hidden state.
:::

The quotation above is an `:::epigraph`, set off from the running text, with
an optional `:attribution` beneath it.

## Structure lives in book.edn {:id :structure-demo}

You declare a book's shape in `book.edn`. A flat `:book/chapters` list is still
the zero-config default; to group chapters, use `:book/parts`, and add
`:book/front-matter`, `:book/appendices`, and `:book/back-matter` around them:

```clojure {:id :lst-book-edn :file "book.edn" :caption "Declaring a structured book"}
{:book/slug  "the-manual"
 :book/title "The Manual"
 :book/references "references.edn"
 :book/front-matter [{:role :preface :file "front/preface.md"}]
 :book/parts [{:part/title "Foundations"
               :part/chapters ["chapters/01-intro.md"]}]
 :book/appendices ["appendix/a-glossary.md"]
 :book/back-matter [{:role :list-of-figures}
                    {:role :list-of-tables}
                    {:role :list-of-listings}
                    {:role :bibliography}
                    {:role :index}]}
```

Some matter sections are **generated**: they have a `:role` but no `:file`,
because Smia produces their content. Alongside `:bibliography` and `:index`,
the roles `:list-of-figures`, `:list-of-tables`, and `:list-of-listings` each
emit a navigation section listing every numbered float of that kind, in document
order, with a page reference. The lists at the back of this manual are exactly
these. Give any of them a `:title` to override the default heading. Any other
keyword is a custom role: give it a `:file` and the section renders under a
title-cased version of its name, or a `:title` of your choosing.

Parts are numbered with roman numerals, chapters with arabic, and appendices
with letters. `:book/numbering` overrides any of these per kind with `:roman`,
`:arabic`, `:letter`, or `false` to turn numbering off, for example
`{:chapters :roman}`. Sections are unnumbered by default; `{:sections true}`
adds decimal section numbers. In the print layout,
`{:start-chapters-on :recto}` opens every chapter on a right-hand page. See
[the configuration chapter](#configuration) for the full key reference.

## Figures and captions

Wrap an image in a `:::figure` directive with an `:id` and a `:caption` to get a
numbered, captioned figure. The pipeline that turns your sources into a PDF is
shown in [](#fig-pipeline):

:::figure {:id :fig-pipeline :caption "The Smia rendering pipeline"}
![The Smia pipeline](images/pipeline.svg)
:::

In Hiccup the same figure is `[:figure {:id :fig-pipeline :caption "…"} [:img …]]`.

### Diagrams from text

A figure need not be a pre-drawn image. A fence whose info string is `plantuml` holds diagram source, rendered to SVG at build time, in process, with a pure-Java layout engine — no external drawing tool. It composes with `:::figure` for numbering, and the figure's caption doubles as the diagram's alt text. [](#fig-frontends) is text in this chapter's source:

:::figure {:id :fig-frontends :caption "The two chapter front-ends meet in author Hiccup"}
```plantuml
rectangle "chapter.md" as md
rectangle "chapter.clj" as clj
rectangle "author Hiccup" as hiccup
rectangle "editions" as editions
md --> hiccup
clj --> hiccup
hiccup --> editions
```
:::

A diagram outside a figure carries its own `:alt` (or a `:caption`) in the fence's EDN map. Rendering needs the optional `:diagrams` alias, exactly like math's `:math`: `clojure -M:run:diagrams build`. The renderer measures label widths with the build machine's font metrics, so the drawn output is stable on one machine but may differ in detail across platforms; the diagram source in the manuscript is the canonical artifact.

A second diagram syntax, a `mermaid` fence, is rendered in the reader's browser instead of at build time, because no pure-JVM mermaid renderer exists. It is therefore a **site-only** affordance, opt-in through the theme (see [the theming chapter](#theming)). On the site, each `mermaid` fence becomes a `<pre class="mermaid">` block a small script transforms; with JavaScript disabled, the diagram's source shows. In the PDF and EPUB editions, which have no browser, a `mermaid` diagram renders as a plain source listing. A diagram you need drawn in every edition is a `plantuml` fence; `mermaid` is for the site.

:::admonition {:kind :note}
A diagram with overlaid callouts, leader lines pointing at parts of an image,
should be authored as a single pre-rendered image; the page model cannot
position free-floating marks over arbitrary coordinates. To explain a diagram's
parts, pair the figure with a description list or an annotated listing beneath
it, which carry their own numbered references.
:::

## Captioned tables

A bare EDN map on the line directly above a table supplies its attributes,
including an `:id` and a `:caption`. The section roles Smia understands are
listed in [](#tbl-roles):

{:id :tbl-roles :caption "Section roles in a manuscript"}

| Role | Where | Numbering |
|---|---|---|
| front matter | before the body | roman pages |
| chapter | the body | arabic |
| appendix | after the body | letters |
| back matter | the end | arabic |

## Code listings

A fenced code block whose info map carries a `:file` gets a filename header bar;
a `:caption` makes it a numbered listing you can cross-reference. Source is
syntax-highlighted when the theme enables it, and `:line-numbers true` adds a
gutter. A `:fold` attribute makes a long listing collapsible on the site — it
becomes a native `<details>` disclosure that folds away with no JavaScript, and
`:fold "label"` sets the summary text; print and EPUB always show the full
listing. [](#lst-build) builds a book from the command line:

```bash {:id :lst-build :file "build.sh" :caption "Building both editions" :line-numbers true :annotations [{:line 1 :note "The book directory that holds book.edn"} {:line 2 :note "Render the on-screen edition"} {:line 3 :note "Render the print edition, with mirrored margins"}]}
clojure -M:run build my-book \
  --edition screen \
  --edition print
```

Highlighting is a pure, in-process tokenizer, so builds stay deterministic. The
tokenizer is built from a small library of lexical fragments and a handful of
language-family factories, so a language is usually a reserved-word set plus a
family. It ships for the eight curated tokenizers — Clojure, Java, Kotlin,
Groovy, JavaScript, Python, SQL, and shell scripts (`bash`) — alongside a wider
set keyed by family:

- **C-family** (`//` and `/* */`): `c`, `cpp`, `csharp`, `go`, `rust`, `scala`, `swift`, `dart`, `objc`, `php`, `solidity`, `zig`, `d`, `protobuf`, and `typescript` (with template literals).
- **Hash-comment scripting** (`#`): `ruby`, `r`, `perl`, `julia`, `elixir`, `coffeescript`, `powershell`, `toml`, `yaml`, `dockerfile`, `makefile`.
- **Lisp** (`;`): `scheme`, `racket`, `commonlisp`, `fennel`, `edn`.
- **ML** (`(* *)`): `ocaml`, `fsharp`, `sml`.
- **Percent-comment** (`%`): `latex`, `erlang`, `matlab`, `prolog`.
- **Dash-comment** (`--` and `{- -}`): `haskell`, `elm`, `lua`, `ada`, `purescript`.

An unknown language falls back to plain monospace. The listing above is
highlighted as `bash`.

### Annotating a listing

The numbered marks at the ends of the lines above come from an `:annotations`
vector in the fence's attribute map. Each entry names a 1-based `:line` and a
`:note`, and Smia emits the matching numbered list beneath the code. The notes
live in data, so no markers are woven into the code itself and a reader can
copy it verbatim. A `:note` may be plain text or inline markup, each line
carries at most one note, and a `:line` outside the listing is an error.

## Sidebars

A `:::sidebar` is a titled callout, a generalization of the admonition with an
arbitrary title and an optional icon:

:::sidebar {:title "On determinism" :icon "*"}
The serialized FO is byte-for-byte reproducible. The PDF is not, because the PDF
format embeds a timestamp; the intermediate `.fo` is the equivalence oracle. See
`reproducible`{=cite} for the general argument.
:::

## Citations and the index

References live in the EDN file named by `:book/references`, a map from
citation key to entry. An entry carries `:author`, `:title`, `:year`, and
optionally `:publisher`; the generated bibliography lists every entry, sorted
by author:

```edn
{:typesetting {:author "B. Writer"
               :title  "Foundations of Digital Typesetting"
               :year   2018}}
```

Cite an entry inline with `` `key`{=cite} ``, which links to the
generated bibliography, for example `typesetting`{=cite} on digital
typesetting`Typesetting`{=index} or `dataoriented`{=cite} on data-oriented
design`Data-oriented design`{=index}. Mark a term for the index with
`` `term`{=index} ``; Smia collects every mark into an alphabetical index
with page references. Cross-references`Cross-references`{=index} resolve the same
way whether they point at a chapter, a section like [](#structure-demo), a
figure, a table, or a listing. In Hiccup, an `:xref` with `:style :full`
renders the full label and title, "Chapter 2: Title", in the PDF editions.

## Running heads

Every page carries a running head and a page-number folio. The defaults: a
verso page shows the chapter title, a recto page the current section, and
the symmetric screen layout the chapter title on every page.
`:book/running-heads` overrides any slot. Keys are the page parities,
`:verso` and `:recto` for the print layout and `:screen` for the symmetric
ones; each maps `:before` (the head) and `:after` (the foot) to one of
`:chapter`, `:section`, `:page`, `:book-title`, or `nil` for empty:

```edn
:book/running-heads {:verso {:before :book-title}
                     :recto {:before :chapter}}
```

## Page mechanics

Two thin wrappers control pagination. `:::keep-together` holds a block on one
page, and `:::page-break` forces a break. The Hiccup forms are `[:keep-together
…]` and `[:page-break]`.

:::page-break
:::

:::keep-together
This paragraph and the one after it are kept together on the same page, so a
short, self-contained example is never split across a page boundary by the
line-breaker.

That is the whole apparatus: structure, numbering, references, figures,
listings, sidebars, and page control, all from plain data.
:::
