{:id :distribution}
# Distributing your book

:::overview {:title "What this chapter covers"}
- The publishing split: the site edition for reading, the rest for download
- Declaring downloads with `:book/downloads` and the generated Downloads page
- A date-tagged CI/CD workflow that builds, releases, and deploys
- The default Pages URL, a custom domain, and the one-time repository setup
:::

A built book is a directory of files; getting it in front of readers is a separate
step. This chapter shows one way to do it with nothing but a Git host's built-in
pages and releases, and it uses this manual as the worked example: the copy you are
reading was published exactly this way. Everything here builds on
[the editions chapter](#editions) — distribution is just choosing where each edition
goes.

## The split: read online, download to keep

The five editions fall into two roles. The **site** edition is for reading in a
browser, so it goes to a web host. The **screen**, **print**, **print-x**, and
**epub** editions are files to download and keep, so they attach to a tagged
release. The site carries a Downloads page that links to those release files, which
gives a single chain a reader can follow:

> a link in the README → the manual online (the site edition) → its Downloads page →
> the release assets.

Because the manual's own repository hosts it on GitHub, the rest of this chapter
names GitHub's facilities — Pages for the site, Releases for the files, Actions for
the automation — but the shape is the same on any host with equivalent features.

## Declaring the downloads

The Downloads page is data, not handwritten HTML. Add a `:book/downloads` map to
`book.edn`: a `:base` URL and an `:assets` list, one entry per downloadable edition.
This is the manual's own block:

```clojure {:id :lst-downloads :file "book.edn" :caption "Declaring the downloadable editions"}
:book/downloads
{:base   "https://github.com/leifericf/smia/releases/latest/download"
 :assets [{:label   "Screen PDF"
           :file    "smia-manual-screen.pdf"
           :note    "Symmetric margins, for reading on screen."
           :default true}
          {:label "Print PDF"
           :file  "smia-manual-print.pdf"
           :note  "Mirrored margins and a binding gutter, for printing."}
          {:label "Print-ready (PDF/X)"
           :file  "smia-manual-print-x.pdf"
           :note  "PDF/X-4 with embedded fonts, for a press."}
          {:label "EPUB"
           :file  "smia-manual.epub"
           :note  "Reflowable, for e-readers."}]}
```

Each asset's link is `:base` joined to its `:file`. The asset flagged
`:default true` renders as a prominent primary link at the top of the page — here,
the screen PDF, the right choice for most readers — and the rest follow as a list,
each with its `:note`. At most one asset may be the default. A malformed block fails
the build with `:smia.book.config/invalid-downloads`.

Two details make this robust. The `:base` points at `releases/latest/download`, the
moving target that always resolves to the newest release, so the site never needs
rebuilding when you cut a new one. And the Downloads page is **site-only** by
construction: only the site edition reads `:book/downloads`, so the PDFs and the
EPUB never carry a page of links to themselves. Build the site and you will find a
`downloads.html` beside `index.html`, reachable from the home table of contents.

## Publishing on a date tag

The manual ships a single GitHub Actions workflow, `.github/workflows/release.yml`,
that does the whole job when you push a tag shaped like a calendar date. Smia
uses date tags rather than version numbers; the tag is simply the day you publish.

```yaml {:id :lst-release-trigger :file ".github/workflows/release.yml" :caption "Triggering on a YYYY-MM-DD tag"}
on:
  push:
    tags: ['20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]']   # YYYY-MM-DD (glob, not regex)
```

On such a push the workflow builds all five editions into one output root, then does
three things with them:

- attaches the screen, print, print-x, and epub files to a **GitHub Release** for
  the tag (`softprops/action-gh-release`);
- uploads the site directory as a **Pages artifact** and deploys it
  (`actions/upload-pages-artifact` then `actions/deploy-pages`);
- verifies every promised download exists before publishing, so a Downloads page can
  never link to a missing file.

The build step is the same `build` command you run locally — the workflow has no
private knowledge of the book:

```bash
clojure -M:run build manual \
  --edition screen --edition print --edition print-x \
  --edition epub --edition site \
  --output-root build/release
```

To publish, tag the current date and push it:

```bash
git tag 2026-06-04 && git push origin 2026-06-04
```

:::admonition {:kind :note}
Smia is a build tool: it turns a manuscript into files and stops there. It does
not deploy, and there is no `smia deploy` command. The workflow above is yours to
own and adapt — it lives in your repository, not inside Smia — which is why this
chapter shows it as an example rather than documenting a built-in feature.
:::

## The site URL and a custom domain

This manual serves from a custom domain, `smia.leifericf.com`, and the workflow
writes the `CNAME` for it on every build. The step has a baked-in default, so the
domain works out of the box without any repository configuration:

```yaml {:id :lst-cname :file ".github/workflows/release.yml" :caption "Writing the CNAME with a default domain"}
- name: Set the custom domain
  run: |
    echo "${{ vars.PAGES_CUSTOM_DOMAIN || 'smia.leifericf.com' }}" \
      > build/release/smia-manual/site/CNAME
```

A fork overrides the domain by setting the `PAGES_CUSTOM_DOMAIN` repository variable;
its value wins over the default. Smia's site links are all relative and the
Downloads links are absolute release URLs, so the site also works unchanged at
GitHub's default project URL (`https://leifericf.github.io/smia/`) if you point
`PAGES_CUSTOM_DOMAIN` at an empty value and drop the `CNAME`.

## One-time repository setup

Three things are configured once, in the repository, outside the manuscript:

- In **Settings → Pages**, set the source to **GitHub Actions**, and set the custom
  domain to `smia.leifericf.com` with **Enforce HTTPS** on.
- Point the domain's DNS at GitHub Pages (a `CNAME` record for the subdomain to
  `leifericf.github.io`), or set the **`PAGES_CUSTOM_DOMAIN`** Actions variable to
  your own domain.
- Push the first date tag to trigger the first publish.

From then on, publishing a new edition of the book is one push of a dated tag. The
manuscript itself — chapters, `book.edn`, theme, and the `:book/downloads` block —
carries everything else, the same discipline the rest of [the production
chapter](#book-production) follows.
