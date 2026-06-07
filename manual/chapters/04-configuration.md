# Configuring the Book

:::overview {:title "What this chapter covers"}
- The required keys and the open-map shape of `book.edn`
- Optional metadata, document attributes, and edition-specific keys
- Checking a manuscript with `validate` before rendering
:::

One `book.edn` describes the manuscript. It is an **open map**: required keys are enforced, and any extra keys you add are preserved untouched.

## Required keys

| Key | Meaning |
|---|---|
| `:book/slug` | output directory + file-name stem |
| `:book/title` | title, shown on the title page |
| `:book/chapters` | ordered vector of chapter file paths; when chapters are grouped into parts, `:book/parts` replaces this key (see [the book-production chapter](#book-production)) |

## Optional metadata

`:book/author` is written onto the title page and into the PDF metadata. Chapter paths are resolved relative to the book root; each must exist and must be unique. A chapter file ending in `.md` is compiled from Markdown; any other extension is evaluated as Clojure.

Parts, front and back matter, appendices, numbering, and the bibliography are configured through further `:book/*` keys, covered in [the book-production chapter](#book-production).

## Document attributes

`:book/attributes` is a map of keyword to value, a value you reference by name throughout the manuscript instead of repeating:

```clojure
:book/attributes {:version "2026.1"
                  :product "Smia"
                  :year    2026}
```

A chapter then references one by name with the `{=attr}` marker (a code span holding the name, followed by `{=attr}`), or in Hiccup with `[:attr :version]`, and the reference resolves to the value. A value is a string, a number, or author Hiccup. The book's `:book/title` and `:book/author` are available as `title` and `author`, the build's `--licensee` as `licensee`, and `:book/language` as `language`; a chapter's front-matter map can override any attribute for that chapter. References resolve before numbering, so an attribute whose value is a captioned figure is numbered in order. An undeclared name fails the build. This manual declares one such attribute and resolves it here: `tool`{=attr}.

## Edition-specific keys

Some editions read their own `book.edn` keys, all documented in [the editions chapter](#editions):

- `:book/print-x`: the fonts to embed and the ICC output intent for the PDF/X `print-x` edition. Required when you build that edition.
- `:book/identifier`, `:book/language`, `:book/accessibility`: EPUB package metadata, all optional with defaults. `:book/language` does double duty: in every edition it localizes the apparatus Smia generates, and in the PDF editions it additionally selects the hyphenation patterns for justified text. The localized apparatus covers the float and structure labels ("Figure", "Chapter"), the generated section titles ("Bibliography", "List of Figures"), the admonition labels, and the site chrome. English is built in; an unknown language falls back to it. The manuscript's own content is never translated, only the furniture around it. Set the language whenever a book hyphenates; a region form like `"en-US"` or `"de-CH"` works and the region is passed along. A language without bundled patterns renders unhyphenated with a build warning.
- `:book/downloads`: a base URL and a list of downloadable editions, which the `site` edition turns into a Downloads page. The value is a map with a string `:base` and an `:assets` vector of `{:label :file :note? :default?}` maps, with at most one asset marked `:default`. See [the distribution chapter](#distribution).
- `:book/site-url`: the site's public address, an absolute http(s) URL. With it set, the `site` edition writes a `sitemap.xml` over the canonical pages and a `robots.txt` pointing at it; without it, neither file exists, because a site addressed only relatively cannot name itself.
- `:book/redirects`: a map of old URL path to target id, for a published site whose pages have moved: `{"old/markdown/" :markdown}`. The `site` edition writes a stub page at each old path, carrying an instant redirect, a canonical link, and a plain link, so existing bookmarks and search results keep working. A target may be any anchor in the book; an unknown one fails the build.
- `:book/edit-url`: a base URL, joined to each page's source path to add an "Edit this page" link to the site chrome, for example `"https://github.com/you/book/edit/main"`. Generated pages (the bibliography, index) have no source, so they get no link.

## Validation

A missing required key, a missing chapter file, or a duplicated chapter fails with a structured error. See [the error catalog](#errors) for the full list.

:::admonition {:kind :note}
All book metadata lives in `book.edn` and all styling in [theme.edn](#theming). Chapter files carry content only.
:::
