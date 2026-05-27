# clj-book User-Facing Functionality and Affordances (v1)

## Target User

`clj-book` is built for **semi-technical authors with programming experience** — typically developers writing technical books, technical writers comfortable in a terminal, or adjacent technical roles. The user is expected to:

- Read and follow technical documentation.
- Use `git` and a command-line build tool.
- Edit text files in their editor of choice.
- Diagnose issues from descriptive error messages.
- Read EDN, AsciiDoc, and minimal Clojure (Garden) as configuration/customization formats.
- Not necessarily be a Clojure expert, but willing to author a few lines of EDN and (if reaching for tier-3) Garden.

`clj-book` is **explicitly not** built for:

- Non-technical authors expecting WYSIWYG, browser-based authoring, or a zero-CLI workflow.
- Teams requiring a fully managed publishing platform with hosting, collaboration UI, or editorial workflows.
- Users who need to avoid the command line entirely.

**Future direction (out of scope for v1)**: less-technical authoring tools may be layered *on top of* `clj-book` as separate projects. The platform aims to be the engine, not the editor.

## Core User Experience

- Users run one explicit command to build one or more requested outputs.
- Users control behavior through one config map in `book.edn`.
- Users define one cross-target theme in `styles/tokens.edn` as EDN-native design tokens (conceptually modeled on W3C Design Tokens).
- Users get deterministic output paths and a machine-readable artifact manifest.
- Users get hard, descriptive errors for missing required inputs.

## User Commands

- `validate`: Validate manuscript config and required files.
- `build`: Build explicit targets requested by the user.
- `serve`: Preview the online book locally.

Canonical invocation style is standard JVM Clojure commands (`clojure -X ...`).

## Build Target Selection

- No default targets are assumed.
- User must explicitly provide `:targets`.
- Supported v1 targets:
  - `:site` (Stasis + Hiccup static site, HTML + CSS only)
  - `:pdf` (Asciidoctor PDF)
- Multi-target builds are supported in one run, e.g. `[:site :pdf]`.

## Configuration Affordances

Manuscript metadata and build configuration live in two complementary places:

- **AsciiDoc document header** (`book.adoc`): authorial metadata such as title, author, revision number, and custom document attributes. This is the primary source for anything that affects the rendered manuscript content.
- **`book.edn`**: minimal build configuration that is orthogonal to manuscript content (chapter ordering, output paths, build profiles). Required keys must be present and valid; additional keys are allowed (open map, Clojure-friendly).

## Customization Affordances

User control over presentation is organized into three tiers. Higher tiers stay cross-target by design; the escape hatches at the lowest tier are per-target by definition.

### Tier 1 — Tokens (style)

- `styles/tokens.edn`: EDN-native design tokens (conceptually modeled on W3C Design Tokens) covering colors, typography, spacing, borders, radii.
- One canonical source per manuscript, compiled to:
  - CSS custom properties for site output (via Garden)
  - Asciidoctor PDF theme YAML for PDF output
- Where exact parity is impossible, consistent fallbacks are applied.

### Tier 2 — Layout configuration (symmetric)

A small, deliberate set of layout keys in `book.edn`, translated to both targets:

- `:page-size` (PDF page size: `:a4` / `:letter` / `:digest`)
- `:page-margins` (PDF margins; site derives content max-width from typography tokens)
- `:chapter-opener` (`:page-break` / `:inline`)
- `:toc-depth` (integer)
- `:code-line-numbers` (boolean)
- `:admonition-style` (`:icon` / `:label`)

Tier-2 keys are part of `book.edn`'s open map; unknown keys are preserved but ignored.

### Tier 3 — Per-target escape hatches (asymmetric)

Optional files used only when tiers 1+2 don't cover an author's need. Anything in tier 3 is per-target by definition; using it forfeits automatic cross-target parity for the affected area.

- `styles/site.clj` (Garden): authored as a Clojure file evaluating to a Garden data structure; compiled to CSS and appended after the token-derived stylesheet.
- `styles/pdf-theme.edn`: EDN extras merged into the token-derived Asciidoctor PDF theme YAML before invocation.

The platform validates the *existence* of these files when referenced but does not police semantic correctness of their contents.

## Output Affordances

- Deterministic build output structure under configured output root.
- Artifacts for each requested target are emitted in predictable locations.
- Build emits an artifact manifest containing:
  - requested targets
  - produced artifact paths
  - timestamps and build metadata

## Error and Validation Affordances

- Missing required `:targets` is a hard error.
- Unknown targets are hard errors.
- Missing required config keys or required files are hard errors.
- Error messages are descriptive and include actionable context (key/path/target).

## Content and Repository Affordances

- Third-party book text lives in separate manuscript repositories.
- Manuscript repos consume `clj-book` via normal Clojure dependency usage.
- The one exception is the `clj-book` user manual itself, which is authored as a dogfood manuscript inside the `clj-book` repository under `docs/manual/` and is built by `clj-book`.

## Scope of v1

- Included: `:site` and `:pdf` targets.
- Site output is HTML + CSS only with zero client-side JavaScript runtime.
- Excluded for now: default target behavior, client-side runtime, Datomic dependency, ebook formats.
- Ebook formats may be added later as additional explicit targets.

## BDD Scenarios (Gherkin)

```gherkin
Feature: Explicit target builds
  As a manuscript maintainer
  I want to explicitly select output targets
  So that builds are intentional and predictable

  Scenario: Build fails when targets are missing
    Given a manuscript repo with a valid "book.edn"
    When I run the build command without ":targets"
    Then the command fails
    And the error message says that ":targets" is required
    And the error includes actionable context

  Scenario: Build fails when target is unknown
    Given a manuscript repo with a valid "book.edn"
    When I run the build command with ":targets [:unknown]"
    Then the command fails
    And the error message identifies ":unknown" as unsupported

  Scenario: Build succeeds for one valid target
    Given a manuscript repo with a valid "book.edn"
    And a valid "styles/tokens.edn"
    When I run the build command with ":targets [:site]"
    Then the command succeeds
    And site artifacts are written to deterministic output paths
    And an artifact manifest is emitted

  Scenario: Build succeeds for multiple valid targets
    Given a manuscript repo with a valid "book.edn"
    And a valid "styles/tokens.edn"
    When I run the build command with ":targets [:site :pdf]"
    Then the command succeeds
    And site artifacts are written to deterministic output paths
    And PDF artifacts are written to deterministic output paths
    And one artifact manifest lists both targets

Feature: Open-map manuscript configuration
  As a manuscript maintainer
  I want required keys enforced while still allowing extra keys
  So that configuration is robust and Clojure-friendly

  Scenario: Validation fails when required key is missing
    Given a manuscript repo with "book.edn" missing a required key
    When I run the validate command
    Then the command fails
    And the error identifies the missing key

  Scenario: Validation accepts additional non-required keys
    Given a manuscript repo with all required keys in "book.edn"
    And additional custom keys in "book.edn"
    When I run the validate command
    Then the command succeeds

Feature: Single cross-target theme file
  As a manuscript maintainer
  I want one canonical theme definition
  So that visual identity is consistent across outputs

  Scenario: Tokens compile to site and PDF artifacts
    Given a manuscript repo with valid "styles/tokens.edn"
    When I run the build command with ":targets [:site :pdf]"
    Then token data is compiled to CSS custom properties for site output
    And token data is compiled to Asciidoctor PDF YAML for PDF output

  Scenario: Build fails when tokens file is missing
    Given a manuscript repo with valid "book.edn"
    And no "styles/tokens.edn"
    When I run the build command with ":targets [:site]"
    Then the command fails
    And the error identifies the missing tokens file path

  Scenario: Tier-3 site escape hatch is applied after compiled CSS
    Given a manuscript repo with valid "styles/tokens.edn"
    And a "styles/site.clj" file containing a Garden stylesheet
    When I run the build command with ":targets [:site]"
    Then the site CSS contains the token-derived rules
    And the site CSS contains the Garden-derived rules appearing after the token-derived rules

  Scenario: Tier-3 PDF extras are merged into compiled PDF theme
    Given a manuscript repo with valid "styles/tokens.edn"
    And a "styles/pdf-theme.edn" file containing additional theme keys
    When I run the build command with ":targets [:pdf]"
    Then the Asciidoctor PDF theme contains the token-derived keys
    And the Asciidoctor PDF theme contains the user-provided keys merged on top

Feature: Artifact manifest and deterministic outputs
  As a manuscript maintainer
  I want deterministic paths and machine-readable metadata
  So that CI and publishing automation can consume build results

  Scenario: Manifest includes requested targets and paths
    Given a successful build for ":targets [:site :pdf]"
    When I inspect the artifact manifest
    Then it contains both requested targets
    And it contains output paths for each artifact
    And it contains build timestamps and metadata

  Scenario: Same input produces same output layout
    Given the same manuscript inputs and the same build request
    When I run the build twice
    Then artifact directory layout is identical across both runs

Feature: Repository and content boundaries
  As a platform maintainer
  I want clj-book and manuscript content separated
  So that the platform remains reusable and book text remains external

  Scenario: Platform tests use synthetic manuscripts
    Given the clj-book platform repository
    When I run its test suite
    Then unit and integration tests run using synthetic fixture manuscripts
    And no third-party manuscript text is required

  Scenario: User manual is built by clj-book as a dogfood manuscript
    Given the clj-book platform repository
    And a manuscript at "docs/manual/" containing "book.adoc", "book.edn", and "styles/tokens.edn"
    When I run the build command from the platform repo with ":targets [:site :pdf]" against "docs/manual/"
    Then the command succeeds
    And site and PDF artifacts for the user manual are written to deterministic output paths

  Scenario: Manuscript repository consumes clj-book as a dependency
    Given a separate manuscript repository
    When I configure clj-book in deps.edn
    And I run clj-book commands from that repository
    Then builds run against the manuscript repository inputs
```
