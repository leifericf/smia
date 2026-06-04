# Quickstart

Smia builds a manuscript into screen and print PDFs, an EPUB, and a static
website, all on the JVM. PDFs are rendered with Apache FOP as a library call,
and the build never leaves the Java process.

## Prerequisites

A JDK and the Clojure CLI. Everything else is an ordinary Maven dependency.

## A minimal book

A manuscript is three kinds of file: one `book.edn`, one or more chapter source files, and a `theme.edn`. The smallest useful `book.edn` is:

```edn
{:book/slug    "my-book"
 :book/title   "My Book"
 :book/author  "An Author"
 :book/chapters ["chapters/01-intro.md"]}
```

A Markdown chapter is prose with a single top-level heading for its title:

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

Run the build with the Clojure CLI:

```
clojure -M:run build my-book
```

With no `--edition` given, the screen and print PDFs are written under `build/my-book/pdf/`, alongside an `artifacts.edn` manifest. The website, the EPUB, and the press-ready PDF/X are selected with `--edition`; [the editions chapter](#editions) describes them all. Run the command from inside the book directory and the path can be dropped: `clojure -M:run build`.

While writing, `clojure -M:run preview my-book` rebuilds the book on every save. Previewing the site edition also serves it locally. See [the commands chapter](#commands).

:::admonition {:kind :note}
Read [the authoring chapter](#authoring) next to learn both front-ends: the Markdown surface and the Hiccup vocabulary it compiles to.
:::
