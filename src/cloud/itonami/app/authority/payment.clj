(ns cloud.itonami.app.authority.payment
  "The settlement adapter for `cloud.itonami.app.authority`.

  Same four stages as the card and eSIM adapters, and the same ceiling: a
  committed proposal records a GOVERNED SETTLEMENT RECORD. It does not move
  money. Nothing in this app holds a banking credential, this adapter stores the
  payee's account only as a digest, and the transfer itself is done by a human in
  their bank. What the app owns is the record: what was owed, from which account,
  approved by whom, bound to a Passkey assertion, and refused if it should never
  have been asked.

  The deterministic gate here is the funds check, and it is not hypothetical.
  This organization's June 2026 advisory-fee direct debit failed for insufficient
  funds and was rolled into July, which is the payable this adapter was built
  for. So the pre-check refuses BEFORE a human is asked when the recorded balance
  does not cover the amount -- the card adapter's daily-limit gate, pointed at the
  failure that actually happened.

  Every fact this pre-check needs is PASSED IN, never fetched:

    :funding-account     the account on file        (server-computed)
    :balance             the last recorded balance  (server-computed)
    :balance-freshness   how old that balance is    (server-computed)
    :already-settled?    is this reference paid?    (server-computed)
    :scheduled-debit     what is already leaving    (server-computed)
    :posture             cross-domain posture       (server-computed)

  That keeps it pure -- its answer cannot change between review and consent -- and
  it is also the enforcement point: `cloud.itonami.app.authority.api` OVERWRITES
  all six, so a client cannot send `{:balance {...}}` and buy itself an
  approval. Each is REQUIRED, and an absent one refuses. An absent balance is not
  an unlimited one, and an absent settlement history is not a clean one.

  `:scheduled-debit` is required in a weaker sense than the others, and the
  difference is deliberate (ADR-2608041200 D5). It must be STATED, so a caller
  cannot skip it; but `{:funding/status :never-recorded}` is a legal answer that
  does not refuse. Refusing it would mean no organization could propose a
  payment until someone had imported a card statement -- including organizations
  with no card -- which is a gate that breaks the ordinary case to guard the
  rare one. What it does instead is travel onto the proposal, so a reader can
  see that the funds gate ran on the balance alone."
  (:require [clojure.string :as str]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [cloud.itonami.app.funding :as funding]))

(def authority-key :payment)

(def ops
  "Accepted operations and the Passkey context type each binds under. An
  allowlist -- an unrecognised op is refused, never defaulted."
  {:payment/settle :payment/settlement-approval})

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- blank? [value]
  (str/blank? (str (or value ""))))

(defn payee-summary
  "What is kept about the party being paid.

  The account NUMBER is reduced to last four digits and a digest here, in the
  pre-check, so the number never reaches the stored proposal. A proposal lives in
  `state.edn`; a payee account number in `state.edn` would be a payment
  instrument this app has no business holding, and the digest answers the only
  question consent needs -- 'the account you approved is the account on file'."
  [{:keys [name institution branch account-type number]}]
  (let [fingerprint (funding/account-fingerprint number)]
    (cond-> {:name (str/trim (str name))
             :institution (some-> institution str str/trim not-empty)
             :branch (some-> branch str str/trim not-empty)
             :account-type (some-> account-type keyword)}
      fingerprint (assoc :number-last4 (:last4 fingerprint)
                         :number-digest (:digest fingerprint)))))

(defn pre-check
  "Deterministic. No clock, no network, no store read -- see the namespace
  docstring for why every fact arrives as an argument.

  The order of refusals is deliberate. Shape first, then the account, then
  whether this was already paid, and only then the money. A duplicate settlement
  must refuse as a duplicate even when the balance would also refuse it: telling
  someone they are short of funds for an invoice they already paid sends them to
  the wrong problem."
  [_configuration _session {:keys [op amount-minor currency payee reference
                                   due-date memo funding-account balance
                                   balance-freshness already-settled?
                                   scheduled-debit]
                            posture' :posture}]
  (when-not (contains? ops op)
    (refuse :payment/op-unsupported (str "未対応の op です: " op)))

  ;; Settlement is a spend path, so it inherits the cross-domain hold that
  ;; ADR-2607300300 D4 put on card spend: whoever moved the line has the second
  ;; factor, and 'move the line, then spend' does not care whether the spending
  ;; happens on a card or by bank transfer. Excluding payments here would have
  ;; left the invariant with a door next to it.
  (when-not (:authority/posture posture')
    (refuse :payment/posture-unknown
            "cross-domain posture が不明なままでは事前検査できません（authority.posture/subject-posture を渡すこと）"))
  (when (posture/restricted? posture')
    (refuse :payment/spend-hold
            (str "同一 subject の eSIM ownership transfer により支払いは保留されます: "
                 (pr-str (:authority/signals posture')))))

  (when (blank? (:name payee))
    (refuse :payment/payee-missing "支払先の名称が必要です"))
  ;; Without a reference there is nothing to deduplicate on, so a settlement
  ;; without one could be recorded twice and neither record would be wrong.
  (when (blank? reference)
    (refuse :payment/reference-missing
            "請求書番号などの reference が必要です（重複計上を防げないため）"))
  (when-not (and (integer? amount-minor) (pos? amount-minor))
    (refuse :payment/amount-invalid
            "amount-minor は最小通貨単位の正の整数です（JPY は円、USD はセント）"))

  (when-not (map? funding-account)
    (refuse :payment/account-not-linked
            "支払元の funding account が organization に紐付いていません"))
  (when-not (= :active (:status funding-account))
    (refuse :payment/account-inactive
            (str "支払元口座が利用できません: " (:status funding-account))))

  (let [currency (or (some-> currency str str/upper-case not-empty)
                     (:currency funding-account))]
    (when-not (= currency (:currency funding-account))
      (refuse :payment/currency-mismatch
              (str "支払元口座の通貨は " (:currency funding-account) " です: " currency)))

    ;; `already-settled?` must be stated, not merely absent. An absent value
    ;; would be falsey and would wave every duplicate through -- the same trap
    ;; the card adapter closes by requiring `daily-limit` rather than defaulting
    ;; it.
    (when-not (boolean? already-settled?)
      (refuse :payment/settlement-history-unknown
              "この reference の決済履歴が不明なままでは事前検査できません"))
    (when already-settled?
      (refuse :payment/duplicate-settlement
              (str "この reference は既に決済記録済みです: " reference)))

    (when-not (funding/fresh? balance-freshness)
      (refuse :payment/balance-unknown
              (str "残高が確認できません（"
                   (name (or (:funding/status balance-freshness) :absent))
                   "）。支払元口座の残高を記録してから再度提案してください")))
    ;; The gate this adapter exists for. Reached only with a balance that is
    ;; both present and fresh, so this comparison is against a real number.
    (when (< (:amount-minor balance) amount-minor)
      (refuse :payment/insufficient-funds
              (str "残高が不足しています: " (:amount-minor balance)
                   " < " amount-minor " " currency)))

    ;; The second funds gate, and it looks FORWARD rather than at today. The
    ;; balance can cover this payment and still not survive the card billing
    ;; that is already fixed for next week -- which is the same failure as June
    ;; 2026 arriving through a different door.
    ;;
    ;; Stated, not merely present: a caller who forgot to say what is scheduled
    ;; must be refused, exactly as one who forgot `already-settled?` is. But
    ;; `:never-recorded` is a legal statement and passes, on the balance alone.
    (when-not (map? scheduled-debit)
      (refuse :payment/scheduled-debit-unstated
              "引き落とし予定が不明なままでは事前検査できません（不明なら :never-recorded と明示すること）"))
    (let [available (funding/available balance scheduled-debit)]
      (when (and available (< available amount-minor))
        (refuse :payment/insufficient-available-funds
                (str "確定済みの引き落とし予定を差し引くと残高が不足します: "
                     (:amount-minor balance) " - "
                     (:funding/amount-minor scheduled-debit) " = " available
                     " < " amount-minor " " currency))))

    {:op op
     :funding-account-id (:id funding-account)
     :funding-account-label (:label funding-account)
     :funding-account-last4 (:number-last4 funding-account)
     :payee (payee-summary payee)
     :reference (str/trim (str reference))
     :amount-minor amount-minor
     :currency currency
     :exponent (get funding/currency-exponents currency)
     :due-date (some-> due-date str str/trim not-empty)
     :memo (some-> memo str str/trim not-empty)
     :posture (:authority/posture posture')
     ;; Recorded so the approval can be read later against the balance it was
     ;; judged on. It is NOT part of the digest -- see `material`.
     :balance-at-review {:amount-minor (:amount-minor balance)
                         :as-of (:as-of balance)
                         :source (:source balance)}
     ;; And what was known to be leaving it. `:never-recorded` here is the
     ;; record that the second gate did not run -- without it, a proposal judged
     ;; on the balance alone is indistinguishable from one judged on both.
     :scheduled-debit-at-review
     {:status (:funding/status scheduled-debit)
      :amount-minor (:funding/amount-minor scheduled-debit)
      :cycles (:funding/cycles scheduled-debit)
      :unreconciled (:funding/unreconciled scheduled-debit)
      :available-minor (funding/available balance scheduled-debit)}}))

(defn material
  "The consent-bound string. Fixed field order, never a printed map -- a map's
  iteration order stops being stable past the array-map threshold, so a printed
  map would give a digest that depends on how many keys it happened to have.

  Every field that changes WHAT WOULD HAPPEN is here: the amount, the currency,
  who is paid, which of their accounts, which account it is drawn on, and what it
  settles. The posture is here for the same reason the card adapter puts it
  there -- otherwise a proposal reviewed under `:normal` could be committed after
  a SIM swap moved the subject to `:restricted`, and the digest would still
  match.

  THE BALANCE IS DELIBERATELY NOT HERE. It is a precondition, not a term of what
  was agreed, and binding it would invalidate a legitimate approval every time an
  unrelated deposit landed. The cost of leaving it out is stated plainly rather
  than hidden: the funds gate is evaluated at REVIEW time, so a balance can fall
  between review and commit and this adapter will not notice. The second gate --
  the authority's own governor -- is what is expected to see that, and a human
  reading a committed record must not read it as proof the funds were there when
  it committed."
  [{:keys [op funding-account-id payee reference amount-minor currency due-date
           memo posture]}]
  (str "payment/v1"
       "|op=" op
       "|posture=" posture
       "|from=" funding-account-id
       "|payee=" (:name payee)
       "|payee-institution=" (:institution payee)
       "|payee-branch=" (:branch payee)
       "|payee-account-type=" (:account-type payee)
       "|payee-account=" (:number-digest payee)
       "|reference=" reference
       "|amount-minor=" amount-minor
       "|currency=" currency
       "|due=" due-date
       "|memo=" memo))

(defn domain-with
  "The authority domain map with both hand-offs injected explicitly. `commit-fn`
  hands off to the settlement authority, which is propose-only like every other
  actor in this fleet -- so a committed proposal records a governed settlement
  record, NOT a completed transfer. `status-fn` asks what became of a pending one.

  `:authority/status` became part of the spine's contract when refresh! landed: an
  authority that can answer \"pending\" and cannot be asked what became of it
  leaves proposals nothing can move."
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

;; ---------------------------------------------------------------------------
;; settlement history
;; ---------------------------------------------------------------------------

(defn settled-references
  "The references this organization has already committed a settlement for.

  ORGANIZATION-scoped, not user-scoped, and that difference is the whole point:
  an invoice is owed by the company, so a second member settling the same
  reference is exactly the duplicate this set exists to catch. Reading only the
  current user's proposals would make the check pass for the one case it is for.

  Only `:committed` counts. A rejected, refused or still-pending proposal has not
  settled anything, and treating those as settled would strand a payable that a
  governor refused for an unrelated reason."
  [proposals organization-id]
  (into #{}
        (comp (filter #(= :payment (:authority %)))
              (filter #(= organization-id (:organization-id %)))
              (filter #(= :committed (:status %)))
              (keep #(get-in % [:value :reference])))
        proposals))
