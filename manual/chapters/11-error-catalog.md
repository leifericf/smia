{:id :errors}
# Error Catalog

:::overview {:title "What this chapter covers"}
- How every failure is structured, with a type, message, and context
- The error types grouped by stage, from request to rendering
- What triggers the types you are most likely to hit
:::

Every failure is a structured `ex-info` carrying `:error/type`, `:error/message`, and `:error/context`. The most useful types, grouped by where they arise:

## Request

- `:smia.build.request/invalid-value`: `:book-root`, `:config-path`, or `:output-root` was given as a non-string. A missing or blank `:book-root` is not an error; it defaults to `.`, and a non-existent book then surfaces as `:smia.book.config/missing`.
- `:smia.build.request/unknown-edition`: an edition outside the supported set was requested.
- `:smia.build.request/invalid-editions`: `:editions` was not a vector of keywords.
- `:smia.build.request/print-x-requires-config`: `:print-x` was requested but `book.edn` has no `:book/print-x` map. PDF/X needs embedded fonts and an ICC output intent.
- `:smia.book.scaffold/target-not-empty`: `init` was pointed at a directory that already has entries. The scaffold never overwrites anything; pick a fresh directory.

## Configuration and tokens

- `:smia.book.config/missing`: no `book.edn` at the expected path.
- `:smia.book.config/missing-required-key`: a required `:book/*` key is absent.
- `:smia.book.config/missing-chapter`: a listed chapter file does not exist.
- `:smia.book.config/duplicate-chapter`: a chapter is listed more than once.
- `:smia.book.config/invalid-numbering`: `:book/numbering` uses a key or value outside the policy vocabulary. Keys are `:parts`, `:chapters`, `:appendices`, `:sections`, and `:start-chapters-on`; styles are `:arabic`, `:roman`, `:letter`, or `false`, and `:start-chapters-on` is `:recto` or `:any`.
- `:smia.book.config/invalid-downloads`: `:book/downloads` is malformed. It must be a map with a string `:base` and a non-empty `:assets` vector of `{:label :file :note? :default?}` maps, with at most one asset marked `:default`.
- `:smia.book.config/invalid-redirects`: `:book/redirects` is not a map of old URL path (string) to target id (keyword).
- `:smia.book.config/invalid-site-url`: `:book/site-url` is not an absolute http(s) URL string.
- `:smia.book.config/invalid-edit-url`: `:book/edit-url` is not an absolute http(s) URL string.
- `:smia.book.config/invalid-attributes`: `:book/attributes` is not a map of keyword to (string, number, or author Hiccup).
- `:smia.theme.load/missing`: no `theme.edn`.
- `:smia.theme.load/invalid-tokens`: `theme.edn` fails the token schema — a required group is absent or a token has the wrong shape. The error carries the humanized schema findings.
- `:smia.theme.compile/unknown-page-size`: `:layout {:page-size …}` names a trim outside the known set (`:a4`, `:letter`, `:digest`). Token validation rejects this at load time as `:smia.theme.load/invalid-tokens`; the compile-time error guards programmatic callers.

## Authoring

- `:smia.book.load/chapter-eval-error`: a chapter `.clj` file failed to evaluate.
- `:smia.book.load/duplicate-chapter-id`: two chapters resolve to the same `:id`.
- `:smia.book.load/invalid-chapter`: a loaded chapter is not a `[:chapter {…} …]` form.
- `:smia.book.load/missing-chapter-id` / `:smia.book.load/missing-chapter-title`: a `:chapter` is missing its `:id` or `:title`.
- `:smia.book.attrs/unknown-attribute`: an `[:attr :k]` reference (or `` `k`{=attr} ``) names an attribute that is not declared in `:book/attributes` or front-matter.
- `:smia.book.attrs/invalid-attribute`: an `:attr` reference is not the form `[:attr <keyword>]`.
- `:smia.book.conditional/invalid-condition`: a `:::when` (or `[:when …]`) condition is not one of `{:defined :k}`, `{:equals [:k v]}`, `{:any-of […]}`, `{:all-of […]}`, or `{:not c}`.
- `:smia.book.number/unresolved-xref`: an `:xref` points at an unknown id.
- `:smia.book.number/duplicate-id`: an `:id` is used twice. Every id (heading, chapter, appendix, part, matter, or captioned float) must be unique across the whole book, since each becomes an anchor target.
- `:smia.fo.expand/unknown-tag`: an element tag is neither known sugar nor a `:fo/*` tag.
- `:smia.fo.expand/invalid-annotation`: a listing's `:annotations` reference a line outside the listing, or more than one note lands on the same line.

## Markdown front-end

- `:smia.md.frontmatter/invalid-front-matter`: the leading EDN front-matter map is unreadable or is not a map.
- `:smia.book.load/invalid-front-matter`: front-matter fails the chapter-attribute schema, for example a non-keyword `:id`.
- `:smia.book.load/missing-title`: a Markdown chapter has neither a top-level `# Heading` nor a `:title` in front-matter.
- `:smia.book.load/markdown-parse-error`: the Markdown chapter could not be parsed.
- `:smia.md.parse/unclosed-directive`: a `:::` directive has no closing fence, so it would swallow everything after its opening line. The error names the directive and its line.
- `:smia.book.load/missing-include`: a code block's `:include` file was not found.
- `:smia.book.load/missing-include-tag`: an include's `:tag` opens nowhere in its file; no line contains the `tag::name` marker.
- `:smia.book.load/conflicting-include-keys`: an include carries both `:tag` and `:lines`; the two selectors are exclusive.
- `:smia.md.compile/unsupported-node`: a Markdown construct (such as raw HTML) has no mapping; use a `{=hiccup}` or `{=fo}` escape.
- `:smia.md.compile/unknown-directive`: a `:::` directive name that is not recognized.
- `:smia.md.compile/invalid-admonition` / `:smia.md.compile/invalid-overview` / `:smia.md.compile/invalid-directive-attrs`: a directive's attributes are malformed.
- `:smia.md.compile/invalid-fence-info` / `:smia.md.compile/invalid-raw-escape`: a code-fence info string or raw-escape payload is not readable EDN.
- `:smia.md.compile/unknown-marker`: an inline `{=marker}` escape names a marker that is not registered.
- `:smia.md.compile/unknown-footnote`: a footnote reference has no definition.

## Math and diagrams

- `:smia.math/renderer-unavailable`: the manuscript carries `[:math]` notation but the renderer's optional dependency is not on the classpath. The packaged `smia` command bundles the renderer, so this arises only on the Clojure CLI track; compose the `:math` alias with the command: `clojure -M:run:math build`.
- `:smia.math/render-failed`: a formula's LaTeX could not be rendered (a syntax error in the notation); the error names the offending notation.
- `:smia.diagram/renderer-unavailable`: the manuscript carries a `[:diagram]` but the renderer's optional dependency is not on the classpath. The packaged `smia` command bundles the renderer, so this arises only on the Clojure CLI track; compose the `:diagrams` alias with the command: `clojure -M:run:diagrams build`.
- `:smia.diagram/render-failed`: a diagram's source could not be rendered; the error names the offending source.

## Site islands

- `:smia.site.islands/compiler-unavailable`: the theme enables a site island but the ClojureScript compiler is not on the classpath. The packaged `smia` command bundles the compiler, so this arises only on the Clojure CLI track; compose the `:cljs` alias with the command: `clojure -M:run:cljs build`.
- `:smia.site.islands/missing-source`: an island's ClojureScript source is missing from the classpath. This is a packaging bug in Smia, not an authoring error.

## Code validation

- `:smia.eval/validation-failed`: one or more `{:test true}` blocks failed; the context lists each failure.
- `:smia.eval/unsupported-language`: a marked block names a language with no registered evaluator.
- `:smia.eval/missing-language`: a `{:test true}` block has no `:lang`.
- `:smia.eval/evaluator-unavailable`: an evaluator's optional dependency is not on the classpath. The packaged `smia` command does not bundle the Groovy and Kotlin evaluators; validating those languages runs on the Clojure CLI track with the `:eval-groovy` or `:eval-kotlin` alias composed with the command.

## Editions

- `:smia.html.expand/missing-alt-text`: an image has no `:alt` text. Every image needs it; use `:alt ""` for a decorative one.
- `:smia.html.expand/fo-tag-in-html` / `:smia.fo.expand/html-tag-in-pdf`: a per-format escape hatch was used in the wrong format: a `:fo/*` element in an HTML edition, or a `:html/*` element in a PDF edition.
- `:smia.html.assemble/unsafe-resource-path`: an image `:src` is absolute or contains `..`, so it would escape the output directory. Image paths must stay within the book.
- `:smia.html.assemble/duplicate-page`: two sections assemble to the same output filename.
- `:smia.site.layout/unknown-layout`: the `theme.edn` `:site {:layout …}` token names a site layout outside the known set (`:plain`, `:sidebar`).
- `:smia.site.redirects/unknown-target`: a `:book/redirects` entry points at an id no page declares.
- `:smia.site.redirects/redirect-overwrites-page`: a redirect's old path resolves to a file a real page already occupies.

## Rendering

- `:smia.fo.render/fo-error`: FOP reported an error in the FO, often from invalid raw `:fo/*`.
- `:smia.fo.render/render-failed`: the FO could not be transformed to PDF.

:::admonition {:kind :tip}
Run [validate](#commands) first: it surfaces the config, vocabulary, and cross-reference errors above without spending time on rendering.
:::
