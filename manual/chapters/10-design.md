# How It Works

:::overview {:title "What this chapter covers"}
- The build pipeline and its functional core
- Plain data throughout, with a superset vocabulary per format
- Determinism, in-process rendering, and computed `.clj` chapters
:::

Smia is small because it leans on two ideas: everything is plain data, and the only effects live at the edges.

## The pipeline

A build is a short series of transforms from your Hiccup to PDF bytes:

:::deflist
**load**

Read `book.edn` and `theme.edn`; compile each Markdown or `.clj` chapter.

**assemble**

Chapters plus metadata plus theme become one `:fo/root` tree.

**expand**

HTML sugar and book extensions become FO; any `:fo/*` tag passes through.

**serialize**

The FO-Hiccup tree becomes XSL-FO XML.

**render**

Apache FOP turns XSL-FO into PDF, once per PDF edition.
:::

The middle three steps, assemble through serialize, are **pure functions** over plain Clojure data. The only effects are reading inputs at the start and FOP writing bytes at the end.

## Data all the way down

The manuscript, the theme, and the XSL-FO document are all ordinary Clojure data structures. XSL-FO is XML, and Hiccup is a generic XML-tree literal, so an FO element is a vector:

```clojure
[:fo/block {:space-before "12pt"} "text"]
;; serializes to
<fo:block space-before="12pt">text</fo:block>
```

Because the FO tree is data, it can be assembled, transformed, and inspected with the same tools as any other Clojure value.

The vocabulary itself is data too. The Markdown front-end compiles each block and inline construct through a registry — a map from a node's kind to a function — rather than a fixed `case`, so the set of directives (`:::figure`, `:::sidebar`) and inline markers (`` `…`{=cite} ``) is open: a construct is one map entry. Each output format expands the resulting tags through the same kind of table, and a single test pins the two in step.

The same idea localizes the apparatus. Every string smia generates — the float and structure labels, the generated section titles, the admonition labels, the site chrome — is looked up by a stable key in a dictionary keyed by `:book/language`, with English as the shipped baseline and the fallback. A language is a map of overrides; the lookup threads through the numbering, assembly, and expansion passes, so one knob localizes the furniture in every edition while the manuscript's content stays exactly as written.

## A superset per format

For each output format the author vocabulary is a superset of that format's substrate. In a PDF edition any `:fo/*` tag passes straight through, so every XSL-FO construct is reachable; in an HTML edition `:html/*` does the same for HTML. The portable sugar sits on top of that substrate, so an author reaches for a raw tag only when a construct has no sugar yet. The sugar in [the authoring chapter](#authoring) is the portable core that renders in every edition. Reaching for one format's hatch while building another is a structured error at build time, so a portable manuscript stays portable.

:::admonition {:kind :note}
Sugar nested inside a raw `:fo/*` or `:html/*` element still expands, so the layers compose freely in the same tree.
:::

## Functional core, imperative shell

Keeping assembly, expansion, and serialization pure means the hard part of the engine runs on in-memory data, with no files and no FOP. Reading inputs, evaluating chapters, and rendering are confined to a thin shell. Values that cross between the two are checked against schemas, so a malformed value fails at the boundary with a structured error.

## Why Apache FOP, in-process

FOP is a pure-JVM XSL-FO formatter. Smia calls it as a library, never as a subprocess, so a build needs only a JVM. One process also makes errors easy to surface: FOP's events become structured Smia errors and warnings.

## Determinism

Identical inputs should produce equivalent output. Smia serializes FO without pretty-printing, so `white-space="pre"` survives, emits attributes in sorted order, and pins FOP's document metadata. Two builds of the same manuscript agree on pages, text, and bookmarks.

## Styling without CSS

XSL-FO has no CSS cascade: every block carries its own properties. Styling is therefore a **style map** from tag to FO properties, derived from the tokens and the edition's page layout and applied as the sugar expands. There is no stylesheet language to learn beyond the tokens in [the theming chapter](#theming).

## No JavaScript required

The site edition treats JavaScript the way print treats it: the reading experience cannot depend on it. A default build ships none at all. Every scripted feature is an opt-in island layered as progressive enhancement over a page that already works: search over a plain form with a static fallback, the dark toggle and reader preferences over a stylesheet that already follows the system setting, keyboard shortcuts over visible controls. The same discipline holds for layout: the sidebar's narrow-screen contents fold is a native details element the browser opens and closes itself, not a scripted menu. Each island is ClojureScript, and the build compiles its bundle on demand the first site build that needs it. The compiler is an ordinary Maven dependency behind the optional `:cljs` alias, so nothing compiled is committed, building a book runs no Node and no JavaScript toolchain, and the build stays a single JVM process.

## Chapters are programs

A `.clj` chapter is a file whose value is its last form, so a chapter can compute its content: read a real source file, build a table from data, or factor out helpers. The trade-off is explicit. Building such a book runs the author's code, so you build only manuscripts you trust. A Markdown chapter, by contrast, is read as data.
