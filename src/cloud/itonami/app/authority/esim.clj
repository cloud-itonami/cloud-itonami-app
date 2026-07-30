(ns cloud.itonami.app.authority.esim
  "The eSIM adapter for `cloud.itonami.app.authority`.

  What this namespace is allowed to contain: the mapping from an app request to a
  proposal, a DETERMINISTIC pre-check, a stable digest material string, and the
  hand-off to the authority. Nothing else -- no eSIM domain rules of its own.

  The pre-check delegates every fact to `kotoba-lang/esim`:

    EID structure        -> kotoba.esim/validate-eid
    ICCID check digit    -> kotoba.esim/validate-iccid   (E.118, Luhn via kotoba.card)
    reachability         -> kotoba.esim.lifecycle/apply-operation
    single-enabled rule  -> the same, via :enable/would-displace

  That is the point of the layering. `cloud-itonami/cloud-itonami-esim`'s governor
  recomputes these too, from its own recorded state. Both read ONE table, so the
  pre-check cannot be more permissive than the governor -- which is the specific
  failure mode a pre-check exists to avoid, since a pre-check that waves work
  through only to have the governor refuse it has wasted a human's approval.

  What is deliberately NOT here: whether the operator is licensed, whether a
  transfer is fraudulent, whether a jurisdiction permits this. Those need
  judgement or law and belong to the actor's Governor."
  (:require [cloud.itonami.app.authority :as authority]
            [kotoba.esim :as esim]
            [kotoba.esim.lifecycle :as lifecycle]))

(def authority-key :esim)

(def ops
  "The operations this adapter accepts, and the Passkey context type each binds
  under. An allowlist: an unrecognised op is refused rather than defaulted, so a
  new op cannot reach a consent prompt before anyone wires its pre-check."
  {:profile/download   :esim/profile-download
   :profile/lifecycle  :esim/profile-transition
   :ownership/transfer :esim/ownership-transfer})

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- check-identifiers [{:keys [eid iccid]}]
  (let [issues (into (esim/validate-eid eid) (esim/validate-iccid iccid))]
    (when (seq issues)
      (refuse :esim/identifier-invalid
              (str "eSIM 識別子が不正です: " (pr-str (mapv :esim/issue issues)))))))

(defn pre-check
  "Deterministic. No model, no network, no clock -- only the request and the
  profiles the caller already holds on record.

  `:profiles` is supplied by the caller (the read model for this eUICC) rather
  than fetched here, so this function stays pure and testable. Refusing to fetch
  is deliberate: a pre-check that reads the world is a pre-check whose answer can
  change between review and consent."
  [_configuration _session {:keys [op eid iccid operation profiles
                                   from-subject to-subject]}]
  (when-not (contains? ops op)
    (refuse :esim/op-unsupported (str "未対応の op です: " op)))
  (case op
    :profile/download
    (do (check-identifiers {:eid eid :iccid iccid})
        (when (some #(= iccid (:esim/iccid %)) profiles)
          (refuse :esim/profile-already-installed
                  "この ICCID の profile は既に installed です"))
        {:op op :eid eid :iccid iccid})

    :profile/lifecycle
    (do (check-identifiers {:eid eid :iccid iccid})
        (let [outcome (lifecycle/apply-operation (vec profiles) iccid operation)]
          (when-not (:esim/ok? outcome)
            (refuse :esim/transition-unreachable
                    (str "記録上の状態から到達できない遷移です: "
                         (pr-str (mapv :esim/issue (:esim/issues outcome))))))
          {:op op :eid eid :iccid iccid :operation operation
           :from (:esim/from outcome) :to (:esim/to outcome)}))

    :ownership/transfer
    (do (check-identifiers {:eid eid :iccid iccid})
        ;; The library refuses a self-transfer; surface that rather than
        ;; re-deriving the rule.
        (when-not (esim/ownership-transfer iccid from-subject to-subject)
          (refuse :esim/transfer-invalid
                  "transfer の subject が不正です（同一 subject / 欠落）"))
        {:op op :eid eid :iccid iccid
         :from-subject from-subject :to-subject to-subject})))

(defn material
  "The string the human's consent is bound to.

  Built field by field in a fixed order, NOT by printing a map: a map's iteration
  order is only stable below the array-map threshold, so `pr-str` of a map would
  make the digest depend on how many keys it happens to carry. Every field that
  changes what would happen must appear here -- a field left out is a field an
  attacker may edit after consent."
  [{:keys [op eid iccid operation from-subject to-subject from to]}]
  (str "esim/v1"
       "|op=" op
       "|eid=" eid
       "|iccid=" iccid
       "|operation=" operation
       "|from=" from
       "|to=" to
       "|from-subject=" from-subject
       "|to-subject=" to-subject))

(defn domain
  "The authority domain map. `commit-fn` is the hand-off to the governed actor
  (`cloud-itonami/cloud-itonami-esim`), injected rather than hardcoded: this app
  holds no transport to it, and every op it would call is propose-only, so a
  committed proposal here records a governed proposal -- never a downloaded
  profile or a completed number transfer."
  [commit-fn]
  {:authority/key authority-key
   :authority/context-type #(get ops %)
   :authority/pre-check pre-check
   :authority/material material
   :authority/commit! commit-fn})

(defn review!
  "Pre-check and record a proposal awaiting consent."
  [commit-fn configuration session request]
  (authority/review! (domain commit-fn) configuration session request))
