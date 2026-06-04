# Writing in Markdown

A chapter whose filename ends in `.md` is written in a curated CommonMark dialect and compiled to the same author Hiccup a `.clj` chapter produces. Everything downstream — assembly, expansion, theming, and the screen and print editions — is identical. Markdown is prose-first, so it removes the string-escaping friction of writing prose and code samples directly in Hiccup.

## Title, id, and front-matter

- The chapter **title** is the first level-1 heading (`# Title`), which is not repeated in the body.
- The chapter **id** — the target of cross-references — is derived from the filename, with any leading `NN-` ordering prefix stripped: `chapters/05-theming.md` becomes `:theming`.
- An optional **front-matter** map — a bare EDN map as the very first content of the file — overrides either and supplies any extra keys. This chapter's neighbor uses one to keep the id `:errors` while the file is named `error-catalog`:

```edn
{:id :errors}
```

Front-matter is EDN, never YAML, and is read as data — never evaluated.

## Extension grammar

Beyond base CommonMark, a small, curated set of constructs maps one-to-one onto the book vocabulary. Attributes everywhere are **EDN maps** — the same literal you would write in Clojure.

| Need | Markdown | Author Hiccup |
|---|---|---|
| admonition | a `:::admonition {:kind :tip}` … `:::` block | `[:admonition {:kind :tip} …]` |
| overview | a `:::overview` … `:::` block | `[:overview …]` |
| description list | a `:::deflist` block of **bold term** lines and definitions | `[:dl [:dt …] [:dd …] …]` |
| cross-reference | a link whose target is `#id` | `[:xref {:to :id} …]` |
| footnote | `text[^1]` plus a `[^1]:` definition | `[:footnote …]` |
| code block | a fence whose info is `clojure {:test true}` | `[:pre {:lang :clojure :test true} …]` |
| include source | a fence info of `clojure {:include "src/x.clj" :lines [1 20]}` | `[:pre …]` with the file's text |
| raw Hiccup | a fence whose info is `{=hiccup}` | spliced author Hiccup (re-expands) |
| raw FO | a fence whose info is `{=fo}` | spliced FO-Hiccup (verbatim) |
| table widths | a bare `{:cols [3 1]}` line directly above a table | `[:table {:cols [3 1]} …]` |

A code fence's info string is a language token followed by an optional EDN map. An inline escape also works: a code span carrying the payload, immediately followed by the marker `{=hiccup}`.

:::admonition {:kind :note}
The `{=hiccup}` escape is strictly more powerful than `{=fo}`: spliced author Hiccup re-enters expansion, so sugar nested inside it still expands, whereas raw FO is terminal.
:::

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

A code listing can carry numbered notes anchored to specific lines, without touching the sample itself. Add an `:annotations` vector to the fence's EDN map — each entry names a 1-based `:line` and a `:note`. Smia appends a small numbered mark at the end of each referenced line and emits a matching numbered list beneath the listing:

````
```clojure {:id :ex :caption "The reducing core" :annotations [{:line 1 :note "Defines the accumulator"} {:line 2 :note "Folds the sequence with +"}]}
(def xs [1 2 3])
(reduce + xs)
```
````

Because the notes are data, the code stays pristine and copy-pasteable — no in-text markers. A `:note` may be a plain string or inline markup (for example ``[:span "Folds with " [:code "reduce"]]`` in Hiccup). Each line carries at most one note, and every `:line` must fall within the listing.

## Validating code examples

For a programming book, a code sample should actually work. Mark a fenced block `{:test true}` and run a build or `validate` with `:validate-code true`:

```
clojure -M:run validate manual --validate-code
```

Smia then evaluates each marked block through a language-keyed **evaluator registry** and fails the build if any block fails. Validation is **verify, not capture**: the rendered text stays exactly as written — only the check runs — so output stays deterministic. The assertion below, for instance, is checked at build time when validation is on:

```clojure {:test true}
(assert (= 6 (reduce + [1 2 3])))
```

An optional `:level` in the block's EDN map selects how far to go: `:parse`, `:compile`, `:run` (the default), or `:assert` (the block's value must be truthy).

### Shipped languages

| Language | Engine | Dependency |
|---|---|---|
| Clojure | native `eval` | none (built in) |
| Groovy | `GroovyShell` | the `:eval-groovy` alias |
| Java | JShell (part of the JDK) | none |
| Kotlin | JSR-223 scripting | the `:eval-kotlin` alias |

A book pulls in only the evaluators it uses; compose the aliases with the command, for example `clojure -M:run:eval-groovy validate manual --validate-code`. Scala and non-JVM languages are designed for — the registry accepts them as data — but not yet shipped; non-JVM validation would need an external toolchain, stepping outside the pure-JVM, hermetic guarantee.

:::admonition {:kind :warning}
Validation is **not** sandboxed: a `{:test true}` block runs with the full authority of the build JVM — the same trust model as a `.clj` chapter. This is deliberate, so a validated sample behaves exactly as it will for a reader. Only validate manuscripts you trust.
:::
