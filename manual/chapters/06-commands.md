# Commands

:::overview {:title "What this chapter covers"}
- The everyday commands: `init`, `validate`, `build`, and `preview`
- Dry runs, exit codes, and how failures are reported
- The Clojure track: source checkout, git dependency, tool, and the programmatic API
:::

Smia has two front-ends over one engine. The **command-line interface**, the installed `smia` command, is the one for day-to-day work: it takes plain arguments, has `--help`, and reports errors as readable one-liners. The **programmatic API**, `smia.api`, takes an EDN request map and is meant for scripts and other tools. Clojure developers can reach both without installing anything; the last section of this chapter covers that track, where `clojure -M:run` stands in for `smia`.

## init

Scaffold a new book:

```
smia init my-book
```

The target directory is created when missing, and the slug and title are derived from its name: `my-book` becomes the slug `my-book` and the title "My Book". The scaffold is a complete, buildable manuscript — a `book.edn`, a `theme.edn`, and one Markdown chapter — so the next command can be `build`. A target directory that already has entries is refused with `:smia.book.scaffold/target-not-empty`; `init` never overwrites anything.

## validate

Check that the manuscript is well-formed without rendering anything. Validation covers the config, the theme tokens, the chapter vocabulary, and cross-references:

```
smia validate manual
```

The `book-root` argument is optional and defaults to `.`, so from inside a book directory `smia validate` is enough.

A broken cross-reference is a hard error. A hand-written anchor link (`[:a {:href "#id"}]`, say from a `{=hiccup}` escape) that targets no known id is reported as a warning instead: the page still builds, but the dead link is named so you can fix it.

## build

Build the requested editions. With no `--edition`, both PDF editions build:

```
smia build my-book --edition screen --edition print
```

The installed command bundles the math and diagram renderers and the site islands' compiler, so a book that uses them needs nothing extra. From a source checkout those capabilities sit behind optional aliases composed with the command; a build that needs a dependency it cannot load fails with a structured error naming the alias. See the Clojure track below.

PDF output is written under `build/<slug>/pdf/` with deterministic names like `<slug>-screen.pdf`, and `--edition site` writes a static site under `build/<slug>/site/`. Every build adds an `artifacts.edn` manifest listing the editions, paths, and build metadata. The editions themselves are described in [the editions chapter](#editions); see `smia build --help` for the full option list.

A rebuild overwrites each edition in place, and the site edition removes stale pages, so a deleted chapter leaves no orphan page. Files you add to the site directory yourself, such as a `CNAME`, are kept. Pass `--clean` to remove the whole `build/<slug>/` directory before building; that also discards output from editions you no longer build. `--clean` does nothing under `--dry-run`.

`--licensee TEXT` stamps a "Licensed to TEXT" line in the footer of every PDF page, for distributing a personalized copy per recipient. It applies to the PDF editions only and is not part of the manuscript:

```
smia build manual --edition print \
  --licensee "Ada Lovelace <ada@example.com>"
```

Because the text varies per copy, a stamped build differs between recipients. An unstamped build is unaffected.

## preview

Rebuild the book on every save while you write. Preview builds once, then watches the book directory and rebuilds in the same warm JVM whenever a source file changes. A save takes around 150 ms, while each cold `build` pays a few seconds of JVM start-up first:

```
smia preview my-book
```

The whole book tree is watched: chapters, `book.edn`, `theme.edn`, references, included code files, and images. Editor temp files and the build output are ignored. Changes are detected by polling modification times every 250 ms, which is simpler than the JVM's file-watching service and on some platforms faster.

Preview renders only the **screen** edition by default. Rendering dominates the cost of a save, and a tight loop wants one edition. Pass `--edition` to choose others, and `--validate-code` to evaluate `{:test true}` blocks on every rebuild. Rendering is all-or-nothing, because page layout is global: page numbers, the table of contents, and keeps all span the document. Each save therefore re-renders the edition in full. A `.clj` chapter runs on every rebuild, the same trust boundary as `build`.

Previewing the **site** edition also starts a small static file server, because the site's directory URLs (see [the editions chapter](#editions)) resolve through a web server, not from the file system:

```
smia preview manual --edition site
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

## version

Print the installed version:

```
smia version
```

`--version` does the same. A jar carries the release tag it was built from; running from source prints a dev marker.

## For Clojure developers

Everything above also runs without installing anything, through the Clojure CLI. Four routes share the one engine.

**From a source checkout**, the CLI is the `:run` alias: `clojure -M:run <command>` wherever this chapter says `smia <command>`. The optional capabilities the jar bundles sit behind deps aliases composed with the command: `:math` and `:diagrams` for the SVG renderers, `:cljs` for the site islands' compiler. This manual uses all three, so a checkout builds it with:

```
clojure -M:run:cljs:math:diagrams build manual --edition site
```

**As a git dependency**, Smia is `io.github.leifericf/smia` pinned to a release tag. A consumer project adds the dependency and its own `:run` alias:

```edn
{:deps {io.github.leifericf/smia {:git/tag "<tag>" :git/sha "<sha>"}}
 :aliases {:run {:main-opts ["-m" "smia.cli"]}}}
```

The optional aliases compose the same way; the consumer declares the extra dependency (say, the math renderer) in its own deps.

**As a Clojure CLI tool**, installed once and available in any directory:

```
clojure -Ttools install io.github.leifericf/smia '{:git/tag "<tag>" :git/sha "<sha>"}' :as smia
clojure -Tsmia init :target '"my-book"'
clojure -Tsmia build :book-root '"my-book"'
```

`-T` calls the `smia.api` functions directly with EDN key-value arguments, so this route speaks the programmatic API below.

**The programmatic API**: tools that assemble the request themselves call `smia.api/build` and `smia.api/validate` with `-X`, passing an EDN map. `-X` reads its arguments as EDN, so string values carry both shell and EDN quotes:

```
clojure -X smia.api/build :book-root '"manual"' :editions '[:screen :print]'
```

The keys mirror the CLI options: `:book-root` (default `.`), `:editions`, `:config-path`, `:output-root`, `:dry-run`, `:validate-code`, `:clean`, and `:licensee`. In Smia's own checkout the `:init`, `:build`, and `:validate` aliases carry the function, so `clojure -X:build` works as well. `smia.api/init` is the scaffold: `clojure -X:init :target '"my-book"'`. At the REPL the same functions are ordinary Clojure, and `smia.build.preview/preview!` gives the watch loop shown under preview.

One capability lives only on this track: [code validation](#markdown) for Groovy and Kotlin. The packaged jar does not bundle those evaluators; compose the `:eval-groovy` or `:eval-kotlin` alias with a checkout or git-dependency command, for example `clojure -M:run:eval-groovy validate manual --validate-code`.
