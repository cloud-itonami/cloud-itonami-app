(ns cloud.itonami.app.bitcoin-consensus-sync
  "Supervised, bounded P2P synchronization for the embedded Bitcoin client.

  Peer discovery and transport are availability inputs only. Every header and
  block still crosses `bitcoin.node.disk-consensus`, which owns proof-of-work,
  fork choice, block, Script, UTXO, reorg, and durable publication validation."
  (:require [bitcoin.node.disk-consensus :as disk-consensus]
            [bitcoin.node.peer :as peer]
            [bitcoin.node.peer-pool :as peer-pool]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defonce ^:private pools (atom {}))
(defonce ^:private runtime
  (atom {:status :stopped :running? false :cycles 0}))
(defonce ^:private supervisor (atom nil))

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- bounded-integer! [value minimum maximum field]
  (when-not (and (integer? value) (<= minimum value maximum))
    (fail! :bitcoin.node/sync-configuration
           "Bitcoin consensus sync bounds are invalid."
           {:field field :minimum minimum :maximum maximum :value value}))
  value)

(defn normalize-options
  "Validate app configuration and return the bounded library-facing options.

  A path is mandatory because a peer pool without durable cooldown and
  rotation history is vulnerable to predictable restart selection. DNS is
  discovery only; deployments may instead use explicit operator anchors."
  [embedded-options]
  (let [raw (:peer-sync embedded-options)
        network (or (:network embedded-options) :mainnet)
        enabled? (true? (:enabled? raw))
        dns? (true? (:dns-discovery? raw))
        peers (vec (or (:peers raw) []))
        path (:path embedded-options)
        pool-path (or (:pool-path raw)
                      (when (and (string? path) (not (str/blank? path)))
                        (str path ".peers.edn")))
        interval-seconds
        (bounded-integer! (or (:interval-seconds raw) 300) 10 86400
                          :interval-seconds)
        maximum-peers
        (bounded-integer! (or (:maximum-peers raw) 8) 1 32
                          :maximum-peers)
        required-successes
        (bounded-integer! (or (:required-successes raw) 1) 1 maximum-peers
                          :required-successes)
        max-header-batches
        (bounded-integer! (or (:max-header-batches raw) 32) 1 10000
                          :max-header-batches)
        max-blocks
        (bounded-integer! (or (:max-blocks-per-cycle raw) 32) 1 1024
                          :max-blocks-per-cycle)
        max-validation-retries
        (bounded-integer! (or (:max-validation-retries raw) 32) 1 32
                          :max-validation-retries)
        discovery-timeout-ms
        (bounded-integer! (or (:discovery-timeout-ms raw) 5000) 100 60000
                          :discovery-timeout-ms)
        maximum-discovered
        (bounded-integer! (or (:maximum-discovered-peers raw) 64) 1 1024
                          :maximum-discovered-peers)
        peer-timeout-ms
        (bounded-integer! (or (:peer-timeout-ms raw) 10000) 100 120000
                          :peer-timeout-ms)
        block-batch-timeout-ms
        (bounded-integer!
         (or (:block-batch-timeout-ms raw) 30000)
         1000 120000 :block-batch-timeout-ms)]
    (when-not (contains? peer/network-configuration network)
      (fail! :bitcoin.node/sync-configuration
             "Bitcoin consensus sync network is unsupported."
             {:network network}))
    (when (and enabled? (str/blank? pool-path))
      (fail! :bitcoin.node/sync-configuration
             "Enabled Bitcoin consensus sync requires path-backed peer history."
             {:field :pool-path}))
    (when (and enabled? (not dns?) (empty? peers))
      (fail! :bitcoin.node/sync-configuration
             "Enabled Bitcoin consensus sync requires DNS discovery or an operator peer."
             {:field :peers}))
    (when (and enabled? dns? (empty? peers)
               (empty? (get peer-pool/dns-seeds network)))
      (fail! :bitcoin.node/sync-configuration
             "This Bitcoin network has no DNS seeds; configure an operator peer."
             {:field :peers :network network}))
    (when (> (count peers) 32)
      (fail! :bitcoin.node/sync-configuration
             "Bitcoin consensus sync accepts at most 32 operator peers."
             {:field :peers :count (count peers)}))
    (when (and enabled? (not dns?)
               (> required-successes (count peers)))
      (fail! :bitcoin.node/sync-configuration
             "Required peer successes exceed the configured operator peer set."
             {:field :required-successes
              :required-successes required-successes
              :peer-count (count peers)}))
    (let [operator-peers
          (mapv
           (fn [configuration]
             (when-not (and (map? configuration)
                            (string? (:host configuration))
                            (not (str/blank? (:host configuration))))
               (fail! :bitcoin.node/sync-configuration
                      "Every Bitcoin operator peer requires a host."
                      {:field :peers}))
             (when-let [port (:port configuration)]
               (when-not (and (integer? port) (<= 1 port 65535))
                 (fail! :bitcoin.node/sync-configuration
                        "Bitcoin operator peer port is invalid."
                        {:field :port :value port})))
             (when-let [timeout (:timeout-ms configuration)]
               (bounded-integer! timeout 100 120000 :peer-timeout-ms))
             (when-let [services (:required-services configuration)]
               (when-not
                (and (integer? services)
                     (<= 0 services peer/maximum-service-mask))
                 (fail! :bitcoin.node/sync-configuration
                        "Bitcoin operator peer service mask is invalid."
                        {:field :required-services :value services})))
             (-> configuration
                 (assoc :network network
                        :source :operator
                        :anchor? true)
                 (update :timeout-ms #(or % peer-timeout-ms))
                 (update :required-services
                         #(or % peer/node-network-service))))
           peers)]
      (when-not
       (= (count operator-peers)
          (count
           (into #{}
                 (map #(select-keys % [:host :port :network]))
                 operator-peers)))
        (fail! :bitcoin.node/sync-configuration
               "Bitcoin operator peers must be unique."
               {:field :peers}))
      {:enabled? enabled?
       :network network
       :dns-discovery? dns?
       :operator-peers operator-peers
       :pool-path pool-path
       :interval-seconds interval-seconds
       :maximum-peers maximum-peers
       :required-successes required-successes
       :max-header-batches max-header-batches
       :max-blocks-per-cycle max-blocks
       :max-validation-retries max-validation-retries
       :discovery-timeout-ms discovery-timeout-ms
       :maximum-discovered-peers maximum-discovered
       :peer-timeout-ms peer-timeout-ms
       :block-batch-timeout-ms block-batch-timeout-ms})))

(defn configured?
  [options]
  (and (string? (:pool-path options))
       (not (str/blank? (:pool-path options)))
       (or (:dns-discovery? options)
           (seq (:operator-peers options)))))

(defn- discovered-peers [options]
  (if-not (:dns-discovery? options)
    []
    (mapv #(assoc % :timeout-ms (:peer-timeout-ms options)
                    :required-services peer/node-network-service)
          (peer-pool/discover-dns!
           (:network options)
           {:timeout-ms (:discovery-timeout-ms options)
            :maximum-results (:maximum-discovered-peers options)}))))

(defn- persisted-pool [options]
  (let [path (:pool-path options)
        file (io/file path)
        initial (if (.isFile file)
                  (peer-pool/load! path)
                  (peer-pool/create []))
        candidates (concat (:operator-peers options)
                           (discovered-peers options))
        result (peer-pool/add-peers initial candidates)]
    (when (empty? (:peers result))
      (fail! :bitcoin.node/peer-set
             "Bitcoin peer discovery returned no usable peers."
             {:network (:network options)}))
    (peer-pool/save! path result)
    result))

(defn- pool-for! [options]
  (let [key [(:network options) (:pool-path options)]]
    (or (get @pools key)
        (get (swap! pools
                    #(if (contains? % key)
                       %
                       (assoc % key (atom (persisted-pool options)))))
             key))))

(defn- sync-blocks-with-pool!
  [node pool-atom options]
  (if-not (seq (disk-consensus/pending-best-chain-blocks node 1))
    {:status :synced :downloaded 0 :more? false
     :consensus (disk-consensus/consensus-status node)}
    (let [now-ms (System/currentTimeMillis)]
      (disk-consensus/sync-blocks-managed!
       node pool-atom (quot now-ms 1000)
       {:max-blocks (:max-blocks-per-cycle options)
        :max-validation-retries (:max-validation-retries options)
        :maximum-peers (:maximum-peers options)
        :parallel-peers
        (min peer/maximum-block-download-peers
             (:maximum-peers options))
        :per-peer-limit 16
        :batch-timeout-ms (:block-batch-timeout-ms options)
        :now-ms now-ms
        :pool-path (:pool-path options)}))))

(defn sync-cycle!
  "Run one headers-first, fully validating, bounded synchronization cycle."
  [node pool-atom options]
  (let [unix-time (quot (System/currentTimeMillis) 1000)]
    (try
      (let [headers
            (disk-consensus/sync-headers-managed!
             node pool-atom unix-time
             {:maximum-peers (:maximum-peers options)
              :required-successes (:required-successes options)
              :max-batches (:max-header-batches options)
              :pool-path (:pool-path options)})
            blocks (sync-blocks-with-pool! node pool-atom options)]
        {:status (if (:more? blocks) :batch-limit :synced)
         :headers headers
         :blocks blocks
         :pool (peer-pool/status @pool-atom
                                 (System/currentTimeMillis))
         :consensus (disk-consensus/consensus-status node)})
      (finally
        (peer-pool/save! (:pool-path options) @pool-atom)))))

(defn- claim-cycle! []
  (locking runtime
    (when (:running? @runtime)
      (fail! :bitcoin.node/sync-busy
             "Bitcoin consensus synchronization is already running."
             {:started-at (:started-at @runtime)}))
    (swap! runtime assoc
           :status :running :running? true
           :started-at (str (java.time.Instant/now))
           :last-error nil)))

(defn run-once!
  "Run one exclusive cycle and retain bounded operator-visible evidence."
  [node options]
  (when-not (configured? options)
    (fail! :bitcoin.node/sync-not-configured
           "Bitcoin consensus peer synchronization is not configured."
           {}))
  (claim-cycle!)
  (try
    (let [result (sync-cycle! node (pool-for! options) options)]
      (swap! runtime
             #(-> %
                  (assoc :status (:status result)
                         :running? false
                         :completed-at (str (java.time.Instant/now))
                         :last-result result)
                  (update :cycles inc)))
      result)
    (catch Throwable error
      (let [data (ex-data error)
            evidence
            (select-keys
             data
             [:block-validation-result :block-hash :invalid-block-hash
              :consensus-invalid? :retryable? :source-peer
              :peer-feedback])]
        (swap! runtime
               #(-> %
                    (assoc :status :failed :running? false
                           :completed-at (str (java.time.Instant/now))
                           :last-error
                           (merge
                            {:type (or (:type data)
                                       :bitcoin.node/sync-failed)
                             :message (.getMessage error)}
                            evidence))
                    (update :cycles inc))))
      (throw error))))

(defn status [options]
  (merge
   {:configured? (boolean (configured? options))
    :enabled? (boolean (:enabled? options))
    :network (:network options)
    :interval-seconds (:interval-seconds options)}
   @runtime))

(defn stop!
  []
  (when-let [{:keys [^CountDownLatch stop]} @supervisor]
    (.countDown stop))
  (reset! supervisor nil)
  (swap! runtime #(if (:running? %)
                    (assoc % :supervised? false)
                    (assoc % :status :stopped :supervised? false)))
  (status {:enabled? false}))

(defn start!
  "Start an interruptible supervisor when `:enabled?` is explicit.

  Network failures are retained in status and retried after the configured
  interval; they do not crash the local UI. Invalid configuration has already
  failed startup synchronously in `normalize-options`."
  [node options]
  (stop!)
  (if-not (:enabled? options)
    (status options)
    (let [stop (CountDownLatch. 1)
          task
          (future
            (loop []
              (when (zero? (.getCount stop))
                nil)
              (when (pos? (.getCount stop))
                (try
                  (run-once! node options)
                  (catch Throwable _))
                (when-not (.await stop (:interval-seconds options)
                                  TimeUnit/SECONDS)
                  (recur)))))]
      (reset! supervisor {:stop stop :future task})
      (swap! runtime assoc :supervised? true)
      (status options))))

(defn clear-caches!
  "Test/process-reload hook. It never deletes durable peer history."
  []
  (stop!)
  (reset! pools {})
  (reset! runtime {:status :stopped :running? false :cycles 0})
  nil)
