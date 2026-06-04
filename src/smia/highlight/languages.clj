(ns smia.highlight.languages
  "Pure core: the language families expressed as keyword-set data.

   Each entry is a language keyword mapped to its reserved-word set; the
   surrounding family factory (smia.highlight.fragments) supplies the
   comment, string, and number rules. Adding a language in a known family is
   one line of data here — no bespoke scanner. The keyword sets are
   representative rather than exhaustive: enough to color the structure of a
   listing. `tokenizers` is the merged `{lang -> tokenize}` map the registry
   pulls in. No IO."
  (:require
   [smia.highlight.fragments :as f]))

;; --- C-family ----------------------------------------------------------------

(def ^:private c-family-keywords
  {:c          #{"auto" "break" "case" "char" "const" "continue" "default" "do"
                 "double" "else" "enum" "extern" "float" "for" "goto" "if" "inline"
                 "int" "long" "register" "restrict" "return" "short" "signed"
                 "sizeof" "static" "struct" "switch" "typedef" "union" "unsigned"
                 "void" "volatile" "while" "_Bool" "NULL"}
   :cpp        #{"alignas" "alignof" "auto" "bool" "break" "case" "catch" "char"
                 "class" "const" "constexpr" "continue" "decltype" "default"
                 "delete" "do" "double" "else" "enum" "explicit" "export" "extern"
                 "false" "float" "for" "friend" "goto" "if" "inline" "int" "long"
                 "mutable" "namespace" "new" "noexcept" "nullptr" "operator"
                 "private" "protected" "public" "return" "short" "signed" "sizeof"
                 "static" "struct" "switch" "template" "this" "throw" "true" "try"
                 "typedef" "typename" "union" "unsigned" "using" "virtual" "void"
                 "volatile" "while"}
   :csharp     #{"abstract" "as" "base" "bool" "break" "byte" "case" "catch"
                 "char" "checked" "class" "const" "continue" "decimal" "default"
                 "delegate" "do" "double" "else" "enum" "event" "explicit" "extern"
                 "false" "finally" "fixed" "float" "for" "foreach" "goto" "if"
                 "implicit" "in" "int" "interface" "internal" "is" "lock" "long"
                 "namespace" "new" "null" "object" "operator" "out" "override"
                 "params" "private" "protected" "public" "readonly" "ref" "return"
                 "sbyte" "sealed" "short" "sizeof" "static" "string" "struct"
                 "switch" "this" "throw" "true" "try" "typeof" "uint" "ulong"
                 "unchecked" "unsafe" "ushort" "using" "var" "virtual" "void"
                 "volatile" "while" "async" "await" "yield" "record"}
   :go         #{"break" "case" "chan" "const" "continue" "default" "defer" "else"
                 "fallthrough" "for" "func" "go" "goto" "if" "import" "interface"
                 "map" "package" "range" "return" "select" "struct" "switch" "type"
                 "var" "nil" "true" "false" "iota" "string" "int" "bool" "byte"
                 "rune" "error"}
   :rust       #{"as" "async" "await" "break" "const" "continue" "crate" "dyn"
                 "else" "enum" "extern" "false" "fn" "for" "if" "impl" "in" "let"
                 "loop" "match" "mod" "move" "mut" "pub" "ref" "return" "self"
                 "Self" "static" "struct" "super" "trait" "true" "type" "unsafe"
                 "use" "where" "while" "Some" "None" "Ok" "Err" "Box" "Vec"}
   :scala      #{"abstract" "case" "catch" "class" "def" "do" "else" "extends"
                 "false" "final" "finally" "for" "forSome" "if" "implicit" "import"
                 "lazy" "match" "new" "null" "object" "override" "package" "private"
                 "protected" "return" "sealed" "super" "this" "throw" "trait" "try"
                 "true" "type" "val" "var" "while" "with" "yield" "given" "using"}
   :swift      #{"associatedtype" "class" "deinit" "enum" "extension" "fileprivate"
                 "func" "import" "init" "inout" "internal" "let" "open" "operator"
                 "private" "protocol" "public" "static" "struct" "subscript"
                 "typealias" "var" "break" "case" "continue" "default" "defer" "do"
                 "else" "fallthrough" "for" "guard" "if" "in" "repeat" "return"
                 "switch" "where" "while" "as" "catch" "false" "is" "nil" "rethrows"
                 "self" "super" "throw" "throws" "true" "try" "some" "any"}
   :dart       #{"abstract" "as" "assert" "async" "await" "break" "case" "catch"
                 "class" "const" "continue" "covariant" "default" "deferred" "do"
                 "dynamic" "else" "enum" "export" "extends" "extension" "external"
                 "factory" "false" "final" "finally" "for" "Function" "get" "hide"
                 "if" "implements" "import" "in" "is" "late" "library" "mixin" "new"
                 "null" "on" "operator" "part" "required" "rethrow" "return" "set"
                 "show" "static" "super" "switch" "sync" "this" "throw" "true" "try"
                 "typedef" "var" "void" "while" "with" "yield"}
   :objc       #{"auto" "break" "case" "char" "const" "continue" "default" "do"
                 "double" "else" "enum" "extern" "float" "for" "goto" "if" "inline"
                 "int" "long" "register" "return" "short" "signed" "sizeof" "static"
                 "struct" "switch" "typedef" "union" "unsigned" "void" "volatile"
                 "while" "id" "self" "super" "nil" "YES" "NO" "BOOL" "instancetype"}
   :php        #{"abstract" "and" "array" "as" "break" "callable" "case" "catch"
                 "class" "clone" "const" "continue" "declare" "default" "do" "echo"
                 "else" "elseif" "empty" "enddeclare" "endfor" "endforeach" "endif"
                 "endswitch" "endwhile" "enum" "extends" "final" "finally" "fn"
                 "for" "foreach" "function" "global" "goto" "if" "implements"
                 "include" "instanceof" "insteadof" "interface" "isset" "list"
                 "match" "namespace" "new" "or" "print" "private" "protected"
                 "public" "readonly" "require" "return" "static" "switch" "throw"
                 "trait" "try" "unset" "use" "var" "while" "xor" "yield" "true"
                 "false" "null"}
   :solidity   #{"address" "bool" "break" "bytes" "calldata" "constant" "constructor"
                 "continue" "contract" "do" "else" "emit" "enum" "event" "external"
                 "false" "for" "function" "if" "import" "indexed" "interface"
                 "internal" "is" "library" "mapping" "memory" "modifier" "new"
                 "payable" "pragma" "private" "public" "pure" "require" "return"
                 "returns" "revert" "storage" "string" "struct" "true" "uint"
                 "using" "view" "while"}
   :zig        #{"align" "and" "asm" "async" "await" "break" "catch" "comptime"
                 "const" "continue" "defer" "else" "enum" "errdefer" "error"
                 "export" "extern" "fn" "for" "if" "inline" "or" "orelse" "pub"
                 "return" "struct" "switch" "test" "true" "false" "try" "union"
                 "unreachable" "usingnamespace" "var" "while" "null" "undefined"}
   :d          #{"abstract" "alias" "align" "asm" "assert" "auto" "bool" "break"
                 "byte" "case" "cast" "catch" "char" "class" "const" "continue"
                 "default" "delegate" "do" "double" "else" "enum" "export" "extern"
                 "false" "final" "finally" "float" "for" "foreach" "function" "goto"
                 "if" "immutable" "import" "in" "int" "interface" "is" "long"
                 "mixin" "module" "new" "null" "override" "package" "private"
                 "protected" "public" "pure" "return" "scope" "short" "static"
                 "struct" "super" "switch" "template" "this" "throw" "true" "try"
                 "typeof" "union" "unittest" "version" "void" "while" "with"}
   :protobuf   #{"syntax" "package" "import" "option" "message" "enum" "service"
                 "rpc" "returns" "repeated" "optional" "required" "reserved" "oneof"
                 "map" "extend" "extensions" "to" "true" "false" "double" "float"
                 "int32" "int64" "uint32" "uint64" "sint32" "sint64" "fixed32"
                 "fixed64" "bool" "string" "bytes"}})

(def ^:private c-template-keywords
  {:typescript #{"abstract" "any" "as" "asserts" "async" "await" "boolean" "break"
                 "case" "catch" "class" "const" "continue" "debugger" "declare"
                 "default" "delete" "do" "else" "enum" "export" "extends" "false"
                 "finally" "for" "from" "function" "get" "if" "implements" "import"
                 "in" "infer" "instanceof" "interface" "is" "keyof" "let" "namespace"
                 "never" "new" "null" "number" "object" "of" "private" "protected"
                 "public" "readonly" "return" "set" "static" "string" "super"
                 "switch" "this" "throw" "true" "try" "type" "typeof" "undefined"
                 "unique" "unknown" "var" "void" "while" "yield"}})

;; --- hash-comment scripting --------------------------------------------------

(def ^:private hash-keywords
  {:ruby       #{"BEGIN" "END" "alias" "and" "begin" "break" "case" "class" "def"
                 "defined?" "do" "else" "elsif" "end" "ensure" "false" "for" "if"
                 "in" "module" "next" "nil" "not" "or" "redo" "rescue" "retry"
                 "return" "self" "super" "then" "true" "undef" "unless" "until"
                 "when" "while" "yield" "attr_accessor" "attr_reader" "require"
                 "include" "extend" "puts" "lambda" "proc"}
   :r          #{"if" "else" "repeat" "while" "function" "for" "in" "next" "break"
                 "TRUE" "FALSE" "NULL" "Inf" "NaN" "NA" "library" "require"
                 "return" "invisible" "switch"}
   :perl       #{"if" "elsif" "else" "unless" "while" "until" "for" "foreach" "do"
                 "my" "our" "local" "sub" "return" "use" "no" "package" "require"
                 "last" "next" "redo" "and" "or" "not" "eq" "ne" "lt" "gt" "le"
                 "ge" "cmp" "print" "printf" "say" "die" "warn" "defined" "undef"
                 "ref" "bless" "wantarray"}
   :julia      #{"abstract" "baremodule" "begin" "break" "catch" "const" "continue"
                 "do" "else" "elseif" "end" "export" "false" "finally" "for"
                 "function" "global" "if" "import" "in" "let" "local" "macro"
                 "module" "mutable" "primitive" "quote" "return" "struct" "true"
                 "try" "type" "using" "where" "while" "nothing" "missing"}
   :elixir     #{"def" "defp" "defmodule" "defmacro" "defstruct" "defprotocol"
                 "defimpl" "do" "end" "fn" "if" "else" "unless" "case" "cond"
                 "when" "and" "or" "not" "in" "for" "with" "try" "catch" "rescue"
                 "after" "raise" "throw" "import" "alias" "require" "use" "true"
                 "false" "nil" "receive" "send" "spawn"}
   :coffeescript #{"and" "or" "is" "isnt" "not" "yes" "no" "on" "off" "true"
                   "false" "null" "undefined" "if" "else" "unless" "then" "switch"
                   "when" "while" "until" "loop" "for" "in" "of" "by" "do" "break"
                   "continue" "return" "throw" "try" "catch" "finally" "class"
                   "extends" "super" "new" "delete" "typeof" "instanceof" "this"}
   :powershell #{"begin" "break" "catch" "class" "continue" "data" "define" "do"
                 "dynamicparam" "else" "elseif" "end" "enum" "exit" "filter"
                 "finally" "for" "foreach" "from" "function" "if" "in" "param"
                 "process" "return" "switch" "throw" "trap" "try" "until" "using"
                 "while" "true" "false"}
   :toml       #{"true" "false"}
   :yaml       #{"true" "false" "null" "yes" "no" "on" "off"}
   :dockerfile #{"FROM" "RUN" "CMD" "LABEL" "MAINTAINER" "EXPOSE" "ENV" "ADD"
                 "COPY" "ENTRYPOINT" "VOLUME" "USER" "WORKDIR" "ARG" "ONBUILD"
                 "STOPSIGNAL" "HEALTHCHECK" "SHELL" "AS"}
   :makefile   #{"ifeq" "ifneq" "ifdef" "ifndef" "else" "endif" "define" "endef"
                 "include" "override" "export" "unexport" "vpath"}})

;; --- lisp family -------------------------------------------------------------

(def ^:private lisp-keywords
  {:scheme     #{"define" "lambda" "let" "let*" "letrec" "if" "cond" "case" "and"
                 "or" "not" "begin" "do" "when" "unless" "set!" "quote" "quasiquote"
                 "unquote" "else" "define-syntax" "syntax-rules" "call/cc" "values"
                 "car" "cdr" "cons" "list" "map" "for-each" "apply" "display"}
   :racket     #{"define" "lambda" "let" "let*" "letrec" "if" "cond" "case" "and"
                 "or" "not" "begin" "when" "unless" "set!" "quote" "require"
                 "provide" "struct" "module" "for" "for/list" "match" "else"
                 "define-syntax" "syntax-rules" "car" "cdr" "cons" "list" "map"}
   :commonlisp #{"defun" "defvar" "defparameter" "defmacro" "defclass" "defmethod"
                 "defgeneric" "defstruct" "lambda" "let" "let*" "flet" "labels"
                 "if" "cond" "case" "when" "unless" "and" "or" "not" "progn" "loop"
                 "do" "dolist" "dotimes" "setf" "setq" "quote" "function" "return"
                 "block" "nil" "t" "car" "cdr" "cons" "list" "mapcar" "format"}
   :fennel     #{"fn" "lambda" "local" "let" "var" "set" "global" "if" "when"
                 "unless" "do" "each" "for" "while" "match" "and" "or" "not"
                 "require" "import-macros" "tset" "values" "nil" "true" "false"}
   :edn        #{"true" "false" "nil"}})

;; --- ML family ---------------------------------------------------------------

(def ^:private ml-keywords
  {:ocaml      #{"and" "as" "assert" "begin" "class" "do" "done" "downto" "else"
                 "end" "exception" "external" "false" "for" "fun" "function"
                 "functor" "if" "in" "include" "inherit" "let" "match" "method"
                 "module" "mutable" "new" "object" "of" "open" "or" "private" "rec"
                 "sig" "struct" "then" "to" "true" "try" "type" "val" "virtual"
                 "when" "while" "with"}
   :fsharp     #{"abstract" "and" "as" "assert" "base" "begin" "class" "default"
                 "delegate" "do" "done" "downcast" "downto" "elif" "else" "end"
                 "exception" "extern" "false" "finally" "for" "fun" "function"
                 "global" "if" "in" "inherit" "inline" "interface" "internal" "lazy"
                 "let" "match" "member" "module" "mutable" "namespace" "new" "null"
                 "of" "open" "or" "override" "private" "public" "rec" "return" "sig"
                 "static" "struct" "then" "to" "true" "try" "type" "upcast" "use"
                 "val" "void" "when" "while" "with" "yield"}
   :sml        #{"abstype" "and" "andalso" "as" "case" "datatype" "do" "else" "end"
                 "exception" "fn" "fun" "functor" "handle" "if" "in" "include"
                 "infix" "infixr" "let" "local" "nonfix" "of" "op" "open" "orelse"
                 "raise" "rec" "sig" "signature" "struct" "structure" "then" "type"
                 "val" "where" "while" "with" "withtype" "true" "false"}})

;; --- percent-comment ---------------------------------------------------------

(def ^:private percent-keywords
  {:latex      #{"documentclass" "usepackage" "begin" "end" "section" "subsection"
                 "subsubsection" "chapter" "paragraph" "textbf" "textit" "emph"
                 "item" "label" "ref" "cite" "newcommand" "renewcommand" "include"
                 "input" "frac" "sum" "int" "left" "right"}
   :erlang     #{"after" "and" "andalso" "band" "begin" "bnot" "bor" "bsl" "bsr"
                 "bxor" "case" "catch" "cond" "div" "end" "fun" "if" "let" "not"
                 "of" "or" "orelse" "receive" "rem" "try" "when" "xor" "true"
                 "false" "module" "export" "import" "spawn"}
   :matlab     #{"break" "case" "catch" "classdef" "continue" "else" "elseif" "end"
                 "for" "function" "global" "if" "otherwise" "parfor" "persistent"
                 "return" "spmd" "switch" "try" "while" "true" "false"}
   :prolog     #{"is" "mod" "rem" "div" "true" "false" "fail" "not" "assert"
                 "asserta" "assertz" "retract" "findall" "bagof" "setof" "forall"
                 "member" "append" "length" "between" "nth0" "nth1"}})

;; --- dash-comment ------------------------------------------------------------

(def ^:private dash-keywords
  {:haskell    #{"as" "case" "class" "data" "default" "deriving" "do" "else"
                 "foreign" "hiding" "if" "import" "in" "infix" "infixl" "infixr"
                 "instance" "let" "module" "newtype" "of" "qualified" "then" "type"
                 "where" "forall" "True" "False" "Nothing" "Just" "Left" "Right"
                 "IO" "Maybe" "Either"}
   :elm        #{"as" "case" "else" "exposing" "if" "import" "in" "let" "module"
                 "of" "port" "then" "type" "where" "True" "False" "Nothing" "Just"
                 "Ok" "Err" "Cmd" "Sub" "Html" "Maybe" "Result"}
   :lua        #{"and" "break" "do" "else" "elseif" "end" "false" "for" "function"
                 "goto" "if" "in" "local" "nil" "not" "or" "repeat" "return" "then"
                 "true" "until" "while" "self"}
   :ada        #{"abort" "abstract" "accept" "access" "aliased" "all" "and" "array"
                 "at" "begin" "body" "case" "constant" "declare" "delay" "delta"
                 "digits" "do" "else" "elsif" "end" "entry" "exception" "exit"
                 "for" "function" "generic" "goto" "if" "in" "is" "limited" "loop"
                 "mod" "new" "not" "null" "of" "or" "others" "out" "package"
                 "pragma" "private" "procedure" "protected" "raise" "range" "record"
                 "rem" "renames" "return" "reverse" "select" "separate" "subtype"
                 "task" "terminate" "then" "type" "until" "use" "when" "while"
                 "with" "xor" "true" "false"}
   :purescript #{"ado" "as" "case" "class" "data" "derive" "do" "else" "false"
                 "foreign" "hiding" "if" "import" "in" "infix" "infixl" "infixr"
                 "instance" "let" "module" "newtype" "of" "then" "true" "type"
                 "where" "Nothing" "Just" "Left" "Right" "Maybe" "Either" "Effect"}})

;; --- the merged registry -----------------------------------------------------

(def ^:private families
  "Family factory paired with its `{lang -> keyword-set}` data."
  [[f/c-family          c-family-keywords]
   [f/c-family+template c-template-keywords]
   [f/hash-script       hash-keywords]
   [f/lisp-family       lisp-keywords]
   [f/ml-family         ml-keywords]
   [f/percent-comment   percent-keywords]
   [f/dash-comment      dash-keywords]])

(def tokenizers
  "`{language-keyword -> (fn [code] -> tokens)}` for every family language."
  (into {}
        (for [[factory langs] families
              [lang keywords]  langs]
          [lang (factory keywords)])))
