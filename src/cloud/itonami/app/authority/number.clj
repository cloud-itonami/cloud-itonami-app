(ns cloud.itonami.app.authority.number
  "The numbering adapter for `cloud.itonami.app.authority` -- allocation (払い出し),
  assignment, lifecycle and portability (MNP).

  Same shape as the eSIM and card adapters, and the same discipline: the
  pre-check delegates every fact to `kotoba-lang/phone`, which is the table a
  governed numbering actor enforces from its own recorded state:

    number blocks / allocation -> kotoba.phone.numbering/allocation-issues
    reachability               -> kotoba.phone.lifecycle/apply-event
    port-out / port-in         -> kotoba.phone.porting

  Two rules from that table are easy to guess wrong and worth restating where a
  reader of this adapter will see them. A released number CANNOT be allocated
  again by waiting for a flag: `:assign` is unreachable from `:released`, and the
  path back runs through `:quarantine` and `:recycle`, the latter refusing unless
  the aged-out window is stated as a fact. And a port-out request whose subject
  disagrees with the record is refused before a human is asked -- that mismatch
  IS the port-out scam, and asking a human about it first would be asking them
  to authorise their own takeover.

  What is deliberately NOT here: whether this operator really holds the block,
  whether a regulator permits the assignment, whether the requester is who they
  say. Those need judgement or law and belong to the actor's Governor."
  (:require [clojure.string :as str]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [kotoba.phone :as phone]
            [kotoba.phone.lifecycle :as lifecycle]
            [kotoba.phone.numbering :as numbering]
            [kotoba.phone.porting :as porting]))

(def authority-key :number)

(def ops
  "Accepted operations and the Passkey context type each binds under. An
  allowlist -- an unrecognised op is refused, never defaulted.

  `:number/port-out` has its own context type rather than sharing
  `:number/lifecycle-transition` with the other state changes, because a human
  approving 'move my line to another operator' and a human approving 'suspend my
  line' are not agreeing to the same thing, and a shared context type would let
  an assertion for one authorise the other."
  {:number/allocate  :number/allocation
   :number/assign    :number/assignment
   :number/lifecycle :number/lifecycle-transition
   :number/port-in   :number/port-in
   :number/port-out  :number/port-out})

(def lifecycle-ops
  "The lifecycle operations reachable through `:number/lifecycle`.

  Allocation, assignment and the two port directions are deliberately EXCLUDED
  even though they are lifecycle operations: each one is its own op above, with
  its own consent context and its own pre-check. Letting them through here would
  make the context type a matter of which op name the caller happened to pick."
  #{:activate :suspend :restore :release :quarantine :recycle :port-out-cancel})

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- check-msisdn [msisdn]
  (or (phone/normalize-e164 msisdn)
      (refuse :number/msisdn-invalid
              (str "E.164 として不正な番号です: " (pr-str msisdn)))))

(defn- issue-keywords [issues]
  (mapv :phone/issue issues))

(defn pre-check
  "Deterministic. No model, no network, no clock.

  `:records`, `:blocks`, `:now-ms` and `:posture` are supplied by the caller
  (`cloud.itonami.app.authority.api` computes them from the store and
  configuration) rather than fetched here, so this stays pure -- and so its
  answer cannot change between review and consent."
  [_configuration _session
   {:keys [op msisdn operation subject iccid records blocks now-ms
           quarantine-days freeze-days donor recipient port-id]
    posture' :posture}]
  (when-not (contains? ops op)
    (refuse :number/op-unsupported (str "未対応の op です: " op)))
  ;; The cross-domain gate first, before anything op-specific. An eSIM ownership
  ;; transfer already in flight restricts moving the number too -- swap the
  ;; profile, then port the number is one attack with two steps, and the second
  ;; step is the one that becomes permanent.
  (when (posture/restricts? authority-key op)
    (when-not (:authority/posture posture')
      (refuse :number/posture-unknown
              "cross-domain posture が不明なままでは事前検査できません（authority.posture/subject-posture を渡すこと）"))
    (when (posture/refuses? posture' authority-key op)
      (refuse :number/control-change-hold
              (str "同一 subject の支配権変更（eSIM transfer / port-out）によりこの操作は保留されます: "
                   (pr-str (:authority/signals posture'))))))
  (case op
    :number/allocate
    (let [m (check-msisdn msisdn)
          issues (numbering/allocation-issues
                  {:blocks blocks :records records :msisdn m :subject subject
                   :now-ms now-ms :quarantine-days quarantine-days})]
      (when (seq issues)
        (refuse :number/allocation-refused
                (str "この番号は今この subject に払い出せません: " (pr-str (issue-keywords issues)))))
      {:op op :msisdn m :subject subject
       :plan (mapv (comp name first) (:phone/plan (numbering/plan-allocation
                                                   {:blocks blocks :records records
                                                    :msisdn m :subject subject
                                                    :now-ms now-ms
                                                    :quarantine-days quarantine-days})))
       :posture (:authority/posture posture')})

    ;; Reassignment to a DIFFERENT subject. Structurally the same transition as
    ;; the second half of an allocation, but a separate op because the number is
    ;; already held: this is a line changing hands, which is exactly what
    ;; kotoba.phone.lifecycle/takeover-signals names.
    :number/assign
    (let [m (check-msisdn msisdn)
          outcome (lifecycle/apply-event records m :assign {:phone/subject subject})]
      (when-not (:phone/ok? outcome)
        (refuse :number/transition-unreachable
                (str "記録上の状態から到達できない遷移です: "
                     (pr-str (issue-keywords (:phone/issues outcome))))))
      {:op op :msisdn m :subject subject
       :from (:phone/from outcome) :to (:phone/to outcome)
       :posture (:authority/posture posture')})

    :number/lifecycle
    (let [m (check-msisdn msisdn)]
      (when-not (contains? lifecycle-ops operation)
        (refuse :number/operation-unsupported
                (str "この op から実行できない lifecycle operation です: " (pr-str operation))))
      (let [current (first (filter #(= m (:phone/msisdn %)) records))
            elapsed? (when (= :recycle operation)
                       (numbering/quarantine-elapsed? current now-ms quarantine-days))
            outcome (lifecycle/apply-event records m operation
                                           (cond-> {}
                                             (some? iccid) (assoc :phone/iccid iccid)
                                             (= :recycle operation)
                                             (assoc :phone/quarantine-elapsed? (true? elapsed?))))]
        (when-not (:phone/ok? outcome)
          (refuse :number/transition-unreachable
                  (str "記録上の状態から到達できない遷移です: "
                       (pr-str (issue-keywords (:phone/issues outcome))))))
        (cond-> {:op op :msisdn m :operation operation
                 :from (:phone/from outcome) :to (:phone/to outcome)}
          (some? iccid) (assoc :iccid iccid)
          ;; Surfaced because a release starts an aging window during which the
          ;; number cannot be given to anybody. A consent that does not say so is
          ;; understating what the human is agreeing to.
          (= :release operation)
          (assoc :quarantine-days (or quarantine-days numbering/default-quarantine-days)))))

    :number/port-out
    (let [m (check-msisdn msisdn)
          request {:phone/port-id port-id :phone/msisdn m :phone/direction :out
                   :phone/donor donor :phone/recipient recipient
                   :phone/subject subject :phone/requested-at-ms now-ms}
          issues (porting/port-out-issues {:records records :request request
                                           :now-ms now-ms :freeze-days freeze-days})]
      (when (seq issues)
        (refuse :number/port-out-refused
                (str "この port-out は事前検査で拒否されました: " (pr-str (issue-keywords issues)))))
      {:op op :msisdn m :subject subject :donor donor :recipient recipient
       :port-id port-id :posture (:authority/posture posture')})

    :number/port-in
    (let [m (check-msisdn msisdn)
          request {:phone/port-id port-id :phone/msisdn m :phone/direction :in
                   :phone/donor donor :phone/recipient recipient
                   :phone/subject subject :phone/requested-at-ms now-ms}
          issues (porting/port-in-issues {:records records :request request})]
      (when (seq issues)
        (refuse :number/port-in-refused
                (str "この port-in は事前検査で拒否されました: " (pr-str (issue-keywords issues)))))
      {:op op :msisdn m :subject subject :donor donor :recipient recipient
       :port-id port-id})))

(defn material
  "The consent-bound string. Fixed field order, never a printed map -- see the
  eSIM adapter's `material` for why. Every field that changes what would happen
  appears here, and that includes the posture: a proposal reviewed under
  `:normal` must not be committable after a transfer moved the subject to
  `:restricted`."
  [{:keys [op msisdn subject operation from to donor recipient port-id iccid
           quarantine-days plan posture]}]
  (str "number/v1"
       "|op=" op
       "|posture=" posture
       "|msisdn=" msisdn
       "|subject=" subject
       "|operation=" operation
       "|from=" from
       "|to=" to
       "|iccid=" iccid
       "|donor=" donor
       "|recipient=" recipient
       "|port=" port-id
       "|quarantine-days=" quarantine-days
       "|plan=" (when plan (str/join "," plan))))

(defn domain-with
  "The authority domain map with both hand-offs injected explicitly: `commit-fn`
  carries a consented proposal to the actor, and `status-fn` asks what became of
  a pending one. Both are injected rather than hardcoded -- this app holds no
  transport of its own.

  The actor this points at (`cloud-itonami/cloud-itonami-numbering`) IS NOT
  BUILT (ADR-2608034000). Until it is, an enabled `:number` authority has no
  endpoint and a consented proposal is recorded as refused with
  `:endpoint-not-configured` -- which is the honest outcome, and is why the
  authority ships disabled."
  [commit-fn status-fn]
  {:authority/key authority-key
   :authority/status status-fn
   :authority/context-type #(get ops %)
   :authority/pre-check pre-check
   :authority/material material
   :authority/commit! commit-fn})

(defn domain
  "The domain wired to this authority's configured transport."
  [commit-fn]
  (domain-with commit-fn (transport/status-fn authority-key)))

(defn review!
  [commit-fn configuration session request]
  (authority/review! (domain commit-fn) configuration session request))
