(ns cloud.itonami.app.tenant-connection
  "Tenant-bound leases for agent loops.

  A session's active organization is mutable UI state and therefore not a safe
  loop context. A tenant connection instead binds one requesting agent session,
  one membership and an explicit capability set until approval, expiry or
  revocation. The connection id is a handle, not a credential: every operation
  must still present the agent session that requested it."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store])
  (:import [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.tenant-connections.v1")
(def min-ttl-seconds 60)
(def max-ttl-seconds (* 24 60 60))
(def default-ttl-seconds 3600)

(def allowed-capabilities
  #{"tenant.read" "workspace.read" "workspace.write" "chat.read"
    "chat.write" "actor.invoke" "repository.query" "repository.write"})

(defn- identity-state []
  (merge {:organizations {} :memberships {} :tenant-connections {}}
         (:identity (store/snapshot))))

(defn- memberships-for [state user-id]
  (filter #(= user-id (:user-id %)) (vals (:memberships state))))

(defn- organization-for [state membership]
  (get-in state [:organizations (:organization-id membership)]))

(defn- resolve-membership [state session tenant-id]
  (some (fn [membership]
          (let [organization (organization-for state membership)]
            (when (or (= tenant-id (:id organization))
                      (= (some-> tenant-id str str/lower-case)
                         (:organization-id organization)))
              membership)))
        (memberships-for state (:user-id session))))

(defn- require-membership [state session tenant-id]
  (or (resolve-membership state session tenant-id)
      (throw (ex-info "このTenantへのmembershipがありません。"
                      {:type :tenant-connection/forbidden}))))

(defn- positive-long [value default-value]
  (cond
    (nil? value) default-value
    (integer? value) (long value)
    (string? value) (or (parse-long value) -1)
    :else -1))

(defn- validate-ttl [value]
  (let [ttl (positive-long value default-ttl-seconds)]
    (when-not (<= min-ttl-seconds ttl max-ttl-seconds)
      (throw (ex-info "ttl_secondsは60から86400の範囲で指定してください。"
                      {:type :tenant-connection/invalid-ttl})))
    ttl))

(defn- validate-capabilities [values]
  (let [capabilities (->> values (map str) distinct sort vec)
        unknown (remove allowed-capabilities capabilities)]
    (when (empty? capabilities)
      (throw (ex-info "capabilitiesを1つ以上指定してください。"
                      {:type :tenant-connection/invalid-capability})))
    (when (seq unknown)
      (throw (ex-info "許可されていないcapabilityです。"
                      {:type :tenant-connection/invalid-capability})))
    capabilities))

(defn- validate-budget [budget]
  (let [operations (positive-long (or (:max-operations budget)
                                      (:max_operations budget)) 100)
        storage (positive-long (or (:max-storage-bytes budget)
                                   (:max_storage_bytes budget))
                               (* 10 1024 1024))]
    (when-not (<= 1 operations 100000)
      (throw (ex-info "max_operationsが範囲外です。"
                      {:type :tenant-connection/invalid-budget})))
    (when-not (<= 0 storage (* 1024 1024 1024))
      (throw (ex-info "max_storage_bytesが範囲外です。"
                      {:type :tenant-connection/invalid-budget})))
    {:max-operations operations :max-storage-bytes storage}))

(defn- public-tenant [organization membership]
  {:id (:id organization)
   :organization-id (:organization-id organization)
   :did (:did organization)
   :name (:name organization)
   :domain (:domain organization)
   :role (:role membership)})

(defn tenants [session]
  (identity/require-passkey! session)
  (let [state (identity-state)]
    {:schema "cloud.itonami.app.tenants.v1"
     :tenants (->> (memberships-for state (:user-id session))
                   (map #(public-tenant (organization-for state %) %))
                   (sort-by (juxt :organization-id :id))
                   vec)}))

(defn- public-connection [connection]
  (select-keys connection
               [:id :tenant-id :tenant-organization-id :tenant-did :agent-id
                :capabilities :budget :operations-used :status :created-at
                :approved-at :expires-at :revoked-at :renewal-requested-at
                :requested-ttl-seconds :repository-stream
                :storage-used-bytes :workspace-bytes :published-bytes]))

(defn connections [session]
  (identity/require-passkey! session)
  (let [state (identity-state)]
    {:schema schema
     :connections (->> (:tenant-connections state)
                       vals
                       (filter #(= (:user-id session) (:user-id %)))
                       (sort-by (juxt :created-at :id))
                       (mapv public-connection))}))

(defn request!
  [session request]
  (identity/require-passkey! session)
  (let [tenant-id (or (:tenant-id request) (:tenant_id request))
        agent-id (or (:agent-id request) (:agent_id request))
        capabilities (:capabilities request)
        ttl-seconds (or (:ttl-seconds request) (:ttl_seconds request))
        budget (:budget request)
        idempotency-key (some-> (or (:idempotency-key request)
                                    (:idempotency_key request))
                                str str/trim not-empty)]
    (when (or (nil? tenant-id) (str/blank? (str tenant-id)))
      (throw (ex-info "tenant_idが必要です。"
                      {:type :tenant-connection/tenant-required})))
    (let [capabilities (validate-capabilities capabilities)
          ttl (validate-ttl ttl-seconds)
          budget (validate-budget budget)
          id (str "tc-" (UUID/randomUUID))
          now (store/now)
          result (volatile! nil)]
      ;; Duplicate detection and insertion share one transaction so concurrent
      ;; retries with the same key cannot mint multiple handles.
      (store/transact!
       (fn [root]
         (let [state (merge {:organizations {} :memberships {}
                             :tenant-connections {}}
                            (:identity root))
               duplicate (when idempotency-key
                           (some #(when (and (= (:user-id session) (:user-id %))
                                             (= (:id session)
                                                (:requesting-session-id %))
                                             (= idempotency-key
                                                (:idempotency-key %)))
                                    %)
                                 (vals (:tenant-connections state))))]
           (if duplicate
             (do (vreset! result duplicate) root)
             (let [membership (require-membership state session tenant-id)
                   organization (organization-for state membership)
                   connection
                   {:id id :user-id (:user-id session)
                    :requesting-session-id (:id session)
                    :membership-id (:id membership)
                    :tenant-id (:id organization)
                    :tenant-organization-id (:organization-id organization)
                    :tenant-did (:did organization)
                    :agent-id (or (not-empty (some-> agent-id str str/trim))
                                  (:label session) (:id session))
                    :capabilities capabilities :budget budget
                    :operations-used 0 :storage-used-bytes 0
                    :workspace-bytes 0 :published-bytes 0
                    :accounted-publications {}
                    :requested-ttl-seconds ttl
                    :idempotency-key idempotency-key
                    :repository-stream (str "tenant/" (:id organization)
                                            "/agent/" (:id session))
                    :status :pending-approval :created-at now}]
               (vreset! result connection)
               (assoc-in root [:identity :tenant-connections id]
                         connection))))))
      (public-connection @result))))

(defn- require-visible [state session connection-id]
  (let [connection (get-in state [:tenant-connections connection-id])]
    (when-not (and connection (= (:user-id session) (:user-id connection)))
      (throw (ex-info "tenant connectionが見つかりません。"
                      {:type :tenant-connection/not-found})))
    connection))

(defn connection [session connection-id]
  (identity/require-passkey! session)
  (public-connection (require-visible (identity-state) session connection-id)))

(defn approve! [session connection-id]
  (when-not (identity/human-session? session)
    (throw (ex-info "tenant connectionの承認にはPasskey sessionが必要です。"
                    {:type :tenant-connection/human-approval-required})))
  (identity/require-passkey! session)
  (let [result (volatile! nil)]
    (store/transact!
     (fn [root]
       (let [state (merge {:organizations {} :memberships {}
                           :tenant-connections {}}
                          (:identity root))
             connection (require-visible state session connection-id)
             _ (require-membership state session (:tenant-id connection))
             _ (when-not (or (= :pending-approval (:status connection))
                             (and (= :active (:status connection))
                                  (:renewal-requested-at connection)))
                 (throw (ex-info "このtenant connectionは承認待ちではありません。"
                                 {:type :tenant-connection/invalid-state})))
             now (Instant/now)
             expires (.plusSeconds now (:requested-ttl-seconds connection))
             updated (-> connection
                         (assoc :status :active :approved-at (str now)
                                :expires-at (str expires))
                         (dissoc :renewal-requested-at))]
         (vreset! result updated)
         (assoc-in root [:identity :tenant-connections connection-id] updated))))
    (public-connection @result)))

(defn- require-requesting-agent [session connection]
  (when-not (or (identity/human-session? session)
                (= (:id session) (:requesting-session-id connection)))
    (throw (ex-info "このagent sessionのtenant connectionではありません。"
                    {:type :tenant-connection/forbidden})))
  connection)

(defn request-renewal! [session connection-id ttl-seconds]
  (identity/require-passkey! session)
  (let [ttl (validate-ttl ttl-seconds)
        result (volatile! nil)]
    (store/transact!
     (fn [root]
       (let [state (merge {:organizations {} :memberships {}
                           :tenant-connections {}}
                          (:identity root))
             connection (->> (require-visible state session connection-id)
                             (require-requesting-agent session))
             _ (when-not (= :active (:status connection))
                 (throw (ex-info "activeなtenant connectionだけrenewできます。"
                                 {:type :tenant-connection/invalid-state})))
             _ (when-not (.isAfter (Instant/parse (:expires-at connection))
                                   (Instant/now))
                 (throw (ex-info "期限切れconnectionはrenewできません。"
                                 {:type :tenant-connection/expired})))
             updated (assoc connection :requested-ttl-seconds ttl
                            :renewal-requested-at (store/now))]
         (vreset! result updated)
         (assoc-in root [:identity :tenant-connections connection-id] updated))))
    (public-connection @result)))

(defn revoke! [session connection-id]
  (identity/require-passkey! session)
  (let [result (volatile! nil)]
    (store/transact!
     (fn [root]
       (let [state (merge {:organizations {} :memberships {}
                           :tenant-connections {}}
                          (:identity root))
             connection (->> (require-visible state session connection-id)
                             (require-requesting-agent session))
             updated (assoc connection :status :revoked
                            :revoked-at (store/now))]
         (vreset! result updated)
         (assoc-in root [:identity :tenant-connections connection-id] updated))))
    (public-connection @result)))

(defn context!
  "Resolve an immutable loop context and consume one operation.

  `:storage-bytes` replaces local workspace capacity. A publication supplies
  `:published-byte-delta` and stable `:publication-id`; retrying the same sealed
  transaction is storage-idempotent. Validation and counters move in one store
  transaction."
  ([session connection-id capability]
   (context! session connection-id capability nil))
  ([session connection-id capability
    {:keys [storage-bytes published-byte-delta publication-id]}]
   (identity/require-passkey! session)
   (let [chosen (volatile! nil)
         capability (str capability)
         storage-bytes (when (some? storage-bytes) (long storage-bytes))
         published-byte-delta (when (some? published-byte-delta)
                                (long published-byte-delta))]
     (when (or (and storage-bytes (neg? storage-bytes))
               (and published-byte-delta (neg? published-byte-delta))
               (and published-byte-delta (str/blank? (str publication-id))))
       (throw (ex-info "storage bytes must not be negative"
                       {:type :tenant-connection/invalid-budget})))
    ;; Validation and increment are one store transaction. Checking first and
    ;; incrementing later lets two loops both consume the last budget unit.
     (store/transact!
      (fn [root]
        (let [state (merge {:organizations {} :memberships {}
                            :tenant-connections {}}
                           (:identity root))
              connection (->> (require-visible state session connection-id)
                              (require-requesting-agent session))
              now (Instant/now)
              already-accounted? (and publication-id
                                      (contains?
                                       (:accounted-publications connection)
                                       publication-id))
              workspace-bytes (if (some? storage-bytes)
                                storage-bytes
                                (long (or (:workspace-bytes connection) 0)))
              publication-delta (if already-accounted?
                                  0
                                  (long (or published-byte-delta 0)))
              published-bytes (+ (long (or (:published-bytes connection) 0))
                                 publication-delta)
              total-storage (+ workspace-bytes published-bytes)]
          (when-not (= :active (:status connection))
            (throw (ex-info "tenant connectionはactiveではありません。"
                            {:type :tenant-connection/not-active})))
          (when-not (.isAfter (Instant/parse (:expires-at connection)) now)
            (throw (ex-info "tenant connectionのleaseが期限切れです。"
                            {:type :tenant-connection/expired})))
          (when-not (contains? (set (:capabilities connection)) capability)
            (throw (ex-info "tenant connectionにcapabilityがありません。"
                            {:type :tenant-connection/capability-denied})))
          (when (>= (:operations-used connection)
                    (get-in connection [:budget :max-operations]))
            (throw (ex-info "tenant connectionのoperation budgetを使い切りました。"
                            {:type :tenant-connection/budget-exhausted})))
          (when (> total-storage
                   (get-in connection [:budget :max-storage-bytes]))
            (throw (ex-info "tenant connectionのstorage budgetを超えます。"
                            {:type :tenant-connection/storage-budget-exhausted
                             :storage-bytes total-storage})))
          (vreset! chosen connection)
          (cond->
           (update-in root
                      [:identity :tenant-connections connection-id
                       :operations-used]
                      (fnil inc 0))
            (or (some? storage-bytes) (some? published-byte-delta))
            (-> (assoc-in [:identity :tenant-connections connection-id
                           :workspace-bytes] workspace-bytes)
                (assoc-in [:identity :tenant-connections connection-id
                           :published-bytes] published-bytes)
                (assoc-in [:identity :tenant-connections connection-id
                           :storage-used-bytes] total-storage))
            (and publication-id (not already-accounted?))
            (assoc-in [:identity :tenant-connections connection-id
                       :accounted-publications publication-id]
                      publication-delta)))))
     (let [connection @chosen]
       (cond->
        {:connection-id connection-id :tenant-id (:tenant-id connection)
         :membership-id (:membership-id connection)
         :agent-id (:agent-id connection)
         :repository-stream (:repository-stream connection)
         :capability capability}
         (or (some? storage-bytes) (some? published-byte-delta))
         (assoc :storage-used-bytes
                (+ (if (some? storage-bytes)
                     storage-bytes
                     (long (or (:workspace-bytes connection) 0)))
                   (long (or (:published-bytes connection) 0))
                   (if (and published-byte-delta
                            (not (contains? (:accounted-publications connection)
                                            publication-id)))
                     published-byte-delta 0))))))))
