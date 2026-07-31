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
            [cloud.itonami.app.bitcoin-consensus-sync :as consensus-sync]
            [cloud.itonami.app.store :as store]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]
           [java.util UUID]))

(defonce ^:private backends (atom {}))
(defonce ^:private disk-backends (atom {}))
(def ^:private maximum-evidence-bytes 65536)
(def ^:private full-history-evidence-schema
  "kotoba.bitcoin.full-history-differential.v1")
(def ^:private hash-pattern #"[0-9a-f]{64}")

(defn- evidence-fail! [message data]
  (throw
   (ex-info message
            (assoc data :type :bitcoin.node/full-history-evidence-invalid))))

(defn- absolute-path! [value field]
  (when-not (and (string? value) (not (str/blank? value)))
    (evidence-fail! "Bitcoin full-history evidence path is invalid."
                    {:field field}))
  (let [path (Path/of value (make-array String 0))]
    (when-not (.isAbsolute path)
      (evidence-fail! "Bitcoin full-history evidence paths must be absolute."
                      {:field field}))
    (.normalize path)))

(defn full-history-evidence-status
  "Validate local Core differential evidence and bind it to active ancestry.

  Absolute storage paths are used only for binding and are never returned."
  [options node current-status]
  (if-not (:full-history-evidence-path options)
    {:configured? false :status :not-configured}
    (let [evidence-path
          (absolute-path! (:full-history-evidence-path options) :evidence-path)]
      (if-not (Files/exists evidence-path (make-array LinkOption 0))
        {:configured? true :status :missing}
        (do
          (when (or (Files/isSymbolicLink evidence-path)
                    (not (Files/isRegularFile
                          evidence-path (make-array LinkOption 0))))
            (evidence-fail! "Bitcoin full-history evidence must be a regular file."
                            {:field :evidence-path}))
          (let [size (Files/size evidence-path)]
            (when (> size maximum-evidence-bytes)
              (evidence-fail! "Bitcoin full-history evidence is too large."
                              {:maximum maximum-evidence-bytes :actual size})))
          (let [evidence
                (try
                  (json/read-str (slurp (.toFile evidence-path)))
                  (catch Exception error
                    (throw
                     (ex-info "Bitcoin full-history evidence is not valid JSON."
                              {:type :bitcoin.node/full-history-evidence-invalid}
                              error))))
                target (get evidence "target")
                target-height (get target "height")
                target-hash (get target "hash")
                utxo-hash (get evidence "hash_serialized_3")
                txouts (get evidence "txouts")
                database
                (absolute-path! (get evidence "database") :database)
                configured-database
                (absolute-path! (str (:path options)) :configured-database)
                network (some-> (:network options) name)]
            (when-not
             (and (= full-history-evidence-schema (get evidence "schema"))
                  (= "match" (get evidence "result"))
                  (= "ok" (get evidence "sqlite_integrity"))
                  (= network (get evidence "network"))
                  (= database configured-database)
                  (integer? target-height) (not (neg? target-height))
                  (string? target-hash) (re-matches hash-pattern target-hash)
                  (string? utxo-hash) (re-matches hash-pattern utxo-hash)
                  (integer? txouts) (not (neg? txouts))
                  (string? (get evidence "core_version"))
                  (string? (get evidence "completed_at")))
              (evidence-fail!
               "Bitcoin full-history evidence does not match local configuration."
               {:network network :target-height target-height}))
            (let [current-height (:height current-status)
                  local-hash
                  (when (and (integer? current-height)
                             (<= target-height current-height))
                    (disk-consensus/active-block-hash-at-height
                     node target-height))
                  status
                  (cond
                    (nil? local-hash) :ahead-of-local-chain
                    (= target-hash local-hash) :verified-ancestor
                    :else :stale-chain)]
              (cond->
               {:configured? true :status status
                :target-height target-height :target-hash target-hash
                :current-height current-height
                :core-version (get evidence "core_version")
                :completed-at (get evidence "completed_at")
                :txouts txouts :hash-serialized-3 utxo-hash}
                (= :stale-chain status)
                (assoc :local-active-hash local-hash)))))))))

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

(defn- consensus-sync-options [configuration]
  (consensus-sync/normalize-options
   (get-in configuration [:bitcoin :embedded-consensus] {})))

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
         (let [node (disk-backend configuration options)
               status (disk-consensus/consensus-status node)]
           (assoc
            status
            :sync (consensus-sync/status
                   (consensus-sync-options configuration))
            :full-history-evidence
            (full-history-evidence-status options node status)))
         (disk-utxo/status (disk-backend configuration options)))))))

(defn preflight!
  "Open and validate configured embedded storage before the HTTP server binds.

  A corrupt or network-mismatched reindex pointer therefore prevents startup
  instead of surfacing only after the first consensus API request."
  [configuration]
  ;; Validate supervisor bounds and durable peer-history requirements before
  ;; the HTTP listener makes the deployment appear ready.
  (consensus-sync-options configuration)
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

(defn sync-consensus!
  "Run one exclusive, bounded headers-first P2P synchronization cycle."
  [configuration]
  (when-not (and (integrated-consensus? configuration)
                 (disk-configured? configuration))
    (throw (ex-info "Embedded Bitcoin consensus client is not configured."
                    {:type :bitcoin.node/sync-not-configured})))
  (consensus-sync/run-once!
   (disk-backend configuration)
   (consensus-sync-options configuration)))

(defn start-consensus-sync!
  "Start the configured background supervisor after server preflight."
  [configuration]
  (let [options (consensus-sync-options configuration)]
    (if (and (:enabled? options)
             (integrated-consensus? configuration)
             (disk-configured? configuration))
      (consensus-sync/start! (disk-backend configuration) options)
      (do
        (consensus-sync/stop!)
        (consensus-sync/status options)))))

(defn stop-consensus-sync! []
  (consensus-sync/stop!))

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
