(ns cloud.itonami.app.work-governance
  "Pure contracts joining organization, DoDAF performers, approval, Kanban work,
  yakuwari capacity, and bounded AgentRuns.

  This namespace dispatches nothing and writes nothing. A host persists these
  namespaced EDN values, executes the returned plan, and records receipts. That
  separation keeps a board item from becoming execution authority merely by
  moving columns."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [yakuwari.reconcile :as reconcile]
            [yakuwari.spec :as yakuwari]))

(def schema "cloud.itonami.app.work-governance.v1")

(def performer-kinds #{:person :organization :system})
(def actor-kinds #{:user :agent :organism-worker :external-system :organization})
(def unit-kinds #{:organization :division :department :team :program})

(def dodaf-types
  {:person #{:dodaf/performer :dodaf/person}
   :organization #{:dodaf/performer :dodaf/organization}
   :system #{:dodaf/performer :dodaf/system}})

(defn- required! [value field type]
  (when (or (nil? value) (and (string? value) (str/blank? value)))
    (throw (ex-info (str field " is required") {:type type :field field})))
  value)

(defn performer
  "Validate one DoDAF performer. Artificial workers are systems, never persons.
  Display personas belong in `:performer/persona`; they do not change authority."
  [value]
  (let [id (required! (:performer/id value) :performer/id
                      :work-governance/invalid-performer)
        organization (required! (:performer/organization value)
                                :performer/organization
                                :work-governance/invalid-performer)
        kind (:performer/kind value)
        value (cond-> value
                (and (:performer/user-id value) (nil? (:performer/actor value)))
                (assoc :performer/actor {:actor/kind :user
                                         :actor/id (:performer/user-id value)}))
        actor (:performer/actor value)
        allowed-actors {:person #{:user}
                        :system #{:agent :organism-worker :external-system}
                        :organization #{:organization}}]
    (when-not (performer-kinds kind)
      (throw (ex-info "performer kind must be person, organization, or system"
                      {:type :work-governance/invalid-performer
                       :field :performer/kind :kind kind})))
    (when (and (= :system kind)
               (contains? (set (:performer/dodaf-types value)) :dodaf/person))
      (throw (ex-info "an artificial worker cannot acquire person authority"
                      {:type :work-governance/person-system-conflict :id id})))
    (when actor
      (required! (:actor/id actor) :actor/id
                 :work-governance/invalid-actor-binding)
      (when-not (contains? actor-kinds (:actor/kind actor))
        (throw (ex-info "unknown actor kind"
                        {:type :work-governance/invalid-actor-binding
                         :actor actor})))
      (when-not (contains? (get allowed-actors kind #{}) (:actor/kind actor))
        (throw (ex-info "actor kind does not match its DoDAF performer"
                        {:type :work-governance/actor-performer-conflict
                         :performer id :performer-kind kind
                         :actor-kind (:actor/kind actor)}))))
    (assoc value
           :performer/schema schema
           :performer/id id
           :performer/organization organization
           :performer/dodaf-types (dodaf-types kind))))

(defn organization-unit [value]
  (doseq [field [:org.unit/id :org.unit/organization :org.unit/name]]
    (required! (get value field) field :work-governance/invalid-unit))
  (let [kind (or (:org.unit/kind value) :team)]
    (when-not (unit-kinds kind)
      (throw (ex-info "unknown organization unit kind"
                      {:type :work-governance/invalid-unit :kind kind})))
    (assoc value :org.unit/schema schema :org.unit/kind kind)))

(defn position [value]
  (doseq [field [:org.position/id :org.position/organization
                 :org.position/unit :org.position/name]]
    (required! (get value field) field :work-governance/invalid-position))
  (assoc value :org.position/schema schema))

(defn organization-role [value]
  (doseq [field [:org.role/id :org.role/organization :org.role/name]]
    (required! (get value field) field :work-governance/invalid-role))
  (assoc value :org.role/schema schema
         :org.role/capabilities (set (:org.role/capabilities value))))

(defn assignment
  "A performer filling organizational roles. Reporting lines are deliberately
  separate: being someone's manager does not itself grant approval authority."
  [value]
  (doseq [field [:org.assignment/id :org.assignment/organization
                 :org.assignment/performer :org.assignment/position]]
    (required! (get value field) field :work-governance/invalid-assignment))
  (let [roles (set (:org.assignment/roles value))
        from (:org.assignment/effective-from value)
        to (:org.assignment/effective-to value)]
    (when (empty? roles)
      (throw (ex-info "an organizational assignment requires at least one role"
                      {:type :work-governance/invalid-assignment
                       :field :org.assignment/roles})))
    (when (and from to (pos? (compare from to)))
      (throw (ex-info "assignment effective range is reversed"
                      {:type :work-governance/invalid-assignment-range
                       :from from :to to})))
    (assoc value :org.assignment/schema schema :org.assignment/roles roles
           :org.assignment/status (or (:org.assignment/status value) :active))))

(defn- cyclic-unit-ids [units]
  (let [parents (into {} (map (juxt :org.unit/id :org.unit/parent)) units)]
    (->> (keys parents)
         (filter
          (fn [start]
            (loop [at start seen #{}]
              (cond
                (nil? at) false
                (seen at) true
                :else (recur (get parents at) (conj seen at))))))
         set)))

(defn organization-graph
  "Validate performer assignments and reporting edges for one organization.
  Reports every structural problem as data; approval policy is not inferred."
  [{:org/keys [id units positions roles performers assignments reporting-lines]
    :as graph}]
  (required! id :org/id :work-governance/invalid-organization)
  (let [units (mapv organization-unit units)
        positions (mapv position positions)
        role-definitions (mapv organization-role roles)
        performers (mapv performer performers)
        assignments (mapv assignment assignments)
        unit-ids (set (map :org.unit/id units))
        position-ids (set (map :org.position/id positions))
        role-ids (set (map :org.role/id role-definitions))
        performer-ids (set (map :performer/id performers))
        assignment-ids (set (map :org.assignment/id assignments))
        cyclic (cyclic-unit-ids units)
        problems
        (vec
         (concat
          (for [p performers :when (not= id (:performer/organization p))]
            {:problem :performer-organization-boundary :id (:performer/id p)})
          (for [u units :when (not= id (:org.unit/organization u))]
            {:problem :unit-organization-boundary :id (:org.unit/id u)})
          (for [u units
                :when (and (:org.unit/parent u)
                           (not (unit-ids (:org.unit/parent u))))]
            {:problem :unknown-parent-unit :id (:org.unit/id u)
             :parent (:org.unit/parent u)})
          (for [u units
                :when (and (:org.unit/performer u)
                           (not (performer-ids (:org.unit/performer u))))]
            {:problem :unknown-unit-performer :id (:org.unit/id u)
             :performer (:org.unit/performer u)})
          (for [u units
                :let [p (some #(when (= (:org.unit/performer u)
                                        (:performer/id %)) %) performers)]
                :when (and p (not= :organization (:performer/kind p)))]
            {:problem :unit-performer-not-organization :id (:org.unit/id u)
             :performer (:org.unit/performer u)})
          (for [unit-id cyclic]
            {:problem :cyclic-unit :id unit-id})
          (for [p positions :when (not= id (:org.position/organization p))]
            {:problem :position-organization-boundary :id (:org.position/id p)})
          (for [p positions :when (not (unit-ids (:org.position/unit p)))]
            {:problem :unknown-position-unit :id (:org.position/id p)
             :unit (:org.position/unit p)})
          (for [r role-definitions :when (not= id (:org.role/organization r))]
            {:problem :role-organization-boundary :id (:org.role/id r)})
          (for [a assignments
                :when (not= id (:org.assignment/organization a))]
            {:problem :assignment-organization-boundary
             :id (:org.assignment/id a)})
          (for [a assignments
                :when (not (performer-ids (:org.assignment/performer a)))]
            {:problem :unknown-performer :id (:org.assignment/performer a)})
          (for [a assignments
                :when (and (seq positions)
                           (not (position-ids (:org.assignment/position a))))]
            {:problem :unknown-position :id (:org.assignment/position a)})
          (for [a assignments role (:org.assignment/roles a)
                :when (and (seq role-definitions) (not (role-ids role)))]
            {:problem :unknown-role :id role
             :assignment (:org.assignment/id a)})
          (mapcat
           (fn [{:reporting/keys [manager report] :as edge}]
             (cond-> []
               (not (assignment-ids manager))
               (conj {:problem :unknown-manager :edge edge})
               (not (assignment-ids report))
               (conj {:problem :unknown-report :edge edge})
               (= manager report)
               (conj {:problem :self-reporting-line :edge edge})))
           reporting-lines)))]
    {:ok? (empty? problems)
     :problems problems
     :graph (assoc graph :org/schema schema :org/units units
                   :org/positions positions :org/roles role-definitions
                   :org/performers performers
                   :org/assignments assignments
                   :org/reporting-lines (vec reporting-lines))}))

(defn approval-policy
  "Validate the policy for one capability. Eligible roles select candidates;
  organization hierarchy alone never does."
  [value]
  (doseq [field [:approval.policy/id :approval.policy/organization
                 :approval.policy/capability]]
    (required! (get value field) field :work-governance/invalid-approval-policy))
  (let [roles (set (:approval.policy/eligible-roles value))
        minimum (or (:approval.policy/minimum value) 1)]
    (when (empty? roles)
      (throw (ex-info "approval policy requires eligible roles"
                      {:type :work-governance/invalid-approval-policy
                       :field :approval.policy/eligible-roles})))
    (when-not (pos-int? minimum)
      (throw (ex-info "approval minimum must be a positive integer"
                      {:type :work-governance/invalid-approval-policy
                       :field :approval.policy/minimum})))
    (assoc value
           :approval.policy/schema schema
           :approval.policy/eligible-roles roles
           :approval.policy/minimum minimum
           :approval.policy/requires-user-verification?
           (not= false (:approval.policy/requires-user-verification? value))
           :approval.policy/separation-of-duties?
           (not= false (:approval.policy/separation-of-duties? value))
           :approval.policy/rejection-mode
           (or (:approval.policy/rejection-mode value) :veto))))

(defn- assignment-for [assignments performer-id organization]
  (some #(when (and (= :active (:org.assignment/status %))
                    (= organization (:org.assignment/organization %))
                    (= performer-id (:org.assignment/performer %)))
           %)
        assignments))

(defn- performer-for [performers id]
  (some #(when (= id (:performer/id %)) %) performers))

(defn- eligible-decision?
  [policy item performers assignments decision]
  (let [actor (:approval.decision/actor decision)
        p (performer-for performers actor)
        a (assignment-for assignments actor (:approval.policy/organization policy))]
    (and (= (:work.item/organization item)
            (:approval.policy/organization policy))
         (= (:work.item/capability item) (:approval.policy/capability policy))
         (= (:work.item/id item) (:approval.decision/work-item decision))
         (= (:work.item/content-hash item) (:approval.decision/content-hash decision))
         (= :person (:performer/kind p))
         (seq (set/intersection (:approval.policy/eligible-roles policy)
                                (:org.assignment/roles a)))
         (or (not (:approval.policy/requires-user-verification? policy))
             (true? (:approval.decision/user-verified? decision)))
         (or (not (:approval.policy/separation-of-duties? policy))
             (not= actor (:work.item/submitted-by item))))))

(defn approval-state
  "Fold content-bound human decisions for one work item. Ineligible, stale, or
  system-authored decisions are ignored and included in `:ignored`; they never
  become authority by being present in the EDN ledger."
  [policy item performers assignments decisions]
  (let [policy (approval-policy policy)
        performers (mapv performer performers)
        assignments (mapv assignment assignments)
        [eligible ignored]
        ((juxt filter remove)
         #(eligible-decision? policy item performers assignments %)
         decisions)
        eligible (vec eligible)
        ignored (vec ignored)
        rejected (filterv #(= :rejected (:approval.decision/decision %)) eligible)
        approved (->> eligible
                      (filter #(= :approved (:approval.decision/decision %)))
                      (reduce (fn [m d] (assoc m (:approval.decision/actor d) d)) {})
                      vals vec)
        status (cond
                 (and (= :veto (:approval.policy/rejection-mode policy))
                      (seq rejected)) :rejected
                 (>= (count approved) (:approval.policy/minimum policy)) :approved
                 :else :pending)]
    {:approval/status status
     :approval/policy (:approval.policy/id policy)
     :approval/approved-by (mapv :approval.decision/actor approved)
     :approval/rejected-by (mapv :approval.decision/actor rejected)
     :approval/required (:approval.policy/minimum policy)
     :approval/ignored ignored}))

(def work-statuses
  #{:backlog :ready :leased :running :held :review :done :failed
    :rejected :cancelled})

(def work-transitions
  {:backlog #{:ready :cancelled}
   :ready #{:leased :held :cancelled}
   :leased #{:running :ready :failed :cancelled}
   :running #{:held :review :done :failed :cancelled}
   :held #{:ready :leased :running :rejected :cancelled}
   :review #{:done :held :failed :cancelled}
   :failed #{:ready :cancelled}
   :done #{} :rejected #{} :cancelled #{}})

(defn work-item [value]
  (doseq [field [:work.item/id :work.item/organization :work.item/project
                 :work.item/title :work.item/capability :work.item/yakuwari
                 :work.item/content-hash]]
    (required! (get value field) field :work-governance/invalid-work-item))
  (let [status (or (:work.item/status value) :backlog)]
    (when-not (work-statuses status)
      (throw (ex-info "unknown work item status"
                      {:type :work-governance/invalid-work-item :status status})))
    (assoc value :work.item/schema schema :work.item/status status)))

(defn transition-work
  "Apply a legal Kanban transition. A caller supplies the receipt or approval
  evidence in attrs; this function preserves it but never fabricates it."
  [item status now-ms attrs]
  (let [item (work-item item)
        from (:work.item/status item)]
    (when-not (contains? (get work-transitions from #{}) status)
      (throw (ex-info "invalid work item transition"
                      {:type :work-governance/invalid-transition
                       :work-item (:work.item/id item) :from from :to status})))
    (merge item attrs {:work.item/status status :work.item/updated-at now-ms})))

(defn route-item
  "Route one ready/held item under the yakuwari policy and organizational
  approval ledger. Returns intent only; an adapter performs the transition."
  [role item approval-policy-value performers assignments decisions]
  (let [item (work-item item)
        capability (:work.item/capability item)
        decision (yakuwari/decide role capability)]
    (case decision
      :autonomous {:action :dispatch :work-item (:work.item/id item)}
      :voice-required {:action :notify-and-dispatch :work-item (:work.item/id item)}
      :blocked {:action :block :work-item (:work.item/id item)
                :reason :capability-blocked}
      :approval-required
      (if-not approval-policy-value
        {:action :block :work-item (:work.item/id item)
         :reason :approval-policy-missing}
        (let [a (approval-state approval-policy-value item performers assignments
                                decisions)]
          (case (:approval/status a)
            :approved {:action :dispatch :work-item (:work.item/id item)
                       :approval a}
            :rejected {:action :reject :work-item (:work.item/id item)
                       :approval a}
            {:action :hold :work-item (:work.item/id item) :approval a})))
      {:action :block :work-item (:work.item/id item)
       :reason :unknown-yakuwari-decision})))

(defn reconcile-plan
  "Join Kanban demand to yakuwari capacity. Only `:dispatch` and
  `:notify-and-dispatch` consume spawn capacity; held or blocked cards remain
  visible without being mistaken for executions."
  [{:keys [role items runs approval-policy approval-policies performers
           assignments decisions now-ms]}]
  (let [role (yakuwari/validate! role)
        capacity (reconcile/plan role runs now-ms)
        candidates (->> items
                        (map work-item)
                        (filter #(= (:yakuwari/id role) (:work.item/yakuwari %)))
                        (filter #(contains? #{:ready :held} (:work.item/status %)))
                        (sort-by (juxt #(or (:work.item/priority %) 0)
                                      #(or (:work.item/created-at %) 0)
                                      :work.item/id)))
        routes (mapv #(route-item role %
                                 (or (get approval-policies
                                          [(:work.item/organization %)
                                           (:work.item/capability %)])
                                     approval-policy)
                                 performers assignments decisions)
                     candidates)
        dispatchable (filterv #(contains? #{:dispatch :notify-and-dispatch}
                                           (:action %))
                              routes)
        selected (set (map :work-item (take (:spawn capacity) dispatchable)))]
    {:schema schema
     :yakuwari (:yakuwari/id role)
     :capacity (dissoc capacity :active)
     :actions (mapv #(if (and (contains? #{:dispatch :notify-and-dispatch}
                                         (:action %))
                              (not (selected (:work-item %))))
                       (assoc % :action :wait-capacity)
                       %)
                    routes)}))
