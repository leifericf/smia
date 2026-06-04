# Preface

This manual is a Smia manuscript: Smia built every page you are reading from
the Markdown sources under `manual/`.

*Smia* is the Norwegian word for a smithy. The tool is written in Clojure, runs
on the JVM, and builds one manuscript into several editions: screen and print
PDFs, a press-ready PDF/X, an EPUB, and a website.

The manual is organized into parts. The first covers authoring, the second
covers configuration, theming, and the build commands, and the third covers the
structure of a long-form book. An appendix catalogs the error types. Smia
generates the back matter from the sources: the lists of figures, tables, and
listings, the bibliography, and the index. Front matter like this preface is
numbered in roman numerals; the body switches to arabic at the first chapter.

Because the manual exercises the platform that builds it, it doubles as a
regression test: if a feature breaks, this book stops building.
