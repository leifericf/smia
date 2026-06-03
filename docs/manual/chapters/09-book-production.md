{:id :book-production}
# Producing a Book

A single chapter is only the start. A finished book has parts, numbered chapters
and appendices, named front and back matter, figures and code listings with
captions, cross-references that read "Figure 1" rather than a bare page number,
running heads, an index, and a bibliography. clj-book produces all of these from
data, and this chapter both explains and exercises them.

:::epigraph {:attribution "C. Designer"}
Programs that manipulate plain data are easier to reason about than programs that
manipulate objects with hidden state.
:::

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
 :book/back-matter [{:role :bibliography} {:role :index}]}
```

Parts are numbered with roman numerals, chapters with arabic, and appendices
with letters. Sections are unnumbered by default; set
`:book/numbering {:sections true}` for decimal section numbers. See
[the configuration chapter](#configuration) for the full key reference.

## Figures and captions

Wrap an image in a `:::figure` directive with an `:id` and a `:caption` to get a
numbered, captioned figure. The pipeline that turns your sources into a PDF is
shown in [](#fig-pipeline):

:::figure {:id :fig-pipeline :caption "The clj-book rendering pipeline"}
![The clj-book pipeline](images/pipeline.svg)
:::

In Hiccup the same figure is `[:figure {:id :fig-pipeline :caption "…"} [:img …]]`.

## Captioned tables

A bare EDN map on the line directly above a table supplies its attributes,
including an `:id` and a `:caption`. The section roles clj-book understands are
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
gutter. [](#lst-build) builds a book from the command line:

```bash {:id :lst-build :file "build.sh" :caption "Building both editions" :line-numbers true}
clojure -X clj-book.api/build \
  :book-root '"."' \
  :profiles '[:screen :print]'
```

Highlighting is a pure, in-process tokenizer — no subprocess — so builds stay
deterministic. It ships for Clojure, Java, Kotlin, and Groovy; an unknown
language falls back to plain monospace.

## Sidebars

A `:::sidebar` is a titled callout — a generalization of the admonition with an
arbitrary title and an optional icon:

:::sidebar {:title "On determinism" :icon "*"}
The serialized FO is byte-for-byte reproducible. The PDF is not, because the PDF
format embeds a timestamp; the intermediate `.fo` is the equivalence oracle. See
`reproducible`{=cite} for the general argument.
:::

## Citations and the index

Cite a bibliography entry inline with `` `key`{=cite} ``, which links to the
generated bibliography — for example `typesetting`{=cite} on digital
typesetting`Typesetting`{=index}, or `dataoriented`{=cite} on data-oriented
design`Data-oriented design`{=index}. Mark a term for the index with
`` `term`{=index} ``; clj-book collects every mark into an alphabetical index
with page references. Cross-references`Cross-references`{=index} resolve the same
way whether they point at a chapter, a section like [](#structure-demo), a
figure, a table, or a listing.

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
listings, sidebars, and page control — all from plain data.
:::
