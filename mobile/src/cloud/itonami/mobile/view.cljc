(ns cloud.itonami.mobile.view
  "The mobile screen, as hiccup.

  Pure: it is handed a state map and a map of handlers and returns hiccup. No
  fetch, no atom, no window — the same discipline as `cloud.itonami.edge.view`,
  and for the same reason: a view that can reach for data is a view that cannot
  be rendered in a test. `mobile/test` renders every phase of it on the JVM.

  There is deliberately **no app CSS**. Everything is a DADS component or a
  `dds-ext-*` layout primitive, so nothing here names a spacing, a font size or
  a radius. On this base that is not a preference: the `--hig-*` bridge carries
  no `--hig-spacing-*`, so `padding: var(--hig-spacing-4)` compiles to
  `padding: ;` — the build passes, the app ships, and the layout is quietly
  wrong (ADR-2608060000).

  This is a phone screen and not the edge's directory page, so it is a list of
  cards rather than a four-column table; a table that needs horizontal scrolling
  on a 390 pt screen is a desktop layout that was carried across rather than a
  mobile one. What the two surfaces must not disagree about is the DATA, and
  they cannot: both read `cloud.itonami.app.fleet-core` through the same edge
  API.

  An `endpoint` is rendered as text and not as a link. A link here would
  navigate the WKWebView away from the app's own origin, and this app has no
  browser chrome to come back with — `browser/open-url` is not one of the ten
  commands the in-app bridge implements (ADR-2608072000), so there is no way to
  hand the URL to the system browser either. Showing an address that cannot be
  opened is honest; a link that silently does nothing is not."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]))

(defn- code-text
  "An identifier, in the document's monospace face. `[:code]` is HTML semantics
  rather than a style choice, so it needs no class of ours."
  [s]
  [:code s])

(defn- actor-chips
  "The facts the catalog states about an actor, as chips.

  Only the ones it actually states: an absent `:domain` produces no chip rather
  than a chip reading `—`. An empty cell in a table is a column that had to be
  filled; a missing chip is a fact that was not recorded."
  [{:keys [domain execution status]}]
  (let [chips (cond-> []
                domain    (conj (dds/chip-label (name domain) {:color "blue"}))
                execution (conj (dds/chip-label (name execution) {:color "gray"}))
                status    (conj (dds/chip-label (name status) {:color "gray"})))]
    (when (seq chips) (into (dds/row) chips))))

(defn actor-card
  [{:keys [repo endpoint] :as actor}]
  ;; `:name` is read with `get` rather than destructured: a local named `name`
  ;; shadows `clojure.core/name`, which the chips below call on keywords.
  (let [title (get actor :name)]
    (dds/card
     (dds/stack
      [:p [:strong (code-text repo)]]
      (when-not (str/blank? title) [:p title])
      (actor-chips actor)
      [:p [:small (if (str/blank? endpoint)
                    "未デプロイ"
                    (code-text endpoint))]]))))

(defn- summary
  "What the current query selected, in words.

  `total` is the size of the fleet as this app last measured it, which is not
  the same claim as `matched`. When the app has not yet completed an unfiltered
  read it says so instead of printing `matched / matched` and implying the
  fleet is exactly what the filter returned."
  [{:keys [matched total query]}]
  (cond
    (nil? matched) "まだ読み込んでいません。"
    (str/blank? query) (str "全 " matched " 件を表示しています。")
    total (str matched " 件が一致しました（全 " total " 件中）。条件: " query)
    :else (str matched " 件が一致しました。条件: " query)))

(defn- results
  [{:keys [phase actors shown error] :as state}]
  (case phase
    :loading (dds/notification-banner {:type :info-1 :heading "読み込んでいます"}
                                      [:p "フリートの目録を取得しています。"])
    ;; The two failures are different facts and the app says which one it has.
    ;; "0 件" for an unreachable API would be the shape this workspace keeps
    ;; finding: a check that could not run answering the way a check that ran
    ;; and found nothing answers.
    :error (dds/notification-banner
            {:type :error :heading "取得できませんでした"}
            [:p (:message error)]
            [:p [:small "これは「該当が 0 件」ではありません。目録に問い合わせられて"
                 "いません。"]])
    :ready (if (empty? actors)
             (dds/notification-banner {:type :warning :heading "該当がありません"}
                                      [:p "条件に一致する actor は見つかりませんでした。"])
             (dds/stack
              [:p (summary state)]
              (into (dds/stack)
                    (for [a actors]
                      ^{:key (:repo a)} (actor-card a)))
              (when (and shown (:matched state) (> (:matched state) shown))
                [:p [:small (str "残り " (- (:matched state) shown)
                                 " 件は表示していません。条件を絞ってください。")]])))
    nil))

(defn screen
  "The whole app, for a state map.

  `handlers` carries `:on-query`, `:on-search` and `:on-retry`. They are passed
  in rather than closed over so this namespace stays free of the atom — and so
  a JVM test can render every phase without a browser."
  [{:keys [query] :as state} {:keys [on-query on-search on-retry]}]
  (dds/container
   (dds/stack
    (dds/heading 1 "営みフリート")
    [:p "cloud-itonami の actor 群を、blueprint が宣言している内容で引きます。"]
    (dds/form-field
     {:label "検索" :for "q"
      :support "repo 名・領域・国コードなどで絞り込みます。"}
     (dds/input-text {:id "q" :value (or query "")
                      :type "search" :inputmode "search"
                      :enterkeyhint "search"
                      :autocapitalize "none" :autocorrect "off"
                      :on-change on-query
                      :on-key-down on-search}))
    (dds/row
     ;; `:attrs` is the passthrough DADS gives consumers for exactly this; the
     ;; component keeps ownership of class / data-type / data-size.
     (dds/button "検索" {:attrs {:on-click on-search}})
     (when (= :error (:phase state))
       (dds/button "再試行" {:type :outline :attrs {:on-click on-retry}})))
    (dds/divider)
    (results state)
    (dds/divider)
    [:p [:small "この画面は端末の中で動く ClojureScript が描画し、目録は "
         [:code "cloud.itonami.app.fleet-core"]
         " —— JVM サーバと同じ 1 つの実装 —— を実行している edge から読みます"
         "（ADR-2608081500 / ADR-2608311000）。"]])))
