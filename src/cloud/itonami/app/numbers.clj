(ns cloud.itonami.app.numbers
  "What this app knows about the numbers a subject holds.

  READ THIS BEFORE TRUSTING IT. This is not the numbering plane. The numbering
  plane belongs to a governed actor with its own ledger, and its governor
  re-derives every state from its own records. What lives here is the app's
  record of the proposals IT committed -- a number appears because a
  `:number/allocate` or `:number/port-in` proposal for it was consented to and
  the authority recorded it, and it moves state because a `:number/lifecycle`
  proposal did.

  That distinction decides what this read model may be used for. It may answer
  'does this subject have a governed claim on this number?' -- which is exactly
  what the anti-spoofing check in `kotoba.phone.origination` needs, and what
  `cloud.itonami.app.authority.number` pre-checks against. It may NOT be read as
  'this number is live on a network': every authority in this fleet is
  propose-only today, so a `:active` record here means a governed proposal said
  activate, not that a switch anywhere agrees.

  A number the subject holds through some other operator, or held before this
  app existed, is simply ABSENT -- and absent refuses. That is the fail-closed
  direction and it is deliberate: seeding this model from an operator's
  assertion would make the spoofing check answerable by the party it is meant to
  constrain."
  (:require [cloud.itonami.app.store :as store]
            [kotoba.phone :as phone]
            [kotoba.phone.lifecycle :as lifecycle]
            [kotoba.phone.numbering :as numbering]))

(def schema "cloud.itonami.app.numbers.v1")

(defn- path [] [:authority :numbers])

(defn all
  "Every number record this app holds, across subjects."
  []
  (vec (vals (get-in (store/snapshot) (path)))))

(defn records
  "The number records for this session's subject.

  Scoped by user-id for the same reason every other read model here is: a
  calling-number check that could see another subject's numbers would let one
  subject present another's line."
  [session]
  (filterv #(= (:user-id session) (:owner-user-id %)) (all)))

(defn record
  "One number record for this session's subject, or nil."
  [session msisdn]
  (let [m (phone/normalize-e164 msisdn)]
    (first (filter #(= m (:phone/msisdn %)) (records session)))))

(defn blocks
  "The numbering blocks this deployment holds, from configuration.

  Configuration, not the store: which blocks an operator was assigned is a
  deployment fact that predates any proposal, and a block that could be added by
  a request would let a caller allocate themselves a number from a range nobody
  gave this operator."
  [configuration]
  (vec (keep (fn [{:keys [id first last kind operator]}]
               (numbering/block id first last (keyword (name (or kind :mobile)))
                                :operator operator))
             (get-in configuration [:authorities :number :blocks]))))

(defn quarantine-days
  [configuration]
  (get-in configuration [:authorities :number :quarantine-days]))

(defn freeze-days
  [configuration]
  (get-in configuration [:authorities :number :freeze-days]))

(defn now-ms
  "The current instant in epoch milliseconds.

  kotoba.phone.numbering takes time as a number rather than a timestamp string
  so that it stays portable and clock-free; this is the one place that
  conversion happens, and it happens at the edge where an impure read already
  is."
  []
  (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; writes -- only from a committed proposal
;; ---------------------------------------------------------------------------

(defn- put! [r]
  (store/transact! assoc-in (conj (path) (:phone/msisdn r)) r))

(defn admit!
  "Record that this subject now has a governed claim on a number.

  Called only for a COMMITTED `:number/allocate` or `:number/port-in` proposal.
  The state it lands in is the one the lifecycle says that operation lands in --
  `:assigned`, never `:active` -- because activation is its own decision and
  writing it here would let an allocation quietly produce a number the
  origination check treats as presentable."
  [session msisdn & {:keys [iccid block-id]}]
  (when-let [m (phone/normalize-e164 msisdn)]
    (let [r (assoc (numbering/record m :assigned
                                     :subject (:user-id session)
                                     :iccid iccid
                                     :block-id block-id
                                     :changed-at-ms (now-ms))
                   :owner-user-id (:user-id session)
                   :organization-id (:organization-id session)
                   :schema schema)]
      (put! r)
      r)))

(defn apply-transition!
  "Move a recorded number through one lifecycle operation.

  Runs the SAME state machine the pre-check ran, against the records as they are
  now. It can therefore refuse -- and returning the refusal rather than writing
  anything is the point: between review and commit the world may have moved, and
  a read model that applied a transition its own state machine rejects would
  start disagreeing with the actor it is supposed to shadow."
  [session msisdn operation & {:keys [subject iccid quarantine-elapsed?]}]
  (let [rs (records session)
        outcome (lifecycle/apply-event rs (phone/normalize-e164 msisdn) operation
                                       (cond-> {}
                                         subject (assoc :phone/subject subject)
                                         (some? iccid) (assoc :phone/iccid iccid)
                                         (some? quarantine-elapsed?)
                                         (assoc :phone/quarantine-elapsed? quarantine-elapsed?)))]
    (when (:phone/ok? outcome)
      (doseq [r (:phone/records outcome)]
        (put! (assoc r :phone/changed-at-ms (now-ms)))))
    outcome))

(defn forget!
  "Drop a number record. Used when a port-out completes: the number is on
  another operator's plane and nothing this app records about it afterwards is
  true. Kept as an explicit operation rather than a state, so that 'we no longer
  have this' is a decision somebody made."
  [session msisdn]
  (when-let [r (record session msisdn)]
    (store/transact! update-in (path) dissoc (:phone/msisdn r))
    r))
