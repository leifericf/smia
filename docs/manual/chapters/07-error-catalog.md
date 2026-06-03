{:id :errors}
# Error catalog

Every failure is a structured `ex-info` carrying `:error/type`, `:error/message`, and `:error/context`. The most useful types, grouped by where they arise:

## Request

- `:clj-book.request/invalid-value` — `:book-root`, `:config-path`, or `:output-root` was given as a non-string. A missing or blank `:book-root` is not an error: it defaults to `.`, the current directory, and a non-existent book then surfaces as `:clj-book.config/missing`.
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

## Markdown front-end

- `:clj-book.md.frontmatter/invalid-front-matter` — the leading EDN front-matter map is unreadable or is not a map.
- `:clj-book.book.load/invalid-front-matter` — front-matter fails the chapter-attribute schema (for example a non-keyword `:id`).
- `:clj-book.book.load/missing-title` — a Markdown chapter has neither a top-level `# Heading` nor a `:title` in front-matter.
- `:clj-book.book.load/markdown-parse-error` — the Markdown chapter could not be parsed.
- `:clj-book.book.load/missing-include` — a code block's `:include` file was not found.
- `:clj-book.md.compile/unsupported-node` — a Markdown construct (such as raw HTML) has no mapping; use a `{=hiccup}` or `{=fo}` escape.
- `:clj-book.md.compile/unknown-directive` — a `:::` directive other than `admonition`.
- `:clj-book.md.compile/invalid-admonition` / `:clj-book.md.compile/invalid-directive-attrs` — a directive's attributes are malformed.
- `:clj-book.md.compile/invalid-fence-info` / `:clj-book.md.compile/invalid-raw-escape` — a code-fence info string or raw-escape payload is not readable EDN.
- `:clj-book.md.compile/unknown-footnote` — a footnote reference has no definition.

## Code validation

- `:clj-book.eval/validation-failed` — one or more `{:test true}` blocks failed; the context lists each failure.
- `:clj-book.eval/unsupported-language` — a marked block names a language with no registered evaluator.
- `:clj-book.eval/missing-language` — a `{:test true}` block has no `:lang`.
- `:clj-book.eval/evaluator-unavailable` — an evaluator's optional dependency is not on the classpath.

## Rendering

- `:clj-book.fo.render/fo-error` — FOP reported an error in the FO (often from invalid raw `:fo/*`).
- `:clj-book.fo.render/render-failed` — the FO could not be transformed to PDF.

:::admonition {:kind :tip}
Run [validate](#commands) first: it surfaces the config, vocabulary, and cross-reference errors above without spending time on rendering.
:::
