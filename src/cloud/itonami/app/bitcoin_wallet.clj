(ns cloud.itonami.app.bitcoin-wallet
  "Watch-only Bitcoin balances and Passkey-bound PSBT approvals."
  (:require [cloud.itonami.app.bitcoin :as bitcoin]
            [cloud.itonami.app.bitcoin-node :as bitcoin-node]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util UUID]))

(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 8))
      .build))

(defn- bitcoin-link [session link-id]
  (let [link (get-in (store/snapshot)
                     [:identity :wallet-bindings (:user-id session) link-id])]
    (when-not (and link (= :active (:status link))
                   (= "bip122" (:namespace link)))
      (throw (ex-info "Active Bitcoin Account Link が見つかりません。"
                      {:type :bitcoin/wallet-not-found})))
    link))

(defn balance!
  "Fetch public chain statistics for a linked watch-only address. The
  configured explorer sees the queried address; no private material is sent."
  [configuration session link-id]
  (let [link (bitcoin-link session link-id)
        base-url (some-> (get-in configuration [:bitcoin :explorer-base-url])
                         (str/replace #"/+$" ""))]
    (when-not base-url
      (throw (ex-info "Bitcoin explorer が設定されていません。"
                      {:type :bitcoin/explorer-not-configured})))
    (let [address (URLEncoder/encode (:address link)
                                     StandardCharsets/UTF_8)
          request (-> (HttpRequest/newBuilder
                       (URI/create (str base-url "/address/" address)))
                      (.timeout (Duration/ofSeconds 12))
                      (.header "Accept" "application/json")
                      .GET .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          _ (when-not (= 200 (.statusCode response))
              (throw (ex-info "Bitcoin explorerから残高を取得できませんでした。"
                              {:type :bitcoin/explorer-failed
                               :status (.statusCode response)})))
          payload (json/read-str (.body response) :key-fn keyword)
          chain (:chain_stats payload)
          mempool (:mempool_stats payload)
          confirmed (- (long (or (:funded_txo_sum chain) 0))
                       (long (or (:spent_txo_sum chain) 0)))
          pending (- (long (or (:funded_txo_sum mempool) 0))
                     (long (or (:spent_txo_sum mempool) 0)))
          balance
          {:schema "cloud.itonami.bitcoin.balance.v1"
           :link-id link-id :address (:address link) :network (:network link)
           :confirmed-sats confirmed :pending-sats pending
           :total-sats (+ confirmed pending)
           :transaction-count (+ (long (or (:tx_count chain) 0))
                                 (long (or (:tx_count mempool) 0)))
           :source base-url :watch-only? true :fetched-at (store/now)}]
      (store/transact! assoc-in [:bitcoin :balance-cache link-id] balance)
      balance)))

(defn activity!
  "Fetch and cache public transaction activity for one watch-only address."
  [configuration session link-id]
  (let [link (bitcoin-link session link-id)
        base-url (some-> (get-in configuration [:bitcoin :explorer-base-url])
                         (str/replace #"/+$" ""))]
    (when-not base-url
      (throw (ex-info "Bitcoin explorer が設定されていません。"
                      {:type :bitcoin/explorer-not-configured})))
    (let [address (URLEncoder/encode (:address link)
                                     StandardCharsets/UTF_8)
          request (-> (HttpRequest/newBuilder
                       (URI/create (str base-url "/address/" address "/txs")))
                      (.timeout (Duration/ofSeconds 15))
                      (.header "Accept" "application/json")
                      .GET .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          _ (when-not (= 200 (.statusCode response))
              (throw (ex-info "Bitcoin explorerから取引履歴を取得できませんでした。"
                              {:type :bitcoin/explorer-failed
                               :status (.statusCode response)})))
          transactions (json/read-str (.body response) :key-fn keyword)
          address (:address link)
          items
          (mapv
           (fn [transaction]
             (let [received
                   (reduce
                    + 0
                    (for [output (:vout transaction)
                          :when (= address
                                   (get-in output [:scriptpubkey_address]))]
                      (long (or (:value output) 0))))
                   sent
                   (reduce
                    + 0
                    (for [input (:vin transaction)
                          :when (= address
                                   (get-in input
                                           [:prevout :scriptpubkey_address]))]
                      (long (or (get-in input [:prevout :value]) 0))))
                   net (- received sent)
                   status (:status transaction)]
               {:id (:txid transaction)
                :link-id link-id :namespace "bip122"
                :network (:network link) :address address
                :direction (cond (pos? net) :received
                                 (neg? net) :sent
                                 :else :self)
                :amount-sats (abs net)
                :fee-sats (long (or (:fee transaction) 0))
                :confirmed? (true? (:confirmed status))
                :block-height (:block_height status)
                :at (when-let [seconds (:block_time status)]
                      (str (java.time.Instant/ofEpochSecond (long seconds))))}))
           (take 50 transactions))
          result {:schema "cloud.itonami.bitcoin.activity.v1"
                  :link-id link-id :items items :source base-url
                  :fetched-at (store/now)}]
      (store/transact! assoc-in [:bitcoin :activity-cache link-id] result)
      result)))

(defn create-psbt-review!
  [configuration session {:keys [link-id psbt]}]
  (let [link (bitcoin-link session link-id)
        review (bitcoin/parse-psbt psbt (:network link))
        external-outputs (remove #(= (:address link) (:address %))
                                 (:outputs review))
        max-fee (bigint (or (get-in configuration [:bitcoin :max-fee-sats])
                            1000000))
        max-fee-rate (double
                      (or (get-in configuration [:bitcoin :max-fee-ratio])
                          0.1))]
    (when (some #(nil? (:address %)) (:outputs review))
      (throw (ex-info
              "未知のoutput scriptを含むPSBTは承認できません。"
              {:type :bitcoin/unsafe-psbt})))
    (when (empty? external-outputs)
      (throw (ex-info "外部送金先のないPSBTは承認できません。"
                      {:type :bitcoin/unsafe-psbt})))
    (when (or (> (:fee-sats review) max-fee)
              (and (pos? (:input-sats review))
                   (> (/ (double (:fee-sats review))
                         (double (:input-sats review)))
                      max-fee-rate)))
      (throw (ex-info "PSBT feeが安全上限を超えています。"
                      {:type :bitcoin/unsafe-psbt})))
    (let [proposal-id (str (UUID/randomUUID))
          proposal (merge
                    review
                    {:id proposal-id :user-id (:user-id session)
                     :organization-id (:organization-id session)
                     :link-id link-id :address (:address link)
                     :status :awaiting-passkey
                     :psbt psbt :created-at (store/now)})]
      (store/transact! assoc-in [:bitcoin :psbt-proposals proposal-id] proposal)
      (dissoc proposal :psbt :user-id :organization-id))))

(defn start-psbt-approval!
  [session proposal-id rp-id origin]
  (let [proposal (get-in (store/snapshot)
                         [:bitcoin :psbt-proposals proposal-id])]
    (when-not (and proposal
                   (= (:user-id session) (:user-id proposal))
                   (= :awaiting-passkey (:status proposal)))
      (throw (ex-info "PSBT承認要求が見つかりません。"
                      {:type :bitcoin/proposal-not-found})))
    (passkey/start-authorization!
     (:user-id session)
     {:type :bitcoin/psbt-approval
      :proposal-id proposal-id
      :unsigned-tx-sha256 (:unsigned-tx-sha256 proposal)
      :fee-sats (:fee-sats proposal)}
     rp-id origin)))

(defn finish-psbt-approval!
  [session proposal-id transaction-id credential]
  (let [result (passkey/finish-authorization! transaction-id credential)
        context (:authorization-context result)
        proposal (get-in (store/snapshot)
                         [:bitcoin :psbt-proposals proposal-id])]
    (when-not (and proposal
                   (= (:user-id session) (:user-id result) (:user-id proposal))
                   (= :bitcoin/psbt-approval (:type context))
                   (= proposal-id (:proposal-id context))
                   (= (:unsigned-tx-sha256 proposal)
                      (:unsigned-tx-sha256 context))
                   (= :awaiting-passkey (:status proposal)))
      (throw (ex-info "Passkey承認とPSBTが一致しません。"
                      {:type :bitcoin/approval-mismatch})))
    (let [approved (assoc proposal :status :approved-for-external-signing
                          :approved-at (store/now)
                          :passkey-credential-id (:credential-id result))]
      (store/transact! assoc-in [:bitcoin :psbt-proposals proposal-id] approved)
      (select-keys approved
                   [:schema :id :link-id :network :address :status
                    :input-count :output-count :input-sats :output-sats
                    :fee-sats :outputs :unsigned-tx-sha256 :psbt
                    :created-at :approved-at]))))

(defn attach-signed-psbt!
  "Accept a PSBT signed by an external/hardware wallet only when its unsigned
  transaction is byte-for-byte the one approved with Passkey. Broadcasting is
  deliberately separate and not implemented by this identity service."
  [session proposal-id signed-psbt]
  (let [proposal (get-in (store/snapshot)
                         [:bitcoin :psbt-proposals proposal-id])]
    (when-not (and proposal (= (:user-id session) (:user-id proposal))
                   (= :approved-for-external-signing (:status proposal)))
      (throw (ex-info "承認済みPSBTが見つかりません。"
                      {:type :bitcoin/proposal-not-found})))
    (let [review (bitcoin/parse-psbt signed-psbt (:network proposal))]
      (when-not (= (:unsigned-tx-sha256 proposal)
                   (:unsigned-tx-sha256 review))
        (throw (ex-info "署名済みPSBTの取引内容が承認時から変更されています。"
                        {:type :bitcoin/approval-mismatch})))
      (let [updated (assoc proposal :status :externally-signed
                           :signed-psbt signed-psbt
                           :signed-psbt-sha256 (:psbt-sha256 review)
                           :signed-at (store/now))]
        (store/transact! assoc-in
                         [:bitcoin :psbt-proposals proposal-id] updated)
        (select-keys updated
                     [:schema :id :link-id :network :address :status
                      :fee-sats :outputs :unsigned-tx-sha256
                      :signed-psbt-sha256 :signed-at])))))

(defn proposals [session]
  (->> (get-in (store/snapshot) [:bitcoin :psbt-proposals] {})
       vals
       (filter #(= (:user-id session) (:user-id %)))
       (sort-by :created-at)
       reverse
       (mapv #(dissoc % :psbt :signed-psbt :user-id :organization-id))))

(defn wallet-snapshot
  "Return a no-network aggregate for the Wallet workspace. Remote balances and
  activity are included only from explicit refreshes already cached locally."
  [configuration session]
  (let [state (store/snapshot)
        links (->> (get-in state
                           [:identity :wallet-bindings (:user-id session)] {})
                   vals
                   (sort-by :connected-at)
                   reverse
                   (mapv #(select-keys
                           % [:id :namespace :network :address :chain-id
                              :account :did :status :proof-type :capabilities
                              :connected-at :revoked-at :sync-status])))
        balances (->> links
                      (keep #(get-in state [:bitcoin :balance-cache (:id %)]))
                      vec)
        activity (->> links
                      (mapcat #(get-in state
                                      [:bitcoin :activity-cache (:id %) :items]
                                      []))
                      (sort-by #(or (:at %) ""))
                      reverse
                      vec)
        active-links (filter #(= :active (:status %)) links)]
    {:schema "cloud.itonami.wallet.workspace.v1"
     :accounts links
     :vaults (bitcoin-node/vaults session)
     :bitcoin-core {:configured? (bitcoin-node/configured? configuration)}
     :balances balances
     :activity activity
     :approvals (proposals session)
     :summary {:account-count (count active-links)
               :bitcoin-sats (reduce + 0 (map :total-sats balances))
               :bitcoin-account-count
               (count (filter #(= "bip122" (:namespace %)) active-links))
               :evm-account-count
               (count (remove #(= "bip122" (:namespace %)) active-links))}
     :capabilities
     {:eip155 {:ownership-proof "EIP-4361"
               :watch-balance "injected-provider"
               :transaction-signing "external-wallet"}
      :bip122 {:ownership-proof "BIP-322 simple / P2WPKH + Taproot key-path"
               :watch-balance "configured-explorer"
               :transaction-signing "BIP-174 PSBT / external-wallet"}
      :taproot {:status "available"
                :ownership-proof "BIP-322 simple / BIP-340"
                :transaction-signing "BIP-174 PSBT / external-wallet"}
      :multisig {:status "planned"
                 :reason "descriptor/miniscript policy required"}}}))
