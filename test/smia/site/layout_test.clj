(ns smia.site.layout-test
  (:require
   [smia.book.number :as number]
   [smia.book.structure :as structure]
   [smia.error :as error]
   [smia.html.assemble :as html-assemble]
   [smia.site.assemble :as site]
   [smia.site.layout :as layout]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; --- chrome-for selection ----------------------------------------------------

(deftest default-and-plain-select-the-minimal-chrome
  (testing "no :site key falls back to the plain, unchanged chrome"
    (is (= html-assemble/default-chrome (layout/chrome-for {}))))
  (testing ":plain selects the same minimal chrome explicitly"
    (is (= html-assemble/default-chrome
           (layout/chrome-for {:site {:layout :plain}})))))

(deftest sidebar-selects-a-distinct-chrome
  (let [chrome (layout/chrome-for {:site {:layout :sidebar}})]
    (is (not= html-assemble/default-chrome chrome))
    (is (fn? (:page-wrap chrome)))))

(deftest unknown-layout-is-a-structured-error
  (try
    (layout/chrome-for {:site {:layout :nope}})
    (is false "expected an unknown-layout error")
    (catch clojure.lang.ExceptionInfo e
      (let [{:error/keys [type context]} (error/data e)]
        (is (= :smia.site.layout/unknown-layout type))
        (is (= :nope (:layout context)))))))

;; --- serialized output through site/assemble --------------------------------

(def ^:private manuscript
  {:title     "The Book"
   :author    "An Author"
   :numbering structure/default-numbering
   :sections
   [{:kind :chapter
     :content [:chapter {:id :ch-one :title "One"}
               [:h2 {:id :sec-a} "Alpha"]]}
    {:kind :chapter
     :content [:chapter {:id :ch-two :title "Two"}
               [:h2 {:id :sec-b} "Beta"]]}]})

(def ^:private book (:manuscript (number/assign manuscript)))

(defn- assemble-with [layout]
  (:pages (site/assemble book (cond-> {:color {} :type {} :code {}
                                       :spacing {} :layout {}}
                                layout (assoc :site {:layout layout})))))

(deftest sidebar-layout-wraps-every-page-in-a-sidebar-listing-the-chapters
  (let [pages (assemble-with :sidebar)]
    (doseq [page ["ch-one/index.html" "ch-two/index.html"]]
      (testing (str page " carries the sidebar nav with every chapter")
        (is (str/includes? (get pages page) "class=\"book-sidebar\""))
        (is (str/includes? (get pages page) ">One<"))
        (is (str/includes? (get pages page) ">Two<"))))))

(def ^:private parted
  (:manuscript
    (number/assign
      {:title "The Book" :author "An Author"
       :numbering structure/default-numbering
       :sections
       [{:kind :part :index 1 :title "First Part"}
        {:kind :chapter :part 1
         :content [:chapter {:id :ch-one :title "One"}]}]})))

(deftest sidebar-marks-part-dividers-as-headings
  (let [pages (:pages (site/assemble parted
                                     {:color {} :type {} :code {} :spacing {}
                                      :layout {} :site {:layout :sidebar}}))
        one   (->> (vals pages)
                   (filter #(str/includes? % "class=\"book-sidebar\""))
                   first)]
    (testing "a part divider carries the part-heading class the rail styles"
      (is (re-find #"toc-level-0 part-heading" one)))
    (testing "a chapter link carries no part-heading class"
      (is (not (re-find #"part-heading[^>]*href=" one))))))

(deftest reader-renders-a-part-and-chapter-breadcrumb
  (let [pages (:pages (site/assemble parted
                                     {:color {} :type {} :code {} :spacing {}
                                      :layout {} :site {:layout :sidebar :reader true}}))
        one   (->> (vals pages)
                   (filter #(str/includes? % "class=\"breadcrumb\""))
                   first)]
    (testing "the trail names the part above the chapter"
      (is (some? one))
      (is (str/includes? one "breadcrumb-part"))
      (is (str/includes? one "First Part"))
      (is (str/includes? one "breadcrumb-page")))))

(deftest sidebar-layout-marks-the-current-page
  (let [pages (assemble-with :sidebar)]
    (testing "chapter one marks its own entry current, not chapter two's"
      (let [one (get pages "ch-one/index.html")]
        (is (re-find #"class=\"current\"[^>]*href=\"\.\./ch-one/\"" one))
        (is (not (re-find #"class=\"current\"[^>]*href=\"\.\./ch-two/\"" one)))))))

(deftest plain-layout-emits-no-sidebar
  (let [pages (assemble-with nil)]
    (is (not (str/includes? (get pages "ch-one/index.html") "book-sidebar")))))

(deftest sidebar-home-page-is-a-title-card-without-the-duplicate-toc
  (let [home (get (assemble-with :sidebar) "index.html")]
    (testing "the landing keeps the title and author"
      (is (str/includes? home "The Book"))
      (is (str/includes? home "An Author")))
    (testing "but drops the contents nav that the sidebar already provides"
      (is (not (str/includes? home "class=\"toc\""))))
    (testing "the sidebar rail is still the navigation, and links the chapters"
      (is (str/includes? home "class=\"book-sidebar\""))
      (is (str/includes? home "href=\"ch-one/\"")))
    (testing "the title card is a centered cover"
      (is (str/includes? home "class=\"book-header cover\"")))))

(deftest plain-home-page-keeps-its-contents-list
  (let [home (get (assemble-with nil) "index.html")]
    (is (str/includes? home "class=\"toc\""))
    (is (str/includes? home "Contents"))
    (testing "a landing that carries its own contents is not a full cover"
      (is (str/includes? home "class=\"book-header\""))
      (is (not (str/includes? home "class=\"book-header cover\""))))))
