# Commands

:::overview {:title "What this chapter covers"}
- The everyday commands: `init`, `validate`, `build`, and `preview`
- Dry runs, exit codes, and how failures are reported
- The programmatic API for scripts
:::

Smia has two front-ends over one engine. The **command-line interface**, `clojure -M:run`, is the one for day-to-day work: it takes plain arguments, has `--help`, and reports errors as readable one-liners. The **programmatic API**, `clojure -X smia.api/…`, takes an EDN request map and is meant for scripts and other tools.

## init

Scaffold a new book:

```
clojure -M:run init my-book
```

The target directory is created when missing, and the slug and title are derived from its name: `my-book` becomes the slug `my-book` and the title "My Book". The scaffold is a complete, buildable manuscript — a `book.edn`, a `theme.edn`, and one Markdown chapter — so the next command can be `build`. A target directory that already has entries is refused with `:smia.book.scaffold/target-not-empty`; `init` never overwrites anything.

## validate

Check that the manuscript is well-formed without rendering anything. Validation covers the config, the theme tokens, the chapter vocabulary, and cross-references:

```
clojure -M:run validate manual
```

The `book-root` argument is optional and defaults to `.`, so from inside a book directory `clojure -M:run validate` is enough.

A broken cross-reference is a hard error. A hand-written anchor link (`[:a {:href "#id"}]`, say from a `{=hiccup}` escape) that targets no known id is reported as a warning instead: the page still builds, but the dead link is named so you can fix it.

## build

Build the requested editions. With no `--edition`, both PDF editions build:

```
clojure -M:run build my-book --edition screen --edition print
```

A book that uses math, diagrams, or the site's script islands composes the optional aliases with the command — this manual builds with `clojure -M:run:cljs:math:diagrams build manual`. A build that needs a dependency it cannot load fails with a structured error naming the alias.

PDF output is written under `build/<slug>/pdf/` with deterministic names like `<slug>-screen.pdf`, and `--edition site` writes a static site under `build/<slug>/site/`. Every build adds an `artifacts.edn` manifest listing the editions, paths, and build metadata. The editions themselves are described in [the editions chapter](#editions); see `clojure -M:run build --help` for the full option list.

A rebuild overwrites each edition in place, and the site edition removes stale pages, so a deleted chapter leaves no orphan page. Files you add to the site directory yourself, such as a `CNAME`, are kept. Pass `--clean` to remove the whole `build/<slug>/` directory before building; that also discards output from editions you no longer build. `--clean` does nothing under `--dry-run`.

`--licensee TEXT` stamps a "Licensed to TEXT" line in the footer of every PDF page, for distributing a personalized copy per recipient. It applies to the PDF editions only and is not part of the manuscript:

```
clojure -M:run:math:diagrams build manual --edition print \
  --licensee "Ada Lovelace <ada@example.com>"
```

Because the text varies per copy, a stamped build differs between recipients. An unstamped build is unaffected.

## preview

Rebuild the book on every save while you write. Preview builds once, then watches the book directory and rebuilds in the same warm JVM whenever a source file changes. A save takes around 150 ms, while each cold `build` pays a few seconds of JVM start-up first:

```
clojure -M:run preview my-book
```

Preview renders the book, so it composes the same optional aliases as `build`: previewing this manual, which uses math and diagrams, is `clojure -M:run:math:diagrams preview manual`.

The whole book tree is watched: chapters, `book.edn`, `theme.edn`, references, included code files, and images. Editor temp files and the build output are ignored. Changes are detected by polling modification times every 250 ms, which is simpler than the JVM's file-watching service and on some platforms faster.

Preview renders only the **screen** edition by default. Rendering dominates the cost of a save, and a tight loop wants one edition. Pass `--edition` to choose others, and `--validate-code` to evaluate `{:test true}` blocks on every rebuild. Rendering is all-or-nothing, because page layout is global: page numbers, the table of contents, and keeps all span the document. Each save therefore re-renders the edition in full. A `.clj` chapter runs on every rebuild, the same trust boundary as `build`.

Previewing the **site** edition also starts a small static file server, because the site's directory URLs (see [the editions chapter](#editions)) resolve through a web server, not from the file system:

```
clojure -M:run:cljs:math:diagrams preview manual --edition site
```

This rebuilds on every save and serves the site at `http://localhost:8000/`: edit, save, refresh the browser. The server reads from disk, so a rebuild needs no restart. Choose another port with `--port`. The server is part of the JDK, adds no dependency, and runs only for the site edition. Live reload would need JavaScript in the page, so you refresh by hand.

A save that fails, say a typo in front-matter or an unresolved cross-reference, prints the same structured error as `build`, and the session keeps watching; the next save tries again. Stop with Ctrl-C. For a PDF preview, a viewer that reloads a changed file completes the loop: keep the PDF open beside the editor and it refreshes after each save.

At the REPL the same engine is `smia.build.preview/preview!`, which returns a handle whose `:stop!` ends the session:

```clojure
(def h (preview! {:book-root "manual"}))
;; … write, save, watch it rebuild …
((:stop! h))
```

## Dry run

Add `--dry-run` to a build to print the build plan without rendering or writing anything.

:::admonition {:kind :warning}
A `.clj` chapter is a program, so building one runs the author's code; build only manuscripts you trust. A `.md` chapter is read as data and runs nothing, unless you opt into code validation with `--validate-code`, which evaluates blocks marked `{:test true}`.
:::

## Exit codes

The CLI's exit codes are stable, so it composes in scripts and CI:

| Code | Meaning |
|---|---|
| `0` | success, or `--help` |
| `1` | the build or validation failed |
| `2` | the command was used incorrectly (bad option, unknown command) |

## The programmatic API

Tools that assemble the request themselves call `smia.api/build` and `smia.api/validate` with `-X`, passing an EDN map. `-X` reads its arguments as EDN, so string values carry both shell and EDN quotes:

```
clojure -X smia.api/build :book-root '"manual"' :editions '[:screen :print]'
```

The keys mirror the CLI options: `:book-root` (default `.`), `:editions`, `:config-path`, `:output-root`, `:dry-run`, `:validate-code`, `:clean`, and `:licensee`. The `:init`, `:build`, and `:validate` aliases carry the function, so `clojure -X:build` works as well. `smia.api/init` is the scaffold: `clojure -X:init :target '"my-book"'`.
