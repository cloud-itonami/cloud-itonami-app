(ns cloud.itonami.app.domain-verification
  "DNS proof that one organization controls a custom domain.

  A Passkey-authenticated human owner starts a short-lived challenge. The
  challenge is bound to the session's active organization and to one exact
  domain. Verification reads only the exact TXT owner name issued here; a TXT
  record on a parent domain is not inherited. A successful claim is exclusive
  across organizations.

  This is deliberately not Domain Connect. Domain Connect is an optional DNS
  provider automation layer; the TXT proof below is the authority boundary it
  would automate, and works with every DNS host without giving Cloud Itonami
  write access to the customer's zone."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store])
  (:import [java.net IDN]
           [java.security SecureRandom]
           [java.time Duration Instant]
           [java.util Base64 UUID]
           [javax.naming.directory InitialDirContext]))

(def schema "cloud.itonami.app.domain-verifications.v1")
(def ^:private challenge-prefix "itonami-domain-verification=")
(def ^:private challenge-ttl (Duration/ofHours 24))
(def ^:private managed-domain "itonami.cloud")

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

(defn normalize-domain
  "Return a lower-case ASCII DNS name, or nil when the input is not a domain.
  URL syntax, ports, paths, wildcards and public-suffix-only names are refused."
  [value]
  (try
    (let [domain (-> (str value) str/trim (str/replace #"\.$" "")
                     (IDN/toASCII IDN/USE_STD3_ASCII_RULES)
                     str/lower-case)
          labels (str/split domain #"\.")]
      (when (and (<= 3 (count domain) 253)
                 (<= 2 (count labels))
                 (every? #(and (<= 1 (count %) 63)
                               (re-matches #"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" %))
                         labels))
        domain))
    (catch Exception _ nil)))

(defn- random-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- owner-session! [session]
  (identity/require-passkey! session)
  (when-not (identity/human-session? session)
    (fail! :domain-verification/human-required
           "ドメイン確認の開始と完了にはブラウザの Passkey session が必要です。"))
  (let [state (:identity (store/snapshot))
        membership (get-in state [:memberships (:membership-id session)])
        organization (get-in state [:organizations (:organization-id session)])]
    (when-not (and membership organization
                   (= (:user-id session) (:user-id membership))
                   (= (:id organization) (:organization-id membership)))
      (fail! :domain-verification/membership-required
             "この Organization への有効な membership がありません。"))
    (when-not (= :owner (:role membership))
      (fail! :domain-verification/owner-required
             "ドメイン確認は Organization owner だけが実行できます。"))
    {:membership membership :organization organization}))

(defn- verification-name [domain]
  (str "_itonami-verification." domain))

(defn- clean-txt [value]
  ;; JNDI renders a multi-string TXT record as adjacent quoted strings.
  (-> (str value) (str/replace #"\"" "") str/trim))

(defn dns-txt-values
  "Resolve TXT values at one exact owner name. Kept replaceable in tests so a
  test never depends on public DNS or propagation timing."
  [owner-name]
  (let [context (InitialDirContext.)]
    (try
      (if-let [attribute (.get (.getAttributes context owner-name
                                               (into-array String ["TXT"]))
                               "TXT")]
        (loop [values [] entries (.getAll attribute)]
          (if (.hasMore entries)
            (recur (conj values (clean-txt (.next entries))) entries)
            values))
        [])
      (catch javax.naming.NameNotFoundException _ [])
      (finally (.close context)))))

(def ^:dynamic *txt-resolver* dns-txt-values)

(defn- public-record [record]
  (select-keys record [:id :organization-id :domain :status :method
                       :record-type :record-name :record-value :created-at
                       :expires-at :verified-at :last-checked-at]))

(defn list-for-session [session]
  (let [{:keys [organization]} (owner-session! session)]
    {:schema schema
     :verifications (->> (get-in (store/snapshot)
                                 [:identity :domain-verifications] {})
                         vals
                         (filter #(= (:id organization) (:organization-id %)))
                         (sort-by (juxt :created-at :id))
                         (mapv public-record))}))

(defn start!
  "Issue or replace a pending TXT challenge for the active Organization."
  [session {:keys [domain]}]
  (let [{:keys [organization]} (owner-session! session)
        domain (normalize-domain domain)]
    (when-not domain
      (fail! :domain-verification/invalid-domain
             "確認する完全なドメイン名を入力してください。"))
    (when (or (= managed-domain domain)
              (str/ends-with? domain (str "." managed-domain)))
      (fail! :domain-verification/managed-domain
             "itonami.cloud 配下はサービス管理ドメインであり、外部所有権確認の対象ではありません。"))
    (let [state (get-in (store/snapshot) [:identity :domain-verifications] {})
          claimed (some #(when (and (= domain (:domain %))
                                    (= :verified (:status %))
                                    (not= (:id organization) (:organization-id %)))
                           %)
                        (vals state))]
      (when claimed
        (fail! :domain-verification/already-claimed
               "このドメインは別の Organization で確認済みです。")))
    (let [id (str "domain-verification-" (UUID/randomUUID))
          token (random-token)
          created-at (store/now)
          record {:id id
                  :organization-id (:id organization)
                  :domain domain
                  :status :pending
                  :method :dns-txt
                  :record-type "TXT"
                  :record-name (verification-name domain)
                  :record-value (str challenge-prefix token)
                  :created-at created-at
                  :expires-at (str (.plus (Instant/parse created-at) challenge-ttl))}]
      (store/transact!
       (fn [current]
         (-> current
             (assoc-in [:identity :domain-verifications id] record)
             (update :events conj {:type :identity/domain-verification-started
                                   :at created-at :organization-id (:id organization)
                                   :domain domain :verification-id id}))))
      (public-record record))))

(defn verify!
  "Read public DNS and atomically bind a matching domain to this Organization."
  [session {:keys [verification-id]}]
  (let [{:keys [organization]} (owner-session! session)
        record (get-in (store/snapshot)
                       [:identity :domain-verifications verification-id])]
    (when-not record
      (fail! :domain-verification/not-found "ドメイン確認が見つかりません。"))
    (when-not (= (:id organization) (:organization-id record))
      (fail! :domain-verification/forbidden
             "このドメイン確認は別の Organization に属します。"))
    (if (= :verified (:status record))
      (public-record record)
      (do
        (when-not (= :pending (:status record))
          (fail! :domain-verification/invalid-state
                 "確認待ちのドメインだけを検証できます。"))
        (when-not (.isBefore (Instant/now) (Instant/parse (:expires-at record)))
          (fail! :domain-verification/expired
                 "ドメイン確認の有効期限が切れました。新しい確認を開始してください。"))
        (let [checked-at (store/now)
              observed (set (*txt-resolver* (:record-name record)))]
          (when-not (contains? observed (:record-value record))
            (store/transact!
             (fn [current]
               (assoc-in current [:identity :domain-verifications verification-id
                                  :last-checked-at] checked-at)))
            (fail! :domain-verification/record-not-found
                   "指定された TXT レコードをまだ確認できません。"
                   {:record-name (:record-name record)}))
          (let [verified-at (store/now)
                result
                (store/transact!
                 (fn [current]
                   (let [records (get-in current [:identity :domain-verifications] {})
                         conflicting (some #(when (and (= (:domain record) (:domain %))
                                                       (= :verified (:status %))
                                                       (not= (:id organization)
                                                             (:organization-id %)))
                                              %)
                                           (vals records))]
                     (when conflicting
                       (fail! :domain-verification/already-claimed
                              "このドメインは別の Organization で確認済みです。"))
                     (-> current
                         (assoc-in [:identity :domain-verifications verification-id]
                                   (assoc record :status :verified
                                          :last-checked-at checked-at
                                          :verified-at verified-at))
                         (assoc-in [:identity :organizations (:id organization)
                                    :verified-domain] (:domain record))
                         (update-in [:identity :organizations (:id organization)
                                     :verified-domains] (fnil conj #{}) (:domain record))
                         (update :events conj
                                 {:type :identity/domain-verified :at verified-at
                                  :organization-id (:id organization)
                                  :domain (:domain record)
                                  :verification-id verification-id})))))]
            (public-record
             (get-in result [:identity :domain-verifications verification-id]))))))))
