(ns cloud.itonami.app.mail-domain-authority
  "Whether mail claiming to be from a domain can authenticate as it.

  ADR-0043 scoped this out and said why: \"sending as a domain needs SPF and
  DKIM alignment, which a TXT proof of naming does not establish.\" That is
  still true — this is the OTHER authority, proven separately, and holding one
  never confers the other. A tenant can own `example.co.jp` as a name and have
  no mail posture at all, or the reverse.

  Three records, published in the domain's own zone by the same act, read here
  and added up by `domain_binding_core.kotoba`:

  - **SPF** at the domain — which hosts may send for it, and whether the record
    actually closes. A record ending in `+all` authorizes the whole internet,
    so its presence is not evidence of anything.
  - **DKIM** at `<selector>._domainkey.<domain>` — a published signing key. An
    empty `p=` is a REVOKED key, not a present one.
  - **DMARC** at `_dmarc.<domain>` — that the owner has stated a policy at all.
    Whether that policy enforces is carried and not required: `p=none` is a
    real posture for a domain still reading reports.

  ## What this does NOT claim

  This app does not sign outbound mail. Sending goes through the account's own
  provider (`mail-send`), which does its own signing, so nothing here proves a
  particular message will authenticate. What it proves is that the domain's
  owner has published the records that make authentication possible, and that
  ONE tenant in this deployment holds that domain — which is the property
  `mail-send` enforces.

  `redirect=` in an SPF record is not followed, so a domain that delegates its
  terminal mechanism reads as not-closed. That is a real limit and the owner's
  way past it is an explicit `-all` or `~all`."
  (:require [clojure.string :as str]
            [cloud.itonami.app.domain-binding :as binding]
            [cloud.itonami.app.domain-verification :as naming]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store])
  (:import [java.util UUID]))

(def schema "cloud.itonami.app.mail-domain-authorities.v1")

(def ^:private selector-pattern #"^[a-z0-9](?:[a-z0-9._-]{0,61}[a-z0-9])?$")

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

;; ── reading the three records ────────────────────────────────────────────────

(defn- txt [owner]
  ;; Through the same dynamic resolver the naming gates use, so one binding in a
  ;; test covers both authorities and neither can quietly reach real DNS.
  (naming/*txt-resolver* owner))

(defn- of-kind
  "The first TXT value at `owner` that announces itself as `prefix`.

  Announced, not guessed: a zone can hold many TXT records at one name and
  picking by position rather than by the `v=` tag is how a verification token
  gets parsed as an SPF policy."
  [owner prefix]
  (some (fn [value]
          (let [v (str/trim (str value))]
            (when (str/starts-with? (str/lower-case v) prefix) v)))
        (txt owner)))

(defn spf
  "`{:present? :closed? :value}` for the domain's SPF record.

  `closed?` is the whole point of reading it. `v=spf1 +all` is a syntactically
  valid record that authorizes every host on the internet to send as the
  domain; counting it as proof would be counting a blank page as a signature."
  [domain]
  (if-let [value (of-kind domain "v=spf1")]
    {:present? true
     :closed? (boolean (re-find #"(?i)[-~]all\s*$" value))
     :value value}
    {:present? false :closed? false :value nil}))

(defn dkim
  "`{:present? :value}` for `<selector>._domainkey.<domain>`.

  An empty `p=` is how a key is REVOKED in DKIM, so a record with one is a
  statement that the key is gone — the opposite of what its presence looks
  like."
  [domain selector]
  (if-let [value (of-kind (str selector "._domainkey." domain) "v=dkim1")]
    {:present? (boolean (re-find #"(?i)\bp=[A-Za-z0-9+/=]+" value)) :value value}
    {:present? false :value nil}))

(defn dmarc
  "`{:present? :enforcing? :policy :value}` for `_dmarc.<domain>`."
  [domain]
  (if-let [value (of-kind (str "_dmarc." domain) "v=dmarc1")]
    (let [policy (some-> (re-find #"(?i)\bp\s*=\s*(none|quarantine|reject)" value)
                         second str/lower-case)]
      {:present? (some? policy)
       :enforcing? (contains? #{"quarantine" "reject"} policy)
       :policy policy
       :value value})
    {:present? false :enforcing? false :policy nil :value nil}))

;; ── records ──────────────────────────────────────────────────────────────────

(defn- authorities [] (get-in (store/snapshot) [:identity :mail-domain-authorities] {}))

(defn- public-record [record]
  (-> record
      (select-keys [:id :organization-id :domain :selector :status :created-at
                    :authorized-at :lapsed-at :last-checked-at])
      (assoc :expected {:spf (:domain record)
                        :dkim (str (:selector record) "._domainkey." (:domain record))
                        :dmarc (str "_dmarc." (:domain record))}
             :observed (select-keys (:observed record) [:spf :dkim :dmarc]))))

(defn- claim-exclusive? [all record]
  (not (boolean
        (some (fn [other]
                (and (not= (:id record) (:id other))
                     (= (:domain record) (:domain other))
                     (= :authorized (:status other))))
              (vals all)))))

(defn facts
  "The nine booleans the core is asked about, all established here."
  [all record {:keys [owner-authorized?]}]
  (let [observed (:observed record)]
    {:owner-authorized (boolean owner-authorized?)
     :spf-present (boolean (get-in observed [:spf :present?]))
     :spf-closed (boolean (get-in observed [:spf :closed?]))
     :dkim-present (boolean (get-in observed [:dkim :present?]))
     :dmarc-present (boolean (get-in observed [:dmarc :present?]))
     :dmarc-enforcing (boolean (get-in observed [:dmarc :enforcing?]))
     :claim-exclusive (claim-exclusive? all record)
     :name-is-service-owned (naming/service-owned-name? (:domain record))
     :previously-authorized (boolean (:authorized-at record))}))

;; ── authorization ────────────────────────────────────────────────────────────

(defn- owner-session! [session]
  (identity/require-passkey! session)
  (when-not (identity/human-session? session)
    (fail! :mail-domain-authority/human-required
           "メールドメインの確認にはブラウザの Passkey session が必要です。"))
  (let [state (:identity (store/snapshot))
        membership (get-in state [:memberships (:membership-id session)])
        organization (get-in state [:organizations (:organization-id session)])]
    (when-not (and membership organization
                   (= (:user-id session) (:user-id membership))
                   (= (:id organization) (:organization-id membership))
                   (= :owner (:role membership)))
      (fail! :mail-domain-authority/owner-required
             "メールドメインの確認は Organization owner だけが実行できます。"))
    organization))

;; ── reads ────────────────────────────────────────────────────────────────────

(defn list-for-session [session]
  (let [organization (owner-session! session)]
    {:schema schema
     :authorities (->> (authorities)
                       vals
                       (filter #(= (:id organization) (:organization-id %)))
                       (sort-by (juxt :created-at :id))
                       (mapv public-record))}))

(defn authorized-holder
  "The tenant that has proven mail authority for `domain`, or nil.

  Used by `mail-send` to keep one tenant from sending under a domain another
  tenant proved. Answers only for `:authorized`: a lapsed or pending record
  reserves nothing, because nothing about it is currently true."
  [domain]
  (some (fn [record]
          (when (and (= :authorized (:status record))
                     (= domain (:domain record)))
            (:organization-id record)))
        (vals (authorities))))

(defn- domain-of [address]
  (some-> address str (str/split #"@") second str/trim str/lower-case not-empty))

(defn assert-sender-permitted!
  "Refuse a send whose From-domain another tenant has proven mail authority for.

  This is the enforcement this authority exists for, and it is the one thing
  about outbound mail this deployment can actually decide. It cannot promise a
  message will authenticate — the account's own provider signs it, not this app
  — but it can keep tenant B from sending under a domain tenant A proved, inside
  a deployment that holds both.

  Silent for a domain nobody has proven, which is nearly all of them: an
  unclaimed domain reserves nothing, so gating on it would refuse ordinary mail
  to make a point. And silent for a member of the holding tenant, which is the
  normal case for the tenant that did the proving.

  `user-did` rather than a session because that is what `mail-send/send!` is
  given; the membership is derived from it here rather than passed in, so a
  caller cannot hand itself permission."
  [address user-did]
  (when-let [domain (domain-of address)]
    (when-let [holder (authorized-holder domain)]
      (let [state (:identity (store/snapshot))
            user (some (fn [candidate]
                         (when (= user-did (:did candidate)) candidate))
                       (vals (:users state)))
            member? (some (fn [membership]
                            (and (= (:id user) (:user-id membership))
                                 (= holder (:organization-id membership))))
                          (vals (:memberships state)))]
        (when-not member?
          (fail! :mail-domain-authority/domain-held-by-another-tenant
                 (str domain " のメール権限は別の Organization が保持しています。"
                      "そのドメインの差出人としては送信できません。")
                 {:domain domain})))))
  address)

;; ── writes ───────────────────────────────────────────────────────────────────

(defn- measure!
  "Read the three records for one authority and write whatever they now say.

  The single place a mail authority's state is recomputed from live DNS, so the
  owner's check and the sweep cannot drift into two rules."
  [authority-id]
  (let [record (get (authorities) authority-id)
        _ (when-not record
            (fail! :mail-domain-authority/not-found
                   "メールドメインの確認が見つかりません。"))
        checked-at (store/now)
        domain (:domain record)
        observed {:spf (spf domain)
                  :dkim (dkim domain (:selector record))
                  :dmarc (dmarc domain)}
        result
        (store/transact!
         (fn [current]
           (let [all (get-in current [:identity :mail-domain-authorities] {})
                 measured (-> (get all authority-id)
                              (assoc :observed observed
                                     :last-checked-at checked-at))
                 state (binding/mail-state
                        (facts (assoc all authority-id measured) measured
                               {:owner-authorized? false}))
                 was (:status (get all authority-id))
                 next (cond-> (assoc measured :status state)
                        (and (= :authorized state) (nil? (:authorized-at measured)))
                        (assoc :authorized-at checked-at)
                        (= :lapsed state) (assoc :lapsed-at checked-at))]
             (cond-> (assoc-in current
                               [:identity :mail-domain-authorities authority-id]
                               next)
               (not= was state)
               (update :events conj
                       {:type :identity/mail-domain-authority-changed
                        :at checked-at
                        :organization-id (:organization-id record)
                        :domain domain :authority-id authority-id
                        :from was :to state
                        ;; Which record is missing, not just that one is. An
                        ;; owner told only "未確認" has three zones' worth of
                        ;; places to look.
                        :observed observed})))))]
    (get-in result [:identity :mail-domain-authorities authority-id])))

(defn start!
  "Register a domain and selector to prove mail authority for.

  Answers with the three owner names to publish under. Nothing is proven yet —
  `verify!` reads them."
  [session {:keys [domain selector]}]
  (let [organization (owner-session! session)
        domain (naming/normalize-domain domain)
        selector (some-> selector str str/trim str/lower-case not-empty)]
    (when-not domain
      (fail! :mail-domain-authority/invalid-domain
             "確認する完全なドメイン名を入力してください。"))
    (when-not (and selector (re-matches selector-pattern selector))
      (fail! :mail-domain-authority/invalid-selector
             (str "DKIM セレクタを入力してください"
                  "（送信側が公開している `<selector>._domainkey` の名前）。")
             {:selector selector}))
    (let [all (authorities)
          mine (filter #(and (= (:id organization) (:organization-id %))
                             (= domain (:domain %)))
                       (vals all))
          existing (first mine)
          id (or (:id existing) (str "mail-authority-" (UUID/randomUUID)))
          created-at (or (:created-at existing) (store/now))
          ;; A new selector is a different key, so whatever the old one proved
          ;; is about something else. Keeping `:authorized` across a selector
          ;; change would carry a proof over to a record nobody has read.
          same-selector? (= selector (:selector existing))
          candidate (cond-> {:id id
                             :organization-id (:id organization)
                             :domain domain
                             :selector selector
                             :status (if same-selector?
                                       (or (:status existing) :pending)
                                       :pending)
                             :created-at created-at}
                      (and same-selector? (:authorized-at existing))
                      (assoc :authorized-at (:authorized-at existing))
                      (and same-selector? (:observed existing))
                      (assoc :observed (:observed existing)))]
      (when-not (binding/mail-may-start?
                 (facts all candidate {:owner-authorized? true}))
        (if (naming/service-owned-name? domain)
          (fail! :mail-domain-authority/managed-domain
                 (str "このドメインはサービス管理ドメインであり、"
                      "外部所有権確認の対象ではありません。")
                 {:domain domain})
          (fail! :mail-domain-authority/already-claimed
                 "このドメインのメール権限は別の Organization が保持しています。"
                 {:domain domain})))
      (store/transact!
       (fn [current]
         (-> current
             (assoc-in [:identity :mail-domain-authorities id] candidate)
             (update :events conj
                     {:type :identity/mail-domain-authority-started
                      :at created-at :organization-id (:id organization)
                      :domain domain :selector selector :authority-id id}))))
      (public-record candidate))))

(defn verify!
  "Read the three records and record what they now prove."
  [session {:keys [authority-id]}]
  (let [organization (owner-session! session)
        record (get (authorities) authority-id)]
    (when-not record
      (fail! :mail-domain-authority/not-found
             "メールドメインの確認が見つかりません。"))
    (when-not (= (:id organization) (:organization-id record))
      (fail! :mail-domain-authority/forbidden
             "この確認は別の Organization に属します。"))
    (let [settled (measure! authority-id)]
      (when-not (= :authorized (:status settled))
        ;; Named individually. "未確認" is three different problems in three
        ;; different zones wearing one word.
        (let [observed (:observed settled)]
          (fail! :mail-domain-authority/not-authorized
                 (str "まだ権限を確認できません: "
                      (str/join "、"
                                (cond-> []
                                  (not (get-in observed [:spf :present?]))
                                  (conj "SPF レコードがありません")
                                  (and (get-in observed [:spf :present?])
                                       (not (get-in observed [:spf :closed?])))
                                  (conj "SPF が -all / ~all で閉じていません")
                                  (not (get-in observed [:dkim :present?]))
                                  (conj "DKIM 公開鍵がありません")
                                  (not (get-in observed [:dmarc :present?]))
                                  (conj "DMARC ポリシーがありません"))))
                 {:domain (:domain settled) :observed observed})))
      (public-record settled))))

(defn recheck-all!
  "Re-measure every authority that has been proven at least once.

  Same shape and same evidence floor as the naming sweep: `:scanned` is
  reported so a tick that measured nothing cannot read as a tick where nothing
  was wrong, and one zone's failure does not abort the rest."
  []
  (let [targets (->> (authorities)
                     vals
                     (filter #(contains? #{:authorized :lapsed} (:status %)))
                     (sort-by :id))]
    (reduce
     (fn [summary record]
       (let [was (:status record)]
         (try
           (let [settled (measure! (:id record))]
             (cond-> (update summary :scanned inc)
               (not= was (:status settled))
               (update :changed conj {:authority-id (:id record)
                                      :domain (:domain record)
                                      :from was :to (:status settled)})))
           (catch Exception e
             (-> summary
                 (update :scanned inc)
                 (update :failed conj {:authority-id (:id record)
                                       :domain (:domain record)
                                       :error (or (ex-message e) (str e))}))))))
     {:scanned 0 :changed [] :failed []}
     targets)))
