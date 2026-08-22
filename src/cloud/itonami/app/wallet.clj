(ns cloud.itonami.app.wallet
  "Non-custodial EVM wallets for people and Bots.

  The app stores ownership proofs, assignments, and transfer receipts. It does
  not store or derive private keys. A Bot may expose its receive address and
  propose a transfer; an injected wallet remains the only component capable of
  signing and submitting that transfer."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]
            [wallet.siwe :as siwe])
  (:import [java.math BigInteger]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.wallet.v1")
(def transaction-seconds 600)

(def ^:private address-pattern #"(?i)^0x[0-9a-f]{40}$")
(def ^:private signature-pattern #"(?i)^0x[0-9a-f]{130}$")
(def ^:private tx-hash-pattern #"(?i)^0x[0-9a-f]{64}$")
(def ^:private decimal-pattern #"^[0-9]{1,78}$")

(defn- refuse [type message]
  (throw (ex-info message {:type type})))

(defn- address! [value field]
  (let [value (str value)]
    (when-not (re-matches address-pattern value)
      (refuse :wallet/invalid-address (str field " のEthereum addressが不正です。")))
    value))

(defn- chain-id! [value]
  (let [value (try (long value) (catch Exception _ 0))]
    (when-not (<= 1 value Integer/MAX_VALUE)
      (refuse :wallet/invalid-chain "Ethereum chain IDが不正です。"))
    value))

(defn- wallet-state [] (get (store/snapshot) :wallet {}))
(defn- links-path [user-id] [:wallet :links user-id])
(defn- link-path [user-id link-id] [:wallet :links user-id link-id])

(defn links [session]
  (->> (vals (get-in (store/snapshot) (links-path (:user-id session)) {}))
       (sort-by :connected-at)
       reverse
       vec))

(defn- owned-link! [session link-id]
  (let [link (get-in (store/snapshot) (link-path (:user-id session) link-id))]
    (when-not (and link (= (:organization-id session) (:organization-id link)))
      (refuse :wallet/not-found "Walletが見つかりません。"))
    link))

(defn start-connection!
  "Create a short-lived, one-use SIWE challenge for an injected wallet."
  [session {:keys [address chain-id]} domain origin]
  (let [address (address! address "接続元")
        chain-id (chain-id! (or chain-id 1))
        subject-did (identity/session-did session)]
    (when-not subject-did
      (refuse :wallet/subject-required "Passkey DIDが発行されていません。"))
    (let [transaction-id (str (UUID/randomUUID))
          link-id (str (UUID/randomUUID))
          nonce (str/replace (str (UUID/randomUUID)) #"-" "")
          now (Instant/now)
          expires-at (str (.plusSeconds now transaction-seconds))
          resource (str "urn:cloud-itonami:wallet:" subject-did ":" link-id)
          message (siwe/message
                   {:domain domain :address address
                    :statement "Connect this wallet to Cloud Itonami."
                    :uri origin :version "1" :chain-id chain-id :nonce nonce
                    :issued-at (str now) :expiration-time expires-at
                    :request-id link-id :resources [resource]})
          transaction {:id transaction-id :link-id link-id
                       :user-id (:user-id session)
                       :organization-id (:organization-id session)
                       :subject-did subject-did :address address
                       :chain-id chain-id :domain domain :origin origin
                       :nonce nonce :resource resource :message message
                       :expires-at expires-at :used? false}]
      (store/transact! assoc-in [:wallet :connection-transactions transaction-id]
                       transaction)
      (select-keys transaction [:id :link-id :address :chain-id :message
                                :expires-at]))))

(defn finish-connection!
  "Verify SIWE and persist only the public account proof."
  [session {:keys [transaction-id signature]} domain]
  (let [transaction (get-in (store/snapshot)
                            [:wallet :connection-transactions transaction-id])
        now (Instant/now)]
    (when-not (and transaction
                   (= (:user-id session) (:user-id transaction))
                   (= (:organization-id session) (:organization-id transaction))
                   (= domain (:domain transaction))
                   (not (:used? transaction))
                   (pos? (compare (Instant/parse (:expires-at transaction)) now)))
      (refuse :wallet/invalid-transaction
              "Wallet接続要求が無効、使用済み、または期限切れです。"))
    (when-not (and (string? signature)
                   (re-matches signature-pattern signature))
      (refuse :wallet/verification-failed "Wallet署名の形式が不正です。"))
    (let [verified (try
                     (siwe/verify-sign-in
                      {:message (:message transaction) :signature signature
                       :address (:address transaction)}
                      {:expected-domain domain :expected-nonce (:nonce transaction)
                       :now (str now)})
                     (catch Exception _ {:ok? false}))]
      (when-not (:ok? verified)
        (refuse :wallet/verification-failed "Wallet所有署名を検証できませんでした。"))
      (let [address (str/lower-case (:address transaction))
            duplicate? (some #(and (= :active (:status %))
                                   (= address (str/lower-case (:address %))))
                             (mapcat vals (vals (get-in (wallet-state) [:links] {}))))]
        (when duplicate?
          (refuse :wallet/already-bound "このWalletは既に接続済みです。"))
        (let [link {:schema "cloud.itonami.app.wallet.link.v1"
                    :id (:link-id transaction) :user-id (:user-id session)
                    :organization-id (:organization-id session)
                    :subject-did (:subject-did transaction)
                    :namespace "eip155" :chain-id (:chain-id transaction)
                    :address (:address transaction)
                    :account (str "eip155:" (:chain-id transaction) ":" address)
                    :proof-type "eip4361" :status :active
                    :capabilities ["receive" "propose-send"]
                    :connected-at (store/now)}]
          (store/transact!
           (fn [state]
             (-> state
                 (assoc-in [:wallet :connection-transactions transaction-id :used?]
                           true)
                 (assoc-in (link-path (:user-id session) (:id link)) link))))
          link)))))

(defn assign!
  "Assign one verified address to one owned Bot. `bot` is already ownership-
  checked by the caller. One address cannot silently become two Bots' wallet."
  [session bot link-id]
  (let [link (owned-link! session link-id)
        bot-id (:id bot)]
    (when-not (= :active (:status link))
      (refuse :wallet/inactive "無効なWalletはBotへ割り当てられません。"))
    (when-not (and (= (:user-id session) (:owner-id bot))
                   (= (:organization-id session) (:organization-id bot)))
      (refuse :wallet/bot-forbidden "このBotへWalletを割り当てる権限がありません。"))
    (when-let [other (some (fn [[candidate assignment]]
                             (when (and (not= candidate bot-id)
                                        (= link-id (:link-id assignment)))
                               candidate))
                           (get-in (wallet-state) [:assignments] {}))]
      (refuse :wallet/already-assigned
              (str "このWalletは既に別のBotへ割り当て済みです: " other)))
    (let [assignment {:schema "cloud.itonami.app.wallet.assignment.v1"
                      :bot-id bot-id :bot-did (:did bot) :bot-name (:name bot)
                      :user-id (:user-id session)
                      :organization-id (:organization-id session)
                      :link-id link-id :address (:address link)
                      :chain-id (:chain-id link)
                      :capabilities ["receive" "propose-send"]
                      :assigned-at (store/now)}]
      (store/transact! assoc-in [:wallet :assignments bot-id] assignment)
      assignment)))

(defn unassign! [session bot-id]
  (let [assignment (get-in (wallet-state) [:assignments bot-id])]
    (when-not (and assignment
                   (= (:user-id session) (:user-id assignment))
                   (= (:organization-id session) (:organization-id assignment)))
      (refuse :wallet/assignment-not-found "BotのWallet割り当てが見つかりません。"))
    (store/transact! update-in [:wallet :assignments] dissoc bot-id)
    {:bot-id bot-id :unassigned? true}))

(defn revoke! [session link-id]
  (let [link (owned-link! session link-id)
        assigned (->> (get-in (wallet-state) [:assignments] {})
                      (keep (fn [[bot-id assignment]]
                              (when (= link-id (:link-id assignment)) bot-id)))
                      vec)]
    (when (seq assigned)
      (refuse :wallet/assigned
              "Botへの割り当てを解除してからWallet接続を解除してください。"))
    (let [revoked (assoc link :status :revoked :revoked-at (store/now))]
      (store/transact! assoc-in (link-path (:user-id session) link-id) revoked)
      revoked)))

(defn assignment [bot-id]
  (get-in (wallet-state) [:assignments bot-id]))

(defn- assigned! [bot-id]
  (or (assignment bot-id)
      (refuse :wallet/assignment-not-found "このBotにはWalletがありません。")))

(defn bot-tool-definitions [bot-id]
  (when (assignment bot-id)
    [{:name "wallet_receive_address"
      :description "Read this Bot's verified EVM receive address and chain."
      :parameters {:type "object" :properties {}}}
     {:name "wallet_propose_send"
      :description (str "Propose an EVM transfer from this Bot's wallet. "
                        "The human's external wallet must still sign and submit it. (write)")
      :parameters {:type "object"
                   :properties {:to {:type "string"}
                                :value_wei {:type "string"}}
                   :required ["to" "value_wei"]}}]))

(defn tool? [name] (str/starts-with? (str name) "wallet_"))
(defn write-tool? [name] (= "wallet_propose_send" (str name)))

(defn- value-wei! [value]
  (let [value (str value)]
    (when-not (and (re-matches decimal-pattern value)
                   (pos? (.signum (BigInteger. value))))
      (refuse :wallet/invalid-amount "value_weiは正の10進整数で指定してください。"))
    value))

(defn create-transfer!
  [bot-id {:keys [to value-wei value_wei]} proposed-by]
  (let [assignment (assignment bot-id)]
    (when-not assignment
      (refuse :wallet/assignment-not-found "このBotにはWalletがありません。"))
    (let [id (str (UUID/randomUUID))
          transfer {:schema "cloud.itonami.app.wallet.transfer.v1"
                    :id id :bot-id bot-id :bot-did (:bot-did assignment)
                    :user-id (:user-id assignment)
                    :organization-id (:organization-id assignment)
                    :link-id (:link-id assignment)
                    :chain-id (:chain-id assignment)
                    :from (:address assignment) :to (address! to "送金先")
                    :value-wei (value-wei! (or value-wei value_wei))
                    :status :awaiting-wallet :proposed-by proposed-by
                    :created-at (store/now)}]
      (store/transact! assoc-in [:wallet :transfers id] transfer)
      transfer)))

(defn call-tool! [bot-id name input]
  (case (str name)
    "wallet_receive_address" (select-keys (assigned! bot-id)
                                           [:bot-id :bot-did :address :chain-id
                                            :capabilities])
    "wallet_propose_send" (create-transfer! bot-id input :bot)
    (refuse :wallet/unknown-tool "未知のWallet toolです。")))

(defn submit-transfer! [session transfer-id tx-hash]
  (let [transfer (get-in (wallet-state) [:transfers transfer-id])]
    (when-not (and transfer
                   (= (:user-id session) (:user-id transfer))
                   (= (:organization-id session) (:organization-id transfer)))
      (refuse :wallet/transfer-not-found "送金提案が見つかりません。"))
    (when-not (= :awaiting-wallet (:status transfer))
      (refuse :wallet/transfer-state "この送金提案は既に処理されています。"))
    (when-not (re-matches tx-hash-pattern (str tx-hash))
      (refuse :wallet/invalid-tx-hash "Walletが返したtransaction hashが不正です。"))
    (let [submitted (assoc transfer :status :submitted :tx-hash tx-hash
                           :submitted-by (:user-id session)
                           :submitted-at (store/now))]
      (store/transact! assoc-in [:wallet :transfers transfer-id] submitted)
      submitted)))

(defn snapshot [configuration session bots]
  (let [mine (links session)
        assignments (get-in (wallet-state) [:assignments] {})
        bot-ids (set (map :id bots))
        transfers (->> (vals (get-in (wallet-state) [:transfers] {}))
                       (filter #(and (= (:organization-id session)
                                        (:organization-id %))
                                     (= (:user-id session) (:user-id %))
                                     (contains? bot-ids (:bot-id %))))
                       (sort-by :created-at)
                       reverse
                       vec)]
    {:schema schema
     :custody :external-wallet
     :private-keys-stored? false
     :wallet-provider "EIP-1193"
     :accounts mine
     :bots (mapv (fn [bot]
                   (assoc (select-keys bot [:id :did :name :avatar])
                          :wallet (get assignments (:id bot))))
                 bots)
     :transfers transfers
     :capabilities {:receive true :propose-send true
                    :sign-and-submit :external-wallet}
     :supported-chains (or (get-in configuration [:wallet :chains])
                           [{:chain-id 1 :name "Ethereum"}])}))
