# Clojure for Authors

:::overview {:title "What this appendix covers"}
- The five literals and five forms that cover chapter authoring
- Building elements from data with `for` and `into`
- Reading files, naming values, and the chapter contract
:::

Markdown carries a whole book; most authors never need more, and nothing in the main chapters depends on this appendix. The code track is for the readers who want more: authors reaching for control and customization beyond what the directives expose, and publishers producing books at scale, where manuscripts are assembled by an existing automated pipeline rather than typed by hand.

A `.clj` chapter is a program, but the part of Clojure it needs is small. This appendix teaches exactly that part: enough to read [the code-authoring appendix](#code-authoring) line by line and to write chapters like it, assuming no Clojure or functional-programming background. Every example below is checked by the build itself when code validation is on (see [the Markdown chapter](#markdown)), so what this page claims is what the language does.

## Literals: writing values down

Five kinds of value appear in chapter code. Four of them are the data shapes from [the authoring chapter](#authoring); numbers are the fifth.

{:cols [4 7 9]}

| Value | Written as | Used for |
|---|---|---|
| string | `"two thirds"` | prose, captions, file paths |
| number | `42`, `2/3` | counts, spans, weights |
| keyword | `:caption` | tag and attribute names |
| vector | `["a" "b" "c"]` | elements, and plain lists of things |
| map | `{:id :intro}` | attribute sets, and keyed data |

There are no declarations to write. A vector is a value like any other, so a whole chapter element is a value, and a file of them is a manuscript.

## Calling a function

Everything else is function calls, and they all look the same: an opening parenthesis, the function's name, the arguments, a closing parenthesis.

```clojure {:test true :level :assert}
(= 3 (count ["a" "b" "c"]))
```

That is `count` applied to a vector, and `=` comparing the result with 3. Where other languages write `count(items)`, Clojure writes `(count items)`; the name moves inside the parentheses, and there are no commas. Nesting reads inside out: `(sort (map name tags))` first applies `name` to each tag, then sorts the result.

The handful of functions worth knowing on day one:

{:cols [2 5]}

| Function | What it does |
|---|---|
| `str` | joins its arguments into one string: `(str "page " 7)` is `"page 7"` |
| `count` | how many items a collection holds |
| `sort` | the same items, ordered |
| `name` | the text of a keyword: `(name :figure)` is `"figure"` |
| `slurp` | the contents of a file, as a string |
| `partition-all` | slices a sequence into chunks: rows of five cells, say |

## Naming values with def

`def` gives a value a name so later expressions can use it:

```clojure {:test true :level :assert}
(def trims [:a4 :letter :digest])
(= 3 (count trims))
```

A chapter file typically builds its pieces with a few `def`s and assembles them in the final expression. For a name needed only inside one expression, `let` does the same locally: `(let [n (count trims)] …)` makes `n` available within the parentheses and nowhere else.

## Building elements from data

The working pattern of a generated chapter is: take a collection, turn each item into an element, pour the elements into a parent. Two forms do this.

`for` walks a collection and yields one value per item:

```clojure {:test true :level :assert}
(= [[:td "a"] [:td "b"]]
   (vec (for [cell ["a" "b"]]
          [:td cell])))
```

Read it as: for each `cell` in the vector, build `[:td cell]`. The body is ordinary Hiccup with the loop's name spliced in where the content varies.

`into` pours a sequence of children into a parent element:

```clojure {:test true :level :assert}
(= [:tr [:td "a"] [:td "b"]]
   (into [:tr] (for [cell ["a" "b"]]
                 [:td cell])))
```

Those two lines are the whole trick. A table is `into` a `[:table {…}]` of rows; each row is `into` a `[:tr]` of cells; the cells come from your data. Nested `for`s handle two dimensions:

```clojure {:test true :level :assert}
(= [:table {:cols :auto}
    [:tr [:td "1"] [:td "2"]]
    [:tr [:td "3"] [:td "4"]]]
   (into [:table {:cols :auto}]
         (for [row [[1 2] [3 4]]]
           (into [:tr] (for [n row]
                         [:td (str n)])))))
```

Note the `(str n)`: element content must be text, so numbers pass through `str` on the way in.

## Reading data from files

`slurp` returns a file's text, resolved against the directory the build runs in; chapter code usually pairs it with the book's own files. For delimited data there is rarely a reason to parse by hand, because the `[:table {:data "…"}]` form from [the Markdown chapter](#markdown) reads CSV, TSV, and EDN directly, and it works in `.clj` chapters too. Reach for `slurp` when the file is not tabular: a quotation, a fragment of generated text, a list of names one per line. `(clojure.string/split-lines (slurp "data/names.txt"))` is that last one as a vector of strings.

## Using the build's own namespaces

A chapter runs inside the build, so the functions Smia itself is made of are available. `require` brings a namespace in and gives it a short alias:

```clojure
(require '[smia.theme.compile :as theme])

(theme/canon-margins "140mm" 2/3)
```

The code-authoring appendix uses exactly this to print margin values computed by the implementation. The same mechanism reaches any library on the build classpath. The quote mark before the vector is required syntax in a plain `require` call; copy it as written.

## The chapter contract

A chapter file may contain any number of expressions; the build keeps the value of the last one. That value must be a chapter element:

```clojure
[:chapter {:id :my-appendix :title "My Appendix"}
 [:p "Body content, in the author vocabulary."]]
```

The `:id` is a keyword and the cross-reference target; the `:title` is a string. Everything inside is the vocabulary of [the authoring chapter](#authoring), so a `.clj` chapter can carry anything a Markdown chapter can, plus whatever it computes.

:::admonition {:kind :warning}
A `.clj` chapter runs with the full authority of the build process. This is the point: it can read files and call libraries. It is also the trust model: only build manuscripts you trust, the same rule as validated code blocks.
:::

That is the entire toolkit: five literals, function calls, `def` and `let`, `for` and `into`, `slurp`, and `require`. [The code-authoring appendix](#code-authoring) puts all of it to work in one real chapter, with its source shown beside its output.
