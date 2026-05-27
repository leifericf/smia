# Traceability Matrix

Maps user-facing requirements and Gherkin scenarios to the test
namespace and test name that exercises them.

## Feature: Explicit target builds

| Scenario                              | Requirement                         | Test                                                       |
|---------------------------------------|-------------------------------------|------------------------------------------------------------|
| Build fails when targets are missing  | `:targets` is required              | `clj-book.request-test/missing-targets-is-hard-error`      |
| Build fails when target is unknown    | unsupported targets rejected        | `clj-book.request-test/unknown-target-is-hard-error`       |
| Build succeeds for one valid target   | single-target build emits artifacts | `clj-book.build.execute-test/single-target-site-build`     |
| Build succeeds for multiple targets   | multi-target run emits all artifacts | `clj-book.build.execute-test/multi-target-build-shares-prereqs` |

## Feature: Open-map manuscript configuration

| Scenario                                    | Requirement                | Test                                                  |
|---------------------------------------------|----------------------------|-------------------------------------------------------|
| Validation fails when required key missing  | required-key enforcement   | `clj-book.config-test/missing-required-key-fails`     |
| Validation accepts additional keys          | open-map preservation      | `clj-book.config-test/additional-keys-pass`           |

## Feature: Single cross-target theme file

| Scenario                                            | Requirement                  | Test                                                              |
|-----------------------------------------------------|------------------------------|-------------------------------------------------------------------|
| Tokens compile to site and PDF artifacts            | one canonical theme source   | `clj-book.theme.css-test/tokens-compile-to-css`                   |
|                                                     |                              | `clj-book.theme.pdf-test/tokens-compile-to-yaml`                  |
| Build fails when tokens file is missing             | hard error on missing tokens | `clj-book.theme.load-test/missing-tokens-file-fails`              |
| Tier-3 site escape hatch is applied after CSS       | append semantics             | `clj-book.theme.css-test/site-clj-extras-appended`                |
| Tier-3 PDF extras merged into compiled PDF theme    | deep-merge semantics         | `clj-book.theme.pdf-test/pdf-extras-deep-merged`                  |

## Feature: Artifact manifest and deterministic outputs

| Scenario                                  | Requirement                | Test                                                  |
|-------------------------------------------|----------------------------|-------------------------------------------------------|
| Manifest includes requested targets/paths | manifest shape contract    | `clj-book.artifacts-test/manifest-shape`              |
| Same input produces same output layout    | deterministic build        | `clj-book.compose-test/master-adoc-is-deterministic`  |

## Feature: Repository and content boundaries

| Scenario                                            | Requirement                       | Test                                                            |
|-----------------------------------------------------|-----------------------------------|-----------------------------------------------------------------|
| Platform tests use synthetic manuscripts            | no third-party text in repo       | `clj-book.boundaries-test/uses-synthetic-fixtures-only`         |
| User manual built by clj-book as dogfood manuscript | dogfood at docs/manual/           | covered by CI; `clj-book.dogfood-test/manual-fixture-resolves`  |
| Manuscript repo consumes clj-book as dependency     | public API entrypoints only       | `clj-book.api-test/public-api-surface`                          |

## Non-goal enforcement

| Non-goal                                             | Test                                                          |
|------------------------------------------------------|---------------------------------------------------------------|
| No Datomic dependency                                | `clj-book.non-goals-test/no-datomic-dependency`               |
| No ClojureScript or JS bundler                       | `clj-book.non-goals-test/no-clojurescript-or-bundler`         |
| No implicit/default target behavior                  | `clj-book.non-goals-test/no-default-target-introduced`        |
| Site output contains no client-side JavaScript       | `clj-book.targets.site-test/emitted-html-has-no-script-tags`  |
| EPL 2.0 license present and referenced               | `clj-book.non-goals-test/license-is-epl-2-0` + `/readme-references-license` |
