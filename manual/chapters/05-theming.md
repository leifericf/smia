# Theming and Editions

Styling comes from design **tokens** rather than stylesheet strings. FOP is not a CSS engine, and Smia does not pretend it is.

## theme.edn

Define the theme once in `theme.edn` at the book root, beside `book.edn`. The split is simple: `book.edn` is the manuscript, `theme.edn` is the appearance. Tokens are grouped into `:color`, `:type`, `:spacing`, and `:layout`:

```edn
{:color  {:text "#1c1c1c" :link "#2a52be"}
 :type   {:body-family "serif" :base-size "11pt"}
 :spacing {:paragraph "6pt"}
 :layout {:page-size :digest :margin-outside "20mm"}}
```

The same file drives every output format. For paged output the tokens compile into the FO properties carried by every block, for HTML output into a generated stylesheet. Missing tokens fall back to readable defaults. `:layout` is page geometry and applies to paged output only; `:page-size` is one of `:a4`, `:letter`, or `:digest`.

Syntax highlighting is two more tokens. Set `:type {:highlight true}` to enable it, and override the palette with a `:code` group mapping token kinds to colors: `:keyword`, `:string`, `:comment`, `:number`, and `:literal`.

Smart punctuation is also a `:type` token. It is on by default; `:type {:smart-punctuation false}` keeps typewriter punctuation as typed. [The Markdown chapter](#markdown) describes what it rewrites.

## Styling escape hatches

When the tokens cannot express a styling need, `theme.edn` takes two optional override groups. Both are data, and they mirror the content escape hatches. Content and styling follow the same matrix:

|          | Portable                  | PDF output            | HTML output            |
|----------|---------------------------|-----------------------|------------------------|
| Content  | sugar + book extensions   | `[:fo/* …]` Hiccup    | `[:html/* …]` Hiccup   |
| Styling  | design tokens             | `:fo` group           | `:css` group           |

`:fo` maps a tag to FO properties merged over the compiled style for that tag:

```edn
:fo {:h1 {:space-before "24pt"}
     :blockquote {:font-style "normal"}}
```

`:css` is a vector of `[selector property-map]` rules appended after the generated stylesheet, so they win on equal specificity:

```edn
:css [["p.fancy" {:color "#bada55"}]
      [".hero"   {:padding "2em"}]]
```

A rule may also be an at-rule wrapper holding plain rules one level deep — a media query for the site, a print rule:

```edn
:css [["@media (max-width: 40em)"
       [".hero" {:padding "1em"}]]]
```

Nobody writes raw FO, HTML, or CSS strings. The serializers are internal, and all four cells of the matrix are plain data.

## Editions

The same manuscript builds into **editions**, the deliverable forms of the book: screen and print PDFs, a press-ready PDF/X, a static site, and an EPUB. One theme styles them all. Both PDF editions build by default; select a subset by repeating `--edition` on the command line, or with `:editions` in the API. [The editions chapter](#editions) describes each edition, and [the commands chapter](#commands) the flags.

## Site layout

The `:site` edition can present its pages in more than one **layout**. A layout is the page chrome, the framing around each chapter's content, and you pick one with a single token:

```edn
:site {:layout :sidebar}
```

Two layouts ship today:

- `:plain`, the default: one centered reading column with a contents link and prev/next navigation at the foot of each page. A book that sets no `:site` group gets it.
- `:sidebar`: a two-column layout with a table-of-contents rail beside the reading column, the current page marked. The page you are reading online uses it. Because the rail already lists every page, the home page is a plain title card rather than a second copy of the contents.

Both layouts are pure HTML and CSS with no JavaScript, and both render the same manuscript: switching is a one-line change to `theme.edn`, and nothing in the chapters moves. On a narrow screen the sidebar stacks above the reading column.

Layouts are a set keyed by name. A value outside the known set fails the build with `:smia.site.layout/unknown-layout`, naming the layout it did not recognize.

## Site search

A second `:site` token turns on reader search:

```edn
:site {:layout :sidebar
       :search true}
```

With it, every page carries a search box. As the reader types, a small script suggests matches grouped by what they are — chapters, sections, figures, tables, listings, index terms — straight from the book's own apparatus. The index is a static `search-index.json` the build writes beside the pages; nothing runs on a server, and the index is as deterministic as every other artifact.

The box is progressive enhancement, not a requirement: it is a plain form, and with JavaScript disabled (or the script unreachable) submitting it lands on a static `search/` page listing the book by category, every page still one click away. The default remains off — a book that does not opt in ships a site with no JavaScript at all. The page you are reading has it on; the manual's own `theme.edn` is the example above.

## Dark mode

A third `:site` token adds a dark color scheme to the site:

```edn
:site {:dark true}
```

The build emits an `@media (prefers-color-scheme: dark)` block, so the page honors the reader's operating-system setting with no toggle and no JavaScript. A computed dark palette is the default; override any of its colors — `:text`, `:background`, `:link`, `:muted`, `:rule`, `:code-background`, `:panel` — with a `:dark` token group:

```edn
:site {:dark true}
:dark {:background "#0d1117" :text "#e6edf3"}
```

A book that does not opt in emits exactly the same stylesheet as before.

## Fonts

With no font configuration, PDF output uses the base-14 font families. A book that needs its own faces registers them through the `:book/print-x` map in `book.edn`, described in [the editions chapter](#editions). Registered fonts are embedded in every PDF edition, and the theme's `:type` families lead with the registered names, falling back to the generics. A list like `"Crimson Text, serif"` works as both an FO font-family and a CSS one. Smia bundles no fonts; the manual's manuscript ships its own under the SIL Open Font License, beside the files.
