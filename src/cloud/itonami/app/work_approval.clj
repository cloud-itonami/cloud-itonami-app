(ns cloud.itonami.app.work-approval
  "Passkey-bound organizational approval decisions for governed WorkItems."
  (:require [clojure.set :as set]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.work-governance :as governance]
            [cloud.itonami.app.work-runtime :as runtime])
  (:import [java.util UUID]))

(def schema "cloud.itonami.app.work-approval.v1")

(defn- performer-for-user [state user-id]
  (some (fn [performer]
          (when (or (= user-id (:performer/id performer))
                    (= user-id (:performer/user-id performer)))
            performer))
        (vals (:performers state))))

(defn- policy-for [state item]
  (some #(when (and (= (:work.item/organization item)
                       (:approval.policy/organization %))
                    (= (:work.item/capability item)
                       (:approval.policy/capability %))) %)
        (vals (:approval-policies state))))

(defn authorization-context [session item-id decision]
  (let [state (runtime/ledger)
        item (get-in state [:work-items item-id])
        performer (performer-for-user state (:user-id session))
        policy (policy-for state item)]
    (when-not item
      (throw (ex-info "WorkItem was not found"
                      {:type :work-approval/not-found})))
    (when-not (= (:organization-id session) (:work.item/organization item))
      (throw (ex-info "WorkItem belongs to another organization"
                      {:type :work-approval/organization-boundary})))
    (when-not (= :person (:performer/kind performer))
      (throw (ex-info "approval actor must be a DoDAF Person"
                      {:type :work-approval/person-required})))
    (when-not policy
      (throw (ex-info "approval policy was not found"
                      {:type :work-approval/policy-required})))
    (when-not (#{:approved :rejected} decision)
      (throw (ex-info "approval decision is invalid"
                      {:type :work-approval/invalid-decision})))
    (let [candidate {:approval.decision/id "candidate"
                     :approval.decision/work-item item-id
                     :approval.decision/actor (:performer/id performer)
                     :approval.decision/content-hash (:work.item/content-hash item)
                     :approval.decision/decision decision
                     :approval.decision/user-verified? true}
          evaluated (governance/approval-state
                     policy item (vec (vals (:performers state)))
                     (vec (vals (:assignments state))) [candidate])]
      (when (seq (:approval/ignored evaluated))
        (throw (ex-info "Person is not eligible under the approval policy"
                        {:type :work-approval/not-eligible})))
      {:schema schema
       :operation :work-item/approval
       :work-item item-id
       :organization (:work.item/organization item)
       :capability (:work.item/capability item)
       :content-hash (:work.item/content-hash item)
       :policy (:approval.policy/id policy)
       :actor (:performer/id performer)
       :user-id (:user-id session)
       :decision decision})))

(defn start! [session item-id decision rp-id origin]
  (let [context (authorization-context session item-id decision)]
    (passkey/start-authorization! (:user-id session) context rp-id origin)))

(defn finish! [session item-id transaction-id credential]
  (let [assertion (passkey/finish-authorization! transaction-id credential)
        context (:authorization-context assertion)
        expected (authorization-context session item-id (:decision context))]
    (when-not (and (= expected context)
                   (= (:user-id session) (:user-id assertion)))
      (throw (ex-info "Passkey assertion does not authorize this WorkItem"
                      {:type :work-approval/assertion-mismatch})))
    (runtime/record-approval!
     {:approval.decision/id (str "work-approval-" (UUID/randomUUID))
      :approval.decision/work-item item-id
      :approval.decision/actor (:actor context)
      :approval.decision/content-hash (:content-hash context)
      :approval.decision/decision (:decision context)
      :approval.decision/user-verified? true
      :approval.decision/policy (:policy context)
      :approval.decision/authentication
      {:method :webauthn
       :credential-id (:credential-id assertion)
       :authorization-context-hash (runtime/payload-hash context)}})))

(defn review-context [session item-id]
  (let [state (runtime/ledger)
        item (get-in state [:work-items item-id])
        performer (performer-for-user state (:user-id session))
        assignment (some #(when (and
                                  (= :active (:org.assignment/status %))
                                  (= (:performer/id performer)
                                     (:org.assignment/performer %))
                                  (= (:work.item/organization item)
                                     (:org.assignment/organization %))) %)
                         (vals (:assignments state)))
        eligible (set (get-in item [:work.item/verification-policy
                                    :eligible-reviewer-roles]))]
    (when-not (= :review (:work.item/status item))
      (throw (ex-info "WorkItem is not awaiting review"
                      {:type :work-approval/not-in-review})))
    (when-not (= (:organization-id session) (:work.item/organization item))
      (throw (ex-info "WorkItem belongs to another organization"
                      {:type :work-approval/organization-boundary})))
    (when-not (= :person (:performer/kind performer))
      (throw (ex-info "reviewer must be a DoDAF Person"
                      {:type :work-approval/person-required})))
    (when (and (seq eligible)
               (empty? (set/intersection
                        eligible (:org.assignment/roles assignment))))
      (throw (ex-info "Person is not an eligible reviewer"
                      {:type :work-approval/not-eligible})))
    (when (= (:performer/id performer) (:work.item/submitted-by item))
      (throw (ex-info "submitter cannot independently review own work"
                      {:type :work-approval/separation-of-duties})))
    {:schema schema :operation :work-item/review
     :work-item item-id
     :organization (:work.item/organization item)
     :content-hash (:work.item/content-hash item)
     :agent-run (:work.item/agent-run item)
     :actor (:performer/id performer)
     :user-id (:user-id session)}))

(defn start-review! [session item-id rp-id origin]
  (passkey/start-authorization!
   (:user-id session) (review-context session item-id) rp-id origin))

(defn finish-review! [session item-id transaction-id credential]
  (let [assertion (passkey/finish-authorization! transaction-id credential)
        context (:authorization-context assertion)
        expected (review-context session item-id)]
    (when-not (and (= expected context)
                   (= (:user-id session) (:user-id assertion)))
      (throw (ex-info "Passkey assertion does not authorize this review"
                      {:type :work-approval/assertion-mismatch})))
    (runtime/record-verification!
     {:verification.receipt/id (str "work-review-" (UUID/randomUUID))
      :verification.receipt/work-item item-id
      :verification.receipt/agent-run (:agent-run context)
      :verification.receipt/content-hash (:content-hash context)
      :verification.receipt/kind :review
      :verification.receipt/evidence-hash (runtime/payload-hash context)
      :verification.receipt/verifier (:actor context)
      :verification.receipt/user-verified? true
      :verification.receipt/authentication
      {:method :webauthn
       :credential-id (:credential-id assertion)}})))
