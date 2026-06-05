# Editions

A build produces **editions**, deliverable forms of the same manuscript. One numbered manuscript feeds them all: numbering, cross-reference resolution, index collection, and figure, table, and listing numbering happen once, before any format-specific rendering. Every edition therefore agrees on what "Figure 3" is.

Mathematical notation follows the same once-before-everything rule: each formula renders to SVG right after numbering, and the identical image is inlined into every edition — embedded in the PDFs' page flow, an inline `<svg>` in the site's HTML and the EPUB's XHTML. No edition needs JavaScript, a math font, or a network fetch to show it. PlantUML diagrams render the same way. The one exception is a Mermaid diagram, which has no pure-JVM renderer and so is drawn in the browser on the site only; the PDF and EPUB editions show its source instead (see [book production](#book-production)).

- `:screen`: a PDF with symmetric margins for on-screen reading.
- `:print`: a PDF with mirrored recto/verso margins and a binding gutter.
- `:print-x`: the print PDF as PDF/X-4 for press submission.
- `:site`: a static HTML site.
- `:epub`: an EPUB3 package for e-readers.

With no `--edition`, the two PDF editions build. Select any subset by repeating the flag:

```bash
clojure -M:run build my-book --edition site
clojure -M:run build my-book --edition screen --edition site
```

The `artifacts.edn` manifest lists what was built under `:build/editions`, with one artifact entry per edition.

## Per-edition content

A `:::when` block (see [the Markdown chapter](#markdown)) can hold content for some editions and not others: `:::when {:equals [:edition :epub]}` appears only in the EPUB. This is the one case that bends the once-before-everything rule. When a book has no edition-dependent content, it is numbered once and every edition agrees on "Figure 3", as above. When it does, that book is numbered per edition instead: each edition prunes its own branches first, so a figure present only in the EPUB is numbered there and absent elsewhere, and the surrounding numbers stay correct in every edition. Conditions that do not name `:edition` (a draft flag, a licensee) resolve once and keep the single-numbering guarantee.

A cross-reference is resolved per edition too, so an `:xref` to a target that lives inside an edition-only branch must itself sit in a branch present in the same editions; otherwise that edition reports an unresolved cross-reference.

## The site edition

`--edition site` writes a browsable site under `build/<slug>/site/`: a home `index.html`, one page per chapter and per matter section, a `styles.css` generated from `theme.edn`, and copies of every image the chapters reference. The output contains no JavaScript by default and no build-tool runtime. The one exception is opt-in: the search island ([the theming chapter](#theming)) adds a small script as progressive enhancement, and every page keeps working with JavaScript disabled.

Each page is the `index.html` of its own directory, so the served URL is a clean, extensionless path: a body chapter nests under its part (`part-1/quickstart/`), an appendix under its letter (`appendix-a/errors/`), and matter sits at the root (`preface/`). The slug is the section's `:id`. Internal links are all relative, so the site can be hosted at any domain and under any sub-path. The directory URLs resolve through a web server rather than from the file system; to read a build locally, preview the site. `clojure -M:run preview my-book --edition site` serves it at `http://localhost:8000/` and rebuilds on save (see [the commands chapter](#commands)).

Paged furniture is rendered for a medium with no pages:

- Cross-references and citations become real links (`chapter-02.html#sec-b`) instead of page-number citations.
- Footnotes have no page foot to sit on, so each chapter's notes collect at the chapter's end, linked both ways between the marker and the note.
- The bibliography, the index, and the lists of figures, tables, and listings emit links where the PDF prints page numbers.
- Code listings keep their file bars, syntax highlighting, captions, and annotations.
- Each chapter links to the previous and next in document order, both as a labeled row beneath the reading column and as icon-only chevrons pinned to the page margins.

The same `theme.edn` drives the stylesheet: `:color`, `:type`, `:code`, and `:spacing` map onto generated CSS rules, while `:layout`, the page geometry, applies to the PDF editions only; the site supplies its own reading-column layout. The site's page framing is selectable through the `:site {:layout …}` token, either a plain column or a sidebar layout, covered in [the theming chapter](#theming). The generated stylesheet is deterministic: the same theme always produces byte-identical CSS.

When `book.edn` carries a `:book/downloads` map, the site gains one more page, `downloads.html`, listing the other editions as download links; the asset flagged `:default` renders as the primary link. The page is site-only: the PDF and EPUB editions never emit it. [The distribution chapter](#distribution) walks through setting it up.

Two more `book.edn` keys serve a *published* site. `:book/site-url`, the site's public address, adds a deterministic `sitemap.xml` over the canonical pages and a `robots.txt` pointing at it. `:book/redirects` maps old URL paths to target ids and writes a stub page at each old path — an instant redirect with a canonical link — so a chapter that moves leaves no dead bookmarks. Both are described in [the configuration chapter](#configuration).

Content portability follows the escape-hatch matrix in [the theming chapter](#theming): the shared sugar renders in every edition, `[:html/* …]` is reachable only in HTML editions, and `[:fo/* …]` only in PDF editions. Using one in the other is a structured error at build time, naming the offending tag.

## The EPUB edition

`--edition epub` writes an EPUB3 package at `build/<slug>/epub/<slug>.epub`: the same pages as the site edition, serialized as XHTML and wrapped in the OCF container. The package holds the package document with its metadata, manifest, and reading spine, a navigation document generated from the book structure, the theme stylesheet, and the referenced images. It validates clean under epubcheck, and the build's test suite enforces that.

The edition carries accessibility metadata by default. Schema.org metadata is emitted with computed values: `textual` always, `visual` exactly when the book carries images, structural navigation and a table of contents declared as features, and no hazards. Every image must carry `:alt` text; an empty `:alt ""` marks a decorative image. Footnotes, noterefs, and the endnotes block carry their digital-publishing ARIA roles. A book can override any metadata slot in `book.edn`:

```edn
:book/accessibility {:summary "Short prose description of the book's accessibility."
                     :features ["structuralNavigation" "tableOfContents" "index"]}
```

Two optional `book.edn` keys feed the package metadata: `:book/identifier` (default `urn:smia:<slug>`) and `:book/language` (default `"en"`).

The package is byte-reproducible: the modification stamp and every archive entry's timestamp are pinned, so the same manuscript and theme always produce an identical `.epub`.

## The print-x edition

`--edition print-x` produces the print layout as a PDF/X-4 file, the conformance level print services and presses commonly ask for. PDF/X is the print edition plus hard guarantees: every font embedded (base-14 substitutes are not allowed), an ICC output intent describing the target color space, pinned identification metadata, and no interactive features. Cross-references, citations, and table-of-contents entries render as plain text with their page citations, because PDF/X forbids link annotations; on paper the page number is the link.

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

Requesting `:print-x` without the map fails with `:smia.build.request/print-x-requires-config`. When the map is present, the fonts are embedded in *every* PDF edition, so the book's typography does not change with the conformance mode; the PDF/X mode and output intent apply to `:print-x` alone.

Two things must line up with the config:

- The theme's `:type` families must lead with registered family names (the manual's `theme.edn` uses `"Crimson Text, serif"`), and any furniture faces the tokens do not reach are pointed at registered families through the theme's `:fo` override group. PDF/X requires every glyph to come from an embedded font; an unregistered family falls back to a base-14 font and fails the build.
- A press usually mandates its own CMYK output intent. The manual ships a public-domain RGB profile suitable for digital print-on-demand. For offset work, drop the press's profile into the book and point `:output-intent` at it; press CMYK characterizations are generally not freely redistributable, so Smia cannot bundle one.

The manual's shipped assets keep their own licenses, beside the files: the fonts are under the SIL Open Font License, with `OFL.txt` in each family directory, and the ICC profile is CC0 with its provenance in `NOTICE.txt`. The fonts are not covered by Smia's EPL.
