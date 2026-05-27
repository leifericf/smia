(ns clj-book.proc
  "Shell kernel for running external CLI tools as subprocesses. This is
   the only place in the codebase that constructs a ProcessBuilder, so
   the command-execution surface is centralized in one auditable spot.

   Functions return data; callers decide how to turn a non-zero exit
   into a structured `clj-book.error`.")

(defn run!
  "Run an external command given as a vector of program-and-arguments.
   Merges stderr into stdout and blocks until the process exits. Returns
   `{:exit <int> :out <string>}`. Reads the output stream to completion
   before waiting, so the child never blocks on a full pipe."
  [args]
  (let [pb  (doto (ProcessBuilder. ^java.util.List args)
              (.redirectErrorStream true))
        p   (.start pb)
        out (slurp (.getInputStream p))
        _   (.waitFor p)]
    {:exit (.exitValue p) :out out}))

(defn cli-available?
  "True when `<cmd> --version` launches and exits zero. Any failure to
   launch the command (for example, it is not on PATH) yields false."
  [cmd]
  (try
    (zero? (:exit (run! [cmd "--version"])))
    (catch Exception _ false)))
