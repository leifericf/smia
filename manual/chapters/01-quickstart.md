# Quickstart

:::overview {:title "What this chapter covers"}
- Installing the `smia` command on macOS, Linux, and Windows
- A minimal `book.edn`, `theme.edn`, and first chapter
- Running a build and finding the editions it writes
:::

Smia builds a manuscript into screen and print PDFs, an EPUB, and a static
website, all on the JVM.

:::admonition {:kind :warning}
Smia is young and in active development. The configuration, theme tokens,
authoring vocabulary, and commands can change between releases without a
deprecation cycle. Releases are dated snapshots; pin the one you build
against, and expect to adjust your book when you move to a newer one.
:::

## Installation

Smia is one command, `smia`, backed by a single jar that bundles everything:
the PDF, EPUB, and site pipelines, the math and diagram renderers, and the
site islands' ClojureScript compiler. It runs on a Java runtime, and the
package managers below install one alongside it.

On macOS or Linux, with Homebrew (the tap is added automatically):

```
brew install leifericf/smia/smia
```

On Windows, with Scoop:

```
scoop bucket add java
scoop bucket add smia https://github.com/leifericf/scoop-smia
scoop install smia
```

Without a package manager, download `smia.jar` from the repository's
Releases page, install a JDK (17 or later, for example Temurin), and run
`java -jar smia.jar` wherever this manual says `smia`. jbang users can have
both provisioned in one step with `jbang app install --name smia <jar URL>`.

Check the installation:

```
smia version
```

Clojure developers have a fourth route: running Smia from a source checkout,
as a git dependency, or as a Clojure CLI tool. [The commands
chapter](#commands) covers that track.

## A minimal book

Scaffold one:

```
smia init my-book
```

This writes a minimal, buildable manuscript into a new `my-book/` directory and refuses a directory that already has anything in it. A manuscript is three kinds of file, and the scaffold contains exactly those: one `book.edn`, one or more chapter source files, and a `theme.edn`. The smallest useful `book.edn` is what `init` writes:

```edn
{:book/slug    "my-book"
 :book/title   "My Book"
 :book/author  "Your Name"
 :book/chapters ["chapters/01-introduction.md"]}
```

The scaffold writes a starter `chapters/01-introduction.md` to fill that list. A Markdown chapter is prose under a single top-level heading for its title. At its simplest:

```markdown
# Introduction

Hello from **Smia**.
```

The equivalent Clojure chapter evaluates to a `[:chapter …]` form:

```clojure
[:chapter {:id :intro :title "Introduction"}
 [:p "Hello from " [:strong "Smia"] "."]]
```

## Build it

```
smia build my-book
```

With no `--edition` given, Smia writes the screen and print PDFs under `build/my-book/pdf/`, alongside an `artifacts.edn` manifest. `--edition` selects the website, the EPUB, and the press-ready PDF/X; [the editions chapter](#editions) describes them all. Run the command from inside the book directory and you can drop the path: `smia build`.

While writing, `smia preview my-book` rebuilds the book on every save. Previewing the site edition also serves it locally. See [the commands chapter](#commands).

:::admonition {:kind :note}
Read [writing in Markdown](#markdown) next for the prose-first surface, then [the authoring chapter](#authoring) for the Hiccup vocabulary it compiles to.
:::
