# Configuring the book

One `book.edn` describes the manuscript. It is an **open map**: required keys are enforced, and any extra keys you add are preserved untouched.

## Required keys

| Key | Meaning |
|---|---|
| `:book/slug` | output directory + file-name stem |
| `:book/title` | title, shown on the title page |
| `:book/chapters` | ordered vector of chapter file paths |

## Optional metadata

`:book/author` is written onto the title page and into the PDF metadata. Chapter paths are resolved relative to the book root; each must exist and must be unique. A chapter file ending in `.md` is compiled from Markdown; any other extension is evaluated as Clojure.

Parts, front and back matter, appendices, numbering, and the bibliography are configured through further `:book/*` keys, covered in [the book-production chapter](#book-production).

## Edition-specific keys

Some editions read their own `book.edn` keys, all documented in [the editions chapter](#editions):

- `:book/print-x`: the fonts to embed and the ICC output intent for the PDF/X `print-x` edition. Required when you build that edition.
- `:book/identifier`, `:book/language`, `:book/accessibility`: EPUB package metadata, all optional with defaults.
- `:book/downloads`: a base URL and a list of downloadable editions, which the `site` edition turns into a Downloads page. The value is a map with a string `:base` and an `:assets` vector of `{:label :file :note? :default?}` maps, with at most one asset marked `:default`. See [the distribution chapter](#distribution).

## Validation

A missing required key, a missing chapter file, or a duplicated chapter fails with a structured error. See [the error catalog](#errors) for the full list.

:::admonition {:kind :note}
All book metadata lives in `book.edn` and all styling in [theme.edn](#theming). Chapter files carry content only.
:::
