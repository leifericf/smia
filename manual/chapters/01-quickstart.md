# Quickstart

Smia builds a manuscript into screen and print PDFs, an EPUB, and a static
website, all on the JVM. Apache FOP renders the PDFs as a library call, and
the build never leaves the process.

## Prerequisites

A JDK and the Clojure CLI. Everything else is an ordinary Maven dependency.

## A minimal book

Scaffold one:

```
clojure -M:run init my-book
```

This writes a minimal, buildable manuscript into a new `my-book/` directory and refuses a directory that already has anything in it. A manuscript is three kinds of file, and the scaffold contains exactly those: one `book.edn`, one or more chapter source files, and a `theme.edn`. The smallest useful `book.edn` is what `init` writes:

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

With no `--edition` given, Smia writes the screen and print PDFs under `build/my-book/pdf/`, alongside an `artifacts.edn` manifest. `--edition` selects the website, the EPUB, and the press-ready PDF/X; [the editions chapter](#editions) describes them all. Run the command from inside the book directory and you can drop the path: `clojure -M:run build`.

While writing, `clojure -M:run preview my-book` rebuilds the book on every save. Previewing the site edition also serves it locally. See [the commands chapter](#commands).

:::admonition {:kind :note}
Read [writing in Markdown](#markdown) next for the prose-first surface, then [the authoring chapter](#authoring) for the Hiccup vocabulary it compiles to.
:::
