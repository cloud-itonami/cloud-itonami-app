(ns cloud.itonami.app.app-directory
  "Which apps a Bot can be given, whether each one can actually be picked, and
  what job each one does.

  ## Why a Bot's apps are chosen by ROLE

  The picker used to be one flat grid of every connector this build carries,
  searchable, with a tool count under each name. That answers 'what is
  available'. It does not answer the question somebody creating a Bot is
  actually holding — *what will this Bot use for mail?* — and the difference
  shows up as a person reading eight tiles to find the two that matter to the
  job they have in mind.

  So the same set is also offered one row per JOB: カレンダー, メール,
  チームコミュニケーション, and so on, each naming the apps that can fill it.
  Both renderings read and write the same picked set. That is the whole
  relationship between them: two views of one fact, not two facts. A slot is a
  way to CHOOSE a connector; it is not a second thing a Bot holds, and nothing
  downstream knows a slot exists — `bots/create!` still receives a set of
  connector ids and `default-tools` still computes the grant from those alone.
  A slot that persisted but changed nothing would be decoration; one that
  changed the grant would be a second authority beside `:bot/tools`.

  ## The table is declared, and the gate is that it cannot go quiet

  `slots` is written by hand, because nothing in a connector descriptor says
  what job it does — `connector/model` records id, name, origin domain, auth
  and tools, and a category is not among them. That is where this belongs and
  `docs/adr/0094` records it as the exit condition.

  A hand-written table drifts. Two things stop this one drifting silently:

  - **`other-row` catches whatever is unassigned.** A connector this build
    carries that no slot names is still offered, under その他. Nothing becomes
    unreachable because somebody forgot to classify it.
  - **`unassigned` is asserted empty against the real registry.** A connector
    added to a later build arrives in その他 for the person and RED for the
    developer, which is the pair this workspace keeps having to arrange by
    hand: the runtime stays correct and the omission still makes a noise."
  (:require [kotoba.lang.text :as str]))

;; ── whether a row can be picked at all ──────────────────────────────────

(defn availability
  "Why a catalog row can or cannot be picked.

  `:usable`, `:no-tools` (this build enables none of its tools), `:no-client`
  (no OAuth client is configured on this machine).

  Here rather than in the renderer because BOTH renderings ask it, and it was
  previously answered in JavaScript inside the grid alone. A second copy in the
  slot rows would have been a second copy of a decision whose two halves send a
  person to different places -- one is something an operator turns on in this
  build, the other is something they configure for this machine -- and the copy
  that drifted would be the one shown less often."
  [row]
  (cond
    (not (and (pos? (long (or (:enabled-tool-count row) 0)))
              (boolean (:configurable? row)))) :no-tools
    (false? (:authable? row)) :no-client
    :else :usable))

(def availability-notes
  "What to say under a name that cannot be chosen. `:usable` has none: the row
  says its tool count instead, which is a fact about what it WILL do rather
  than about why it will not."
  {:no-tools "このビルドでは有効なツールがありません"
   :no-client "OAuth クライアント設定が必要です"})

(defn usable? [row] (= :usable (availability row)))

;; ── the table ────────────────────────────────────────────────────────────

(def slots
  "One row per job a Bot may need an outside app for.

  `:slot/detail` is one short line, because it shares a column with the title
  and a sentence there wraps to three ragged lines beside a control that is one
  line tall. What the tools actually do is on the tile in the grid below and on
  the connection card; this says only enough to tell two slots apart.

  `:slot/apps` names connector ids, and an id may appear in more than one slot
  on purpose: Microsoft 365 is this deployment's mail AND its calendar, and a
  table that made somebody choose which one it 'really' is would be wrong about
  the other. Ordered as the picker shows them."
  [{:slot/id "calendar"
    :slot/title "カレンダー"
    :slot/detail "予定と空き時間を読みます"
    :slot/apps ["com.google.calendar" "com.microsoft.graph"]}
   {:slot/id "mail"
    :slot/title "メール"
    :slot/detail "読む・探す・承認のうえで送る"
    :slot/apps ["com.google.gmail" "com.microsoft.graph"]}
   {:slot/id "chat"
    :slot/title "チームコミュニケーション"
    :slot/detail "履歴を読む・承認のうえで投稿"
    :slot/apps ["com.slack" "com.google.chat"]}
   {:slot/id "files"
    :slot/title "ファイルとドライブ"
    :slot/detail "文書を探して取得します"
    :slot/apps ["com.google.drive"]}
   {:slot/id "notes"
    :slot/title "ワークスペースメモ"
    :slot/detail "ページとデータベースを読む"
    :slot/apps ["com.notion"]}
   {:slot/id "code"
    :slot/title "コードとレビュー"
    :slot/detail "リポジトリと Issue を読む"
    :slot/apps ["com.github"]}])

(def other-slot
  {:slot/id "other"
   :slot/title "その他"
   :slot/detail "まだ役割が決まっていないアプリです。"})

(defn assigned-ids
  "Every connector id some slot names."
  []
  (into #{} (mapcat :slot/apps) slots))

(defn unassigned
  "Ids in `catalog` that no slot names.

  The gate reads this and expects nothing. The picker reads it and shows them
  anyway -- the two uses are the point, and they disagree deliberately."
  [catalog]
  (let [named (assigned-ids)]
    (into [] (comp (map #(str (:id %)))
                   (remove named))
          catalog)))

;; ── the projection the client renders ───────────────────────────────────

(defn- candidate [row]
  (let [state (availability row)]
    (cond-> {:id (str (:id row))
             :name (:name row)
             :availability (name state)
             :connected? (boolean (:connected? row))
             :tool-count (long (or (:enabled-tool-count row) 0))}
      (availability-notes state) (assoc :note (availability-notes state)))))

(defn rows
  "Slot rows over a catalog projection.

  Structure only. WHICH apps are selected is not here and deliberately not on
  the wire: the selected set lives in one place on the client, both renderings
  read it, and there is therefore nothing for them to disagree about. A
  `:chosen` computed here would be a second answer, correct at the moment the
  page loaded and wrong from the first click.

  A slot whose apps this build does not carry at all is dropped: an empty row
  offers nothing and asks a person to read a label for a choice they cannot
  make. The その他 row is appended only when something is actually unassigned."
  [catalog]
  (let [by-id (into {} (map (juxt #(str (:id %)) identity)) catalog)
        named (assigned-ids)
        row-of (fn [slot ids]
                 (let [candidates (into [] (keep #(some-> (by-id %) candidate)) ids)]
                   (when (seq candidates)
                     {:id (:slot/id slot)
                      :title (:slot/title slot)
                      :detail (:slot/detail slot)
                      :candidates candidates})))]
    (into (into [] (keep #(row-of % (:slot/apps %))) slots)
          (keep identity)
          [(row-of other-slot (unassigned catalog))])))

(defn summary
  "What this build cannot offer, and why.

  A fact about the deployment, so it is answered here rather than counted in
  the browser. How many apps somebody has picked is NOT here: that is client
  state, and assembling one sentence out of both halves would put half of it in
  each place. The two unofferable reasons stay apart for the same reason
  `availability` keeps them apart."
  [catalog]
  (let [by-state (frequencies (map availability catalog))]
    (str/join
     " "
     (remove str/blank?
             [(when-let [n (:no-tools by-state)]
                (str n " 件はこのビルドに有効なツールが無いので選べません。"))
              (when-let [n (:no-client by-state)]
                (str n " 件は OAuth クライアントが未設定なので選べません"
                     "（Settings の接続に同じ表示が出ます）。"))]))))
