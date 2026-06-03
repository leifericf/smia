# Theming and editions

Styling comes from design **tokens** — never from arbitrary stylesheet strings. For paged output FOP is not a CSS engine, and clj-book does not pretend it is.

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
- `:site` — a static HTML site styled by the same theme.
- `:epub` — an accessible EPUB3 package over the same pages.

Both PDF editions are built by default; pass `:editions '[:print]'` (or repeat `--edition` on the command line) to select a subset. See [the commands chapter](#commands) and [the editions chapter](#editions).

## Fonts

The default theme uses the PDF base-14 font families, so output is zero-config and always reproducible. Authors who want their own fonts register them through configuration; clj-book bundles no fonts.
