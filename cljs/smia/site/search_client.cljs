(ns smia.site.search-client
  "The opt-in search island a Smia site's pages load when the theme sets
   `:site {:search true}`.

   One book-agnostic bundle serves every book: everything book-specific
   — the entries, the category labels and their order, the page-relative
   paths — arrives in `search-index.json` and the form's data
   attributes. The island mounts on each `form[data-island=smia-search]`,
   lazy-loads the index on first focus, and runs substring matching in
   memory, rendering a popover with Replicant. With JavaScript disabled
   nothing here runs and the form's plain GET lands on the static
   fallback page; any load or fetch error degrades to the same."
  (:require [clojure.string :as str]
            [replicant.dom :as r.dom]))

;; ---------- Normalization --------------------------------------------------

(def ^:private combining-marks "[\\u0300-\\u036f]")

(defn- normalize [s]
  (some-> s
          str/lower-case
          (.normalize "NFD")
          (.replace (js/RegExp. combining-marks "g") "")))

(defn- fold-with-offsets
  "Fold `s` the same way `normalize` does, but per character, returning
   `#js [folded offsets]` where `offsets[k]` is the index in `s` of the
   character the k-th folded character came from. The fold can change a
   string's length (decomposed source loses its standalone combining
   marks), so a match located in the folded string must map its
   boundaries back through `offsets` to slice the original correctly."
  [s]
  (let [n    (count s)
        offs #js []]
    (loop [i 0, out ""]
      (if (< i n)
        (let [f (normalize (.charAt s i))]
          (dotimes [_ (count f)] (.push offs i))
          (recur (inc i) (str out f)))
        #js [out offs]))))

;; ---------- Index ----------------------------------------------------------

(defonce ^:private index-state
  (atom {:status :idle    ; :idle | :loading | :ready | :error
         :index  nil}))

(defn- prepare
  "Derive the normalized match fields once per entry."
  [index]
  (update index :entries
          (fn [es]
            (mapv #(assoc %
                          :title-norm (normalize (:title %))
                          :text-norm  (some-> (:text %) normalize))
                  es))))

(defn- ensure-index!
  "Fetch the index once (from `url`, page-relative); resolve the promise
   with it on every later call. Errors park the state in :error so the
   form keeps its native fallback."
  [url]
  (let [{:keys [status]} @index-state]
    (cond
      (= :ready status)
      (js/Promise.resolve (:index @index-state))

      (= :loading status)
      (js/Promise.
        (fn [resolve _]
          (let [check (fn check []
                        (let [s (:status @index-state)]
                          (cond
                            (= :ready s) (resolve (:index @index-state))
                            (= :error s) (resolve nil)
                            :else (js/setTimeout check 30))))]
            (check))))

      :else
      (do
        (swap! index-state assoc :status :loading)
        (-> (js/fetch url)
            (.then (fn [resp]
                     (if (.-ok resp)
                       (.json resp)
                       (throw (js/Error. (str "HTTP " (.-status resp)))))))
            (.then (fn [data]
                     (let [idx (prepare (js->clj data :keywordize-keys true))]
                       (swap! index-state assoc :status :ready :index idx)
                       idx)))
            (.catch (fn [e]
                      (js/console.warn "search index unavailable" e)
                      (swap! index-state assoc :status :error)
                      nil)))))))

;; ---------- Matching --------------------------------------------------------

(def ^:private per-kind 5)

(defn- score
  "Lower is better; nil is no match. Title matches always beat
   text-only matches."
  [q {:keys [title-norm text-norm]}]
  (cond
    (= q title-norm)                  0
    (str/starts-with? title-norm q)   (+ 10 (count title-norm))
    (str/includes? title-norm q)      (+ 1000 (count title-norm))
    (and text-norm
         (str/includes? text-norm q)) (+ 10000 (count title-norm))
    :else nil))

(defn- snippet
  "~100 chars of `text` centred on the first occurrence of `q`, the
   match wrapped in <mark>. `q` is matched against the folded text, and
   its match boundaries are mapped back to the original text through the
   fold's offset table, so the highlight lands on the right characters
   even when folding changed the string's length."
  [q {:keys [text]}]
  (when text
    (let [fo    (fold-with-offsets text)
          tnorm (aget fo 0)
          offs  (aget fo 1)
          i     (.indexOf tnorm q)]
      (when (>= i 0)
        (let [qn     (count q)
              start  (aget offs i)
              end    (if (< (+ i qn) (.-length offs)) (aget offs (+ i qn)) (count text))
              pad    50
              from   (max 0 (- start pad))
              to     (min (count text) (+ end pad))
              before (subs text from start)
              match  (subs text start end)
              after  (subs text end to)]
          [(cond->> before (pos? from) (str "…"))
           [:mark match]
           (cond-> after (< to (count text)) (str "…"))])))))

(defn- match-all
  "Group the top hits by kind, in the index's kind order. Returns
   `[{:kind :label :hits [entry …]} …]`."
  [q index]
  (let [nq (normalize q)]
    (when-not (str/blank? nq)
      (let [hits (->> (:entries index)
                      (keep (fn [e]
                              (when-let [s (score nq e)]
                                (assoc e
                                       :score s
                                       :snippet (when (>= s 10000)
                                                  (snippet nq e))))))
                      (group-by :kind))]
        (->> (:kinds index)
             (keep (fn [{:keys [kind label]}]
                     (when-let [ks (seq (get hits kind))]
                       {:kind  kind
                        :label label
                        :hits  (->> ks (sort-by :score) (take per-kind) vec)})))
             vec)))))

(defn- flat-hits [groups]
  (vec (mapcat :hits groups)))

;; ---------- Rendering --------------------------------------------------------

(defn- hit-view [root hit idx selected]
  (let [active? (= idx selected)]
    [:li {:class (when active? "active")
          :role "option"
          :aria-selected (if active? "true" "false")}
     [:a {:href (str root (:url hit))}
      [:span {:class "search-hit-title"} (:title hit)]
      (when-let [sn (:snippet hit)]
        (into [:span {:class "search-hit-snippet"}] sn))]]))

(defn- popover-view [state root labels]
  (let [{:keys [groups selected query]} state
        idx (atom -1)]
    (cond
      (seq groups)
      [:div {:class "search-popover" :role "listbox"
             :aria-label (:suggestions labels)}
       (for [{:keys [label hits]} groups]
         [:div {:class "search-cat"}
          [:h4 label]
          (into [:ul]
                (for [h hits]
                  (hit-view root h (swap! idx inc) selected)))])]

      (not (str/blank? query))
      [:div {:class "search-popover" :role "status"}
       [:p {:class "search-no-results"} (:no-matches labels)]])))

(defn- island-view [state root labels open?]
  [:div {:class "search-island-mount"}
   (when open? (popover-view state root labels))])

;; ---------- Wiring -----------------------------------------------------------

(defn- debounce [f ms]
  (let [t (atom nil)]
    (fn [& args]
      (when-let [id @t] (js/clearTimeout id))
      (reset! t (js/setTimeout #(apply f args) ms)))))

(defn- mount-one! [^js form]
  (let [input     (.querySelector form "input[name=q]")
        index-url (.getAttribute form "data-index-url")
        root      (or (.getAttribute form "data-root") "")
        ;; the page carries the localized strings; the literals are only a
        ;; fallback for a form rendered without them
        labels    {:no-matches
                   (or (.getAttribute form "data-no-matches")
                       "No matches — press Enter to browse the book by category.")
                   :suggestions
                   (or (.getAttribute form "data-suggestions-label")
                       "Search suggestions")}
        mount     (let [div (.createElement js/document "div")]
                    (set! (.-className div) "search-island")
                    (.appendChild form div)
                    div)
        state     (atom {:query "" :groups [] :selected nil :open? false})
        render!   (fn []
                    (let [s @state]
                      (r.dom/render mount (island-view s root labels (:open? s)))))
        update-query!
        (debounce
          (fn []
            (-> (ensure-index! index-url)
                (.then (fn [idx]
                         (when idx
                           (let [q      (.-value input)
                                 groups (or (match-all q idx) [])
                                 flat   (flat-hits groups)]
                             (swap! state assoc
                                    :query q
                                    :groups groups
                                    :selected (when (seq flat) 0)
                                    :open? (not (str/blank? q)))
                             (render!)))))))
          120)]
    (add-watch state ::render (fn [_ _ _ _] (render!)))
    (.addEventListener input "focus" (fn [_] (ensure-index! index-url)))
    (.addEventListener input "input" (fn [_] (update-query!)))
    (.addEventListener input "blur"
                       (fn [_]
                         ;; Delay so clicks on hits still land.
                         (js/setTimeout #(swap! state assoc :open? false) 150)))
    (.addEventListener
      input "keydown"
      (fn [^js e]
        (let [k    (.-key e)
              s    @state
              flat (flat-hits (:groups s))
              n    (count flat)]
          (cond
            (and (= "ArrowDown" k) (pos? n))
            (do (.preventDefault e)
                (swap! state update :selected #(mod (inc (or % -1)) n)))

            (and (= "ArrowUp" k) (pos? n))
            (do (.preventDefault e)
                (swap! state update :selected #(mod (dec (or % 0)) n)))

            (= "Escape" k)
            (swap! state assoc :open? false :selected nil)

            (= "Enter" k)
            (when-let [hit (and (:selected s) (nth flat (:selected s) nil))]
              (.preventDefault e)
              (set! (.-location js/window) (str root (:url hit))))))))
    nil))

(defn init []
  (doseq [^js form (array-seq
                     (.querySelectorAll js/document
                                        "form[data-island=\"smia-search\"]"))]
    (mount-one! form)))
