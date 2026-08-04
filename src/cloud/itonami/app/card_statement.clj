(ns cloud.itonami.app.card-statement
  "Cards this organization did NOT issue, and the statements they produce.

  `cloud.itonami.app.authority.card` is the other kind: a governed proposal to
  the issuer this fleet operates. This namespace is the opposite direction, and
  the distinction is the whole design (ADR-2608041200 D1):

    authority.card    we decide, then it happens      -> propose/consent/commit
    card-statement    it happened, then we record it  -> attest / observe

  An external issuer -- MoneyForward ビジネスカード is the one this was built for
  -- authorises at the point of sale, against ITS OWN per-card limits. Putting
  that behind the consent spine would make three things untrue at once: our
  daily-limit gate would not affect the decision, the Passkey would be asked
  after the money moved, and `:committed` would stop meaning `a governed
  proposal was recorded`. So none of that is here. AN EXTERNAL ISSUER IS NOT AN
  AUTHORITY; IT IS AN OBSERVED FACT SOURCE.

  What IS here is the funding-plane discipline, copied deliberately from
  `cloud.itonami.app.funding` rather than reinvented:

  1. NO PAYMENT INSTRUMENT. A card number may be supplied once; what is kept is
     its last four digits and a SHA-256 digest, through `funding/account-
     fingerprint`. Same reason: an app that stored the number would be one
     credential away from being able to actuate.

  2. AN UNKNOWN FIGURE IS NOT ZERO. A card whose statement nobody has imported
     has an unknown spend, and unknown refuses to be summed as 0.

  3. NO CONNECTOR. There is no login here, no cookie, no scraping and no stored
     credential. The only entrance is `ingest!`, which takes CSV TEXT that a
     human exported and handed over -- the same shape `kotoba-lang/kakeibo`
     states for itself and `mf-ingest-extension` implements. Measured 2026-08-04:
     the card's own outward interface is a CSV export (利用明細 / 入出金履歴 /
     請求明細 / カード); no public API is documented. Choosing CSV is also what
     keeps this portable when the issuer changes -- a different card's export
     lands in the same function.

  The ceiling, stated because a UI must not overstate it: nothing here actuates.
  This plane cannot stop a card, change a limit, or hold a billing cycle. Those
  happen in the issuer's own console, by a person. What is recorded is a COPY of
  what the issuer showed; where the copy and the issuer disagree, the issuer is
  right and the disagreement is the thing worth displaying."
  (:require [clojure.string :as str]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.store :as store])
  (:import [java.math BigDecimal RoundingMode]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.card-statement.v1")

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- blank? [value]
  (str/blank? (str (or value ""))))

;; ---------------------------------------------------------------------------
;; CSV -- pure
;; ---------------------------------------------------------------------------

(defn parse-csv
  "RFC 4180 rows, as vectors of strings. PURE.

  Takes TEXT, not bytes: an export may be Shift_JIS or UTF-8-with-BOM, and
  deciding which is the caller's job -- guessing an encoding here would corrupt
  every 加盟店 name in the file and do it silently. The BOM is stripped, because
  a BOM left on the first header makes that one column fail to match while every
  other column matches, which reads as a mapping bug rather than an encoding one.

  Blank lines are skipped rather than becoming empty rows: an export that ends
  with a newline is not an export with a trailing empty transaction."
  [text]
  (let [s (str/replace (str text) #"^﻿" "")
        n (count s)]
    (loop [i 0
           field (StringBuilder.)
           row []
           rows []
           quoted? false]
      (let [^StringBuilder fb field]
        (if (>= i n)
          (let [row' (conj row (str fb))]
            (if (and (= 1 (count row')) (str/blank? (first row')))
              rows
              (conj rows row')))
          (let [c (.charAt s i)]
            (cond
              quoted?
              (cond
                (and (= \" c) (< (inc i) n) (= \" (.charAt s (inc i))))
                (recur (+ i 2) (.append fb \") row rows true)

                (= \" c) (recur (inc i) fb row rows false)
                :else (recur (inc i) (.append fb c) row rows true))

              (= \" c) (recur (inc i) fb row rows true)
              (= \, c) (recur (inc i) (StringBuilder.) (conj row (str fb)) rows false)

              (or (= \newline c) (= \return c))
              (let [i' (if (and (= \return c) (< (inc i) n)
                                (= \newline (.charAt s (inc i))))
                         (+ i 2)
                         (inc i))
                    row' (conj row (str fb))]
                (if (and (= 1 (count row')) (str/blank? (first row')))
                  (recur i' (StringBuilder.) [] rows false)
                  (recur i' (StringBuilder.) [] (conj rows row') false)))

              :else (recur (inc i) (.append fb c) row rows false))))))))

;; ---------------------------------------------------------------------------
;; column mapping -- declared, never guessed
;; ---------------------------------------------------------------------------

(def default-transaction-columns
  "Canonical key -> the CSV header expected in a 利用明細 export.

  DECLARED, NOT VERIFIED. The issuer's support page shows the export screen but
  does not publish the column list, and this workspace has not yet run a real
  export (ADR-2608041200 records that as the first implementation step). So this
  map is a STATEMENT OF WHAT WE EXPECT, and `column-index` refuses a header row
  that does not match it rather than falling back to positions.

  That refusal is the point. A parser that guesses which column is the amount
  will one day guess a different column, produce a smaller number, and pass the
  funds gate with it. A parser that stops and says `expected 利用金額, found
  [...]` costs one config line and cannot be wrong quietly.

  Override per deployment or per file: `(ingest! ... {:columns {...}})`."
  {:used-at        "利用日"
   :merchant       "利用店名"
   :amount         "利用金額"
   :card-last4     "カード番号下4桁"
   :transaction-id "取引ID"})

(def required-transaction-columns
  "Without these three there is no transaction. `:transaction-id` is NOT here:
  it is what makes deduplication exact, and its absence is handled (see
  `natural-key`) rather than refused -- refusing would reject an export the
  issuer genuinely does not offer."
  #{:used-at :merchant :amount})

(defn column-index
  "canonical key -> position in this header row, or a refusal naming both sides.

  PURE. The refusal carries the headers it actually saw, because the one thing a
  person needs in order to fix a mapping is what the file really said."
  ([headers] (column-index headers default-transaction-columns))
  ([headers columns]
   (let [trimmed (mapv #(str/trim (str %)) headers)
         index (into {}
                     (keep (fn [[k header]]
                             (when-let [i (some (fn [[i h]] (when (= h header) i))
                                                (map-indexed vector trimmed))]
                               [k i])))
                     columns)
         missing (remove index required-transaction-columns)]
     (when (seq missing)
       (refuse :card-statement/columns-missing
               (str "CSV の見出しが期待と一致しません。見つからない列: "
                    (str/join ", " (map #(get columns %) (sort-by name missing)))
                    "／実際の見出し: " (str/join ", " trimmed))))
     index)))

;; ---------------------------------------------------------------------------
;; field parsing -- pure, and refusing rather than defaulting
;; ---------------------------------------------------------------------------

(defn parse-amount-minor
  "`¥1,234` / `1,234` / `-1,234` / `12.34` -> an integer in the currency's minor
  unit. PURE. Returns nil for anything it cannot read.

  nil rather than 0, and the caller refuses on nil. A cell this function could
  not parse is a transaction of unknown size; recording it as ¥0 would make a
  statement total that is wrong in the direction nobody checks.

  The sign is kept. A refund is a real row and a statement that dropped it would
  overstate the spend."
  [value exponent]
  (when (and (integer? exponent) (not (blank? value)))
    (let [cleaned (-> (str value)
                      str/trim
                      (str/replace "," "")
                      (str/replace "，" "")
                      (str/replace "¥" "")
                      (str/replace "￥" "")
                      (str/replace "円" "")
                      (str/replace " " "")
                      (str/replace " " ""))]
      (when (re-matches #"-?\d+(\.\d+)?" cleaned)
        (try
          (-> (BigDecimal. cleaned)
              (.movePointRight (int exponent))
              ;; UNNECESSARY rather than HALF_UP: `12.345` in a 2-exponent
              ;; currency is not a rounding question, it is a column that is not
              ;; the amount. Throwing sends it to the nil branch and the caller
              ;; refuses, which is the honest outcome.
              (.setScale 0 RoundingMode/UNNECESSARY)
              (.longValueExact))
          (catch ArithmeticException _ nil))))))

(def ^:private date-pattern
  #"(\d{4})[/-](\d{1,2})[/-](\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?")

(defn parse-used-at
  "`2026/08/01` or `2026-08-01 13:45` -> `{:date \"2026-08-01\" :time \"13:45\"}`.
  PURE. nil when it cannot be read.

  The time is kept SEPARATELY and may be absent, because whether it is present
  decides whether two same-day rows at the same merchant for the same amount can
  be told apart -- see `natural-key`. Folding a missing time into 00:00 would
  make them look distinguishable when they are not."
  [value]
  (when-not (blank? value)
    (when-let [[_ y m d hh mm ss] (re-find date-pattern (str/trim (str value)))]
      (cond-> {:date (format "%04d-%02d-%02d"
                             (parse-long y) (parse-long m) (parse-long d))}
        hh (assoc :time (cond-> (format "%02d:%s" (parse-long hh) mm)
                          ss (str ":" ss)))))))

;; ---------------------------------------------------------------------------
;; identity -- the deduplication key
;; ---------------------------------------------------------------------------

(defn- sha256 [s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes ^String s StandardCharsets/UTF_8))
      (->> (.encodeToString (.withoutPadding (Base64/getUrlEncoder))))))

(defn natural-key
  "The string a transaction's `:external-reference` is the digest of. PURE.

  Fixed field order and never a printed map, for the reason
  `cloud.itonami.app.authority/digest` gives: a map's iteration order stops
  being stable past the array-map threshold, so a printed map hashes differently
  depending on how many keys it happens to have.

  TWO SHAPES, and which one is used decides how much this key is worth:

    with a transaction id     card | txn-id            exact
    without one               card | date | time | merchant | amount   best effort

  The second cannot distinguish two purchases at the same merchant, on the same
  day, for the same amount, when the export carries no time -- so the caller
  marks those rows `:ambiguous?` instead of pretending. A ROW NUMBER IS NOT USED
  AS A KEY: it changes with every export."
  [{:keys [card-digest transaction-id date time merchant amount-minor]}]
  (if-not (blank? transaction-id)
    (str "card-txn/v1|card=" card-digest "|txn=" (str/trim (str transaction-id)))
    (str "card-txn/v1|card=" card-digest
         "|date=" date
         "|time=" time
         "|merchant=" (str/trim (str merchant))
         "|amount-minor=" amount-minor)))

(defn external-reference [key-string] (sha256 key-string))

;; ---------------------------------------------------------------------------
;; rows -> transactions -- pure
;; ---------------------------------------------------------------------------

(defn- cell [row index k]
  (when-let [i (get index k)]
    (when (< i (count row))
      (str/trim (str (nth row i))))))

(defn parse-transactions
  "CSV text -> `{:transactions [...] }`, or a refusal. PURE: no store, no clock.

  Every row that parses becomes a transaction; a row that does not refuses the
  WHOLE ingest rather than being skipped. A skipped row is a transaction that
  silently is not in the total, and the total is what a person reads.

  `:ambiguous?` marks rows that carry no transaction id and whose natural key
  collided with another row in the same file. A collision means every field the
  export gave us matched, so those rows are genuinely indistinguishable from
  each other -- the export did not contain enough to tell them apart. They are
  still ingested (the money was really spent) and are separated by an occurrence
  index, which is stable for the SAME file and is not claimed to be stable
  across exports of different periods. That is what the mark is for: a human
  decides, rather than the count silently changing under a key nobody checked.

  A collision when there IS a transaction id means the same transaction appears
  twice in one file. Those collapse onto one reference, which is correct -- they
  are one transaction -- and surface as a duplicate in the ingest report."
  [text {:keys [card-digest currency columns]}]
  (let [exponent (get funding/currency-exponents currency)
        _ (when-not exponent
            (refuse :card-statement/currency-unsupported
                    (str "未対応の通貨です: " currency)))
        rows (parse-csv text)
        _ (when (empty? rows)
            (refuse :card-statement/empty "CSV に行がありません"))
        index (column-index (first rows) (or columns default-transaction-columns))
        parsed
        (vec (for [[n row] (map-indexed vector (rest rows))
                   :let [used (parse-used-at (cell row index :used-at))
                         merchant (cell row index :merchant)
                         amount (parse-amount-minor (cell row index :amount) exponent)
                         txn-id (cell row index :transaction-id)]]
               (do
                 (when-not used
                   (refuse :card-statement/date-invalid
                           (str (+ n 2) " 行目の利用日が読めません: "
                                (pr-str (cell row index :used-at)))))
                 (when (blank? merchant)
                   (refuse :card-statement/merchant-missing
                           (str (+ n 2) " 行目の利用店名が空です")))
                 (when-not amount
                   (refuse :card-statement/amount-invalid
                           (str (+ n 2) " 行目の利用金額が読めません: "
                                (pr-str (cell row index :amount)))))
                 {:used-at (:date used)
                  :used-at-time (:time used)
                  :merchant merchant
                  :amount-minor amount
                  :currency currency
                  :exponent exponent
                  :transaction-id (not-empty (str txn-id))
                  :card-last4 (not-empty (str (cell row index :card-last4)))})))
        keyed (mapv #(let [k (natural-key (assoc % :card-digest card-digest
                                                 :date (:used-at %)
                                                 :time (:used-at-time %)))]
                       (assoc % :natural-key k :external-reference (external-reference k)))
                    parsed)
        collisions (into #{}
                         (keep (fn [[k occurrences]]
                                 (when (< 1 (count occurrences)) k))
                               (group-by :natural-key keyed)))
        ;; The occurrence index is applied ONLY to collided rows that have no
        ;; transaction id, so an unambiguous file produces exactly the same
        ;; references however many times it is imported.
        seen (volatile! {})]
    {:schema "cloud.itonami.app.card-statement.parse.v1"
     :transactions
     (mapv (fn [t]
             (let [k (:natural-key t)
                   ambiguous? (and (contains? collisions k)
                                   (blank? (:transaction-id t)))
                   k' (if ambiguous?
                        (str k "|occurrence="
                             (get (vswap! seen update k (fnil inc 0)) k))
                        k)]
               (cond-> (-> t
                           (dissoc :natural-key)
                           (assoc :external-reference (external-reference k')))
                 ambiguous? (assoc :ambiguous? true
                                   :ambiguous-reason :indistinguishable-rows))))
           keyed)}))

;; ---------------------------------------------------------------------------
;; store -- cards
;; ---------------------------------------------------------------------------

(defn- card-path [card-id] [:card-statement :cards card-id])
(defn- transaction-path [ref] [:card-statement :transactions ref])
(defn- cycle-path [cycle-id] [:card-statement :cycles cycle-id])

(defn- require-organization! [session]
  (or (:organization-id session)
      (refuse :identity/unauthenticated
              "card statement には organization に属する session が必要です")))

(defn link-card!
  "Record a card this organization holds but did not issue.

  `:funding-account-id` is the account its billing debits -- the join that makes
  D5 possible. It is not required at link time (a card may be linked before
  anyone has said which account pays it) and its absence simply means no
  scheduled debit can be attributed, which is `:never-recorded`, not zero.

  `:limit-observed` records the issuer's own per-card caps if someone read them
  off the console. RECORDED, NOT ENFORCED: this app does not decide that card's
  authorizations, and a limit it cannot enforce must not look like a gate."
  [session {:keys [label issuer holder number currency funding-account-id
                   limit-observed]}]
  (let [organization-id (require-organization! session)
        currency (or (some-> currency str str/upper-case not-empty) "JPY")]
    (when (blank? issuer)
      (refuse :card-statement/issuer-missing "カード発行会社が必要です"))
    (when-not (contains? funding/currency-exponents currency)
      (refuse :card-statement/currency-unsupported
              (str "未対応の通貨です: " currency)))
    (let [id (str "extcard-" (UUID/randomUUID))
          fingerprint (funding/account-fingerprint number)
          record (cond-> {:schema schema
                          :id id
                          :organization-id organization-id
                          :linked-by (:user-id session)
                          :label (or (some-> label str str/trim not-empty)
                                     (str/trim (str issuer)))
                          :issuer (str/trim (str issuer))
                          :holder (some-> holder str str/trim not-empty)
                          :currency currency
                          :funding-account-id (some-> funding-account-id str not-empty)
                          :limit-observed limit-observed
                          :status :active
                          :linked-at (store/now)}
                   fingerprint (assoc :number-last4 (:last4 fingerprint)
                                      :number-digest (:digest fingerprint)))]
      (store/transact! assoc-in (card-path id) record)
      record)))

(defn card
  "One card, only when it belongs to this session's organization."
  [session card-id]
  (let [record (get-in (store/snapshot) (card-path card-id))]
    (when (and record (= (:organization-id session) (:organization-id record)))
      record)))

(defn cards
  [session]
  (->> (vals (get-in (store/snapshot) [:card-statement :cards] {}))
       (filter #(= (:organization-id session) (:organization-id %)))
       (sort-by :linked-at)
       vec))

;; ---------------------------------------------------------------------------
;; store -- transactions
;; ---------------------------------------------------------------------------

(defn transactions
  "Every recorded transaction for this organization, newest use first.
  Optionally narrowed to one card."
  ([session] (transactions session nil))
  ([session card-id]
   (->> (vals (get-in (store/snapshot) [:card-statement :transactions] {}))
        (filter #(= (:organization-id session) (:organization-id %)))
        (filter #(or (nil? card-id) (= card-id (:card-id %))))
        (sort-by (juxt :used-at :used-at-time))
        reverse
        vec)))

(defn ingest!
  "Import a 利用明細 export for one card. Returns what happened, per row.

  IDEMPOTENT BY CONSTRUCTION, not by convention: a transaction is stored under
  its `:external-reference`, so importing the same file twice writes the same
  keys and the second import reports every row as a duplicate. That is the
  property that makes 'export the last three months again' a safe thing for a
  person to do, and it is the reason the reference is a digest of the row's own
  content rather than of when it arrived.

  `:as-of` is the instant the ISSUER showed this data, and is required for the
  same reason `funding/record-balance!` requires it: an export copied from a
  three-day-old screen is three days old however recently it was uploaded."
  [session card-id text {:keys [columns as-of source-detail]}]
  (let [record (card session card-id)]
    (when-not record
      (refuse :card-statement/card-not-found "カードが見つかりません"))
    (when (blank? as-of)
      (refuse :card-statement/as-of-required
              "as-of は発行会社の画面が示した時刻の ISO-8601 instant です（省略不可）"))
    (when-not (:number-digest record)
      (refuse :card-statement/card-not-fingerprinted
              "カード番号が未登録のため明細を一意化できません"))
    (let [parsed (:transactions (parse-transactions
                                 text
                                 {:card-digest (:number-digest record)
                                  :currency (:currency record)
                                  :columns columns}))
          existing (get-in (store/snapshot) [:card-statement :transactions] {})
          now (store/now)
          prepared
          (vec
           (for [t parsed]
             (assoc t
                    :schema "cloud.itonami.app.card-statement.transaction.v1"
                    :card-id card-id
                    :organization-id (:organization-id record)
                    :as-of as-of
                    :source :statement-csv
                    :source-detail (some-> source-detail str str/trim not-empty)
                    :recorded-by (:user-id session)
                    :recorded-at now
                    ;; Already in the store from an earlier import, OR the same
                    ;; transaction listed twice in THIS file -- both are the same
                    ;; fact arriving twice, and both must count once.
                    :duplicate? (contains? existing (:external-reference t)))))
          fresh (->> prepared
                     (remove :duplicate?)
                     (reduce (fn [acc t]
                               (if (contains? acc (:external-reference t))
                                 acc
                                 (assoc acc (:external-reference t) t)))
                             {})
                     vals
                     vec)]
      (when (seq fresh)
        (store/transact!
         (fn [state]
           (reduce (fn [s t]
                     (assoc-in s (transaction-path (:external-reference t))
                               (dissoc t :duplicate?)))
                   state
                   fresh))))
      {:schema "cloud.itonami.app.card-statement.ingest.v1"
       :card-id card-id
       :as-of as-of
       :parsed (count prepared)
       :recorded (count fresh)
       :duplicates (- (count prepared) (count fresh))
       :ambiguous (count (filter :ambiguous? prepared))
       :references (mapv :external-reference fresh)})))

;; ---------------------------------------------------------------------------
;; store -- billing cycles
;; ---------------------------------------------------------------------------

(def cycle-statuses
  "A billing cycle's life. `:provisional` is this month accumulating and is
  DELIBERATELY not usable as a scheduled debit -- see `funding/scheduled-debit`.
  `:confirmed` is an amount the issuer has fixed with a debit date. `:debited`
  is one that has already left the account."
  #{:provisional :confirmed :debited})

(defn record-billing-cycle!
  "Record a 請求明細 period: what will be taken, from which account, and when.

  The amount is TAKEN FROM THE ISSUER'S 請求 FIGURE, never summed from the
  transactions in this store. A total computed here would be missing exactly the
  rows nobody imported, and it would be that total which then opened or closed
  the funds gate."
  [session {:keys [card-id closing-date debit-date amount-minor currency
                   funding-account-id status as-of source-detail]}]
  (let [organization-id (require-organization! session)
        record (card session card-id)
        status (some-> status keyword)]
    (when-not record
      (refuse :card-statement/card-not-found "カードが見つかりません"))
    (when-not (contains? cycle-statuses status)
      (refuse :card-statement/cycle-status-invalid
              (str "status は " (str/join " / " (map name (sort cycle-statuses)))
                   " のいずれかです")))
    (when-not (integer? amount-minor)
      (refuse :card-statement/amount-invalid
              "amount-minor は最小通貨単位の整数です"))
    (when (blank? closing-date)
      (refuse :card-statement/closing-date-required "締め日が必要です"))
    (when (blank? as-of)
      (refuse :card-statement/as-of-required
              "as-of は発行会社の画面が示した時刻の ISO-8601 instant です（省略不可）"))
    ;; A confirmed cycle with no debit date cannot be subtracted from anything:
    ;; `scheduled-debit` compares the debit date against the balance's own date,
    ;; and without one it could not tell an upcoming debit from a past one.
    (when (and (= :confirmed status) (blank? debit-date))
      (refuse :card-statement/debit-date-required
              "確定した請求には引き落とし日が必要です"))
    (let [currency (or (some-> currency str str/upper-case not-empty)
                       (:currency record))
          account-id (or (some-> funding-account-id str not-empty)
                         (:funding-account-id record))]
      (when-not (= currency (:currency record))
        (refuse :card-statement/currency-mismatch
                (str "カードの通貨は " (:currency record) " です: " currency)))
      (when (blank? account-id)
        (refuse :card-statement/funding-account-required
                "引き落とし先の funding account が特定できません"))
      (let [id (str "cycle-" card-id "-" closing-date)
            cycle {:schema "cloud.itonami.app.card-statement.cycle.v1"
                   :id id
                   :card-id card-id
                   :organization-id organization-id
                   :funding-account-id account-id
                   :closing-date closing-date
                   :debit-date (some-> debit-date str str/trim not-empty)
                   :amount-minor amount-minor
                   :currency currency
                   :exponent (get funding/currency-exponents currency)
                   :status status
                   :as-of as-of
                   :source-detail (some-> source-detail str str/trim not-empty)
                   :recorded-by (:user-id session)
                   :recorded-at (store/now)}]
        (store/transact! assoc-in (cycle-path id) cycle)
        cycle))))

(defn billing-cycles
  "This organization's billing cycles, optionally narrowed to the funding
  account they debit. Oldest closing date first."
  ([session] (billing-cycles session nil))
  ([session funding-account-id]
   (->> (vals (get-in (store/snapshot) [:card-statement :cycles] {}))
        (filter #(= (:organization-id session) (:organization-id %)))
        (filter #(or (nil? funding-account-id)
                     (= funding-account-id (:funding-account-id %))))
        (sort-by :closing-date)
        vec)))

(defn snapshot
  "The card-statement read model for this session's organization."
  [session]
  {:schema "cloud.itonami.app.card-statement.snapshot.v1"
   :organization-id (:organization-id session)
   :cards (mapv (fn [c]
                  {:card c
                   :cycles (filterv #(= (:id c) (:card-id %)) (billing-cycles session))
                   :transaction-count (count (transactions session (:id c)))})
                (cards session))})
