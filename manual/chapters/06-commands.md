# Commands

clj-book has two front-ends over one engine. The **command-line interface**, `clojure -M:run`, is the one to reach for day to day: it takes plain arguments, has `--help`, and reports errors as readable one-liners. The **programmatic API**, `clojure -X clj-book.api/…`, takes an EDN request map and is meant for scripts and other tools.

## validate

Check that the manuscript is well-formed without rendering anything — config, tokens, chapter vocabulary, and cross-references:

```
clojure -M:run validate manual
```

The `book-root` argument is optional and defaults to `.`, so from inside a book directory `clojure -M:run validate` is enough.

## build

Render the requested profiles to PDF. With no `--profile`, both editions build:

```
clojure -M:run build manual --profile screen --profile print
```

Outputs land under `build/<slug>/pdf/` with deterministic names like `<slug>-screen.pdf`, plus an `artifacts.edn` manifest listing the profiles, paths, and build metadata. See `clojure -M:run build --help` for the full option list.

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

Tools that assemble the request themselves call `clj-book.api/build` and `clj-book.api/validate` with `-X`, passing an EDN map. Because `-X` reads its arguments as EDN, string values carry both shell and EDN quotes:

```
clojure -X clj-book.api/build :book-root '"manual"' :profiles '[:screen :print]'
```

The keys mirror the CLI options: `:book-root` (default `.`), `:profiles`, `:config-path`, `:output-root`, `:dry-run`, and `:validate-code`. The `:build` and `:validate` aliases carry the function, so `clojure -X:build` works as well.
