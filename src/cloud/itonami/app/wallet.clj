(ns cloud.itonami.app.wallet
  "Non-custodial EVM wallets for people and Bots.

  The app stores ownership proofs, assignments, and transfer receipts. It does
  not store or derive private keys. Signers come in two custodies
  (ADR-2608241100 decision 6):

    :custody :external-wallet  an injected wallet (MetaMask, Coinbase Wallet,
                               etc.) signs in the browser — the original shape.
    :custody :kagi             the org's self-custodied signer
                               (wallet.signer/Signer, production:
                               kagi.chain-signer/vault-signer — the seed never
                               leaves the kagi vault, every signature is
                               governed and ledgered there).

  Both walk the SAME one-use SIWE challenge/verify path; the proof recorded is
  the same EIP-4361 signature either way, so this module still never stores or
  derives a private key."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]
            [wallet.chain :as wchain]
            [wallet.siwe :as siwe])
  (:import [java.math BigInteger]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.wallet.v1")
(def bot-wallet-schema "cloud.itonami.app.bot-wallet.v1")
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
(defn- bot-wallet-path [bot-id] [:wallet :bot-wallets bot-id])

(defn- bot-field [bot public-key internal-key]
  (or (get bot public-key) (get bot internal-key)))

(defn provision-bot!
  "Create the durable Wallet container that every Bot owns from birth.

  This creates identity and policy state, never a private key. An external
  signer is attached later with SIWE, so Bot creation cannot silently obtain
  authority to move funds. The operation is idempotent for retries."
  [session bot]
  (let [bot-id (bot-field bot :id :bot/id)
        owner-id (bot-field bot :owner-id :bot/owner)
        organization-id (bot-field bot :organization-id :bot/organization)]
    (when-not (and bot-id
                   (= (:user-id session) owner-id)
                   (= (:organization-id session) organization-id))
      (refuse :wallet/bot-forbidden "このBotのWalletを作成する権限がありません。"))
    (or (get-in (store/snapshot) (bot-wallet-path bot-id))
        (let [record {:schema bot-wallet-schema
                      :id (str "wallet-" (UUID/randomUUID))
                      :bot-id bot-id
                      :bot-did (or (bot-field bot :did :bot/did)
                                   (str "urn:cloud-itonami:bot:" bot-id))
                      :bot-name (bot-field bot :name :bot/name)
                      :user-id owner-id
                      :organization-id organization-id
                      :status :awaiting-signer
                      :custody :external-wallet
                      :capabilities ["receive" "propose-send"]
                      :created-at (store/now)}]
          (store/transact! assoc-in (bot-wallet-path bot-id) record)
          record))))

(defn bot-wallet
  "Return a Bot's durable Wallet container, without signer secrets."
  [bot-id]
  (get-in (wallet-state) [:bot-wallets bot-id]))

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
  "Create a short-lived, one-use SIWE challenge. Injected wallets pass only
  {:address :chain-id}; the self-custody path (connect-kagi-signer!) also
  threads :custody / :derivation-path through the transaction so the link can
  record HOW it is signed for."
  [session {:keys [address chain-id custody derivation-path]} domain origin]
  (let [address (address! address "接続元")
        chain-id (chain-id! (or chain-id 1))
        principal-id (identity/session-principal-id session)
        account-did (identity/session-did session)]
    (when-not principal-id
      (refuse :wallet/subject-required "Principalが発行されていません。"))
    (let [transaction-id (str (UUID/randomUUID))
          link-id (str (UUID/randomUUID))
          nonce (str/replace (str (UUID/randomUUID)) #"-" "")
          now (Instant/now)
          expires-at (str (.plusSeconds now transaction-seconds))
          resource (str "urn:cloud-itonami:wallet-link:" link-id)
          message (siwe/message
                   {:domain domain :address address
                    :statement (str "Connect this wallet to Cloud Itonami Principal "
                                    principal-id ".")
                    :uri origin :version "1" :chain-id chain-id :nonce nonce
                    :issued-at (str now) :expiration-time expires-at
                    :request-id link-id :resources [resource]})
          transaction (cond-> {:id transaction-id :link-id link-id
                               :user-id (:user-id session)
                               :organization-id (:organization-id session)
                               :principal-id principal-id
                               :account-did account-did
                               :address address
                               :chain-id chain-id :domain domain :origin origin
                               :nonce nonce :resource resource :message message
                               :expires-at expires-at :used? false}
                        custody (assoc :custody custody)
                        derivation-path (assoc :derivation-path derivation-path))]
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
            chain-id (:chain-id transaction)
            duplicate? (some #(and (= :active (:status %))
                                   (= "eip155" (:namespace %))
                                   (= chain-id (:chain-id %))
                                   (= address (str/lower-case (:address %))))
                             (mapcat vals (vals (get-in (wallet-state) [:links] {}))))]
        (when duplicate?
          (refuse :wallet/already-bound "このchain accountは既に接続済みです。"))
        (let [link (cond-> {:schema "cloud.itonami.app.wallet.link.v2"
                            :id (:link-id transaction) :user-id (:user-id session)
                            :organization-id (:organization-id session)
                            :identity-role :linked-chain-account
                            :principal-id (:principal-id transaction)
                            :account-did (:account-did transaction)
                            :namespace "eip155" :chain-id (:chain-id transaction)
                            :address (:address transaction)
                            :account (str "eip155:" (:chain-id transaction) ":" address)
                            :proof-type "eip4361" :status :active
                            :custody (or (:custody transaction) :external-wallet)
                            :capabilities ["receive" "propose-send"]
                            :connected-at (store/now)}
                     (:derivation-path transaction)
                     (assoc :derivation-path (:derivation-path transaction)))]
          (store/transact!
           (fn [state]
             (-> state
                 (assoc-in [:wallet :connection-transactions transaction-id :used?]
                           true)
                 (assoc-in (link-path (:user-id session) (:id link)) link))))
          link)))))

(defn connect-kagi-signer!
  "Connect the org's self-custodied wallet as a signer link. Walks the SAME
  one-use SIWE challenge/verify path an injected wallet walks — the recorded
  proof is the same EIP-4361 signature — with the signature produced by
  `sgnr` (a wallet.signer/Signer; production: kagi.chain-signer/vault-signer,
  where the seed never leaves the vault and every signature is governed and
  ledgered) instead of a browser popup. The link carries :custody :kagi and
  the BIP-44 :derivation-path so later signing knows WHICH key answers for
  this address. `opts`: {:chain <wallet.chains key, default :eth>
  :account-index <BIP-44 account, default 0>}."
  [session sgnr {:keys [chain account-index] :or {chain :eth account-index 0}}
   domain origin]
  (let [account (wchain/account-with sgnr chain account-index)
        challenge (start-connection! session
                                     {:address (:address account)
                                      :chain-id (:chain-id account)
                                      :custody :kagi
                                      :derivation-path (:path account)}
                                     domain origin)
        signature (siwe/sign-message-with (:message challenge) sgnr (:path account))]
    (finish-connection! session
                        {:transaction-id (:id challenge) :signature signature}
                        domain)))

(defn assign!
  "Assign one verified chain account to one owned Bot. `bot` is already
  ownership-checked by the caller. One link cannot silently become two Bots'
  wallet."
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
    (let [container (provision-bot! session bot)
          custody (or (:custody link) :external-wallet)
          assignment {:schema "cloud.itonami.app.wallet.assignment.v1"
                      :bot-id bot-id :bot-did (:did bot) :bot-name (:name bot)
                      :user-id (:user-id session)
                      :organization-id (:organization-id session)
                      :link-id link-id :address (:address link)
                      :chain-id (:chain-id link)
                      :custody custody
                      :capabilities ["receive" "propose-send"]
                      :assigned-at (store/now)}]
      (store/transact!
       (fn [state]
         (-> state
             (assoc-in [:wallet :assignments bot-id] assignment)
             (assoc-in (conj (bot-wallet-path bot-id) :status) :active)
             (assoc-in (conj (bot-wallet-path bot-id) :activated-at) (store/now))
             (assoc-in (conj (bot-wallet-path bot-id) :custody) custody)
             (assoc-in (conj (bot-wallet-path bot-id) :signer-link-id) link-id))))
      (assoc assignment :wallet-id (:id container)))))

(defn unassign! [session bot-id]
  (let [assignment (get-in (wallet-state) [:assignments bot-id])]
    (when-not (and assignment
                   (= (:user-id session) (:user-id assignment))
                   (= (:organization-id session) (:organization-id assignment)))
      (refuse :wallet/assignment-not-found "BotのWallet割り当てが見つかりません。"))
    (store/transact!
     (fn [state]
       (-> state
           (update-in [:wallet :assignments] dissoc bot-id)
           (assoc-in (conj (bot-wallet-path bot-id) :status) :awaiting-signer)
           ;; back to the birth default — the next signer decides the custody
           (assoc-in (conj (bot-wallet-path bot-id) :custody) :external-wallet)
           (update-in (bot-wallet-path bot-id) dissoc :signer-link-id :activated-at))))
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
  (let [containers (into {}
                         (map (fn [bot]
                                [(:id bot) (or (bot-wallet (:id bot))
                                               {:schema bot-wallet-schema
                                                :id (str "wallet-for-" (:id bot))
                                                :bot-id (:id bot)
                                                :bot-did (:did bot)
                                                :bot-name (:name bot)
                                                :user-id (:user-id session)
                                                :organization-id (:organization-id session)
                                                :status :awaiting-signer
                                                :custody :external-wallet
                                                :capabilities ["receive" "propose-send"]
                                                :created-at (:created-at bot)})]))
                         bots)
        mine (links session)
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
                   (let [assignment (get assignments (:id bot))]
                     (assoc (select-keys bot [:id :did :name :avatar])
                            :wallet (cond-> (get containers (:id bot))
                                      assignment (merge assignment)
                                      true (assoc :signer-connected? (boolean assignment))))))
                 bots)
     :transfers transfers
     :capabilities {:receive true :propose-send true
                    :sign-and-submit :external-wallet}
     :supported-chains (or (get-in configuration [:wallet :chains])
                           [{:chain-id 1 :name "Ethereum"}])}))
