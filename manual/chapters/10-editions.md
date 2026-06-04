# Editions

A build produces **editions** — deliverable forms of the same manuscript. One numbered manuscript feeds them all: numbering, cross-reference resolution, index collection, and figure/table/listing numbering happen once, before any format-specific rendering, so every edition agrees on what "Figure 3" is.

- `:screen` — a PDF with symmetric margins for on-screen reading.
- `:print` — a PDF with mirrored recto/verso margins and a binding gutter.
- `:print-x` — the print PDF hardened to PDF/X-4 for press submission.
- `:site` — a static HTML site.
- `:epub` — an EPUB3 package for e-readers.

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

When `book.edn` carries a `:book/downloads` map, the site gains one more page — `downloads.html` — listing the other editions as download links, with the asset flagged `:default` rendered as a prominent primary link. The page is site-only: the PDF and EPUB editions never emit it. It is the bridge from an online manual to its downloadable PDFs and EPUB, and [the distribution chapter](#distribution) walks through setting it up end to end.

Content portability follows the escape-hatch matrix in [the theming chapter](#theming): the shared sugar renders in every edition, `[:html/* …]` is reachable only in HTML editions, and `[:fo/* …]` only in PDF editions — using one in the other is a structured error at build time, naming the offending tag.

## The EPUB edition

`--edition epub` writes an EPUB3 package at `build/<slug>/epub/<slug>.epub` — the same pages as the site edition, serialized as XHTML and wrapped in the OCF container: the package document (metadata, manifest, reading spine), an EPUB navigation document generated from the book structure, the theme stylesheet, and the referenced images. The package validates clean under epubcheck, and the build's test suite enforces that.

The edition is accessible by construction. Schema.org accessibility metadata is emitted with computed defaults — `textual` always, `visual` exactly when the book carries images, structural navigation and a table of contents declared as features, no hazards — and every image must carry `:alt` text (an empty `:alt ""` marks a decorative image). Footnotes, noterefs, and the endnotes block carry their digital-publishing ARIA roles. A book can override any metadata slot in `book.edn`:

```edn
:book/accessibility {:summary "Short prose description of the book's accessibility."
                     :features ["structuralNavigation" "tableOfContents" "index"]}
```

Two optional `book.edn` keys feed the package metadata: `:book/identifier` (default `urn:clj-book:<slug>`) and `:book/language` (default `"en"`).

The package is byte-reproducible: the modification stamp and every archive entry's timestamp are pinned, so the same manuscript and theme always produce an identical `.epub`. Send the file to any modern reader or store pipeline — EPUB3 is the accepted submission format everywhere that matters.

## The print-x edition

`--edition print-x` produces the print layout as a PDF/X-4 file — the conformance level print-on-demand services and presses ask for. PDF/X is the print edition plus hard guarantees: every font embedded (the base-14 substitutes are not allowed), an ICC output intent describing the target color space, pinned identification metadata, and no interactive features. Cross-references, citations, and table-of-contents entries render as plain text with their page citations — PDF/X forbids link annotations, and on paper the page number is the link.

The edition is gated on a `:book/print-x` map in `book.edn` naming the fonts to embed and the output intent:

```edn
:book/print-x
{:output-intent {:icc "assets/icc/sRGB-v2.icc" :profile-name "sRGB"}
 :fonts [{:family      "Crimson Text"
          :normal      "assets/fonts/crimson-text/CrimsonText-Regular.ttf"
          :bold        "assets/fonts/crimson-text/CrimsonText-Bold.ttf"
          :italic      "assets/fonts/crimson-text/CrimsonText-Italic.ttf"
          :bold-italic "assets/fonts/crimson-text/CrimsonText-BoldItalic.ttf"}]}
```

Requesting `:print-x` without the map fails fast with `:clj-book.build.request/print-x-requires-config`. When the map is present, the fonts are embedded in *every* PDF edition — the book's typography should not change with the conformance mode — while the PDF/X mode and output intent apply to `:print-x` alone.

Two things must line up with the config:

- The theme's `:type` families must lead with registered family names (the manual's `theme.edn` uses `"Crimson Text, serif"`), and any furniture faces the tokens do not reach are pointed at registered families through the theme's `:fo` override group. PDF/X requires every glyph to come from an embedded font; an unregistered family falls back to a base-14 font and fails the build with a clear error.
- A press usually mandates its own CMYK output intent. The manual ships a public-domain RGB profile suitable for digital print-on-demand; for offset work, drop the press's profile into the book and point `:output-intent` at it. Press CMYK characterizations are generally not freely redistributable, so clj-book cannot bundle one.

The manual's shipped assets stay under their own licenses, beside the files: the fonts are SIL Open Font License (each family directory carries `OFL.txt` — the OFL requires the license to travel with the fonts, and they are never covered by clj-book's EPL), and the ICC profile is CC0 with its provenance in `NOTICE.txt`.
