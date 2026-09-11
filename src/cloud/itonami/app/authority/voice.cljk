(ns cloud.itonami.app.authority.voice
  "The voice adapter for `cloud.itonami.app.authority` -- receiving calls, and
  since ADR-2608034000, placing them.

  Both directions are here because the actor that ends up holding the line is
  the same one. Nothing else about them is shared: the inbound ops answer 'may
  this service speak to whoever called?', `:call/originate` answers 'may it
  spend this subject's money to reach somebody who did not ask?'. They have
  separate pre-checks, separate consent context types, and only the outbound
  one is gated on the cross-domain posture.

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
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [kotoba.phone :as phone]
            [kotoba.phone.origination :as origination]))

(def authority-key :voice)

(def ops
  "Accepted operations and their Passkey context types. An allowlist.

  `:call/originate` (発信, ADR-2608034000) binds under its OWN context type
  rather than sharing one with the inbound ops. A human agreeing that this
  service may answer their line has not agreed that it may place calls from it,
  and one context type would let an assertion for the first authorise the
  second."
  {:call/answer-authority :voice/answer-authority
   :call/booking-delegate :voice/booking-delegate
   :call/originate        :voice/outbound-origination})

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- originate-pre-check
  "The outbound half. Every rule is `kotoba.phone.origination`'s -- destination
  classification, the emergency refusal, the short-code allowlist, the
  calling-number-is-held check and the cost arithmetic. None of them is restated
  here, for the same reason the eSIM adapter restates none of the profile rules:
  a pre-check that carries its own copy of a rule is a pre-check that can become
  more permissive than the governor which will see the proposal next.

  Answering an inbound call and placing an outbound one are different
  authorities in every way that matters -- the first is a decision about
  speaking, the second spends money and reaches somebody who did not ask -- so
  this is a separate function with a separate consent context, sharing only the
  actor that ends up holding the line."
  [configuration
   {:keys [destination calling-number records subject retain-recording?
           caller-consented-to-recording? estimated-minutes rate-minor-per-minute
           daily-limit-minor spent-today-minor]
    posture' :posture}]
  (let [settings (get-in configuration [:authorities :voice])
        issues (origination/origination-issues
                {:records records
                 :subject subject
                 :calling-number calling-number
                 :destination destination
                 :home-country-code (:home-country-code settings)
                 :allowed-short-codes (:allowed-short-codes settings)
                 :allow-premium-rate? (:allow-premium-rate? settings)
                 :retain-recording? retain-recording?
                 :caller-consented-to-recording? caller-consented-to-recording?
                 :estimated-minutes estimated-minutes
                 :rate-minor-per-minute rate-minor-per-minute
                 :daily-limit-minor daily-limit-minor
                 :spent-today-minor spent-today-minor})]
    (when (seq issues)
      (refuse :voice/origination-refused
              (str "この発信は事前検査で拒否されました: "
                   (pr-str (mapv :phone/issue issues)))))
    {:op :call/originate
     :calling-number (phone/normalize-e164 calling-number)
     :destination destination
     :destination-class (origination/classify destination (:home-country-code settings))
     :estimated-minutes estimated-minutes
     :rate-minor-per-minute rate-minor-per-minute
     :estimate-minor (* (long estimated-minutes) (long rate-minor-per-minute))
     :retain-recording? (boolean retain-recording?)
     :posture (:authority/posture posture')}))

(defn pre-check
  "Deterministic. The allowlist and the retention policy come from
  configuration; the caller number and the consent flag come from the request."
  [configuration _session {:keys [op caller-number retain-recording?
                                  caller-consented-to-recording? slot]
                           posture' :posture
                           :as request}]
  (when-not (contains? ops op)
    (refuse :voice/op-unsupported (str "未対応の op です: " op)))
  ;; The cross-domain gate, before anything op-specific and only for the ops it
  ;; covers. Placing calls is a spend path, so a subject whose line has just
  ;; changed hands does not get to use it -- toll fraud is the takeover's
  ;; objective through a different meter. An absent posture REFUSES rather than
  ;; passing, which is what stops the invariant being bypassed by not asking.
  (when (posture/restricts? authority-key op)
    (when-not (:authority/posture posture')
      (refuse :voice/posture-unknown
              "cross-domain posture が不明なままでは事前検査できません（authority.posture/subject-posture を渡すこと）"))
    (when (posture/refuses? posture' authority-key op)
      (refuse :voice/control-change-hold
              (str "同一 subject の支配権変更（eSIM transfer / port-out）によりこの操作は保留されます: "
                   (pr-str (:authority/signals posture'))))))
  ;; The outbound path shares none of the inbound checks below: running them
  ;; would test an INBOUND caller allowlist against an OUTBOUND destination,
  ;; which would both refuse legitimate calls and, worse, read as though the
  ;; destination had been checked against something.
  (if (= :call/originate op)
    (originate-pre-check configuration request)
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
      ;; denwaban's G1 rule, now read from kotoba.phone rather than restated:
      ;; retention needs the other party's explicit consent, and the inbound and
      ;; outbound sides used to carry a copy each.
      (when (seq (phone/recording-retention-issues
                  {:phone/retain? retain-recording?
                   :phone/consented? caller-consented-to-recording?}))
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
             :booking-owner "yotei"})))))

(defn material
  "The consent-bound string. Fixed field order, never a printed map.

  The outbound fields are appended rather than folded into the existing ones:
  what a human consents to when placing a call is the DESTINATION and the money,
  and a material that reused `caller` for both directions would let an approval
  for answering +81… be replayed as an approval for dialling it."
  [{:keys [op caller-number retain-recording? slot booking-owner
           destination destination-class estimated-minutes rate-minor-per-minute
           estimate-minor posture]}]
  (str "voice/v1"
       "|op=" op
       "|caller=" caller-number
       "|retain-recording=" (boolean retain-recording?)
       "|slot=" slot
       "|booking-owner=" booking-owner
       "|posture=" posture
       "|destination=" destination
       "|class=" destination-class
       "|minutes=" estimated-minutes
       "|rate=" rate-minor-per-minute
       "|estimate=" estimate-minor))

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
