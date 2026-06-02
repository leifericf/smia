# Commands

clj-book is invoked through the standard Clojure CLI with `clojure -X`.

## validate

Check that the manuscript is well-formed without rendering anything — config, tokens, chapter vocabulary, and cross-references:

```
clojure -X clj-book.api/validate :book-root '"docs/manual"'
```

## build

Render the requested profiles to PDF:

```
clojure -X clj-book.api/build \
  :book-root '"docs/manual"' \
  :profiles  '[:screen :print]'
```

Outputs land under `build/<slug>/pdf/` with deterministic names like `<slug>-screen.pdf`, plus an `artifacts.edn` manifest listing the profiles, paths, and build metadata.

## Dry run

Add `:dry-run true` to a build to print and return the inspectable build plan without rendering or writing anything.

:::admonition {:kind :warning}
A `.clj` chapter is a program, so building one runs the author's code — build only manuscripts you trust. A `.md` chapter is read as data and runs nothing, unless you opt into code validation with `:validate-code true`, which then evaluates blocks marked `{:test true}`.
:::
