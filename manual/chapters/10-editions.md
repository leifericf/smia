# Editions

A build produces **editions** — deliverable forms of the same manuscript. One numbered manuscript feeds them all: numbering, cross-reference resolution, index collection, and figure/table/listing numbering happen once, before any format-specific rendering, so every edition agrees on what "Figure 3" is.

- `:screen` — a PDF with symmetric margins for on-screen reading.
- `:print` — a PDF with mirrored recto/verso margins and a binding gutter.
- `:site` — a static HTML site.

With no `--edition`, the two PDF editions build. Select any subset by repeating the flag:

```bash
clojure -M:run build my-book --edition site
clojure -M:run build my-book --edition screen --edition site
```

The `artifacts.edn` manifest lists what was built under `:build/editions`, with one artifact entry per edition.

## The site edition

`--edition site` writes a browsable site under `build/<slug>/site/`: an `index.html` carrying the title and table of contents, one page per chapter (and per named or generated matter section), a `styles.css` generated from `theme.edn`, and copies of every image the chapters reference. Open `index.html` straight from disk — there is no server, no JavaScript, and no build-tool runtime in the output.

Paged furniture is rendered for a medium with no pages:

- Cross-references and citations become real links (`chapter-02.html#sec-b`) instead of page-number citations.
- Footnotes have no page foot to sit on, so each chapter's notes collect at the chapter's end, linked both ways between the marker and the note.
- The bibliography, the index, and the lists of figures/tables/listings emit links where the PDF prints page numbers.
- Code listings keep their file bars, syntax highlighting, captions, and annotations.

The same `theme.edn` drives the stylesheet: `:color`, `:type`, `:code`, and `:spacing` map onto generated CSS rules, while `:layout` (page geometry) applies to the PDF editions only — the site supplies its own reading-column layout. The generated stylesheet is deterministic: the same theme always produces byte-identical CSS.

Content portability follows the escape-hatch matrix in [the theming chapter](#theming): the shared sugar renders in every edition, `[:html/* …]` is reachable only in HTML editions, and `[:fo/* …]` only in PDF editions — using one in the other is a structured error at build time, naming the offending tag.
