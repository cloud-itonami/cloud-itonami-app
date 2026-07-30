(ns cloud.itonami.app.bitcoin-node
  "Fail-closed Bitcoin Core descriptor adapter.

  Cloud Itonami stores canonical public output descriptors only. Private
  descriptors are rejected. Bitcoin Core performs descriptor parsing,
  checksum validation, address derivation, and UTXO scanning."
  (:require [bitcoin.node.core :as core]
            [bitcoin.node.descriptor :as descriptor]
            [bitcoin.node.disk-consensus :as disk-consensus]
            [bitcoin.node.disk-utxo :as disk-utxo]
            [bitcoin.node.protocol :as node]
            [chain.observer.protocol :as chain-observer]
            [cloud.itonami.app.store :as store]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defonce ^:private backends (atom {}))
(defonce ^:private disk-backends (atom {}))

(defn- backend [configuration]
  (let [rpc-configuration (get-in configuration [:bitcoin :core-rpc])]
    (or (get @backends rpc-configuration)
        (get (swap! backends
                    #(if (contains? % rpc-configuration)
                       %
                       (assoc % rpc-configuration
                              (core/backend rpc-configuration))))
             rpc-configuration))))

(defn configured? [configuration]
  (node/configured? (backend configuration)))

(defn- disk-configuration [configuration]
  (or (get-in configuration [:bitcoin :embedded-consensus])
      (get-in configuration [:bitcoin :embedded-utxo])))

(defn- integrated-consensus? [configuration]
  (some? (get-in configuration [:bitcoin :embedded-consensus])))

(defn- disk-configured? [configuration]
  (let [options (disk-configuration configuration)]
    (some?
     (if (integrated-consensus? configuration)
       (or (:path options) (:reindex-pointer options))
       (:path options)))))

(defn- effective-disk-options
  [configuration]
  (let [options (disk-configuration configuration)]
    (if-let [pointer (and (integrated-consensus? configuration)
                          (:reindex-pointer options))]
      (let [published
            (disk-consensus/load-reindex-pointer
             pointer (:network options))]
        (-> options
            (assoc :path (:target-storage published)
                   :reindex-handoff published)
            (dissoc :reindex-pointer)))
      options)))

(defn- disk-backend
  ([configuration]
   (disk-backend configuration (effective-disk-options configuration)))
  ([configuration options]
   (let [integrated? (integrated-consensus? configuration)
        open-options
        (cond-> (dissoc options :genesis-hex :reindex-handoff)
          (:genesis-hex options)
          (assoc :genesis-bytes
                 (mapv #(Integer/parseInt (apply str %) 16)
                       (partition 2 (:genesis-hex options)))))
         cache-key [integrated? open-options]]
     (or (get @disk-backends cache-key)
         (get (swap! disk-backends
                     #(if (contains? % cache-key)
                        %
                        (assoc %
                               cache-key
                               (if integrated?
                                 (disk-consensus/open open-options)
                                 (disk-utxo/open open-options)))))
              cache-key)))))

(defn consensus-status
  "Expose the durable embedded UTXO boundary separately from Bitcoin Core."
  [configuration]
  (if-not (disk-configured? configuration)
    {:configured? false :status :not-configured}
    (let [options (effective-disk-options configuration)]
      (merge
       {:configured? true :status :connected}
       (when-let [handoff (:reindex-handoff options)]
         {:reindex-handoff
          (select-keys handoff
                       [:format :source-tip :target-tip :fork-height
                        :published-at])})
       (if (integrated-consensus? configuration)
         (disk-consensus/consensus-status
          (disk-backend configuration options))
         (disk-utxo/status (disk-backend configuration options)))))))

(defn preflight!
  "Open and validate configured embedded storage before the HTTP server binds.

  A corrupt or network-mismatched reindex pointer therefore prevents startup
  instead of surfacing only after the first consensus API request."
  [configuration]
  (consensus-status configuration))

(defn resolve-configuration!
  "Resolve a reindex pointer once for a process-supervised startup.

  The returned configuration contains the verified target path and handoff
  evidence but no live pointer path, so later requests cannot switch storage
  merely because the pointer file changes."
  [configuration]
  (if (and (integrated-consensus? configuration)
           (:reindex-pointer (disk-configuration configuration)))
    (assoc-in configuration
              [:bitcoin :embedded-consensus]
              (effective-disk-options configuration))
    configuration))

(defn rpc!
  "Compatibility boundary for application tests and API error mapping."
  [configuration method params]
  (core/rpc! (backend configuration) method params))

(defn status [configuration]
  (if-not (configured? configuration)
    {:configured? false :status :not-configured}
    (let [node-backend (backend configuration)
          node-status (node/node-status node-backend)]
      {:configured? true
       :status node-status
       :ready? (node/ready? node-status)})))

(defn diagnostics [configuration]
  (if-not (configured? configuration)
    {:configured? false :status :not-configured}
    (let [node-backend (backend configuration)]
      {:configured? true
       :identity (node/node-identity node-backend)
       :capabilities (node/capabilities node-backend)
       :scan (node/scan-status node-backend)
       :embedded-consensus (consensus-status configuration)})))

(defn snapshot
  "Compatibility boundary for the shared chain-observer protocol."
  [configuration]
  (chain-observer/snapshot (backend configuration)))

(defn observation
  "Return the validated, cross-chain-compatible Bitcoin observation."
  [configuration]
  (if-not (configured? configuration)
    {:configured? false :status :not-configured}
    (snapshot configuration)))

(defn scan-status [configuration]
  (node/scan-status (backend configuration)))

(defn abort-scan! [configuration]
  (node/abort-scan! (backend configuration)))

(defn scan!
  "Run a coordinated current-UTXO descriptor scan."
  [configuration descriptors]
  (node/scan-descriptors (backend configuration) descriptors))

(defn register-vault!
  [configuration session {:keys [name descriptor]}]
  (when-not (and (string? name) (<= 1 (count (str/trim name)) 80)
                 (string? descriptor) (<= 8 (count descriptor) 16384))
    (throw (ex-info "Vault nameまたはdescriptorが不正です。"
                    {:type :bitcoin/invalid-descriptor})))
  (let [policy (descriptor/validate-info
                (rpc! configuration "getdescriptorinfo" [descriptor]))
        canonical (:descriptor policy)
        kind (:kind policy)
        range-value (when (:ranged? policy) [0 4])
        addresses (rpc! configuration "deriveaddresses"
                        (cond-> [canonical] range-value (conj range-value)))
        vault-id (str (UUID/randomUUID))
        vault {:schema "cloud.itonami.bitcoin.descriptor-vault.v1"
               :id vault-id :user-id (:user-id session)
               :organization-id (:organization-id session)
               :name (str/trim name) :kind kind
               :descriptor canonical :checksum (:checksum policy)
               :ranged? (:ranged? policy)
               :solvable? true :private-keys? false
               :addresses (vec addresses)
               :next-index 0 :status :active
               :balance-sats nil :utxo-count nil
               :created-at (store/now)}]
    (store/transact! assoc-in [:bitcoin :descriptor-vaults vault-id] vault)
    (dissoc vault :user-id :organization-id)))

(defn- user-vault [session vault-id]
  (let [vault (get-in (store/snapshot)
                      [:bitcoin :descriptor-vaults vault-id])]
    (when-not (and vault (= (:user-id session) (:user-id vault)))
      (throw (ex-info "Descriptor Vaultが見つかりません。"
                      {:type :bitcoin/vault-not-found})))
    vault))

(defn refresh-vault!
  [configuration session vault-id]
  (let [vault (user-vault session vault-id)
        scan (scan! configuration [(:descriptor vault)])
        amount (bigdec (or (:total-amount scan) 0))
        sats (.longValueExact (.movePointRight amount 8))
        updated (assoc vault :balance-sats sats
                       :utxo-count (count (:unspents scan))
                       :scan-height (:height scan)
                       :scan-best-block (:best-block scan)
                       :scan-id (:scan-id scan)
                       :scan-status
                       (if (:success? scan) :completed :aborted)
                       :refreshed-at (store/now))]
    (store/transact! assoc-in [:bitcoin :descriptor-vaults vault-id] updated)
    (dissoc updated :user-id :organization-id)))

(defn next-address!
  [configuration session vault-id]
  (let [vault (user-vault session vault-id)
        index (long (:next-index vault 0))
        _ (when-not (:ranged? vault)
            (throw (ex-info "固定descriptorは新しいaddressを導出できません。"
                            {:type :bitcoin/descriptor-not-ranged})))
        addresses (rpc! configuration "deriveaddresses"
                        [(:descriptor vault) [index index]])
        address (first addresses)
        updated (-> vault
                    (assoc :next-index (inc index)
                           :last-derived-at (store/now))
                    (update :addresses
                            #(vec (distinct (conj (or % []) address)))))]
    (store/transact! assoc-in [:bitcoin :descriptor-vaults vault-id] updated)
    {:vault-id vault-id :index index :address address
     :kind (:kind vault) :derived-at (:last-derived-at updated)}))

(defn vaults [session]
  (->> (get-in (store/snapshot) [:bitcoin :descriptor-vaults] {})
       vals
       (filter #(= (:user-id session) (:user-id %)))
       (sort-by :created-at)
       reverse
       (mapv #(dissoc % :user-id :organization-id))))
