{:id :errors}
# Error catalog

Every failure is a structured `ex-info` carrying `:error/type`, `:error/message`, and `:error/context`. The most useful types, grouped by where they arise:

## Request

- `:clj-book.request/missing-book-root` — no `:book-root` was given.
- `:clj-book.request/unknown-profile` — a profile other than `:screen` or `:print` was requested.
- `:clj-book.request/invalid-profiles` — `:profiles` was not a vector of keywords.

## Configuration and tokens

- `:clj-book.config/missing` — no `book.edn` at the expected path.
- `:clj-book.config/missing-required-key` — a required `:book/*` key is absent.
- `:clj-book.config/missing-chapter` — a listed chapter file does not exist.
- `:clj-book.config/duplicate-chapter` — a chapter is listed more than once.
- `:clj-book.theme.load/missing` — no `styles/tokens.edn`.
- `:clj-book.theme.load/missing-group` — a required token group is absent.

## Authoring

- `:clj-book.book.load/chapter-eval-error` — a chapter `.clj` file failed to evaluate.
- `:clj-book.book.load/duplicate-chapter-id` — two chapters resolve to the same `:id`.
- `:clj-book.book.assemble/missing-chapter-id` / `:clj-book.book.assemble/missing-chapter-title` — a `:chapter` is missing its `:id` or `:title`.
- `:clj-book.book.assemble/unresolved-xref` — an `:xref` points at an unknown id.
- `:clj-book.fo.expand/unknown-tag` — an element tag is neither known sugar nor a `:fo/*` tag.

## Rendering

- `:clj-book.fo.render/fo-error` — FOP reported an error in the FO (often from invalid raw `:fo/*`).
- `:clj-book.fo.render/render-failed` — the FO could not be transformed to PDF.

:::admonition {:kind :tip}
Run [validate](#commands) first: it surfaces the config, vocabulary, and cross-reference errors above without spending time on rendering.
:::
