(ns cloud.itonami.app.authority.voice
  "The inbound-voice adapter for `cloud.itonami.app.authority`.

  The authority is `cloud-itonami/denwaban` (a voice receptionist: answer,
  converse, delegate the booking to `cloud-itonami/yotei`). Number validity is
  delegated to `kotoba-lang/phone`, which also supplies the canonical form -- the
  record stores `normalize-e164`'s +<digits>, not the caller's input, so one line
  cannot appear as two.

  Three things this adapter refuses deterministically, before any human is asked:

  - a caller number that is not a valid E.164 number;
  - a number outside the configured allowlist (`:allowed-callers`), when one is
    configured. An EMPTY allowlist means nothing is allowed, not everything --
    an absent policy is not a permissive policy;
  - answering with recording retention when the caller has not consented.
    denwaban's own G1 gate says recording is transient by default and retention
    needs explicit up-front consent; this refuses rather than quietly downgrading
    the request to transient, because silently answering a different request than
    the one asked for is how a consent stops meaning anything.

  What is NOT here: whether to answer this particular call, what to say, or
  whether a booking is appropriate. Those are denwaban's, and denwaban is R0 --
  its `run-session` raises at the G7 outward gate, so committing a proposal here
  cannot answer a real call. See ADR-2607300300's ceiling section."
  (:require [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.transport :as transport]
            [kotoba.phone :as phone]))

(def authority-key :voice)

(def ops
  "Accepted operations and their Passkey context types. An allowlist."
  {:call/answer-authority :voice/answer-authority
   :call/booking-delegate :voice/booking-delegate})

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn pre-check
  "Deterministic. The allowlist and the retention policy come from
  configuration; the caller number and the consent flag come from the request."
  [configuration _session {:keys [op caller-number retain-recording?
                                  caller-consented-to-recording? slot]}]
  (when-not (contains? ops op)
    (refuse :voice/op-unsupported (str "未対応の op です: " op)))
  (let [canonical (phone/normalize-e164 caller-number)
        allowed (get-in configuration [:authorities :voice :allowed-callers])]
    (when-not canonical
      (refuse :voice/caller-invalid
              (str "発信者番号が E.164 として不正です: " (pr-str caller-number))))
    ;; An allowlist that is present but empty allows nothing. Treating an empty
    ;; policy as "allow all" is the direction that fails open.
    (when (and (some? allowed) (not (contains? (set allowed) canonical)))
      (refuse :voice/caller-not-allowed
              (str "許可リストに無い発信者です: " canonical)))
    (when (and retain-recording? (not caller-consented-to-recording?))
      (refuse :voice/recording-consent-missing
              "録音の保持には発信者の明示同意が必要です（denwaban G1）"))
    (case op
      :call/answer-authority
      {:op op :caller-number canonical
       :retain-recording? (boolean retain-recording?)}

      :call/booking-delegate
      (do (when-not slot
            (refuse :voice/slot-missing "予約枠 (slot) が必要です"))
          ;; denwaban delegates booking to yotei and never confirms it locally
          ;; (its G2). This adapter records the delegation target so a reader of
          ;; the proposal can see the booking is not this app's to confirm.
          {:op op :caller-number canonical :slot slot
           :booking-owner "yotei"}))))

(defn material
  "The consent-bound string. Fixed field order, never a printed map."
  [{:keys [op caller-number retain-recording? slot booking-owner]}]
  (str "voice/v1"
       "|op=" op
       "|caller=" caller-number
       "|retain-recording=" (boolean retain-recording?)
       "|slot=" slot
       "|booking-owner=" booking-owner))

(defn domain-with
  "The authority domain map with both hand-offs injected explicitly: `commit-fn`
  carries a consented proposal to the actor (denwaban), and `status-fn` asks
  what became of a pending one. Both are injected rather than hardcoded -- this
  app holds no transport of its own, and every op the actor would run is
  propose-only, so a committed proposal here records a governed proposal."
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
