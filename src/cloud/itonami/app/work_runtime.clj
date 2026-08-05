(ns cloud.itonami.app.work-runtime
  "Durable adapter for the pure work-governance contract.

  One reconcile tick is deliberately finite.  The supervised scheduler calls
  it repeatedly; this namespace atomically leases WorkItems, dispatches bounded
  AgentRuns, validates receipts, and performs basis-checked source write-back."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.github-projects-source :as github-source]
            [cloud.itonami.app.github-projects-writeback :as github]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.work-organism-dispatch :as organism-dispatch]
            [cloud.itonami.app.work-partition-store :as work-partitions]
            [cloud.itonami.app.work-receipt-signature :as receipt-signature]
            [cloud.itonami.app.work-governance :as governance])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util UUID]
           [java.util.concurrent Executors ScheduledExecutorService
            ThreadFactory TimeUnit]))

(def schema "cloud.itonami.app.work-runtime.v2")
(def terminal-run-statuses #{:succeeded :failed :rejected :cancelled})
(def active-run-statuses #{:queued :leased :running :checkpointed :held})

(defn empty-ledger []
  {:schema schema
   :organizations {}
   :organization-units {}
   :positions {}
   :organization-roles {}
   :performers {}
   :assignments {}
   :reporting-lines []
   :roles {}
   :approval-policies {}
   :work-items {}
   :approval-decisions []
   :execution-receipts []
   :verification-receipts []
   :projection-receipts []
   :audit-events []
   :dead-letters []
   :source-bases {}
   :source-cursors {}
   :runtime {:ticks 0 :failures 0 :fencing-sequence 0}})

(defn- migrate-ledger [value]
  (let [value (merge (empty-ledger) value)]
    (-> value
        (assoc :schema schema)
        (update :runtime #(merge {:ticks 0 :failures 0
                                  :fencing-sequence 0} %)))))

(defn ledger []
  (migrate-ledger (:work-governance (store/snapshot))))

(defn organization-view
  "Organization-scoped control-plane projection. Authentication evidence is
  reduced to its method; credential identifiers never leave the store."
  [organization]
  (let [state (ledger)
        items (into {} (filter (fn [[_ item]]
                                 (= organization
                                    (:work.item/organization item))))
                    (:work-items state))
        item-ids (set (keys items))]
    {:schema schema
     :organization-id organization
     :organization (get-in state [:organizations organization])
     :organization-units (->> (:organization-units state) vals
                              (filter #(= organization
                                          (:org.unit/organization %))) vec)
     :positions (->> (:positions state) vals
                     (filter #(= organization
                                 (:org.position/organization %))) vec)
     :organization-roles (->> (:organization-roles state) vals
                              (filter #(= organization
                                          (:org.role/organization %))) vec)
     :performers (->> (:performers state) vals
                      (filter #(= organization
                                  (:performer/organization %))) vec)
     :assignments (->> (:assignments state) vals
                       (filter #(= organization
                                   (:org.assignment/organization %))) vec)
     :reporting-lines (filterv #(= organization
                                   (:reporting/organization %))
                               (:reporting-lines state))
     :roles (vec (vals (:roles state)))
     :approval-policies (->> (:approval-policies state) vals
                             (filter #(= organization
                                         (:approval.policy/organization %)))
                             vec)
     :work-items (vec (vals items))
     :approval-decisions
     (->> (:approval-decisions state)
          (filter #(item-ids (:approval.decision/work-item %)))
          (mapv #(update % :approval.decision/authentication
                         (fn [auth] (when auth (select-keys auth [:method]))))))
     :execution-receipts
     (filterv #(item-ids (:execution.receipt/work-item %))
              (:execution-receipts state))
     :verification-receipts
     (filterv #(item-ids (:verification.receipt/work-item %))
              (:verification-receipts state))
     :dead-letters (filterv #(item-ids (:dead-letter/work-item %))
                            (:dead-letters state))
     :runtime (:runtime state)}))

(defn- update-ledger! [f & args]
  (apply store/transact!
         (fn [s & more]
           (assoc s :work-governance
                  (apply f (migrate-ledger (:work-governance s)) more)))
         args))

(defn- append-audit [state event]
  (update state :audit-events
          #(vec (take-last 5000
                           (conj (vec %) (assoc event
                                               :audit/id
                                               (str "audit-" (UUID/randomUUID))))))))

(defn health []
  (let [state (ledger)]
    {:schema schema
     :enabled-state (get-in state [:runtime :supervisor :status] :stopped)
     :last-tick-at (get-in state [:runtime :last-tick-at])
     :last-error (get-in state [:runtime :last-error])
     :ticks (get-in state [:runtime :ticks] 0)
     :failures (get-in state [:runtime :failures] 0)
     :dead-letters (count (:dead-letters state))
     :persistence (work-partitions/status)
     :pending-writebacks
     (count (filter #(contains? #{:pending :failed :dead-letter}
                                (get-in % [:work.item/writeback :status]))
                    (vals (:work-items state))))}))

(defn put-role! [role]
  (let [id (:yakuwari/id role)]
    (when-not id
      (throw (ex-info "yakuwari id is required"
                      {:type :work-runtime/invalid-role})))
    ;; reconcile-plan performs the full yakuwari validation before use.
    (update-ledger! assoc-in [:roles id] role)
    role))

(defn put-organization!
  "Validate and persist one organization chart. Reporting edges remain
  structural data and are never converted into approval grants."
  [graph]
  (let [{:keys [ok? problems graph]} (governance/organization-graph graph)]
    (when-not ok?
      (throw (ex-info "organization graph is invalid"
                      {:type :work-runtime/invalid-organization
                       :problems problems})))
    (let [organization (:org/id graph)
          declared (set (map :org.role/id (:org/roles graph)))
          policies (->> (:approval-policies (ledger)) vals
                        (filter #(= organization
                                    (:approval.policy/organization %))))
          missing (set (mapcat #(set/difference
                                 (:approval.policy/eligible-roles %) declared)
                               policies))]
      (when (seq missing)
        (throw (ex-info
                "organization update would orphan approval policy roles"
                {:type :work-runtime/organization-policy-role-conflict
                 :organization organization :roles missing}))))
    (update-ledger!
     (fn [state]
       (let [organization (:org/id graph)]
         (-> state
           (assoc-in [:organizations (:org/id graph)]
                     (dissoc graph :org/units :org/positions :org/roles
                             :org/performers :org/assignments
                             :org/reporting-lines))
           ;; An organization graph PUT is replacement semantics. Otherwise a
           ;; performer removed in the editor would retain approval authority
           ;; invisibly in the ledger.
           (update :performers
                   #(into {} (remove (fn [[_ value]]
                                       (= organization
                                          (:performer/organization value)))) %))
           (update :assignments
                   #(into {} (remove (fn [[_ value]]
                                       (= organization
                                          (:org.assignment/organization value)))) %))
           (update :organization-units
                   #(into {} (remove (fn [[_ value]]
                                       (= organization
                                          (:org.unit/organization value)))) %))
           (update :positions
                   #(into {} (remove (fn [[_ value]]
                                       (= organization
                                          (:org.position/organization value)))) %))
           (update :organization-roles
                   #(into {} (remove (fn [[_ value]]
                                       (= organization
                                          (:org.role/organization value)))) %))
           (update :organization-units into
                   (map (juxt :org.unit/id identity) (:org/units graph)))
           (update :positions into
                   (map (juxt :org.position/id identity) (:org/positions graph)))
           (update :organization-roles into
                   (map (juxt :org.role/id identity) (:org/roles graph)))
           (update :performers into
                   (map (juxt :performer/id identity) (:org/performers graph)))
           (update :assignments into
                   (map (juxt :org.assignment/id identity)
                        (:org/assignments graph)))
           (update :reporting-lines
                   (fn [edges]
                     (->> (concat (remove #(= (:org/id graph)
                                              (:reporting/organization %))
                                          edges)
                                  (map #(assoc % :reporting/organization
                                               (:org/id graph))
                                       (:org/reporting-lines graph)))
                          vec)))
           (append-audit {:audit/type :organization/graph-updated
                          :audit/at (System/currentTimeMillis)
                          :audit/organization organization})))))
    graph))

(defn put-performer! [value]
  (let [value (governance/performer value)]
    (update-ledger! assoc-in [:performers (:performer/id value)] value)
    value))

(defn put-assignment! [value]
  (let [value (governance/assignment value)]
    (update-ledger! assoc-in [:assignments (:org.assignment/id value)] value)
    value))

(defn put-approval-policy! [value]
  (let [value (governance/approval-policy value)
        state (ledger)
        organization (:approval.policy/organization value)
        declared (->> (:organization-roles state) vals
                      (filter #(= organization (:org.role/organization %)))
                      (map :org.role/id) set)
        unknown (set/difference (:approval.policy/eligible-roles value) declared)]
    (when (and (seq declared) (seq unknown))
      (throw (ex-info "approval policy references undeclared organization roles"
                      {:type :work-runtime/unknown-approval-role
                       :organization organization :roles unknown})))
    (update-ledger! assoc-in [:approval-policies (:approval.policy/id value)] value)
    value))

(defn put-work-item! [value]
  (let [value (governance/work-item value)
        id (:work.item/id value)
        basis (get-in value [:work.item/source :basis])]
    (update-ledger!
     (fn [state]
       (cond-> (assoc-in state [:work-items id] value)
         basis (assoc-in [:source-bases id] basis))))
    value))

(defn ingest-work-item!
  "CAS-safe source ingestion. Once execution has begun, a changed board item
  is recorded as a conflict instead of overwriting the leased content/basis."
  [value now-ms]
  (let [value (governance/work-item value)
        id (:work.item/id value)
        basis (get-in value [:work.item/source :basis])
        answer (volatile! nil)]
    (update-ledger!
     (fn [state]
       (let [current (get-in state [:work-items id])
             unchanged? (= basis (get-in state [:source-bases id]))
             execution-started? (and current
                                     (or (:work.item/lease current)
                                         (:work.item/agent-run current)
                                         (contains? #{:running :held :review :done}
                                                    (:work.item/status current))))]
         (cond
           (nil? current)
           (do (vreset! answer {:status :created :work-item value})
               (-> state
                   (assoc-in [:work-items id] value)
                   (assoc-in [:source-bases id] basis)))

           unchanged?
           (do (vreset! answer {:status :unchanged :work-item current}) state)

           execution-started?
           (let [conflict {:status :conflict :at now-ms
                           :observed-basis basis
                           :retained-basis (get-in state [:source-bases id])}]
             (vreset! answer {:status :conflict :work-item current})
             (-> state
                 (assoc-in [:work-items id :work.item/source-sync] conflict)
                 (update :dead-letters conj
                         {:dead-letter/id (str "dead-source-" (UUID/randomUUID))
                          :dead-letter/kind :source-conflict
                          :dead-letter/work-item id
                          :dead-letter/at now-ms
                          :dead-letter/data conflict})))

           :else
           (do (vreset! answer {:status :updated :work-item value})
               (-> state
                   (assoc-in [:work-items id] value)
                   (assoc-in [:source-bases id] basis)))))))
    @answer))

(defn sync-github-sources!
  "Fetch one bounded page per configured source and checkpoint its cursor only
  after every item in that page has been durably handled."
  [configuration transport now-ms]
  (mapv
   (fn [source]
     (let [source-id (:id (github-source/source-config source))
           state (ledger)
           source-state (get-in state [:runtime :sources source-id])]
       (if (> (:next-at source-state 0) now-ms)
         {:source source-id :status :backoff
          :next-at (:next-at source-state)}
         (try
           (let [cursor (get-in state [:source-cursors source-id])
                 page (github-source/fetch-page transport source cursor)
                 results (mapv #(ingest-work-item! % now-ms) (:items page))]
             (update-ledger!
              (fn [current]
                (-> current
                    (assoc-in [:source-cursors source-id] (:cursor page))
                    (assoc-in [:runtime :sources source-id]
                              {:status :ok :at now-ms :attempt 0
                               :count (count results) :cursor (:cursor page)
                               :has-next? (:has-next? page)}))))
             {:source source-id :status :ok :cursor (:cursor page)
              :has-next? (:has-next? page) :results results})
           (catch Exception error
             (let [attempt (inc (:attempt source-state 0))
                   delay (min 300000
                              (* 5000 (bit-shift-left
                                       1 (min 6 (dec attempt)))))]
               (update-ledger!
                assoc-in [:runtime :sources source-id]
                {:status :failed :at now-ms :attempt attempt
                 :next-at (+ now-ms delay)
                 :type (or (:type (ex-data error))
                           :github-projects/source-error)
                 :message (.getMessage error)})
               {:source source-id :status :failed
                :next-at (+ now-ms delay)
                :error (.getMessage error)}))))))
   (get-in configuration [:work-governance :github-sources] [])))

(defn record-approval! [decision]
  (doseq [field [:approval.decision/id :approval.decision/work-item
                 :approval.decision/actor :approval.decision/content-hash
                 :approval.decision/decision]]
    (when (nil? (get decision field))
      (throw (ex-info (str field " is required")
                      {:type :work-runtime/invalid-approval :field field}))))
  (update-ledger!
   (fn [state]
     (-> state
         (update :approval-decisions
                 (fn [xs]
                   (->> (conj (vec xs) decision)
                        (reduce (fn [m d]
                                  (assoc m (:approval.decision/id d) d)) {})
                        vals vec)))
         (append-audit {:audit/type :approval/recorded
                        :audit/at (System/currentTimeMillis)
                        :audit/work-item
                        (:approval.decision/work-item decision)}))))
  decision)

(def verification-kinds #{:test :review :artifact})

(defn record-verification!
  "Append evidence for review -> done. Review evidence must be produced by a
  user-verifying ceremony; test/artifact evidence may come from an executor but
  remains bound to the same WorkItem, AgentRun and content hash."
  [value]
  (doseq [field [:verification.receipt/id :verification.receipt/work-item
                 :verification.receipt/agent-run
                 :verification.receipt/content-hash
                 :verification.receipt/kind
                 :verification.receipt/evidence-hash
                 :verification.receipt/verifier]]
    (when (nil? (get value field))
      (throw (ex-info (str field " is required")
                      {:type :work-runtime/invalid-verification
                       :field field}))))
  (when-not (verification-kinds (:verification.receipt/kind value))
    (throw (ex-info "unknown verification receipt kind"
                    {:type :work-runtime/invalid-verification-kind})))
  (when (and (= :review (:verification.receipt/kind value))
             (not (true? (:verification.receipt/user-verified? value))))
    (throw (ex-info "review verification requires a user-verified Person"
                    {:type :work-runtime/review-user-verification-required})))
  (let [item (get-in (ledger) [:work-items
                               (:verification.receipt/work-item value)])]
    (when-not (and item
                   (= (:work.item/content-hash item)
                      (:verification.receipt/content-hash value))
                   (= (:work.item/agent-run item)
                      (:verification.receipt/agent-run value)))
      (throw (ex-info "verification receipt does not match current work"
                      {:type :work-runtime/verification-mismatch}))))
  (update-ledger!
   (fn [state]
     (-> state
         (update :verification-receipts
                 (fn [receipts]
                   (if (some #(= (:verification.receipt/id value)
                                 (:verification.receipt/id %)) receipts)
                     (vec receipts)
                     (conj (vec receipts)
                           (assoc value :verification.receipt/schema schema)))))
         (append-audit {:audit/type :verification/recorded
                        :audit/at (System/currentTimeMillis)
                        :audit/work-item
                        (:verification.receipt/work-item value)}))))
  value)

(defn complete-work!
  "Advance review -> done only when every required verification kind is
  present for the current content and successful AgentRun."
  [item-id now-ms]
  (let [answer (volatile! nil)]
    (update-ledger!
     (fn [state]
       (let [item (get-in state [:work-items item-id])
             run-id (:work.item/agent-run item)
             execution (some #(when (and
                                      (= item-id
                                         (:execution.receipt/work-item %))
                                      (= run-id
                                         (:execution.receipt/agent-run %))
                                      (= :succeeded
                                         (:execution.receipt/status %))) %)
                             (reverse (:execution-receipts state)))
             required (set (or (get-in item
                                       [:work.item/verification-policy
                                        :required-kinds])
                               #{:test :review}))
             matching (filter #(and
                                (= item-id
                                   (:verification.receipt/work-item %))
                                (= run-id
                                   (:verification.receipt/agent-run %))
                                (= (:work.item/content-hash item)
                                   (:verification.receipt/content-hash %)))
                              (:verification-receipts state))
             present (set (map :verification.receipt/kind matching))]
         (when-not (= :review (:work.item/status item))
           (throw (ex-info "only review work can be completed"
                           {:type :work-runtime/not-in-review})))
         (when-not execution
           (throw (ex-info "successful execution receipt is required"
                           {:type :work-runtime/success-receipt-required})))
         (when-not (set/subset? required present)
           (throw (ex-info "required verification receipts are missing"
                           {:type :work-runtime/verification-required
                            :required required :present present})))
         (let [done (governance/transition-work
                     item :done now-ms
                     {:work.item/verification-receipts
                      (mapv :verification.receipt/id matching)
                      :work.item/writeback {:status :pending :at now-ms}})]
           (vreset! answer done)
           (-> state
               (assoc-in [:work-items item-id] done)
               (append-audit {:audit/type :work/completed
                              :audit/at now-ms
                              :audit/work-item item-id}))))))
    @answer))

(defn- live-lease? [lease now-ms]
  (and lease (> (or (:lease/expires-at lease) 0) now-ms)))

(defn lease!
  "CAS-like persistent lease. `expected-*` are the values seen by the planner;
  a card edited between planning and dispatch cannot be leased."
  [item-id owner expected-content-hash expected-source-basis now-ms lease-ms]
  (let [answer (volatile! nil)]
    (update-ledger!
     (fn [state]
       (let [item (get-in state [:work-items item-id])
             current-basis (get-in state [:source-bases item-id])
             lease (:work.item/lease item)]
         (cond
           (nil? item)
           (do (vreset! answer {:leased? false :reason :not-found}) state)

           (not (contains? #{:ready :held} (:work.item/status item)))
           (do (vreset! answer {:leased? false :reason :not-ready}) state)

           (live-lease? lease now-ms)
           (do (vreset! answer {:leased? false :reason :already-leased}) state)

           (not= expected-content-hash (:work.item/content-hash item))
           (do (vreset! answer {:leased? false :reason :stale-content}) state)

           (not= expected-source-basis current-basis)
           (do (vreset! answer {:leased? false :reason :stale-source-basis}) state)

           :else
           (let [fencing-token (inc (get-in state [:runtime :fencing-sequence] 0))
                 lease-id (str "lease-" (UUID/randomUUID))
                 lease {:lease/id lease-id
                        :lease/owner owner
                        :lease/fencing-token fencing-token
                        :lease/acquired-at now-ms
                        :lease/expires-at (+ now-ms lease-ms)
                        :lease/content-hash expected-content-hash
                        :lease/source-basis expected-source-basis}
                 leased (governance/transition-work
                         item :leased now-ms {:work.item/lease lease})]
             (vreset! answer {:leased? true :lease lease :work-item leased})
             (-> state
                 (assoc-in [:runtime :fencing-sequence] fencing-token)
                 (assoc-in [:work-items item-id] leased)
                 (append-audit {:audit/type :lease/acquired
                                :audit/at now-ms :audit/work-item item-id
                                :audit/lease lease-id
                                :audit/fencing-token fencing-token})))))))
    @answer))

(defn renew-lease!
  "Extend only the currently fenced lease. A stale worker cannot renew a lease
  after another worker has acquired a higher fencing token."
  [item-id lease-id fencing-token now-ms lease-ms]
  (let [answer (volatile! false)]
    (update-ledger!
     (fn [state]
       (let [path [:work-items item-id :work.item/lease]
             lease (get-in state path)]
         (if (and (= lease-id (:lease/id lease))
                  (= fencing-token (:lease/fencing-token lease))
                  (contains? #{:leased :running :held}
                             (get-in state [:work-items item-id
                                            :work.item/status])))
           (do (vreset! answer true)
               (assoc-in state (conj path :lease/expires-at)
                         (+ now-ms lease-ms)))
           state))))
    @answer))

(defn- prepare-dispatch!
  "Persist the deterministic Run ID before crossing the executor boundary."
  [item-id lease now-ms]
  (let [answer (volatile! nil)
        run-id (str "run-" (subs (:lease/id lease) (count "lease-")))]
    (update-ledger!
     (fn [state]
       (let [item (get-in state [:work-items item-id])
             current (:work.item/lease item)]
         (if-not (and (= (:lease/id lease) (:lease/id current))
                      (= (:lease/fencing-token lease)
                         (:lease/fencing-token current)))
           (do (vreset! answer {:prepared? false :reason :stale-fence}) state)
           (let [record (or (:work.item/dispatch-record item)
                            {:dispatch/id (:lease/id lease)
                             :dispatch/agent-run run-id
                             :dispatch/fencing-token
                             (:lease/fencing-token lease)
                             :dispatch/status :planned
                             :dispatch/planned-at now-ms})
                 updated (assoc item :work.item/agent-run
                                (:dispatch/agent-run record)
                                :work.item/dispatch-record record)]
             (vreset! answer {:prepared? true :work-item updated
                              :agent-run (:dispatch/agent-run record)})
             (assoc-in state [:work-items item-id] updated))))))
    @answer))

(defn release-expired-leases! [now-ms]
  (let [released (volatile! [])]
    (update-ledger!
     (fn [state]
       (update state :work-items
               (fn [items]
                 (into {}
                       (map (fn [[id item]]
                              (let [lease (:work.item/lease item)]
                                (cond
                                  (and (= :leased (:work.item/status item))
                                       lease (not (live-lease? lease now-ms)))
                                  (do (vswap! released conj id)
                                      [id (governance/transition-work
                                           item :ready now-ms
                                           {:work.item/lease nil
                                            :work.item/agent-run nil
                                            :work.item/dispatch-record nil
                                            :work.item/recovery :lease-expired})])

                                  (and (contains? #{:running :held}
                                                  (:work.item/status item))
                                       lease (not (live-lease? lease now-ms)))
                                  (let [held (if (= :running
                                                    (:work.item/status item))
                                               (governance/transition-work
                                                item :held now-ms {}) item)]
                                    (vswap! released conj id)
                                    [id (assoc held
                                               :work.item/lease nil
                                               :work.item/recovery
                                               :orphaned-run)])

                                  :else [id item]))))
                       items)))))
    @released))

(defn renew-active-leases! [now-ms lease-ms]
  (let [snapshot (store/snapshot)]
    (->> (vals (get-in snapshot [:work-governance :work-items] {}))
         (keep (fn [item]
                 (let [lease (:work.item/lease item)
                       run (get-in snapshot [:agent-control :runs
                                             (:work.item/agent-run item)])]
                   (when (and lease (active-run-statuses
                                     (:agent.run/status run))
                              (renew-lease! (:work.item/id item)
                                            (:lease/id lease)
                                            (:lease/fencing-token lease)
                                            now-ms lease-ms))
                     (:work.item/id item)))))
         vec)))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn payload-hash [value]
  (str "sha256:"
       (hex (.digest (MessageDigest/getInstance "SHA-256")
                     (.getBytes (pr-str value) StandardCharsets/UTF_8)))))

(defn receipt
  [item run lease now-ms]
  (let [evidence-kinds (cond-> #{:agent-run}
                         (not (str/blank? (str (:agent/result run))))
                         (conj :result)
                         (:agent/error run) (conj :error)
                         (seq (:agent/test-results run)) (conj :test))]
    (let [base {:execution.receipt/schema schema
                :execution.receipt/id (str "receipt-" (UUID/randomUUID))
                :execution.receipt/work-item (:work.item/id item)
                :execution.receipt/agent-run (:agent.run/id run)
                :execution.receipt/status (:agent.run/status run)
                :execution.receipt/evidence-kinds evidence-kinds
                :execution.receipt/executor-provenance
                (select-keys run [:agent.run/actor :agent/executor
                                  :agent/provider-id :agent/model
                                  :agent.run/yakuwari :agent.run/work-item])
                :execution.receipt/content-hash (:lease/content-hash lease)
                :execution.receipt/source-basis (:lease/source-basis lease)
                :execution.receipt/lease (:lease/id lease)
                :execution.receipt/fencing-token (:lease/fencing-token lease)
                :execution.receipt/payload-hash (payload-hash run)
                :execution.receipt/recorded-at now-ms}
          signature (receipt-signature/sign base)]
      (cond-> base
        signature (assoc :execution.receipt/signature signature)))))

(defn valid-receipt?
  "A receipt is usable only for the exact item, lease, content and source basis
  that were admitted."
  ([item value]
   (let [lease (:work.item/lease item)]
     (and (= schema (:execution.receipt/schema value))
          (= (:work.item/id item) (:execution.receipt/work-item value))
          (= (:work.item/content-hash item)
             (:execution.receipt/content-hash value)
             (:lease/content-hash lease))
          (= (:lease/id lease) (:execution.receipt/lease value))
          (= (:lease/fencing-token lease)
             (:execution.receipt/fencing-token value))
          (= (:lease/source-basis lease)
             (:execution.receipt/source-basis value))
          (not (str/blank? (:execution.receipt/agent-run value)))
          (not (str/blank? (:execution.receipt/payload-hash value))))))
  ([item run value]
   (and (valid-receipt? item value)
        (= (:agent.run/id run) (:execution.receipt/agent-run value))
        (= (:agent.run/status run) (:execution.receipt/status value))
        (= (payload-hash run) (:execution.receipt/payload-hash value)))))

(defn- advance-for-run [item run now-ms receipt-value]
  (let [status (:agent.run/status run)
        attrs {:work.item/agent-run (:agent.run/id run)
               :work.item/latest-receipt (:execution.receipt/id receipt-value)}]
    (case status
      (:queued :leased :running :checkpointed)
      (if (= :leased (:work.item/status item))
        (governance/transition-work item :running now-ms attrs)
        (merge item attrs {:work.item/updated-at now-ms}))

      :held
      (let [running (if (= :leased (:work.item/status item))
                      (governance/transition-work item :running now-ms attrs)
                      (merge item attrs))]
        (if (= :held (:work.item/status running))
          running
          (governance/transition-work running :held now-ms attrs)))

      :succeeded
      (let [running (if (contains? #{:leased :held}
                                   (:work.item/status item))
                      (governance/transition-work item :running now-ms attrs)
                      item)]
        (if (= :review (:work.item/status running))
          (merge running attrs)
          (governance/transition-work running :review now-ms attrs)))

      :failed (governance/transition-work item :failed now-ms attrs)
      :rejected (governance/transition-work item :rejected now-ms attrs)
      :cancelled (governance/transition-work item :cancelled now-ms attrs)
      (merge item attrs))))

(defn apply-run-receipt!
  "Persist one AgentRun observation and its WorkItem transition atomically."
  [item-id run now-ms]
  (let [answer (volatile! nil)]
    (update-ledger!
     (fn [state]
       (let [item (get-in state [:work-items item-id])
             lease (:work.item/lease item)
             value (receipt item run lease now-ms)]
         (when-not (valid-receipt? item run value)
           (throw (ex-info "AgentRun receipt does not match its WorkItem lease"
                           {:type :work-runtime/invalid-receipt
                            :work-item item-id})))
         (let [updated (advance-for-run item run now-ms value)
               duplicate? (some #(and (= item-id (:execution.receipt/work-item %))
                                      (= (:agent.run/id run)
                                         (:execution.receipt/agent-run %))
                                      (= (:agent.run/status run)
                                         (:execution.receipt/status %)))
                                (:execution-receipts state))
               state (-> state
                         (assoc-in [:work-items item-id] updated)
                         (cond-> (not duplicate?)
                           (update :execution-receipts conj value))
                         (append-audit {:audit/type :execution/observed
                                        :audit/at now-ms
                                        :audit/work-item item-id
                                        :audit/agent-run (:agent.run/id run)
                                        :audit/status
                                        (:agent.run/status run)}))]
           (vreset! answer {:work-item updated :receipt value
                            :recorded? (not duplicate?)})
           state))))
    @answer))

(defn- policies-by-scope [state]
  (into {}
        (map (fn [policy]
               [[(:approval.policy/organization policy)
                 (:approval.policy/capability policy)] policy]))
        (vals (:approval-policies state))))

(defn- run-records [snapshot]
  (vec (vals (get-in snapshot [:agent-control :runs] {}))))

(defn- default-dispatch [configuration item]
  (if (= :organism-worker (:work.item/executor item))
    (organism-dispatch/dispatch configuration item)
    (agent-control/create-run!
     configuration
     (merge {:id (get-in item [:work.item/dispatch-record
                               :dispatch/agent-run])
             :goal (:work.item/title item)
             :yakuwari (:work.item/yakuwari item)
             :work-item (:work.item/id item)}
            (:work.item/dispatch item))
     (or (:work.item/worker item) "work-governance-reconciler"))))

(defn- record-dispatch-status! [item-id status now-ms attrs]
  (update-ledger!
   update-in [:work-items item-id :work.item/dispatch-record]
   merge attrs {:dispatch/status status :dispatch/updated-at now-ms}))

(defn- heartbeat-executor [item lease-ms]
  (let [lease (:work.item/lease item)
        interval (long (max 1000 (quot lease-ms 3)))
        executor (Executors/newSingleThreadScheduledExecutor
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. runnable "cloud-itonami-work-heartbeat")
                        (.setDaemon true)))))]
    (.scheduleWithFixedDelay
     ^ScheduledExecutorService executor
     ^Runnable #(renew-lease! (:work.item/id item) (:lease/id lease)
                              (:lease/fencing-token lease)
                              (System/currentTimeMillis) lease-ms)
     interval interval TimeUnit/MILLISECONDS)
    executor))

(defn- dispatch-prepared!
  [configuration dispatch item now-ms]
  (let [item-id (:work.item/id item)
        run-id (get-in item [:work.item/dispatch-record :dispatch/agent-run])
        lease-ms (or (get-in configuration [:work-governance :lease-ms]) 120000)
        ^ScheduledExecutorService heartbeat (heartbeat-executor item lease-ms)]
    (try
      (let [run (dispatch configuration item)
            _ (when-not (= run-id (:agent.run/id run))
                (throw (ex-info "executor returned a different AgentRun id"
                                {:type :work-runtime/dispatch-id-mismatch
                                 :expected run-id
                                 :actual (:agent.run/id run)})))
            result (apply-run-receipt! item-id run now-ms)]
        (record-dispatch-status! item-id :observed now-ms
                                 {:dispatch/run-status (:agent.run/status run)})
        {:status :dispatched :run run :result result})
      (catch Exception error
        (let [failed (agent-control/record-dispatch-failure!
                      run-id
                      {:goal (:work.item/title item)
                       :yakuwari (:work.item/yakuwari item)
                       :work-item item-id
                       :actor (or (:work.item/worker item)
                                  "work-governance-reconciler")}
                      error)
              result (apply-run-receipt! item-id failed now-ms)]
          (record-dispatch-status! item-id :failed now-ms
                                   {:dispatch/run-status :failed
                                    :dispatch/error (.getMessage error)})
          {:status :dispatch-failed :run failed :result result
           :error error}))
      (finally (.shutdownNow heartbeat)))))

(defn- recover-prepared-dispatches!
  [configuration dispatch now-ms]
  (->> (vals (:work-items (ledger)))
       (keep (fn [item]
               (let [record (:work.item/dispatch-record item)
                     run-id (:dispatch/agent-run record)]
                 (when (and (= :planned (:dispatch/status record))
                            (contains? #{:leased :running}
                                       (:work.item/status item)))
                   (if-let [run (agent-control/run-by-id run-id)]
                     (let [result (apply-run-receipt!
                                   (:work.item/id item) run now-ms)]
                       (record-dispatch-status! (:work.item/id item) :observed
                                                now-ms
                                                {:dispatch/run-status
                                                 (:agent.run/status run)})
                       {:status :recovered :run run :result result})
                     (dispatch-prepared! configuration dispatch item
                                         now-ms))))))
       vec))

(defn- remember-writeback! [item-id status new-basis now-ms]
  (update-ledger!
   (fn [state]
     (let [receipt {:projection.receipt/id
                    (str "projection-" (UUID/randomUUID))
                    :projection.receipt/work-item item-id
                    :projection.receipt/status :verified
                    :projection.receipt/work-status status
                    :projection.receipt/basis new-basis
                    :projection.receipt/at now-ms}]
       (-> state
           (assoc-in [:source-bases item-id] new-basis)
           (assoc-in [:work-items item-id :work.item/source :basis] new-basis)
           (assoc-in [:work-items item-id :work.item/writeback]
                     {:status :written :work-status status :at now-ms
                      :receipt (:projection.receipt/id receipt)})
           (update :projection-receipts conj receipt)
           (append-audit {:audit/type :projection/written
                          :audit/at now-ms :audit/work-item item-id
                          :audit/status status}))))))

(defn- mark-writeback-failed! [item-id error now-ms]
  (update-ledger!
   (fn [state]
     (let [attempt (inc (get-in state [:work-items item-id
                                       :work.item/writeback :attempt] 0))
           delay-ms (min 300000 (* 5000 (bit-shift-left 1 (min 6 (dec attempt)))))
           terminal? (>= attempt 8)
           failure {:status (if terminal? :dead-letter :failed)
                    :at now-ms :attempt attempt
                    :next-at (when-not terminal? (+ now-ms delay-ms))
                    :type (or (:type (ex-data error))
                              :github-projects/writeback-error)
                    :message (.getMessage error)}
           state (assoc-in state [:work-items item-id :work.item/writeback]
                           failure)]
       (cond-> (append-audit state
                             {:audit/type :projection/failed
                              :audit/at now-ms :audit/work-item item-id
                              :audit/error (:type failure)})
         terminal?
         (update :dead-letters conj
                 {:dead-letter/id (str "dead-projection-" (UUID/randomUUID))
                  :dead-letter/kind :github-writeback
                  :dead-letter/work-item item-id
                  :dead-letter/at now-ms
                  :dead-letter/data failure}))))))

(defn replay-dead-letter! [dead-letter-id now-ms]
  (let [answer (volatile! nil)]
    (update-ledger!
     (fn [state]
       (if-let [letter (some #(when (= dead-letter-id (:dead-letter/id %)) %)
                            (:dead-letters state))]
         (let [item-id (:dead-letter/work-item letter)
               updated (mapv #(if (= dead-letter-id (:dead-letter/id %))
                                (assoc % :dead-letter/replayed-at now-ms)
                                %)
                             (:dead-letters state))]
           (vreset! answer {:status :scheduled :work-item item-id})
           (-> state
               (assoc :dead-letters updated)
               (assoc-in [:work-items item-id :work.item/writeback]
                         {:status :failed :attempt 0 :next-at now-ms
                          :at now-ms :type :manual-replay})
               (append-audit {:audit/type :dead-letter/replayed
                              :audit/at now-ms :audit/work-item item-id})))
         (do (vreset! answer {:status :not-found}) state))))
    @answer))

(defn- writeback-eligible!
  "Re-evaluate terminality, receipt evidence, organizational approval and the
  explicit GitHub capability immediately before mutation."
  [configuration item run receipt-value]
  (let [state (ledger)
        current (get-in state [:work-items (:work.item/id item)])
        role (get-in state [:roles (:work.item/yakuwari current)])
        policy (get (policies-by-scope state)
                    [(:work.item/organization current)
                     (:work.item/capability current)])
        route (when role
                (governance/route-item
                 role current policy (vec (vals (:performers state)))
                 (vec (vals (:assignments state)))
                 (:approval-decisions state)))
        write-capability (get-in current
                                 [:work.item/source :write-capability])
        granted (set (get-in configuration
                             [:work-governance :github-write-capabilities]))]
    (when-not (terminal-run-statuses (:agent.run/status run))
      (throw (ex-info "GitHub write-back requires a terminal AgentRun"
                      {:type :work-runtime/nonterminal-writeback})))
    (when-not (valid-receipt? current run receipt-value)
      (throw (ex-info "GitHub write-back requires a verified execution receipt"
                      {:type :work-runtime/writeback-without-receipt})))
    (when (get-in configuration [:work-governance
                                 :receipt-signature-required?] true)
      (let [signature (:execution.receipt/signature receipt-value)
            unsigned (dissoc receipt-value :execution.receipt/signature)]
        (when-not (and signature
                       (receipt-signature/verify? unsigned signature))
          (throw (ex-info "execution receipt signature is required"
                          {:type :work-runtime/receipt-signature-required})))))
    (when-not (or (and (= :succeeded (:agent.run/status run))
                       (contains? (:execution.receipt/evidence-kinds
                                   receipt-value) :result))
                  (and (contains? #{:failed :rejected :cancelled}
                                  (:agent.run/status run))
                       (seq (set/intersection
                             #{:error :result}
                             (:execution.receipt/evidence-kinds
                              receipt-value)))))
      (throw (ex-info "terminal AgentRun has no required result evidence"
                      {:type :work-runtime/result-receipt-required})))
    (when-not (contains? #{:dispatch :notify-and-dispatch} (:action route))
      (throw (ex-info "approval policy is not satisfied at write-back time"
                      {:type :work-runtime/writeback-approval-required
                       :route route})))
    (when-not (and write-capability (contains? granted write-capability))
      (throw (ex-info "GitHub write capability is not explicitly granted"
                      {:type :work-runtime/github-capability-required
                       :capability write-capability})))
    true))

(defn- writeback! [configuration item run receipt-value transport now-ms]
  (when (and (= :github-projects-v2
                (get-in item [:work.item/source :kind]))
             (get-in configuration [:work-governance
                                    :github-writeback-enabled?]))
    (try
      (let [_ (writeback-eligible! configuration item run receipt-value)
            status (:work.item/status item)
            new-basis (github/write-status! configuration
                                            (:work.item/source item)
                                            status transport)]
        (remember-writeback! (:work.item/id item) status new-basis now-ms)
        new-basis)
      (catch Exception error
        ;; Execution and source projection are separate facts. A stale board
        ;; basis must never rewrite a successful AgentRun as an execution
        ;; failure; retain the failed projection for a later supervised retry.
        (mark-writeback-failed! (:work.item/id item) error now-ms)
        nil))))

(defn- sync-linked-runs! [configuration transport now-ms]
  (let [snapshot (store/snapshot)
        items (vals (get-in snapshot [:work-governance :work-items] {}))]
    (reduce
     (fn [results item]
       (if-let [run-id (:work.item/agent-run item)]
         (let [_ (when (= :organism-worker (:work.item/executor item))
                   (try (organism-dispatch/observe! item)
                        (catch Exception _ nil)))]
           (if-let [run (agent-control/run-by-id run-id)]
           (if (= (:work.item/observed-run-status item)
                  (:agent.run/status run))
             (let [projection (:work.item/writeback item)]
               (if (or (= :pending (:status projection))
                       (and (= :failed (:status projection))
                            (<= (:next-at projection Long/MAX_VALUE) now-ms)))
                 (if-let [receipt-value
                          (some #(when (and
                                        (= (:work.item/id item)
                                           (:execution.receipt/work-item %))
                                        (= run-id (:execution.receipt/agent-run %))
                                        (= (:agent.run/status run)
                                           (:execution.receipt/status %))) %)
                                (reverse (get-in snapshot
                                                 [:work-governance
                                                  :execution-receipts])))]
                   (do (writeback! configuration item run receipt-value
                                   transport now-ms)
                       (conj results {:work-item item :receipt receipt-value
                                      :writeback-retry? true}))
                   results)
                 results))
             (let [{updated :work-item receipt-value :receipt :as result}
                   (apply-run-receipt! (:work.item/id item) run now-ms)]
               (update-ledger! assoc-in
                               [:work-items (:work.item/id item)
                                :work.item/observed-run-status]
                               (:agent.run/status run))
               (writeback! configuration updated run receipt-value transport
                           now-ms)
               (conj results result)))
             results))
         results))
     [] items)))

(defn reconcile-once!
  "Execute one finite reconcile tick. Options are injection seams for tests;
  production uses Agent Control and GitHub GraphQL."
  ([configuration] (reconcile-once! configuration {}))
  ([configuration {:keys [now-ms owner dispatch transport]
                   :or {now-ms (System/currentTimeMillis)
                        owner "work-governance-reconciler"
                        dispatch default-dispatch
                        transport github/github-transport}}]
   (if-not (get-in configuration [:work-governance :enabled?])
     {:status :disabled :dispatched []}
     (let [lease-ms (or (get-in configuration [:work-governance :lease-ms])
                        120000)
           sources (sync-github-sources! configuration transport now-ms)
           renewed (renew-active-leases! now-ms lease-ms)
           expired (release-expired-leases! now-ms)
           recovered (recover-prepared-dispatches! configuration dispatch now-ms)
           observed (sync-linked-runs! configuration transport now-ms)
           snapshot (store/snapshot)
           state (merge (empty-ledger) (:work-governance snapshot))
           performers (vec (vals (:performers state)))
           assignments (vec (vals (:assignments state)))
           all-runs (run-records snapshot)
           plans
           (mapv (fn [role]
                   (let [items (->> (vals (:work-items state))
                                    (remove #(= :orphaned-run
                                                (:work.item/recovery %)))
                                    (remove #(and (:work.item/agent-run %)
                                                  (contains? active-run-statuses
                                                             (:work.item/observed-run-status %))))
                                    vec)]
                     (governance/reconcile-plan
                      {:role role :items items :runs all-runs
                       :approval-policies (policies-by-scope state)
                       :performers performers :assignments assignments
                       :decisions (:approval-decisions state) :now-ms now-ms})))
                 (vals (:roles state)))
           actions (mapcat :actions plans)
           dispatched
           (reduce
            (fn [results action]
              (if-not (contains? #{:dispatch :notify-and-dispatch}
                                 (:action action))
                results
                (let [id (:work-item action)
                      planned (get-in state [:work-items id])
                      lease-result (lease! id owner
                                           (:work.item/content-hash planned)
                                           (get-in state [:source-bases id])
                                           now-ms lease-ms)]
                  (if-not (:leased? lease-result)
                    (conj results {:work-item id :status :lease-refused
                                   :reason (:reason lease-result)})
                    (let [prepared (prepare-dispatch!
                                    id (:lease lease-result) now-ms)]
                      (if-not (:prepared? prepared)
                        (conj results {:work-item id :status :dispatch-refused
                                       :reason (:reason prepared)})
                        (let [{:keys [status run result error]}
                              (dispatch-prepared! configuration dispatch
                                                  (:work-item prepared) now-ms)]
                          (update-ledger!
                           assoc-in [:work-items id :work.item/observed-run-status]
                           (:agent.run/status run))
                          (writeback! configuration (:work-item result) run
                                      (:receipt result) transport now-ms)
                          (conj results
                                (cond-> {:work-item id :status status
                                         :agent-run (:agent.run/id run)
                                         :run-status (:agent.run/status run)}
                                  error (assoc :error (.getMessage error)))))))))))
            [] actions)]
       (update-ledger!
        (fn [s]
          (-> s
              (update-in [:runtime :ticks] (fnil inc 0))
              (assoc-in [:runtime :last-tick-at] now-ms)
              (assoc-in [:runtime :last-result]
                        {:sources (count sources) :renewed (count renewed)
                         :expired expired
                         :recovered (count recovered)
                         :observed (count observed)
                         :dispatched dispatched}))))
       {:status :ok :sources sources :renewed renewed :expired expired
        :recovered recovered
        :observed observed
        :plans plans :dispatched dispatched}))))
