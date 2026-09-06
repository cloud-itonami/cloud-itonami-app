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
            [cloud.itonami.mobile.terminal :as terminal]
            [cloud.itonami.bots-ui :as bots-ui]
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
  "What the shown results are, in words.

  Keyed on `:applied-query` — the query the results on screen came from — and
  never on `:query`, which is whatever is in the search field right now. The
  two are different for as long as someone is typing, and describing one set of
  results with the other produced a sentence that was measured to be simply
  false: with 1,294 unfiltered results on screen and `finance` half-typed, the
  page read `1294 件が一致しました（全 1294 件中）。条件: finance` before any
  search had been issued.

  `total` is the size of the fleet as this app last measured it, which is not
  the same claim as `matched`. When the app has not yet completed an unfiltered
  read it says so instead of printing `matched / matched` and implying the
  fleet is exactly what the filter returned."
  [{:keys [matched total applied-query]}]
  (cond
    (nil? matched) "まだ読み込んでいません。"
    (str/blank? applied-query) (str "全 " matched " 件を表示しています。")
    total (str matched " 件が一致しました（全 " total " 件中）。条件: " applied-query)
    :else (str matched " 件が一致しました。条件: " applied-query)))

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

(defn fleet-pane
  "The fleet directory.

  `handlers` carries `:on-query`, `:on-search` and `:on-retry`. They are passed
  in rather than closed over so this namespace stays free of the atom — and so
  a JVM test can render every phase without a browser."
  [{:keys [query] :as state} {:keys [on-query on-search on-retry]}]
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
         "（ADR-2608081500 / ADR-2608311000）。"]]))

;; ---------------------------------------------------------------------------
;; the terminal
;; ---------------------------------------------------------------------------

(defn- connection-line
  "Where this screen sends, and whether it can.

  A terminal that cannot reach anything must say so before someone types into
  it, not after. `base` is the ingress; `paired?` is whether this device holds
  a token. They are two separate facts and both are shown: a reachable ingress
  with no token and an unreachable ingress with one fail in different places,
  and 「送れません」alone would send the holder to look at the wrong one."
  [{:keys [base paired? base-draft token-draft]}
   {:keys [on-base on-token on-pairing]}]
  (dds/stack
   [:p [:small "接続先: " (if (str/blank? base) [:em "未設定"] [:code base])]]
   (if paired?
     [:p [:small "この端末は対になっています。"]]
     (dds/stack
      (dds/notification-banner
       {:type :warning :heading "この端末はまだ対になっていません"}
       [:p "デスクトップで認証済みの itonami が発行した ingress と token が要ります。"
        "どちらかが空の間、コマンドは組み立てられても"
        [:strong "送られません"] "。"])
      ;; The two fields are here rather than on a settings screen because this
      ;; is the only screen that is blocked by them being empty. A setting that
      ;; lives away from the thing it blocks is a setting nobody finds.
      (dds/form-field
       {:label "ingress" :for "pair-base"
        :support "例: https://agent.itonami.cloud"}
       (dds/input-text {:id "pair-base" :value (or base-draft "")
                        :type "url" :inputmode "url"
                        :autocapitalize "none" :autocorrect "off"
                        :spellcheck "false"
                        :on-change on-base}))
      (dds/form-field
       {:label "token" :for "pair-token"
        :support "デスクトップの itonami が発行した、この端末のための token。"}
       (dds/input-text {:id "pair-token" :value (or token-draft "")
                        :type "password"
                        :autocapitalize "none" :autocorrect "off"
                        :spellcheck "false"
                        :on-change on-token}))
      (dds/row
       (dds/button "保存"
                   {:attrs {:on-click #(on-pairing base-draft token-draft)}}))
      [:p [:small "token はこの端末の中にだけ保存されます。"
           "サーバにも他の端末にも送られません。"]]))))

(defn- entry-heading
  "The heading for one transcript entry.

  `:refused` covers two different things and they need different words. A
  missing flag means the request was never built; an unpaired device means it
  WAS built and deliberately not sent. Rendering both under
  「組み立てられませんでした」put a heading directly above a sentence that
  contradicted it — seen in the phone-size screenshot, not in any assertion,
  which is why the screenshot is taken."
  [{:keys [kind reason]}]
  (case kind
    :refused (if (= :not-paired reason) "送っていません" "組み立てられませんでした")
    :unavailable "この画面では実行できません"
    :failed "届きませんでした"
    nil))

(defn- transcript-entry
  "One line of the transcript.

  Five kinds, rendered five ways, because they are five different facts:

  | kind | what happened |
  |---|---|
  | `:sent` | what was typed |
  | `:answered` | the server answered |
  | `:refused` | this client refused to build the request |
  | `:unavailable` | no such command, or not offered here — nothing was asked |
  | `:failed` | a request went out and did not come back |

  `:unavailable` and `:failed` must not look alike. One means the server was
  never asked; the other means it was asked and said nothing. Rendering both as
  「エラー」is the shape this workspace keeps finding — a check that could not
  run answering the way a check that ran and found nothing answers."
  [{:keys [kind text status suggestions] :as e}]
  (case kind
    :sent [:p [:code "❯ " text]]

    :answered (dds/stack
               (when status [:p [:small "HTTP " (str status)]])
               [:pre text])

    :failed (dds/notification-banner
             {:type :error :heading (entry-heading e)}
             [:p text]
             [:p [:small "これは「サーバが断った」ではありません。"
                  "応答が返っていません。"]])

    :refused (dds/notification-banner
              {:type :warning :heading (entry-heading e)}
              [:p text]
              [:p [:small "サーバには問い合わせていません。"]])

    :unavailable (dds/notification-banner
                  {:type :warning :heading (entry-heading e)}
                  [:p text]
                  (when (seq suggestions)
                    [:p [:small "近いもの: "
                         (str/join " / " suggestions)]])
                  [:p [:small "サーバには問い合わせていません。"]])

    [:p text]))

(defn terminal-pane
  "The command line.

  `handlers` carries `:on-line`, `:on-submit`. Same discipline as the fleet
  pane: no atom in here."
  [{:keys [line transcript busy?] :as state}
   {:keys [on-line on-submit] :as handlers}]
  (dds/stack
   (dds/heading 1 "itonami")
   [:p "デスクトップの "
    [:code "itonami"]
    " と同じコマンドを、同じ表から引きます。"]
   (connection-line state handlers)
   (dds/divider)
   (if (empty? transcript)
     [:p [:small "コマンドを入力してください。例: "
          [:code "bots list"] " / " [:code "commands bot"]]]
     (into (dds/stack)
           (map-indexed (fn [i e] ^{:key i} (transcript-entry e)) transcript)))
   (dds/divider)
   (dds/form-field
    {:label "コマンド" :for "cmd"
     :support (str "この表は " (count terminal/offered-writes)
                   " 本の書き込みだけを提供します。承認は Passkey が要ります。")}
    (dds/input-text {:id "cmd" :value (or line "")
                     :type "text" :inputmode "text"
                     :enterkeyhint "send"
                     :autocapitalize "none" :autocorrect "off"
                     :spellcheck "false"
                     :on-change on-line
                     :on-key-down on-submit}))
   (dds/row
    (dds/button (if busy? "送信中…" "送信")
                {:attrs (cond-> {:on-click on-submit}
                          busy? (assoc :disabled true))}))
   (dds/divider)
   [:p [:small "コマンドの解決は "
        [:code "cloud.itonami.app.commands"]
        " —— デスクトップ CLI と同じ 1 つの表 —— が行います"
        "（ADR-2609061500）。"]]))

;; ---------------------------------------------------------------------------
;; the one document
;; ---------------------------------------------------------------------------

(def panes
  "The screens this app has, as data.

  Generated nav rather than a hand-written one: a pane added to this table and
  forgotten in the nav would be live code nobody can reach, and a nav entry
  with no pane is a dead button. Both are structurally impossible when one
  table produces both (ADR-2608080100).

  There is no hash router here and that is deliberate. This bundle runs inside
  a WKWebView at `kotoba-webbundle://app` with no URL bar, so a fragment
  addresses nothing anyone can type, copy or share, and the workspace already
  carries five copies of the same `fragment->view` (measured 2026-09-06). A
  sixth copy to move between two panes in a chrome-less WebView would be cost
  with no reader. Moving between panes is a state change, which is what the
  ADR asks for; addressability is what it offers, and this surface has no
  address bar to offer it to."
  [{:pane :fleet :label "フリート"}
   {:pane :business :label "Business Bots"}
   {:pane :terminal :label "コマンド"}])

(defn screen
  "The whole app, for a state map.

  One document, one mount, one stylesheet. `:pane` selects which pane renders;
  the nav is generated from `panes` so the two cannot disagree."
  [state handlers]
  ;; The fleet is the default because it is what this app already was. A new
  ;; pane that is unusable until a device is paired must not be the first thing
  ;; an existing user sees on upgrade.
  (let [pane (or (:pane state) :fleet)]
    (dds/container
     (dds/stack
      (into (dds/row)
            (for [{p :pane label :label} panes]
              ;; A stable id per pane. The browser verifier has to address
              ;; these without depending on which button happens to be first
              ;; in the document -- it used to click `button.dads-button`, and
              ;; the day a nav appeared above the search button that selector
              ;; started clicking something else while still finding a button.
              (dds/button label
                          {:type (if (= p pane) :solid-fill :outline)
                           :attrs {:id (str "pane-" (name p))
                                   :on-click #((:on-pane handlers) p)}})))
      (case pane
        :terminal (terminal-pane state handlers)
        :business (dds/stack
                   (dds/heading 1 "Business Bots")
                   [:p (bots-ui/label :ja :lead)]
                   (bots-ui/participation {:locale :ja}))
        (fleet-pane state handlers))))))
