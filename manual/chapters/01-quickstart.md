# Quickstart

clj-book turns a Clojure-data manuscript into publication-quality **PDF** — screen and print editions — entirely on the JVM via Apache FOP. There is no Ruby, no asciidoctor, no external binary, and no subprocess.

## Prerequisites

All you need is a JVM and the Clojure CLI. Everything else arrives as a Maven dependency.

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

Hello from **clj-book**.
```

The equivalent Clojure chapter evaluates to a `[:chapter …]` form:

```clojure
[:chapter {:id :intro :title "Introduction"}
 [:p "Hello from " [:strong "clj-book"] "."]]
```

## Build it

Run the build with the Clojure CLI:

```
clojure -M:run build my-book
```

With no `--edition` given, both the screen and print editions are produced under `build/my-book/pdf/`, alongside a machine-readable `artifacts.edn` manifest. Run it from inside the book directory and you can drop the path entirely: `clojure -M:run build`.

While writing, `clojure -M:run preview my-book` rebuilds the screen edition on every save — see [the commands chapter](#commands).

:::admonition {:kind :note}
Read [the authoring chapter](#authoring) next to learn both front-ends: the Markdown surface and the Hiccup vocabulary it compiles to.
:::
