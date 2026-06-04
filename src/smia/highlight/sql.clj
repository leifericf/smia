(ns smia.highlight.sql
  "Pure core: an SQL tokenizer for syntax highlighting. Keywords match
   case-insensitively; a string doubles its quote to escape it; a
   double-quoted token is an identifier, not a string."
  (:require
   [smia.highlight.lexer :as lexer]
   [clojure.string :as str]))

(def ^:private keywords
  #{"SELECT" "FROM" "WHERE" "INSERT" "INTO" "VALUES" "UPDATE" "DELETE"
    "SET" "CREATE" "TABLE" "DROP" "ALTER" "ADD" "PRIMARY" "KEY"
    "FOREIGN" "REFERENCES" "NOT" "NULL" "DEFAULT" "UNIQUE" "INDEX"
    "JOIN" "LEFT" "RIGHT" "INNER" "OUTER" "FULL" "CROSS" "ON" "AS"
    "AND" "OR" "IN" "IS" "BETWEEN" "LIKE" "EXISTS" "UNION" "ALL"
    "DISTINCT" "ORDER" "BY" "GROUP" "HAVING" "LIMIT" "OFFSET" "CASE"
    "WHEN" "THEN" "ELSE" "END" "BEGIN" "COMMIT" "ROLLBACK"
    "TRANSACTION" "VIEW" "GRANT" "REVOKE" "WITH" "RETURNING" "TRUE"
    "FALSE"})

(def ^:private rules
  [[#"--[^\n]*"             :comment]
   [#"/\*[\s\S]*?\*/"       :comment]
   [#"'[^']*(?:''[^']*)*'"       :string]
   [#"\"[^\"]*(?:\"\"[^\"]*)*\"" :text]
   [#"\d+(?:\.\d+)?"        :number]
   [#"[A-Za-z_][A-Za-z0-9_]*"
    (fn [s] (if (contains? keywords (str/upper-case s)) :keyword :text))]])

(defn tokenize [code] (lexer/scan code rules))
