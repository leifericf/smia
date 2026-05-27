[:chapter {:id :quickstart :title "Quickstart"}
 [:p "clj-book turns a Clojure-data manuscript into publication-quality "
  [:strong "PDF"] " — screen and print editions — entirely on the JVM via "
  "Apache FOP. There is no Ruby, no asciidoctor, no external binary, and no "
  "subprocess."]

 [:h2 {:id :prerequisites} "Prerequisites"]
 [:p "All you need is a JVM and the Clojure CLI. Everything else arrives as a "
  "Maven dependency."]

 [:h2 {:id :a-minimal-book} "A minimal book"]
 [:p "A manuscript is three kinds of file: one " [:code "book.edn"] ", one or "
  "more chapter " [:code ".clj"] " files, and a " [:code "styles/tokens.edn"]
  ". The smallest useful " [:code "book.edn"] " is:"]
 [:pre "{:book/slug    \"my-book\"\n"
  " :book/title   \"My Book\"\n"
  " :book/author  \"An Author\"\n"
  " :book/chapters [\"chapters/01-intro.clj\"]}"]
 [:p "Each chapter file evaluates to a " [:code "[:chapter …]"] " form:"]
 [:pre "[:chapter {:id :intro :title \"Introduction\"}\n"
  " [:p \"Hello from \" [:strong \"clj-book\"] \".\"]]"]

 [:h2 {:id :build-it} "Build it"]
 [:p "Run the build with the Clojure CLI:"]
 [:pre "clojure -X clj-book.api/build :book-root '\"my-book\"'"]
 [:p "With no " [:code ":profiles"] " given, both the screen and print editions "
  "are produced under " [:code "build/my-book/pdf/"] ", alongside a machine-"
  "readable " [:code "artifacts.edn"] " manifest."]

 [:admonition {:kind :note}
  [:p "Read " [:xref {:to :authoring} "the authoring chapter"]
   " next to learn the full Hiccup vocabulary."]]]
