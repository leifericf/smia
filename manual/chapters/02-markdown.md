# Writing in Markdown

Markdown is the prose-first way to write a chapter: text and code samples go down without escaping, and the dialect is CommonMark with a small set of book extensions. A chapter whose filename ends in `.md` compiles to **author Hiccup**, the data vocabulary [the next chapter](#authoring) describes in full, and everything downstream is identical: assembly, expansion, theming, and every edition.

## Title, id, and front-matter

- The chapter **title** is the first level-1 heading (`# Title`). It is not repeated in the body.
- The chapter **id**, the target of cross-references, comes from the filename with any leading `NN-` ordering prefix stripped: `chapters/05-theming.md` becomes `:theming`.
- A **section heading** may end with a bare EDN map to set its attributes, most usefully an anchor id to cross-reference: `## Structure {:id :structure}`.
- An optional **front-matter** map, a bare EDN map as the very first content of the file, overrides the chapter title or id and supplies any extra keys. The error-catalog appendix uses one to keep the id `:errors` while the file is named `error-catalog`:

```edn
{:id :errors}
```

Front-matter is EDN rather than YAML, and it is read as data, not evaluated.

## Extension grammar

Beyond base CommonMark, a small set of constructs maps one-to-one onto the book vocabulary. Attributes everywhere are **EDN maps**, the same literal you would write in Clojure.

| Need | Markdown | Author Hiccup |
|---|---|---|
| admonition | a `:::admonition {:kind :tip}` … `:::` block | `[:admonition {:kind :tip} …]` |
| overview | a `:::overview` … `:::` block | `[:overview …]` |
| description list | a `:::deflist` block of **bold term** lines and definitions | `[:dl [:dt …] [:dd …] …]` |
| cross-reference | a link whose target is `#id` | `[:xref {:to :id} …]` |
| footnote | `text[^1]` plus a `[^1]:` definition | `[:footnote …]` |
| code block | a fence whose info is `clojure {:test true}` | `[:pre {:lang :clojure :test true} …]` |
| include source | a fence info of `clojure {:include "src/x.clj" :lines [1 20]}` | `[:pre …]` with the file's text |
| include a tagged region | a fence info of `clojure {:include "src/x.clj" :tag "core"}` | `[:pre …]` with the region's text |
| inline math | a code span followed by `{=math}` | `[:math {:notation "…"}]` |
| display math | a fence whose info is `math` | `[:math {:notation "…" :display true}]` |
| raw Hiccup | a fence whose info is `{=hiccup}` | spliced author Hiccup (re-expands) |
| raw FO | a fence whose info is `{=fo}` | spliced FO-Hiccup (verbatim) |
| table widths | a bare `{:cols [3 1]}` line directly above a table | `[:table {:cols [3 1]} …]` |

A code fence's info string is a language token followed by an optional EDN map. An inline escape also works: a code span carrying the payload, immediately followed by the marker `{=hiccup}`.

An `:include` pulls a file relative to the book root, so a listing can be the real source rather than a copy. Two selectors narrow it, and they are exclusive. `:lines [from to]` takes a 1-based inclusive line range; it is positional, so it breaks silently when the file grows. `:tag "name"` takes the region between a line containing `tag::name` and one containing `end::name` instead. The markers live in comments in the source file, any comment syntax works, and the marker lines themselves are excluded from the listing. Several regions may share one tag; they concatenate in file order, which lets a listing skip the noise between two interesting parts.

:::admonition {:kind :note}
The `{=hiccup}` escape is the more powerful of the two: spliced author Hiccup re-enters expansion, so sugar nested inside it still expands. Raw FO is terminal.
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

Code is exempt: nothing inside a code span or a fenced block is rewritten, so a flag like `--clean` keeps its hyphens when set in code. The `{=hiccup}` and `{=fo}` escapes and every front-matter value are data and stay authored exactly, as do `.clj` chapters. Quotes pair within each block, so a quote that opens before an emphasized word still closes after it, and an unbalanced quote cannot leak into the next paragraph.

Smart punctuation is on by default. A book that wants its typewriter punctuation kept as typed turns it off with one token in `theme.edn`, described in [the theming chapter](#theming): `:type {:smart-punctuation false}`.

## Mathematical notation

Write math as LaTeX. An inline formula is a code span carrying the notation, immediately followed by the `{=math}` marker — `` `e^{i\pi} + 1 = 0`{=math} `` renders as `e^{i\pi} + 1 = 0`{=math} in the running text. A fence whose info string is `math` is display math, set off and centered:

```math
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
```

Smia renders the notation at build time, in process, into SVG whose glyphs are outline paths. The same image appears in every edition — the PDFs, the site, the EPUB — with no JavaScript in the page and no font needed at view time. A formula used twice renders once. Inline math sits on a fixed middle alignment rather than a true text baseline; notation with deep descenders may sit a little high.

Rendering needs the optional `:math` alias, composed with the command the same way as the code evaluators below:

```
clojure -M:run:math build
```

A manuscript without math needs nothing. A manuscript with math and no renderer on the classpath fails with `:smia.math/renderer-unavailable`, naming the alias.

## Overviews and description lists

Open a chapter with a summary panel using `:::overview`; an optional `:title` replaces the default "Overview" label:

```
:::overview {:title "In this chapter"}
- What you will build
- The two front-ends
:::
```

A `:::deflist` block becomes a description list. Inside it, a paragraph that is a single **bold** span is a term; the block that follows is its definition:

```
:::deflist
**Manuscript**

The normalized document structure.

**Profile**

A layout variant such as screen or print.
:::
```

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

For a programming book, a code sample should actually work. Mark a fenced block `{:test true}` and run a build or `validate` with `--validate-code`:

```
clojure -M:run validate manual --validate-code
```

Smia then evaluates each marked block through a language-keyed **evaluator registry** and fails the build if any block fails. Validation verifies; it does not capture output. The rendered text stays exactly as written, only the check runs, so the build remains deterministic. The assertion below, for instance, is checked at build time when validation is on:

```clojure {:test true}
(assert (= 6 (reduce + [1 2 3])))
```

An optional `:level` in the block's EDN map selects how far to go: `:parse`, `:compile`, `:run` (the default), or `:assert`, which requires the block's value to be truthy.

### Shipped languages

| Language | Engine | Dependency |
|---|---|---|
| Clojure | native `eval` | none (built in) |
| Groovy | `GroovyShell` | the `:eval-groovy` alias |
| Java | JShell (part of the JDK) | none |
| Kotlin | JSR-223 scripting | the `:eval-kotlin` alias |

A book pulls in only the evaluators it uses; compose the aliases with the command, for example `clojure -M:run:eval-groovy validate manual --validate-code`. The registry accepts further languages as data, but no other evaluators ship today. Validating a non-JVM language would need an external toolchain, and the build deliberately stays within one JVM process.

:::admonition {:kind :warning}
Validation is **not** sandboxed: a `{:test true}` block runs with the full authority of the build JVM, the same trust model as a `.clj` chapter. This is deliberate, so a validated sample behaves exactly as it will for a reader. Only validate manuscripts you trust.
:::
