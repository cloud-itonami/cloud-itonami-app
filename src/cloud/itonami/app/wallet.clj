(ns cloud.itonami.app.wallet
  "Passkey-first, non-custodial EVM Smart Accounts for people and Bots.

  A verified WebAuthn P-256 credential is the initial owner of a deterministic
  ERC-4337 account. Cloud Itonami stores only its public counterfactual
  descriptor; the authenticator's private key never leaves the device. Every
  Principal receives one account and every Bot receives a distinct account.

  External accounts are optional links, not the source of identity and not a
  prerequisite for receiving funds:

    :custody :passkey-smart-account  the default account owned by WebAuthn.
    :custody :external-wallet        an optional injected wallet (MetaMask,
                                     Coinbase Wallet, etc.).
    :custody :kagi             the org's self-custodied signer
                               (wallet.signer/Signer, production:
                               kagi.chain-signer/vault-signer — the seed never
                               leaves the kagi vault, every signature is
                               governed and ledgered there).

  External and kagi accounts walk the same one-use SIWE link path. They remain
  useful for legacy assets, funding, recovery and co-signing, but linking one
  never replaces the Passkey Smart Account."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.smart-account :as smart-account]
            [cloud.itonami.app.store :as store]
            [wallet.chain :as wchain]
            [wallet.siwe :as siwe])
  (:import [java.math BigInteger]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.wallet.v2")
(def bot-wallet-schema "cloud.itonami.app.bot-wallet.v2")
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
(defn- principal-wallet-path [principal-id]
  [:wallet :principal-smart-accounts principal-id])

(defn- bot-field [bot public-key internal-key]
  (or (get bot public-key) (get bot internal-key)))

(defn- initial-passkey [state user-id]
  (->> (vals (get-in state [:identity :passkeys] {}))
       (filter #(and (= user-id (:user-id %))
                     (= true (:user-verified? %))
                     (or (:public-key-b64 %) (:public-key-cose %))))
       (sort-by (juxt #(or (:created-at %) "") :credential-id))
       first))

(defn- supported-chains [configuration]
  (or (seq (get-in configuration [:wallet :chains]))
      [{:chain-id 1 :name "Ethereum"}]))

(defn- primary-chain [configuration]
  (or (:chain-id (first (supported-chains configuration))) 1))

(defn- account-view [configuration descriptor]
  (let [chain-id (primary-chain configuration)]
    (cond-> (assoc descriptor :chain-id chain-id)
      (:address descriptor)
      (assoc :account (str "eip155:" chain-id ":"
                           (str/lower-case (:address descriptor)))
             :accounts (mapv (fn [{:keys [chain-id name]}]
                               {:namespace "eip155" :chain-id chain-id :name name
                                :address (:address descriptor)
                                :account (str "eip155:" chain-id ":"
                                              (str/lower-case (:address descriptor)))})
                             (supported-chains configuration))))))

(defn ensure-principal-account!
  "Persist the Principal's counterfactual Passkey Smart Account, idempotently.

  Returns nil only for a pre-Passkey/legacy session whose public credential is
  not present yet. Adding another Passkey never changes the stored address: the
  first verified P-256 owner is pinned in the account descriptor."
  [configuration session]
  (let [state (store/snapshot)
        principal-id (identity/session-principal-id session)
        path (when principal-id (principal-wallet-path principal-id))
        existing (when path (get-in state path))
        credential (initial-passkey state (:user-id session))]
    (cond
      existing (account-view configuration existing)
      (or (nil? principal-id) (nil? credential)) nil
      :else
      (let [record (assoc (smart-account/descriptor
                           configuration principal-id credential :principal principal-id)
                          :id (str "smart-account-principal-" principal-id)
                          :user-id (:user-id session)
                          :organization-id (:organization-id session)
                          :capabilities ["receive" "propose-send"]
                          :created-at (store/now))]
        (store/transact!
         (fn [current]
           (if (get-in current path) current (assoc-in current path record))))
        (account-view configuration (get-in (store/snapshot) path))))))

(defn- bot-smart-record [configuration session bot credential principal-id]
  (let [bot-id (bot-field bot :id :bot/id)
        descriptor (account-view
                    configuration
                    (smart-account/descriptor
                     configuration principal-id credential :bot bot-id))]
    (assoc descriptor
           :schema bot-wallet-schema
           :smart-account-schema smart-account/schema
           :id (str "smart-account-bot-" bot-id)
           :bot-id bot-id
           :bot-did (or (bot-field bot :did :bot/did)
                        (str "urn:cloud-itonami:bot:" bot-id))
           :bot-name (bot-field bot :name :bot/name)
           :user-id (:user-id session)
           :organization-id (:organization-id session)
           :capabilities ["receive" "propose-send"]
           :created-at (store/now))))

(defn provision-bot!
  "Create or migrate the durable Passkey Smart Account every Bot owns.

  The operation only derives public counterfactual state. If a legacy caller
  reaches Bot creation before its Passkey record is present, the container is
  retained as :passkey-required and upgraded on the first Wallet read."
  ([session bot] (provision-bot! {} session bot))
  ([configuration session bot]
   (let [bot-id (bot-field bot :id :bot/id)
         owner-id (bot-field bot :owner-id :bot/owner)
         organization-id (bot-field bot :organization-id :bot/organization)]
     (when-not (and bot-id
                    (= (:user-id session) owner-id)
                    (= (:organization-id session) organization-id))
       (refuse :wallet/bot-forbidden "このBotのWalletを作成する権限がありません。"))
     (let [state (store/snapshot)
           existing (get-in state (bot-wallet-path bot-id))
           principal-id (identity/session-principal-id session)
           credential (initial-passkey state (:user-id session))
           record (if (and principal-id credential)
                    (merge (bot-smart-record configuration session bot credential
                                             principal-id)
                           (select-keys existing [:id :created-at]))
                    (merge existing
                           {:schema bot-wallet-schema
                            :id (or (:id existing) (str "wallet-" (UUID/randomUUID)))
                            :bot-id bot-id
                            :bot-did (or (bot-field bot :did :bot/did)
                                         (str "urn:cloud-itonami:bot:" bot-id))
                            :bot-name (bot-field bot :name :bot/name)
                            :user-id owner-id
                            :organization-id organization-id
                            :status :passkey-required
                            :custody :passkey-smart-account
                            :capabilities ["receive" "propose-send"]
                            :created-at (or (:created-at existing) (store/now))}))]
       (if (and existing (= smart-account/schema (:smart-account-schema existing)))
         (account-view configuration existing)
         (do (store/transact! assoc-in (bot-wallet-path bot-id) record)
             (account-view configuration record)))))))

(declare bot-wallet)

(defn ensure-smart-accounts!
  "Ensure the Principal and all visible Bots have their Passkey accounts."
  [configuration session bots]
  (let [principal (ensure-principal-account! configuration session)]
    (doseq [bot bots]
      (provision-bot! configuration session bot))
    {:principal-account principal
     :bot-accounts (mapv #(bot-wallet (:id %)) bots)}))

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
  "Optionally link one verified legacy chain account to one owned Bot.

  This does not replace the Bot's Passkey Smart Account. It is retained for
  legacy assets, funding, recovery and integrations which still address an
  EOA directly."
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
         (assoc-in state [:wallet :assignments bot-id] assignment)))
      (assoc assignment :wallet-id (:id container)))))

(defn unassign! [session bot-id]
  (let [assignment (get-in (wallet-state) [:assignments bot-id])]
    (when-not (and assignment
                   (= (:user-id session) (:user-id assignment))
                   (= (:organization-id session) (:organization-id assignment)))
      (refuse :wallet/assignment-not-found "BotのWallet割り当てが見つかりません。"))
    (store/transact!
     update-in [:wallet :assignments] dissoc bot-id)
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

(defn- primary-wallet [bot-id]
  (let [smart (bot-wallet bot-id)]
    (or (when (:address smart) smart)
        (assignment bot-id)
        (refuse :wallet/assignment-not-found "このBotにはWalletがありません。"))))

(defn bot-tool-definitions [bot-id]
  (when (:address (bot-wallet bot-id))
    [{:name "wallet_receive_address"
      :description "Read this Bot's deterministic Passkey Smart Account receive address and chain."
      :parameters {:type "object" :properties {}}}
     {:name "wallet_propose_send"
      :description (str "Propose an EVM transfer from this Bot's wallet. "
                        "The owning Passkey must authorize its UserOperation. (write)")
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
  (let [account (primary-wallet bot-id)]
    (let [id (str (UUID/randomUUID))
          transfer {:schema "cloud.itonami.app.wallet.transfer.v1"
                    :id id :bot-id bot-id :bot-did (:bot-did account)
                    :user-id (:user-id account)
                    :organization-id (:organization-id account)
                    :link-id (:link-id account)
                    :chain-id (:chain-id account)
                    :from (:address account) :to (address! to "送金先")
                    :value-wei (value-wei! (or value-wei value_wei))
                    :status (if (= :passkey-smart-account (:custody account))
                              :awaiting-passkey-user-operation
                              :awaiting-wallet)
                    :custody (:custody account)
                    :proposed-by proposed-by
                    :created-at (store/now)}]
      (store/transact! assoc-in [:wallet :transfers id] transfer)
      transfer)))

(defn call-tool! [bot-id name input]
  (case (str name)
    "wallet_receive_address" (select-keys (primary-wallet bot-id)
                                           [:bot-id :bot-did :address :chain-id
                                            :account-kind :deployment-state
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
  (let [{:keys [principal-account]}
        (ensure-smart-accounts! configuration session bots)
        containers (into {} (map (fn [bot] [(:id bot) (bot-wallet (:id bot))])) bots)
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
     :custody :passkey-smart-account
     :private-keys-stored? false
     :wallet-provider "WebAuthn P-256 / ERC-4337"
     :external-wallet-provider "EIP-6963 / EIP-1193 (optional)"
     :principal-account principal-account
     :accounts mine
     :bots (mapv (fn [bot]
                   (let [assignment (get assignments (:id bot))
                         container (get containers (:id bot))]
                     (assoc (select-keys bot [:id :did :name :avatar])
                            :wallet (cond-> container
                                      true (assoc :signer-connected?
                                                  (boolean (:address container))
                                                  :external-account-linked?
                                                  (boolean assignment))
                                      assignment (assoc :linked-account assignment)))))
                 bots)
     :transfers transfers
     :capabilities {:receive true :propose-send true
                    :sign-and-submit :passkey-user-operation-pending}
     :supported-chains (vec (supported-chains configuration))}))
