(ns cloud.itonami.app.esign.retention
  "電子帳簿保存法の検索要件 — and the conflict with encryption, resolved by
  deciding rather than by hoping.

  ## The conflict, stated plainly

  電子帳簿保存法 requires retained electronic transaction data to be searchable
  by **取引年月日・取引金額・取引先** (date, amount, counterparty), with date and
  amount searchable as ranges and the three combinable.

  `cloud.itonami.app.esign.vault` encrypts the outline under a key that is
  destroyed on request, and `cloud.itonami.app.contracts` already records the
  same tension for the kagi vault: **what is sealed cannot be queried.** An
  index derived from encrypted content is either not an index or not encrypted.

  Three ways out exist and only one of them is honest here:

  1. Index the plaintext server-side. Undoes the encryption for exactly the
     fields most likely to be personal (a counterparty is often a person).
  2. Index on the client. Real, and it means search stops working when nobody is
     logged in — which is precisely when a tax inspection reads the archive.
  3. **Keep the three required fields as declared metadata, separate from the
     content, and say so.** They are the fields the law compels retention of, so
     they are not data the operator is choosing to expose — they are the record
     the obligation is about.

  This namespace is (3). The fields are entered or extracted **deliberately**,
  stored in the clear, and **survive `forget-content!`**. That last part is the
  design: a deletion request destroys the document a signer read, and the
  transaction record that the law requires be retained for seven years remains.
  Those are different objects and the law asks different things of them.

  ## What that costs, named rather than buried

  A counterparty name is personal data and it stays readable after erasure. That
  is a lawful-basis argument (retention obligation), not a technical one, and it
  is the operator's to make — so `entry` requires the caller to state a
  `:retention/basis`, and `redactable-fields` marks what may be minimised if the
  obligation lapses. A namespace that quietly kept the name and said nothing
  would be making that argument on the operator's behalf.

  ## What this is not

  Not a claim of compliance. It provides the *searchable index* limb; the
  真実性 limb is `cloud.itonami.app.esign.timestamp` (accredited timestamp) or
  an operator's 事務処理規程, and neither is decided here. `compliance-gaps`
  reports what is still missing rather than letting a green screen imply an
  answer nobody computed."
  (:require [clojure.string :as str]))

(def schema "cloud.itonami.app.esign.retention.v1")

(def required-fields
  "The three the law names, and the shape each must have to be searchable.

  `:transaction-date` is a plain `YYYY-MM-DD` string because range search over
  ISO dates is string comparison, and an epoch would need every reader to agree
  on a timezone before two records could be compared."
  {:transaction-date {:label "取引年月日" :form "YYYY-MM-DD"}
   :amount-minor {:label "取引金額" :form "最小通貨単位の整数"}
   :counterparty {:label "取引先" :form "文字列"}})

(def redactable-fields
  "Fields that may be minimised once the retention obligation lapses.

  Only `:counterparty` — a date and an amount are not personal data on their
  own, and the obligation is precisely about them. Listed so that a future
  minimisation job has a definition rather than a judgement call."
  #{:counterparty})

(def retention-years
  "Seven, the ordinary 法人税法 period for retained records. Longer periods
  exist (欠損金の繰越控除 makes it ten), so this is a DEFAULT an operator
  overrides rather than a rule this code knows."
  7)

(defn- blank? [v] (str/blank? (str v)))

(defn entry
  "One retention record, or a refusal listing what is missing.

  `:retention/basis` is required and free text: the operator states why these
  fields are retained in the clear. Required rather than defaulted because the
  answer is a legal position and a default would be this code taking one."
  [{:keys [envelope-id document-digest transaction-date amount-minor currency
           counterparty basis note]}]
  (let [missing (cond-> []
                  (blank? envelope-id) (conj :envelope-id)
                  (blank? transaction-date) (conj :transaction-date)
                  (nil? amount-minor) (conj :amount-minor)
                  (blank? counterparty) (conj :counterparty)
                  (blank? basis) (conj :retention/basis))]
    (when (seq missing)
      (throw (ex-info (str "検索要件に必要な項目が足りません: "
                           (str/join ", " (map #(get-in required-fields [% :label] (name %))
                                               missing)))
                      {:type :retention/incomplete :missing missing})))
    (when-not (re-matches #"\d{4}-\d{2}-\d{2}" (str transaction-date))
      (throw (ex-info "取引年月日は YYYY-MM-DD で記録します（範囲検索が文字列比較になるため）。"
                      {:type :retention/bad-date :value transaction-date})))
    {:retention/schema schema
     :retention/envelope-id envelope-id
     ;; The link to the evidence, and the only field here that is a digest. The
     ;; index points AT the evidence rather than duplicating it.
     :retention/document-digest document-digest
     :retention/transaction-date (str transaction-date)
     :retention/amount-minor (long amount-minor)
     ;; Per currency, never converted — the same rule `contracts.clj` states:
     ;; a rate is today's fact and not the transaction's.
     :retention/currency (or (not-empty (str currency)) "JPY")
     :retention/counterparty (str counterparty)
     :retention/basis (str basis)
     :retention/note (when-not (blank? note) (str note))
     :retention/recorded-at nil}))

(defn index
  "Every retention record in `state`, newest first."
  [state]
  (->> (vals (get-in state [:esign :retention] {}))
       (sort-by :retention/transaction-date)
       reverse
       vec))

(defn- in-range? [value from to]
  (and (or (blank? from) (<= (compare from value) 0))
       (or (blank? to) (<= (compare value to) 0))))

(defn search
  "The search the law requires: by date range, amount range, counterparty, and
  any combination of the three.

  Combination is the requirement people implement last — 検索要件 asks for the
  three to be usable together, not one at a time. So this takes them together
  and applies every one that is present.

  Counterparty matching is substring and case-folded. An inspector searching a
  company name should not have to reproduce the punctuation the operator typed."
  [state {:keys [date-from date-to amount-min amount-max counterparty currency]}]
  (let [needle (some-> counterparty str str/trim str/lower-case not-empty)]
    (->> (index state)
         (filter (fn [e]
                   (and (in-range? (:retention/transaction-date e) date-from date-to)
                        (or (nil? amount-min) (>= (:retention/amount-minor e) amount-min))
                        (or (nil? amount-max) (<= (:retention/amount-minor e) amount-max))
                        (or (nil? currency) (= currency (:retention/currency e)))
                        (or (nil? needle)
                            (str/includes? (str/lower-case (:retention/counterparty e))
                                           needle)))))
         vec)))

(defn compliance-gaps
  "What is still missing for 電子帳簿保存法 on this envelope, as a list.

  A LIST and not a boolean, and never a green tick. The two limbs are
  independent — 可視性 (this namespace) and 真実性 (an accredited timestamp, a
  correction-and-deletion record, or a written procedure) — and an app that
  reported one number would let a satisfied 検索要件 read as compliance nobody
  established.

  `:procedure-documented?` is the operator's own 事務処理規程 and this code
  cannot observe it, so it is an input rather than a check."
  [{:keys [retention-entry timestamp-attestation procedure-documented?]}]
  (cond-> []
    (nil? retention-entry)
    (conj {:limb :可視性 :gap :no-retention-entry
           :detail "取引年月日・取引金額・取引先が記録されていないため、検索要件を満たしません。"})

    (and (not= :accredited timestamp-attestation)
         (not procedure-documented?))
    (conj {:limb :真実性
           :gap :no-tamper-evidence-measure
           :detail (str "真実性の確保措置がありません。認定タイムスタンプ（現在: "
                        (or timestamp-attestation :app-attested)
                        "）、訂正削除の記録が残るシステム、または事務処理規程のいずれかが要ります。")})

    (and (not= :accredited timestamp-attestation) procedure-documented?)
    (conj {:limb :真実性
           :gap :relying-on-procedure
           :severity :informational
           :detail "事務処理規程に依拠しています。認定タイムスタンプではありません。"})))
