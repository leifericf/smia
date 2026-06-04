# Commands

Smia has two front-ends over one engine. The **command-line interface**, `clojure -M:run`, is the one to reach for day to day: it takes plain arguments, has `--help`, and reports errors as readable one-liners. The **programmatic API**, `clojure -X smia.api/…`, takes an EDN request map and is meant for scripts and other tools.

## validate

Check that the manuscript is well-formed without rendering anything — config, tokens, chapter vocabulary, and cross-references:

```
clojure -M:run validate manual
```

The `book-root` argument is optional and defaults to `.`, so from inside a book directory `clojure -M:run validate` is enough.

## build

Build the requested editions. With no `--edition`, both PDF editions build:

```
clojure -M:run build manual --edition screen --edition print
```

PDF outputs land under `build/<slug>/pdf/` with deterministic names like `<slug>-screen.pdf`; `--edition site` writes a static site under `build/<slug>/site/`. Every build adds an `artifacts.edn` manifest listing the editions, paths, and build metadata. The editions themselves are described in [the editions chapter](#editions); see `clojure -M:run build --help` for the full option list.

Output is incremental: a rebuild overwrites each edition in place, and the site edition sweeps stale pages so a removed chapter leaves no orphan page (files you add to the site directory yourself, such as a `CNAME`, are kept). Pass `--clean` to remove the whole `build/<slug>/` directory before building — a guaranteed-fresh slate that also discards output from editions you no longer build. `--clean` does nothing under `--dry-run`.

`--licensee TEXT` stamps a discreet "Licensed to TEXT" line in the footer of every PDF page — a per-recipient watermark for distributing a personalized copy. It applies to the PDF editions only (the HTML and EPUB editions are unaffected) and is not part of the manuscript, so the same book renders a different copy per licensee:

```
clojure -M:run build manual --edition print \
  --licensee "Ada Lovelace <ada@example.com>"
```

Because the text varies per copy, a licensee-stamped build is intentionally not byte-identical across recipients; an unstamped build stays reproducible as before.

## preview

Rebuild the book on every save while you write. Preview builds once, then watches the book directory and rebuilds in the same warm JVM whenever a source file changes — around 150 ms a save, where each cold `build` pays a few seconds of JVM start-up first:

```
clojure -M:run preview manual
```

The whole book tree is watched — chapters, `book.edn`, `theme.edn`, references, included code files, and images — while editor temp files and the build output are ignored. Changes are detected by polling modification times every 250 ms, which is simpler than the JVM's file-watching service and, on some platforms, faster too.

Preview renders only the **screen** edition by default: rendering dominates the cost of a save, and a tight loop wants one edition. Pass `--edition` to choose others, and `--validate-code` to evaluate `{:test true}` blocks on every rebuild. There is no incremental rendering — page layout is global (page numbers, the table of contents, keeps), so each save re-renders the edition in full. A `.clj` chapter runs on every rebuild, the same trust boundary as `build`.

A save that fails — a typo in front-matter, an unresolved cross-reference — prints the same structured error as `build`, and the session keeps watching; the next save tries again. Stop with Ctrl-C. A PDF viewer that reloads a changed file completes the loop: keep the PDF open beside the editor and it refreshes after each save.

At the REPL the same engine is `smia.build.preview/preview!`, which returns a handle whose `:stop!` ends the session:

```clojure
(def h (preview! {:book-root "manual"}))
;; … write, save, watch it rebuild …
((:stop! h))
```

## Dry run

Add `--dry-run` to a build to print the inspectable build plan without rendering or writing anything.

:::admonition {:kind :warning}
A `.clj` chapter is a program, so building one runs the author's code — build only manuscripts you trust. A `.md` chapter is read as data and runs nothing, unless you opt into code validation with `--validate-code`, which then evaluates blocks marked `{:test true}`.
:::

## Exit codes

The CLI returns a meaningful exit code, so it composes in scripts and CI:

| Code | Meaning |
|---|---|
| `0` | success, or `--help` |
| `1` | the build or validation failed |
| `2` | the command was used incorrectly (bad option, unknown command) |

## The programmatic API

Tools that assemble the request themselves call `smia.api/build` and `smia.api/validate` with `-X`, passing an EDN map. Because `-X` reads its arguments as EDN, string values carry both shell and EDN quotes:

```
clojure -X smia.api/build :book-root '"manual"' :editions '[:screen :print]'
```

The keys mirror the CLI options: `:book-root` (default `.`), `:editions`, `:config-path`, `:output-root`, `:dry-run`, `:validate-code`, `:clean`, and `:licensee`. The `:build` and `:validate` aliases carry the function, so `clojure -X:build` works as well.
