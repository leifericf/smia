# Smia

Smia builds technical books. From one Markdown manuscript it produces screen and
print PDFs, a press-ready PDF/X, an EPUB, and a static website. Manuscripts can
also be written in Hiccup, the Clojure data that Markdown compiles to. The name
is Norwegian for a smithy.

Smia is written in Clojure and renders PDFs with
[Apache FOP](https://xmlgraphics.apache.org/fop/). The whole build runs inside
one JVM process; a Java runtime is the only prerequisite, and the package
managers below install one alongside Smia.

> **Status:** Smia is young and in active development. The configuration,
> theme tokens, authoring vocabulary, and commands can change between releases
> without a deprecation cycle. Releases are dated snapshots; pin the one you
> build against, and expect to adjust your book when you move to a newer one.

**[Read the manual online](https://smia.leifericf.com)**. Smia builds and
publishes its own manual. It walks from the quickstart to a finished, published
book, covers every feature on the way, and its design chapter explains how Smia
works inside. Download any edition from the site's Downloads page or from the
repository's [Releases](https://github.com/leifericf/smia/releases/latest).

## Install

With Homebrew (macOS or Linux; the tap is added automatically):

```bash
brew install leifericf/smia/smia
```

With Scoop (Windows):

```powershell
scoop bucket add java
scoop bucket add smia https://github.com/leifericf/scoop-smia
scoop install smia
```

Both install a JDK alongside Smia. Alternatively, download `smia.jar` from
[Releases](https://github.com/leifericf/smia/releases/latest), install a JDK
(17 or later, for example [Temurin](https://adoptium.net)), and run
`java -jar smia.jar <command>`. [jbang](https://www.jbang.dev) users can skip
both steps: `jbang app install --name smia <jar URL>` provisions the JDK and
registers the command in one go.

## Quick start

```bash
smia init my-book      # scaffold a minimal, buildable book
smia build my-book     # write the screen and print PDFs
smia preview my-book   # rebuild on every save
```

A book is a `book.edn`, its chapter sources, and a `theme.edn`. Output lands
under `build/<slug>/`, and the repeatable `--edition` flag selects the site,
the EPUB, and the press-ready PDF/X beyond the default PDFs. The installed
command bundles the math and diagram renderers and the site islands'
ClojureScript compiler, so every feature is available without further setup.

See `smia build --help` for the full option list, or
[the commands chapter](https://smia.leifericf.com/manual/part-2/commands/)
for the whole command set.

## For Clojure developers

From a checkout of this repository the CLI is `clojure -M:run <command>`,
and the optional capabilities sit behind deps aliases composed with the
command: `:math` and `:diagrams` for the build-time SVG renderers, `:cljs`
for the site's opt-in script islands. The manual that ships in this
repository uses all three:

```bash
clojure -M:run:cljs:math:diagrams build manual --edition site
```

Smia is also a git dependency (`io.github.leifericf/smia` with a `:git/tag`
and `:git/sha`) and a Clojure CLI tool:

```bash
clojure -Ttools install io.github.leifericf/smia '{:git/tag "<tag>" :git/sha "<sha>"}' :as smia
clojure -Tsmia init :target '"my-book"'
```

[The commands chapter](https://smia.leifericf.com/manual/part-2/commands/)
covers the whole Clojure track: the `-X` map API, REPL preview, and library
use.

## License

Eclipse Public License 2.0. See `LICENSE`.
