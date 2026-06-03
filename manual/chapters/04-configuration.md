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

## Validation

A missing required key, a missing chapter file, or a duplicated chapter fails with a descriptive, structured error. See [the error catalog](#errors) for the full list.

:::admonition {:kind :note}
There is no AsciiDoc header anymore: all metadata lives in `book.edn`, and all styling lives in [theme.edn](#theming).
:::
