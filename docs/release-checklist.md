# Release Checklist

A reproducible checklist for cutting a release. Releases are marked with a
date tag (`YYYY-MM-DD`); there is no SemVer scheme and no changelog is kept
between releases.

## Pre-flight

- [ ] CI green on `main` for the candidate SHA.
- [ ] `LICENSE` present and contains the full EPL 2.0 text.
- [ ] `README.md` references the EPL 2.0 license.
- [ ] Dogfood manual under `docs/manual/` builds end-to-end for
      `[:screen :print]` in CI.

## Non-goal verification

- [ ] No external process: no `ProcessBuilder` and no asciidoctor in `src`
      (enforced by `clj-book.non-goals-test`).
- [ ] No HTML/site output or CSS dependencies (`stasis`, `garden`, `hiccup`,
      `ring`) in `deps.edn`.
- [ ] No Datomic dependency in `deps.edn`.
- [ ] No third-party manuscript text in the repo outside `docs/manual/`.

## Tagging

Use the release date as the tag:

```bash
git tag -a "$(date +%F)" -m "Release $(date +%F)"
git push origin "$(date +%F)"
```

## Post-release

- [ ] GitHub Release created from the dated tag.
- [ ] CI uploaded dogfood artifacts attached to the release (optional).
