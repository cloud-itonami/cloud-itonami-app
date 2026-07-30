(ns cloud.itonami.app.organism-worker
  "Contracts for an independently running artificial organism that belongs to
  an organization represented by Cloud Itonami.

  An OrganismWorker is not a `cloud.itonami.app.worker` background model run.
  Cloud Itonami stores an assignment and safe projections; the organism keeps
  its identity, lifecycle, memory, scheduler, and repository authority."
  (:require [clojure.set :as set]))

(def schema "kotoba.ao.worker-assignment.v1")
(def intent-schema "kotoba.ao.worker-intent.v1")

(def organism-owned-authorities
  {:memory :organism-local
   :lifecycle :organism-local
   :source :repository-local})

(def public-assignment-keys
  #{:ao.worker/schema :ao.worker/id :ao.worker/kind
    :ao.worker/organization :ao.worker/subject
    :ao.worker/repository :ao.worker/runtime :ao.worker/status
    :ao.worker/capabilities :ao.worker/authority :ao.worker/incarnation})

(defn- require-value [value field]
  (when (or (nil? value) (and (string? value) (empty? value)))
    (throw (ex-info (str field " is required")
                    {:type :ao.worker/invalid-assignment :field field})))
  value)

(defn assignment
  "Validate an organization assignment without transferring organism-owned
  authority to the application."
  [value]
  (doseq [field [:ao.worker/id :ao.worker/organization :ao.worker/subject
                 :ao.worker/repository]]
    (require-value (get value field) field))
  (when-not (= :artificial-organism (:ao.worker/kind value))
    (throw (ex-info "worker kind must be :artificial-organism"
                    {:type :ao.worker/invalid-assignment
                     :field :ao.worker/kind})))
  (when-not (= :external-supervisor (:ao.worker/runtime value))
    (throw (ex-info "an organism worker must keep an external supervisor"
                    {:type :ao.worker/invalid-assignment
                     :field :ao.worker/runtime})))
  (when-not (set/subset? (set (keys organism-owned-authorities))
                         (set (keys (:ao.worker/authority value))))
    (throw (ex-info "organism-owned authority declarations are incomplete"
                    {:type :ao.worker/invalid-assignment
                     :field :ao.worker/authority})))
  (doseq [[authority owner] organism-owned-authorities]
    (when-not (= owner (get-in value [:ao.worker/authority authority]))
      (throw (ex-info "Cloud Itonami cannot acquire organism-owned authority"
                      {:type :ao.worker/authority-transfer-denied
                       :authority authority
                       :required owner}))))
  (assoc value
         :ao.worker/schema schema
         :ao.worker/capabilities (set (:ao.worker/capabilities value))
         :ao.worker/status (or (:ao.worker/status value) :active)))

(defn public-assignment
  "Return the organization-scoped directory projection. Connection details,
  credentials, private memory, objectives, and event bodies are never copied."
  [value]
  (select-keys (assignment value) public-assignment-keys))

(defn intent-decision
  "Check whether Cloud Itonami may place a typed intent in the organism inbox.
  Acceptance only admits the intent for the organism's own policy evaluation;
  it never means the requested external effect has executed."
  [assignment-value {:intent/keys [id organization worker capability
                                   issued-by expires-at] :as intent}
   now-ms]
  (let [worker-assignment (assignment assignment-value)
        capabilities (:ao.worker/capabilities worker-assignment)
        reason (cond
                 (not= :active (:ao.worker/status worker-assignment))
                 :worker-not-active

                 (or (nil? id) (nil? issued-by))
                 :invalid-intent

                 (not= organization (:ao.worker/organization worker-assignment))
                 :organization-boundary

                 (not= worker (:ao.worker/id worker-assignment))
                 :worker-boundary

                 (not (contains? capabilities capability))
                 :capability-not-granted

                 (or (nil? expires-at) (<= expires-at now-ms))
                 :intent-expired)]
    (if reason
      {:intent/status :rejected :intent/reason reason}
      {:intent/schema intent-schema
       :intent/status :admitted
       :intent/id id
       :intent/worker worker
       :intent/organization organization
       :intent/capability capability
       :intent/issued-by issued-by
       :intent/expires-at expires-at
       :intent/payload-hash (:intent/payload-hash intent)
       :intent/effect-status :not-executed})))
