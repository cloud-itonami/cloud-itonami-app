(ns cloud.itonami.app.authority.card
  "The card-issuing adapter for `cloud.itonami.app.authority`.

  Same shape as the eSIM adapter, and the same discipline: the pre-check
  delegates every fact to `kotoba-lang/card`, which itself MIRRORS the issuer
  side's own tables:

    card reference checksum -> kotoba.card/luhn-valid?     (ISO/IEC 7812-1)
    lifecycle reachability  -> kotoba.card.lifecycle        (mirrors
                               cardissuing.governor/legal-predecessor and
                               cardissuing.store/lifecycle!)
    authorization decision  -> kotoba.card/authorization, approved?,
                               authorized-amount

  Two rules from that table are easy to guess wrong and are therefore worth
  restating where a reader of this adapter will see them: `:activate` is legal
  only from `:issued` (unblocking a blocked card is NOT an activate -- the
  recovery path from `:blocked` is `:reissue`), and `:reissue` is legal only from
  `:blocked`, lands in `:active`, and MINTS A NEW CARD REFERENCE in the same
  operation. This adapter does not encode either rule; it asks the library.

  The spend-limit check is the card analogue of bitcoin's max-fee gate: a
  deterministic arithmetic refusal that happens BEFORE a human is asked, so a
  request that cannot proceed never becomes an approval prompt."
  (:require [cloud.itonami.app.authority :as authority]
            [kotoba.card :as card]
            [kotoba.card.lifecycle :as lifecycle]))

(def authority-key :card)

(def ops
  "Accepted operations and the Passkey context type each binds under. An
  allowlist -- an unrecognised op is refused, never defaulted."
  {:card/issue           :card/issuance
   :card/lifecycle       :card/lifecycle-transition
   :authorization/decide :card/authorization-approval
   :dispute/initiate     :card/dispute-initiation})

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- check-reference [reference]
  ;; The issuer side works in synthetic card references, never real PANs, and
  ;; those references carry a real Luhn check digit -- so this is a genuine
  ;; recomputation, not a shape test.
  (when-not (card/luhn-valid? (str reference))
    (refuse :card/reference-invalid
            "card reference が Luhn チェックディジットを満たしません")))

(defn pre-check
  "Deterministic. `:state` (the card's recorded lifecycle state) and
  `:daily-limit` / `:spent-today` come from the caller's read model rather than
  being fetched here, so this stays pure -- and so its answer cannot change
  between review and consent."
  [_configuration _session {:keys [op card-reference event state amount currency
                                   daily-limit spent-today cardholder-id
                                   transaction-id reason]}]
  (when-not (contains? ops op)
    (refuse :card/op-unsupported (str "未対応の op です: " op)))
  (case op
    :card/issue
    (do (when-not cardholder-id
          (refuse :card/cardholder-missing "cardholder-id が必要です"))
        {:op op :cardholder-id cardholder-id})

    :card/lifecycle
    (do (check-reference card-reference)
        (let [outcome (lifecycle/apply-event state event :card-reference card-reference)]
          (when-not (:card/ok? outcome)
            (refuse :card/transition-unreachable
                    (str "記録上の状態から到達できない遷移です: "
                         (pr-str (mapv :card/issue (:card/issues outcome))))))
          (cond-> {:op op :card-reference card-reference :event event
                   :from (:card/from outcome) :to (:card/to outcome)}
            ;; Surfaced because a reissue creates the replacement card in the
            ;; same operation. A consent that does not say so is understating
            ;; what the human is agreeing to.
            (:card/mints-successor? outcome)
            (assoc :mints-successor? true))))

    :authorization/decide
    (do (check-reference card-reference)
        (when-not (and (number? amount) (pos? amount))
          (refuse :card/amount-invalid "amount は正の数でなければなりません"))
        (when-not (and (number? daily-limit) (number? spent-today))
          (refuse :card/limit-unknown
                  "daily-limit / spent-today が不明なままでは事前検査できません"))
        ;; The deterministic gate, the card analogue of bitcoin's max-fee check.
        ;; An unknown limit refuses rather than passing -- an absent limit is not
        ;; an unlimited one.
        (when (> (+ spent-today amount) daily-limit)
          (refuse :card/daily-limit-exceeded
                  (str "日次上限を超えます: " spent-today " + " amount
                       " > " daily-limit)))
        {:op op :card-reference card-reference :amount amount
         :currency (or currency "USD") :transaction-id transaction-id})

    :dispute/initiate
    (do (check-reference card-reference)
        (when-not transaction-id
          (refuse :card/transaction-missing "dispute には transaction-id が必要です"))
        {:op op :card-reference card-reference
         :transaction-id transaction-id :reason reason})))

(defn material
  "The consent-bound string. Fixed field order, never a printed map -- see the
  eSIM adapter's `material` for why. Every field that changes what would happen
  appears here."
  [{:keys [op card-reference event from to amount currency transaction-id
           cardholder-id reason mints-successor?]}]
  (str "card/v1"
       "|op=" op
       "|reference=" card-reference
       "|event=" event
       "|from=" from
       "|to=" to
       "|mints-successor=" (boolean mints-successor?)
       "|amount=" amount
       "|currency=" currency
       "|transaction=" transaction-id
       "|cardholder=" cardholder-id
       "|reason=" reason))

(defn domain
  "The authority domain map. `commit-fn` hands off to
  `cloud-itonami/cloud-itonami-card-issuing`, whose ops are propose-only -- so a
  committed proposal records a governed proposal, not an issued card."
  [commit-fn]
  {:authority/key authority-key
   :authority/context-type #(get ops %)
   :authority/pre-check pre-check
   :authority/material material
   :authority/commit! commit-fn})

(defn review!
  [commit-fn configuration session request]
  (authority/review! (domain commit-fn) configuration session request))
