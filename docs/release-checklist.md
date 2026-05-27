# Release Checklist — v1.0.0-alpha

A reproducible checklist for cutting the `v1.0.0-alpha` tag.

## Pre-flight

- [ ] CI green on `main`/`master` for the candidate SHA.
- [ ] `LICENSE` present and contains the full EPL 2.0 text.
- [ ] `README.md` references the EPL 2.0 license.
- [ ] `CHANGELOG.md` describes everything since the last tag.
- [ ] `docs/traceability-matrix.md` reflects current test names.
- [ ] Dogfood manual under `docs/manual/` builds end-to-end for `[:site :pdf]` in CI.

## Non-goal verification

- [ ] `build` fails without explicit `:targets`.
- [ ] No `<script>` tags appear in any `:site` output (CI assertion in place).
- [ ] No Datomic dependency in `deps.edn`.
- [ ] No third-party manuscript text in the repo outside `docs/manual/`.

## Tagging

```bash
git tag -a v1.0.0-alpha -m "Initial alpha release"
git push origin v1.0.0-alpha
```

## Post-release

- [ ] GitHub Release created from the tag with `CHANGELOG.md` excerpt.
- [ ] CI uploaded dogfood artifacts attached to the release (optional).
- [ ] `MEMORY.md`/internal notes refreshed if any contract changed.
