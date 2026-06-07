# Writing in Markdown

:::overview {:title "What this chapter covers"}
- Writing a chapter in CommonMark, with its title, id, and front matter
- The book extensions, from admonitions to math and data tables
- Document attributes, conditional content, and validated code samples
:::

Markdown is the prose-first way to write a chapter: text and code samples go down without escaping, and the dialect is CommonMark[^commonmark] with a small set of book extensions. A chapter whose filename ends in `.md` compiles to author Hiccup, the data vocabulary [the next chapter](#authoring) describes in full, and everything downstream is identical: assembly, expansion, theming, and every edition.

[^commonmark]: CommonMark is the strongly specified Markdown dialect; the specification lives at `spec.commonmark.org`.

The [CommonMark tutorial](https://commonmark.org/help/tutorial/) introduces the base syntax.

## Title, id, and front matter

- The chapter **title** is the first level-1 heading (`# Title`). It is not repeated in the body.
- The chapter **id**, the target of cross-references, comes from the filename with any leading `NN-` ordering prefix stripped: `chapters/05-theming.md` becomes `:theming`.
- A **section heading** may end with a bare EDN map to set its attributes, most usefully an anchor id to cross-reference: `## Structure {:id :structure}`.
- An optional **front-matter map**, a bare EDN map as the very first content of the file, overrides the chapter title or id and supplies any extra keys. The error-catalog appendix uses one to keep the id `:errors` while the file is named `error-catalog`:

```edn
{:id :errors}
```

Front matter is EDN (Extensible Data Notation) rather than YAML, and it is read as data, not evaluated.

## Extension grammar

Beyond base CommonMark, a small set of constructs maps one-to-one onto the book vocabulary. Attributes everywhere are **EDN maps**, the same literal you would write in Clojure.

| Need | Markdown | Author Hiccup |
|---|---|---|
| admonition | a `:::admonition {:kind :tip}` … `:::` block | `[:admonition {:kind :tip} …]` |
| overview | a `:::overview` … `:::` block | `[:overview …]` |
| example | a `:::example {:title "…"}` … `:::` block | `[:example {…} …]` |
| disclosure | a `:::details {:summary "…"}` (or `:::open`) block | `[:details {…} …]` |
| conditional | a `:::when {<condition>}` … `:::` block | `[:when {<condition>} …]` |
| description list | a `:::deflist` block of **bold term** lines and definitions | `[:dl [:dt …] [:dd …] …]` |
| cross-reference | a link whose target is `#id` | `[:xref {:to :id} …]` |
| footnote | `text[^1]` plus a `[^1]:` definition | `[:footnote …]` |
| keyboard chord | a code span `["Ctrl" "S"]` followed by `{=kbd}` | `[:kbd …]` (joined with `+`) |
| menu path | a code span `["File" "Export"]` followed by `{=menu}` | `[:menu "File" "Export"]` |
| button, highlight, sub/superscript | a code span followed by `{=button}`, `{=mark}`, `{=sub}`, or `{=sup}` | `[:button …]`, `[:mark …]`, `[:sub …]`, `[:sup …]` |
| code block | a fence whose info is `clojure {:test true}` | `[:pre {…} …]` |
| include source | a fence info of `clojure {:include "src/x.clj" :lines [1 20]}` | `[:pre …]` with the file's text |
| include a tagged region | a fence info of `clojure {:include "src/x.clj" :tag "core"}` | `[:pre …]` with the region's text |
| document attribute | a code span (the attribute name) followed by `{=attr}` | `[:attr :name]` (resolved before numbering) |
| inline math | a code span followed by `{=math}` | `[:math {:notation "…"}]` |
| display math | a fence whose info is `math` | `[:math {:notation "…" :display true}]` |
| diagram | a fence whose info is `plantuml` | `[:diagram {:source "…"}]` |
| raw Hiccup | a fence whose info is `{=hiccup}` | spliced author Hiccup (re-expands) |
| raw FO | a fence whose info is `{=fo}` | spliced FO-Hiccup (verbatim) |
| table widths | a bare `{:cols [3 1]}` line directly above a table | `[:table {:cols [3 1]} …]` |
| data table | a `:::table {:data "data/x.csv"}` block | `[:table …]` built from the file's rows |

A code fence's info string is a language token followed by an optional EDN map. An inline escape also works: a code span carrying the payload, immediately followed by the marker `{=hiccup}`. FO in the table above is XSL-FO, the page-description vocabulary the PDF renderer consumes; [the authoring chapter](#authoring) and [the design chapter](#design) cover it. Figures and the rest of the book apparatus are treated in [the book-production chapter](#book-production). Raw HTML blocks and spans from the CommonMark spec do not pass through: the build rejects them with `:smia.md.compile/unsupported-node`; use a `{=hiccup}` escape instead.

An `:include` pulls a file relative to the book root, so a listing can be the real source rather than a copy. Two selectors narrow it, and they are exclusive. `:lines [from to]` takes a 1-based inclusive line range; it is positional, so it breaks silently when the file grows. `:tag "name"` takes the region between a line containing `tag::name` and one containing `end::name` instead. The markers live in comments in the source file; any comment syntax works, and the marker lines themselves are excluded from the listing. Several regions may share one tag; they concatenate in file order, which lets a listing skip the noise between two interesting parts.

:::admonition {:kind :note}
The `{=hiccup}` escape reaches further: spliced author Hiccup re-enters expansion, so sugar nested inside it still expands. Raw FO is terminal.
:::

## Smart punctuation

Markdown prose is typeset with typographic punctuation. Straight quotes become curly pairs, apostrophes become right single quotes, `--` becomes an en dash, `---` an em dash, and `...` an ellipsis:

| Typed | Rendered |
|---|---|
| `"quoted"` | “quoted” |
| `it's` | it’s |
| `pages 3--5` | pages 3–5 |
| `wait --- now` | wait — now |
| `and so on...` | and so on… |

The right column is the rendered result of the same transform this page goes through, not what you type.

Code is exempt: nothing inside a code span or a fenced block is rewritten, so a flag like `--clean` keeps its hyphens when set in code. The `{=hiccup}` and `{=fo}` escapes and every front-matter value are data and stay authored exactly, as do `.clj` chapters. Quotes pair within each block, so a quote that opens before an emphasized word still closes after it, and an unbalanced quote cannot leak into the next paragraph.

Smart punctuation is on by default. A book that wants its typewriter punctuation kept as typed turns it off with one token in `theme.edn`, described in [the theming chapter](#theming): `:type {:smart-punctuation false}`.

## Mathematical notation

Write math as LaTeX, the notation mathematicians and any LaTeX math reference use; its syntax is a subject of its own. An inline formula is a code span carrying the notation, immediately followed by the `{=math}` marker: `` `e^{i\pi} + 1 = 0`{=math} `` renders as `e^{i\pi} + 1 = 0`{=math} in the running text. A fence whose info string is `math` is display math, set off and centered. Notation of any complexity works; this is Euler's product formula,[^euler] linking the integers to the primes:

[^euler]: Proved by Leonhard Euler in his 1737 paper on infinite series, and the starting point of analytic number theory.

```math
\sum_{n=1}^{\infty} \frac{1}{n^{s}} \;=\; \prod_{p\ \mathrm{prime}} \frac{1}{1 - p^{-s}}
```

The quadratic formula, the Gaussian integral, a continued fraction: anything LaTeX can write, the page can carry.

```math
\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}
```

Smia renders the notation at build time, in process, into SVG whose glyphs are outline paths. The same image appears in every edition: the PDFs, the site, and the EPUB. No JavaScript is needed in the page and no font is needed at view time. A formula used twice renders once. Inline math sits on a fixed middle alignment rather than a true text baseline; notation with deep descenders may sit a little high.

The packaged `smia` command bundles the renderer. On the Clojure CLI track it sits behind the optional `:math` alias, composed with the command just as the code evaluators below are:

```
clojure -M:run:math build
```

A manuscript without math needs nothing. A manuscript with math but no math renderer available fails with `:smia.math/renderer-unavailable`, naming the alias.

Diagrams work the same way: a fence whose info string is `plantuml` holds diagram text, rendered to SVG at build time behind the optional `:diagrams` alias. [The book-production chapter](#book-production) shows one composed with a numbered figure.

## Overviews and description lists

Open a chapter with a summary panel using `:::overview`; an optional `:title` replaces the default "Overview" label:

```
:::overview {:title "In this chapter"}
- What you will build
- The two front ends
:::
```

A `:::deflist` block becomes a description list. Inside it, a paragraph that is a single **bold** span is a term; the block that follows is its definition:

```
:::deflist
**Recto**

The right-hand page of a spread.

**Verso**

The left-hand page, recto's other half.
:::
```

## Interface vocabulary

Writing about software means naming its interface. Six inline markers cover the common cases. A key chord is an EDN sequence of key names followed by `{=kbd}`: `` `["Ctrl" "S"]`{=kbd} `` sets each key in its own box and joins them with a plus. A menu path uses `{=menu}`, as in `` `["File" "Export"]`{=menu} ``, and the separator is drawn for you. The remaining four wrap a literal label: `{=button}` for a control, `{=mark}` to highlight, and `{=sub}` and `{=sup}` for sub- and superscript, as in `` H`2`{=sub}O ``.

Two block directives group richer content. A `:::example` is a titled worked-example callout; a `:::details` (or `:::open`, which starts expanded) is a disclosure that folds away on the site. Print has no interactivity, so a disclosure there shows its summary as a heading above the always-visible body.

```
:::example {:title "Rounding a ratio"}
The `ratio?` branch renders as a double so the output stays portable.
:::
```

## Data tables

A `:::table` directive builds a table from a data file rather than from rows typed by hand. It names the file in `:data`; the build reads it relative to the book root and fills the table with its rows. [](#tbl-trims) is built that way, from `data/page-sizes.csv` shipped beside this manuscript:

```
:::table {:data "data/page-sizes.csv" :header true :cols [2 2 2 5]
          :id :tbl-trims :caption "The named trim sizes"}
:::
```

:::table {:data "data/page-sizes.csv" :header true :cols [2 2 2 5] :id :tbl-trims :caption "The named trim sizes"}
:::

The format follows the file extension (`.csv`, `.tsv`, or `.edn`); an explicit `:format` overrides it. CSV and TSV are read in the usual way, with quoted fields and doubled quotes; the first row of the file above quotes a field holding a comma. An EDN file is a sequence of row sequences. With `:header true` the first row becomes the table head. Any other attribute (`:id`, `:caption`, `:cols`, including `:cols :auto`) rides along to the table, so a data table numbers, captions, and sizes like any other. The one above is a numbered table in the list of tables. A missing file is a build error.

Data tables keep measured values out of the manuscript. A benchmark run, an export from a spreadsheet, or a file another program writes can feed a table directly, and regenerating the file updates the book. When the rows must be computed rather than read from a file, a `.clj` chapter can compute them; [the code-authoring appendix](#code-authoring) shows how.

## Document attributes

A value you repeat belongs in one place: a version, a product name, a release year. Declare it once under `:book/attributes` in `book.edn` (see [the configuration chapter](#configuration)), then reference it by name: a code span carrying the attribute name, followed by the `{=attr}` marker. The reference resolves to the declared value. A chapter's front-matter map can override an attribute for that chapter. The build's `--licensee` and the book `:language` are available too, as `licensee` and `language`.

A value may be a string or author Hiccup, and it resolves before numbering, so an attribute holding a captioned figure is numbered in document order like any other. An undeclared name is a build error rather than a silent blank.

## Conditional content

A `:::when` block keeps or drops its content based on a condition. The condition is the directive's attribute map, one of:

- `{:defined :draft}` — an attribute (or build value) by that name is present
- `{:equals [:edition :epub]}` — the named value equals the given one
- `{:any-of [c …]}` / `{:all-of [c …]}` — combine conditions with or / and
- `{:not c}` — negate a condition

```
:::when {:equals [:edition :epub]}
This note appears only in the EPUB edition.
:::
```

Conditions evaluate against the chapter's attributes and front-matter map, plus the current `:edition`. A condition that names `:edition` makes the content vary by edition; one that does not resolves once for every edition, so the figure and table numbers stay identical across editions. See [the editions chapter](#editions) for how per-edition content interacts with numbering.

## Annotating code

A code listing can carry numbered notes anchored to specific lines, without touching the sample itself. Add an `:annotations` vector to the fence's EDN map; each entry names a 1-based `:line` and a `:note`. Smia appends a small numbered mark at the end of each referenced line and emits a matching numbered list beneath the listing:

````
```clojure {:id :ex :caption "The reducing core" :annotations [{:line 1 :note "Defines the accumulator"} {:line 2 :note "Folds the sequence with +"}]}
(def xs [1 2 3])
(reduce + xs)
```
````

The notes are data, so the code stays exactly as written and a reader can copy it verbatim. A `:note` may be a plain string or inline markup, for example ``[:span "Folds with " [:code "reduce"]]`` in Hiccup. Each line carries at most one note, and every `:line` must fall within the listing.

## Validating code examples

For a programming book, a code sample must work. Mark a fenced block `{:test true}` and run a build or `validate` with `--validate-code`:

```
smia validate manual --validate-code
```

Smia then evaluates each marked block through a language-keyed evaluator registry and fails the build if any block fails. Validation verifies; it does not capture output. The rendered text stays exactly as written, only the check runs, so the build remains deterministic. The assertion below, for instance, is checked at build time when validation is on:

```clojure {:test true}
(assert (= 6 (reduce + [1 2 3])))
```

An optional `:level` in the block's EDN map selects how far to go: `:parse`, `:compile`, `:run` (the default), or `:assert`, which requires the block's value to be truthy.

**The shipped languages** each have an evaluator in the registry. The dependency column is what you act on; the engine column names the machinery behind it, for the curious.

| Language | Engine | Dependency |
|---|---|---|
| Clojure | native `eval` | none (built in) |
| Groovy | `GroovyShell` | the `:eval-groovy` alias |
| Java | JShell (part of the JDK) | none |
| Kotlin | JSR-223 scripting | the `:eval-kotlin` alias |

A book pulls in only the evaluators it uses. Clojure and Java validation works everywhere, including from the packaged `smia` command; the Groovy and Kotlin evaluators are not bundled in the jar, so validating those languages runs on the Clojure CLI track with the alias composed with the command, for example `clojure -M:run:eval-groovy validate manual --validate-code`. The registry accepts further languages as data, but no other evaluators ship today. Validating a non-JVM language would need an external toolchain, and the build deliberately stays within one JVM process.

:::admonition {:kind :warning}
Validation is **not** sandboxed: a `{:test true}` block runs with the full authority of the build JVM, the same trust model as a `.clj` chapter. This is deliberate, so a validated sample behaves exactly as it will for a reader. Only validate manuscripts you trust.
:::
