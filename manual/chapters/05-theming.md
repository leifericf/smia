# Theming and editions

Styling comes from design **tokens** — never from arbitrary stylesheet strings. For paged output FOP is not a CSS engine, and Smia does not pretend it is.

## theme.edn

Define the theme once in `theme.edn` at the book root, beside `book.edn` — `book.edn` is the manuscript, `theme.edn` is the appearance. Tokens are grouped into `:color`, `:type`, `:spacing`, and `:layout`:

```edn
{:color  {:text "#1c1c1c" :link "#2a52be"}
 :type   {:body-family "serif" :base-size "11pt"}
 :spacing {:paragraph "6pt"}
 :layout {:page-size :a4 :margin-outside "20mm"}}
```

The tokens compile into the FO properties carried by every block; missing tokens fall back to readable base-14 defaults. The same file drives every output format: for paged output the tokens compile to FO properties, for HTML output to a generated stylesheet. `:layout` is page geometry and applies to paged output only.

## Styling escape hatches

When the tokens cannot express a styling need, `theme.edn` takes two optional override groups. Both are data, mirroring the content escape hatches — content and styling follow the same matrix:

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

Nobody writes raw FO, HTML, or CSS strings — the serializers are internal, and all four cells of the matrix are plain data.

## Editions

The same manuscript builds into **editions** — the deliverable forms of the book:

- `:screen` — a PDF with comfortable, symmetric margins for on-screen reading.
- `:print` — a PDF with mirrored recto/verso margins and a binding gutter on the inside edge.
- `:print-x` — the print layout as press-ready PDF/X-4.
- `:site` — a static HTML site styled by the same theme.
- `:epub` — an accessible EPUB3 package over the same pages.

Both PDF editions are built by default; pass `:editions '[:print]'` (or repeat `--edition` on the command line) to select a subset. See [the commands chapter](#commands) and [the editions chapter](#editions).

## Site layout

The `:site` edition can present its pages in more than one **layout**. A layout is the page chrome — the framing around each chapter's content — and you pick one with a single token:

```edn
:site {:layout :sidebar}
```

Two layouts ship today:

- `:plain` (the default) — one centered reading column with a contents link and prev/next navigation at the foot of each page. This is the minimal style; a book that sets no `:site` group gets it.
- `:sidebar` — a two-column "docs" layout with a sticky table-of-contents rail beside the reading column, the current page marked. The page you are reading online uses it. Because the rail already lists every page, the home page is a plain title card rather than a second copy of the contents.

Both are pure HTML and CSS with no JavaScript, and both render the same manuscript: switching layout is a one-line change to `theme.edn`, nothing in the chapters moves. The sidebar collapses to a single stacked column on a narrow screen through `flex-wrap` alone, so the generated stylesheet stays flat and deterministic — no media queries.

Layouts are an extensible set keyed by name; a value outside the known set is a build-time error, `:smia.site.layout/unknown-layout`, naming the layout it did not recognize.

## Fonts

With no font configuration, PDF output uses the base-14 font families, so a new book is zero-config and always reproducible. A book that needs its own faces registers them through the `:book/print-x` map in `book.edn` (see [the editions chapter](#editions)); once registered they are embedded in every PDF edition, and the theme's `:type` families lead with the registered names, falling back to the generics — a list like `"Crimson Text, serif"` works as both an FO font-family and a CSS one. The Smia platform bundles no fonts; the manual's manuscript ships its own under their SIL Open Font License, beside the files.
