(ns cloud.itonami.app.repository-measurement
  "Capacity probes for ADR-0013. These return measurements, not qualification:
  production peak-write, sustained-sync and cold-restore evidence still have to
  come from the deployment being admitted."
  (:require [cloud.itonami.app.local-query :as local-query]
            [cloud.itonami.app.repository-storage :as repository]))

(defn- elapsed-seconds [started]
  (/ (double (- (System/nanoTime) started)) 1.0e9))

(defn- positive-iterations [value]
  (let [iterations (long (or value 20))]
    (when-not (pos? iterations)
      (throw (ex-info "positive measurement iterations are required"
                      {:type :repository-storage/invalid-iterations})))
    iterations))

(defn measure-local-capacity
  "Measure reconcile, local-view materialization and Kagi sealing over the
  current representative workspace state. Results are warm-process capacity,
  so they cannot by themselves satisfy cold recovery or network sync gates."
  [{:keys [provider vmk signing-secret owner key-epoch max-chunk-bytes]}
   state iterations]
  (let [iterations (positive-iterations iterations)
        state (repository/validate-state! state)
        logical-bytes (alength ^bytes (repository/canonical-bytes state))
        candidate (assoc state :repository/measurement true)
        view-candidate (update state :datoms (fnil conj [])
                               ["repository-measurement"
                                :repository/measurement true])
        view-toggle (atom false)
        basis-cid (repository/semantic-cid state)
        reconcile-once #(repository/reconcile
                         {:base state :candidate candidate :current state
                          :basis-cid basis-cid})
        view-once #(local-query/materialized-connection
                    (if (swap! view-toggle not) view-candidate state))
        seal-once #(repository/prepare-publication
                    {:provider provider :vmk vmk
                     :signing-secret signing-secret :owner owner
                     :key-epoch key-epoch
                     :max-chunk-bytes (or max-chunk-bytes (* 1024 1024))
                     :base state :candidate candidate :current state
                     :basis-cid basis-cid :previous-head nil})]
    ;; One unmeasured pass prevents namespace/JIT startup from masquerading as
    ;; steady-state capacity.
    (local-query/clear-materialized-view!)
    (reconcile-once)
    (view-once)
    (seal-once)
    (let [started (System/nanoTime)
          _ (dotimes [_ iterations] (reconcile-once))
          reconcile-seconds (elapsed-seconds started)
          started (System/nanoTime)
          _ (dotimes [_ iterations] (view-once))
          view-seconds (elapsed-seconds started)
          started (System/nanoTime)
          sealed (loop [remaining iterations total 0]
                   (if (zero? remaining)
                     total
                     (recur (dec remaining)
                            (+ total (reduce + (map (comp alength second)
                                                   (:blocks (seal-once))))))))
          seal-seconds (elapsed-seconds started)
          logical-total (* iterations logical-bytes)]
      {:measurement/version 1
       :iterations iterations
       :logical-bytes-per-iteration logical-bytes
       :reconcile-bps (long (/ logical-total reconcile-seconds))
       :local-view-apply-bps (long (/ logical-total view-seconds))
       :seal-input-bps (long (/ logical-total seal-seconds))
       :sealed-output-bps (long (/ sealed seal-seconds))
       :encrypted-output-ratio (/ (double sealed) logical-total)
       :scope :warm-local-capacity})))

(defn measure-warm-hydrate
  "Measure a verified hydrate from the configured transport. DataLad may serve
  local annex content; therefore the result is deliberately named warm and is
  not silently substituted for a cold-device RTO drill."
  [context]
  (let [started (System/nanoTime)
        hydrated (repository/hydrate-current context)
        _ (when hydrated (repository/semantic-cid (:state hydrated)))
        elapsed-ms (/ (double (- (System/nanoTime) started)) 1.0e6)]
    (when-not hydrated
      (throw (ex-info "a published head is required for hydrate measurement"
                      {:type :repository-storage/measurement-head-required})))
    {:warm-hydrate-ms elapsed-ms
     :head/revision (:head/revision hydrated)
     :semantic/cid (:basis/cid hydrated)
     :scope :warm-configured-transport}))

(defn measure-workspace
  [context iterations]
  (let [workspace (repository/workspace-snapshot
                   (:workspace-root context) (:owner context))]
    (when-not workspace
      (throw (ex-info "editable workspace is required for measurement"
                      {:type :repository-storage/workspace-missing})))
    {:measurement
     (merge (measure-local-capacity context (:state workspace) iterations)
            (measure-warm-hydrate context))}))
