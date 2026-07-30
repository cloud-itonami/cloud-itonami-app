(ns cloud.itonami.app.operator
  "事業者としての参与 — how one operator joins the cloud-itonami fleet.

  Every blueprint in the fleet says it is an OSS business that a qualified
  operator can fork, deploy, run and sell. Until now nothing in the app let
  anyone actually be that operator: `cloud.itonami.app.fleet` is a directory
  with 1,213 entries and no notion of who runs any of them, reachable only
  through MCP. This namespace is the other half — the operator's own record,
  and the path from 「この blueprint は自分に合うか」 to 「稼働している」.

  ## The path, and what gates each step

      ① 発見    directory — fleet/search, fleet/facets
      ② 適合    `fit`          — 業種/職種/管轄 の重なり
      ③ 要件    `readiness`    — 運用に何が要るか、今どこが欠けているか
      ④ 表明    `declare!`     — 署名付きの参与宣言
      ⑤ 稼働    `register-endpoint!` — 実測が :up のときだけ通る

  ## Two things this namespace refuses to pretend

  **許認可を検証しない。** An operator's licences are recorded as
  `:self-attested`, with the person who attested and the date, and
  `readiness` reports them as `:attested` — never `:met`. The app has no
  channel to 日弁連, a 弁護士会, a 財務局 or any registry, and rendering an
  unverified claim as a green check would be the app asserting something it
  cannot know. `attestation-caveat` is the sentence the UI must show next to
  every one of them. A blueprint whose governor already models licence
  verification (`cloud-itonami-isic-6910-legalsupport`, `lawfirm`) does that
  check itself at operation time; this record is the operator's own statement
  about themselves, which is a different and weaker thing.

  **deploy 経路を推測しない。** `:deploy-config` on a catalog entry is read
  off the repository — 11 of 1,213 ship one. For the other 1,202 `readiness`
  reports `:absent` and says the operator builds the deployment themselves,
  rather than offering a deploy action that would have to invent a path. This
  is the same rule `fleet` applies to `:endpoint` (\"never guess an address\"),
  applied one step earlier: never guess a way to create one.

  What the app CAN verify is the last step, and it does: `register-endpoint!`
  probes the endpoint and refuses to record it unless it answers."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.fleet :as fleet]
            [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.operator.v1")

(def attestation-caveat
  "Shown beside every licence row. The app is not a verifier and says so."
  "自己表明です。このアプリは許認可の実在を検証していません。")

;; ── operator profile ──────────────────────────────────────────────────

(def stages
  "参与の段階. `:deployed` is the only one the app can confirm from outside;
  everything before it is the operator's own record of their intent."
  [:evaluating :declared :ready :deployed :withdrawn])

(defn profile
  "The operator profile, or nil when none has been set up."
  []
  (get (store/snapshot) :operator))

(defn save-profile!
  "Record who this operator is: the codings they work under and the licences
  they hold. `:operator/licences` entries are self-attested — see
  `attestation-caveat`."
  [{:keys [name did isic isco iso3166 technologies licences]}]
  (let [p {:schema schema
           :operator/id (or (:operator/id (profile)) (store/new-id "operator"))
           :operator/name name
           :operator/did did
           :operator/isic (vec (distinct isic))
           :operator/isco (vec (distinct isco))
           :operator/iso3166 (vec (distinct iso3166))
           :operator/technologies (set technologies)
           :operator/licences (mapv #(assoc % :licence/verification :self-attested)
                                    licences)
           :operator/updated-at (store/now)}]
    (store/transact! assoc :operator p)
    p))

;; ── ② 適合 ────────────────────────────────────────────────────────────

(defn- isic-of [a] (or (:isic a) (:isic-rev5 a) (:isic-rev4 a)))

(defn fit
  "How well `actor` matches `op`, as transparent arithmetic with the reasons
  attached. An operator asking why something did or did not surface deserves
  an answer they can check, not a score.

  Jurisdiction is weighted below sector and occupation on purpose: an operator
  in the right business in the wrong country has something to adapt, while one
  in the right country in the wrong business has nothing to adapt."
  [op actor]
  (let [isic? (boolean (and (isic-of actor)
                            (contains? (set (:operator/isic op)) (isic-of actor))))
        isco? (boolean (and (:isco-08 actor)
                            (contains? (set (:operator/isco op)) (:isco-08 actor))))
        juri? (boolean (and (:iso3166 actor)
                            (contains? (set (:operator/iso3166 op)) (:iso3166 actor))))
        score (+ (if isic? 3 0) (if isco? 3 0) (if juri? 1 0))]
    {:score score
     :reasons (cond-> []
                isic? (conj {:on :isic :value (isic-of actor)})
                isco? (conj {:on :isco-08 :value (:isco-08 actor)})
                juri? (conj {:on :iso3166 :value (:iso3166 actor)}))}))

(defn matches
  "Blueprints this operator could plausibly run, best fit first.

  Only actors with a positive fit are returned. Capacity, interest and
  availability are deliberately not part of the ranking — an operator with a
  free calendar is not thereby qualified for a sector they do not work in."
  ([op] (matches op (fleet/actors)))
  ([op actors]
   (->> actors
        (keep (fn [a]
                (let [f (fit op a)]
                  (when (pos? (:score f)) (assoc a :fit f)))))
        (sort-by (juxt (comp - :score :fit) :repo))
        vec)))

;; ── ③ 要件 ────────────────────────────────────────────────────────────

(def ^:private maturity-blocking
  "A `:blueprint` actor is a design, not an implementation. Adopting one is
  legitimate; declaring it *deployed* on the strength of the blueprint alone
  is not, so maturity blocks the last step and not the earlier ones."
  #{:blueprint})

(defn- licence-requirement
  "Whether the operator has attested any licence at all.

  The catalog does not say which licence a blueprint needs — no blueprint
  carries that field — so this cannot check for the *right* one. It reports
  what the operator attested and leaves the judgement where it belongs. Saying
  「適合」 here would be the app inventing a requirement it cannot read and then
  clearing it."
  [op]
  (let [ls (:operator/licences op)]
    (if (seq ls)
      {:requirement :licence
       :state :attested
       :detail (str (count ls) " 件の許認可が自己表明されています")
       :caveat attestation-caveat
       :items (mapv #(select-keys % [:licence/kind :licence/authority :licence/number
                                     :licence/attested-by :licence/attested-on])
                    ls)}
      {:requirement :licence
       :state :unmet
       :detail "許認可が1件も表明されていません。この blueprint の業務に許認可が必要かは運用者が判断します"
       :caveat attestation-caveat})))

(defn- technology-requirement [op actor]
  (let [need (set (:required-technologies actor))
        have (set (:operator/technologies op))
        missing (set/difference need have)]
    (cond
      (empty? need) {:requirement :technologies :state :none
                     :detail "この blueprint は必要技術を宣言していません"}
      (empty? missing) {:requirement :technologies :state :met
                        :detail (str "必要技術 " (count need) " 件すべて保有")}
      :else {:requirement :technologies :state :unmet
             :detail (str "未保有: " (str/join "・" (map name (sort missing))))
             :items (vec (sort missing))})))

(defn- deploy-requirement [actor]
  (if-let [kinds (seq (:deploy-config actor))]
    {:requirement :deploy-path :state :met
     :detail (str "deploy 経路を同梱: " (str/join "・" (map name kinds)))
     :items (vec kinds)}
    {:requirement :deploy-path :state :absent
     :detail (str "この blueprint は deploy 経路を宣言していません。"
                  "運用者が deployment を自分で構築します（1,213 件中 deploy 経路を"
                  "同梱するのは 11 件です）")}))

(defn- maturity-requirement [actor]
  (let [m (:maturity actor)]
    (cond
      (nil? m) {:requirement :maturity :state :unknown
                :detail "成熟度が宣言されていません"}
      (maturity-blocking m) {:requirement :maturity :state :unmet
                             :detail "maturity :blueprint — 設計であって実装ではありません。稼働の表明はできません"}
      :else {:requirement :maturity :state :met :detail (str "maturity " m)})))

(defn- governor-requirement [actor]
  (if-let [g (:governor actor)]
    {:requirement :governor :state :met
     :detail (str "governor " g " が全ての書込・開示・提出・送金を検閲します。"
                  "その HARD 不変条件は運用者が承認しても覆りません")}
    {:requirement :governor :state :unknown
     :detail "governor が宣言されていません"}))

(def ^:private blocking-states
  "States that stop step ⑤. `:attested`, `:absent`, `:none` and `:unknown` do
  not block — they are things the app cannot decide, and turning 「わからない」
  into 「不可」 would be as dishonest as turning it into 「可」. They are
  surfaced and the operator decides."
  #{:unmet})

(defn readiness
  "Every requirement for `op` to run `actor`, and where each one stands.

  `:blocking` lists only the requirements the app can actually adjudicate.
  Everything the app cannot know is reported with its own state and left to
  the operator — see `blocking-states`."
  [op actor]
  (let [rs [(maturity-requirement actor)
            (governor-requirement actor)
            (licence-requirement op)
            (technology-requirement op actor)
            (deploy-requirement actor)]
        blocking (filterv #(blocking-states (:state %)) rs)]
    {:requirements rs
     :blocking blocking
     :ready? (empty? blocking)}))

;; ── ④ 表明 ────────────────────────────────────────────────────────────

(defn adoptions
  "Every blueprint this operator has declared, newest first."
  []
  (->> (get (store/snapshot) :operator-adoptions {})
       vals
       (sort-by :adoption/declared-on)
       reverse
       vec))

(defn adoption [repo] (get-in (store/snapshot) [:operator-adoptions repo]))

(defn declare!
  "参与を表明する。`by` names the person declaring and is required — an
  anonymous adoption is not one, for the same reason an unsigned licence
  attestation is not evidence.

  Declaring is always allowed, including for a blueprint whose requirements
  are unmet: evaluating something you cannot yet run is how an operator finds
  out what they would need. `:adoption/stage` records the difference."
  [repo {:keys [by note]}]
  (when-not (seq (str by))
    (throw (ex-info "参与の表明には表明者が必要です" {:type :operator/anonymous-declaration
                                                    :repo repo})))
  (let [actor (fleet/actor repo)
        _ (when-not actor
            (throw (ex-info "未登録の blueprint は表明できません"
                            {:type :operator/unknown-blueprint :repo repo})))
        op (profile)
        r (readiness op actor)
        a {:schema schema
           :adoption/repo repo
           :adoption/operator-id (:operator/id op)
           :adoption/declared-by by
           :adoption/declared-on (store/now)
           :adoption/note note
           :adoption/stage (if (:ready? r) :ready :evaluating)
           :adoption/readiness-at-declaration
           (mapv #(select-keys % [:requirement :state]) (:requirements r))}]
    (store/transact! assoc-in [:operator-adoptions repo] a)
    a))

(defn withdraw!
  "参与を取り下げる。The record is kept — an operator who ran something and
  stopped is a different fact from one who never started, and the fleet's
  own ledgers keep holds as well as commits for the same reason."
  [repo {:keys [by]}]
  (when-let [a (adoption repo)]
    (let [a' (assoc a :adoption/stage :withdrawn
                    :adoption/withdrawn-by by
                    :adoption/withdrawn-on (store/now))]
      (store/transact! assoc-in [:operator-adoptions repo] a')
      a')))

;; ── ⑤ 稼働 ────────────────────────────────────────────────────────────

(defn- valid-endpoint? [s]
  (boolean (and (string? s) (re-matches #"https://[A-Za-z0-9.\-]+(:\d+)?(/.*)?" s))))

(defn register-endpoint!
  "稼働を登録する。The only step the app can confirm from outside, so it is
  the only one that is actually gated:

    1. 参与が表明されていること
    2. blocking な要件が無いこと
    3. https の endpoint であること
    4. **実際に応答すること** — probed, not asserted

  A probe that cannot reach the endpoint returns `:unknown`, and `:unknown`
  does not register. `fleet` draws the same line for the same reason: a failed
  measurement is not a measurement of success either."
  [repo {:keys [endpoint health-path by probe-fn]
         :or {health-path "/health"}}]
  (let [actor (fleet/actor repo)
        op (profile)
        a (adoption repo)
        probe (or probe-fn fleet/probe-endpoint)]
    (cond
      (nil? actor)
      {:ok? false :reason :unknown-blueprint :detail "未登録の blueprint です"}

      (or (nil? a) (= :withdrawn (:adoption/stage a)))
      {:ok? false :reason :not-declared :detail "先に参与を表明してください"}

      (not (seq (str by)))
      {:ok? false :reason :anonymous :detail "稼働の登録には登録者が必要です"}

      (not (valid-endpoint? endpoint))
      {:ok? false :reason :bad-endpoint :detail "https の endpoint を指定してください"}

      (seq (:blocking (readiness op actor)))
      {:ok? false :reason :requirements-unmet
       :detail "未充足の要件があります"
       :blocking (:blocking (readiness op actor))}

      :else
      (let [health (probe endpoint health-path)]
        (if (= :up health)
          (let [a' (assoc a :adoption/stage :deployed
                          :adoption/endpoint endpoint
                          :adoption/health-path health-path
                          :adoption/registered-by by
                          :adoption/registered-on (store/now)
                          :adoption/last-health :up)]
            (store/transact! assoc-in [:operator-adoptions repo] a')
            {:ok? true :adoption a'})
          {:ok? false :reason :endpoint-not-answering :health health
           :detail (case health
                     :down "endpoint は応答しましたが健全ではありません"
                     "endpoint に到達できませんでした（:unknown — 応答が無いことは稼働の証明になりません）")})))))

(defn deployed
  "この事業者が実際に稼働させている blueprint。"
  []
  (filterv #(= :deployed (:adoption/stage %)) (adoptions)))

(defn summary
  "参与の全体像。The counts the operator pane leads with."
  []
  (let [as (adoptions)
        by-stage (frequencies (map :adoption/stage as))]
    {:profile? (some? (profile))
     :adoptions (count as)
     :by-stage by-stage
     :deployed (get by-stage :deployed 0)
     :fleet (fleet/counts)}))
