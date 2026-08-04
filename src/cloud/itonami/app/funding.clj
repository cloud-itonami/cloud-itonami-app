(ns cloud.itonami.app.funding
  "Funding accounts belonging to an organization, and the balances someone
  attested for them.

  This is the read model the payment authority's pre-check stands on, so its two
  refusals matter more than its features.

  1. IT DOES NOT HOLD A PAYMENT INSTRUMENT. A full account number may be
     supplied once; what is stored is its last four digits and a SHA-256 digest,
     and the number itself is never written to `state.edn`. The digest is enough
     to say 'the account you consented to is the account on file' -- which is the
     only question this app needs to answer -- and not enough to move money. The
     app is a surface and a consent boundary (see `cloud.itonami.app.authority`);
     an app that stored the number would be one credential away from being able
     to actuate, and nothing here should ever be that.

  2. AN UNKNOWN BALANCE IS NOT ZERO, AND IT IS NOT UNLIMITED. It is unknown, and
     it refuses. `cloud.itonami.app.account-services` already states this for
     usage meters -- 'an unavailable meter is never presented as zero usage' --
     and the stakes are higher here: a balance silently defaulted to a large
     number waves a payment through, and one silently defaulted to zero refuses
     every payment forever. So `:never-recorded` and `:stale` are distinct
     statuses that both refuse, and neither is a number.

  There is no bank connector in this app and this namespace does not add one.
  A balance arrives here because a human read it and recorded it, and it carries
  the `:as-of` instant the BANK stated -- not the instant we wrote it down. Those
  differ, and the second one is the one that would lie: a figure copied from a
  three-day-old statement is three days old however recently it was typed in.

  Amounts are integers in the currency's MINOR unit, with the exponent looked up
  rather than assumed. JPY has exponent 0, so ¥38,500 is 38500; USD has exponent
  2, so $38.50 is 3850. An unknown currency refuses instead of defaulting,
  because defaulting an exponent is how a figure becomes wrong by 100x."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.time.format DateTimeParseException]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.funding.v1")

(def currency-exponents
  "ISO 4217 minor-unit exponents for the currencies this deployment accepts.
  Deliberately a short allowlist: a currency absent from here refuses, and
  adding one is a decision someone makes on purpose."
  {"JPY" 0
   "USD" 2
   "EUR" 2})

(def default-balance-max-age-seconds
  "How long a recorded balance stays usable for a payment pre-check.

  24 hours. A company current account moves on business-day timescales, and the
  gate this feeds exists because a direct debit already failed once for
  insufficient funds -- a balance older than a day cannot honestly answer
  'will this clear?'."
  86400)

(def account-types
  "What the bank calls the account. Recorded because it appears on the transfer
  form the human will fill in, and a wrong one bounces the transfer."
  #{:ordinary :current :savings})

;; ---------------------------------------------------------------------------
;; account numbers
;; ---------------------------------------------------------------------------

(defn- digest [value]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes (str value) StandardCharsets/UTF_8))
      (->> (.encodeToString (.withoutPadding (Base64/getUrlEncoder))))))

(defn- digits [value]
  (some-> value str (str/replace #"\D" "") not-empty))

(defn account-fingerprint
  "Reduce an account number to what may be kept: the last four digits, for a
  human to recognise, and a digest, for a machine to compare.

  Returns nil for an absent number -- an account whose number nobody has typed in
  yet is a real state, and this app is not a bank onboarding form. Everything
  downstream treats a nil fingerprint as 'unknown', never as 'matches'."
  [number]
  (when-let [d (digits number)]
    {:last4 (subs d (max 0 (- (count d) 4)))
     :digest (digest d)}))

(defn same-account?
  "True when a supplied number is the one on file. False when either side is
  unknown -- an unknown number does not match, it fails to answer."
  [fingerprint number]
  (boolean (and (:digest fingerprint)
                (when-let [f (account-fingerprint number)]
                  (= (:digest fingerprint) (:digest f))))))

;; ---------------------------------------------------------------------------
;; balance freshness -- pure
;; ---------------------------------------------------------------------------

(defn- instant-of [s]
  (when (string? s)
    (try (Instant/parse s)
         (catch DateTimeParseException _ nil)
         (catch Exception _ nil))))

(defn freshness
  "How usable this recorded balance is, as of `now`. PURE, so the rule that
  decides whether a payment may be proposed is testable without a clock.

  Returns one of
    {:funding/status :never-recorded}
    {:funding/status :fresh  :funding/age-seconds n}
    {:funding/status :stale  :funding/age-seconds n}   ; or no age, if unreadable

  An `:as-of` that cannot be parsed, or is missing, or is in the FUTURE, is
  `:stale`. That is the fail-closed direction here, and it is the opposite of the
  one `cloud.itonami.app.authority.posture` takes -- deliberately. There, an
  unreadable timestamp keeps a restriction on; here, it withholds permission to
  spend. Both resolve the unknown toward refusing, which is the invariant; which
  literal value that maps to depends on whether the timestamp is gating a
  restriction or a permission."
  ([balance now] (freshness balance now default-balance-max-age-seconds))
  ([balance now max-age-seconds]
   (if-not (and (map? balance) (integer? (:amount-minor balance)))
     {:funding/status :never-recorded}
     (let [as-of (instant-of (:as-of balance))
           now' (instant-of now)]
       (if (or (nil? as-of) (nil? now'))
         {:funding/status :stale}
         (let [age (.toSeconds (Duration/between as-of now'))]
           {:funding/status (if (<= 0 age (long max-age-seconds)) :fresh :stale)
            :funding/age-seconds age}))))))

(defn fresh?
  [freshness']
  (= :fresh (:funding/status freshness')))

;; ---------------------------------------------------------------------------
;; scheduled debits -- pure
;; ---------------------------------------------------------------------------

(defn scheduled-debit
  "What is known to be leaving this account, on or after the date its balance
  was stated. PURE, so the gate that reads it is testable without a store.

  `cycles` are `cloud.itonami.app.card-statement` billing cycles. This namespace
  does not know what a card is; it knows that something else has said `this much
  leaves this account on this date`, which is the only part a balance needs.

  Returns one of
    {:funding/status :never-recorded}
    {:funding/status :known
     :funding/amount-minor n
     :funding/cycles [...]         ; counted
     :funding/unreconciled [...]}  ; NOT counted -- see below

  THREE RULES, and each exists because the obvious alternative is wrong.

  1. `:provisional` is not counted. This month's running total moves every day
     and is fixed by nobody, so counting it would make the funds gate tighten
     as the month goes on and loosen the moment the issuer closed a period --
     behaviour driven by their calendar, not by our money.

  2. A cycle whose debit date is BEFORE the balance's own `:as-of` is not
     subtracted. A balance stated after the debit date already reflects whether
     the money left. Subtracting it again would double-count, and double-
     counting refuses payments that would have cleared. Those cycles are
     returned as `:funding/unreconciled` instead of dropped: nobody has said
     whether they went through, and that is worth showing even though it must
     not move the arithmetic.

  3. NO CYCLE RECORDED AT ALL is `:never-recorded`, never zero. `available`
     then declines to answer, rather than answering `balance`. An organization
     that has never imported a statement does not thereby have no card."
  [balance cycles]
  (if (empty? cycles)
    {:funding/status :never-recorded}
    (let [as-of-date (let [s (str (:as-of balance))]
                       ;; The DATE part of the instant the bank stated. A cycle
                       ;; debits on a date, not at an instant, so the comparison
                       ;; has to happen at the coarser of the two.
                       (when (>= (count s) 10) (subs s 0 10)))
          confirmed (filter #(= :confirmed (:status %)) cycles)
          upcoming? (fn [c]
                      (and (:debit-date c)
                           (or (nil? as-of-date)
                               (not (neg? (compare (str (:debit-date c)) as-of-date))))))
          counted (filterv upcoming? confirmed)
          unreconciled (filterv (complement upcoming?) confirmed)]
      {:funding/status :known
       :funding/amount-minor (reduce + 0 (map :amount-minor counted))
       :funding/cycles (mapv :id counted)
       :funding/unreconciled (mapv :id unreconciled)})))

(defn available
  "`balance - scheduled debits`, or nil when either side cannot answer. PURE.

  NIL, NEVER `balance`. Falling back to the balance would silently restore the
  exact gap this function exists to close, and it would do it in the case that
  looks most normal -- an organization that has not imported a statement. A
  caller that gets nil must judge on the balance alone AND record that it did
  so, so a reader of the decision can see which of the two gates was actually
  applied (ADR-2608041200 D5)."
  [balance scheduled]
  (when (and (integer? (:amount-minor balance))
             (= :known (:funding/status scheduled))
             (integer? (:funding/amount-minor scheduled)))
    (- (:amount-minor balance) (:funding/amount-minor scheduled))))

;; ---------------------------------------------------------------------------
;; store paths
;; ---------------------------------------------------------------------------

(defn- account-path [account-id] [:funding :accounts account-id])
(defn- balance-path [account-id] [:funding :balances account-id])

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- require-organization! [session]
  (let [organization-id (:organization-id session)]
    (when-not organization-id
      (refuse :identity/unauthenticated
              "funding account には organization に属する session が必要です"))
    organization-id))

;; ---------------------------------------------------------------------------
;; accounts
;; ---------------------------------------------------------------------------

(defn link-account!
  "Bind a bank account to this session's organization.

  The account belongs to the ORGANIZATION, not to the person who typed it in:
  a company's account outlives whichever member linked it, and scoping it to the
  user would mean an owner change silently orphans the funding for every
  scheduled payment. `:linked-by` records who, without making them the owner."
  [session {:keys [label institution branch account-type holder number currency]}]
  (let [organization-id (require-organization! session)
        currency (or (some-> currency str str/upper-case not-empty) "JPY")
        account-type (some-> account-type keyword)]
    (when (str/blank? (str institution))
      (refuse :funding/institution-missing "金融機関名が必要です"))
    (when-not (contains? currency-exponents currency)
      (refuse :funding/currency-unsupported
              (str "未対応の通貨です: " currency
                   "（対応: " (str/join ", " (sort (keys currency-exponents))) "）")))
    (when-not (contains? account-types account-type)
      (refuse :funding/account-type-invalid
              (str "口座種別は " (str/join " / " (map name (sort account-types)))
                   " のいずれかです")))
    (let [id (str "funding-" (UUID/randomUUID))
          fingerprint (account-fingerprint number)
          record (cond-> {:schema schema
                          :id id
                          :organization-id organization-id
                          :linked-by (:user-id session)
                          :label (or (some-> label str str/trim not-empty)
                                     (str institution))
                          :institution (str/trim (str institution))
                          :branch (some-> branch str str/trim not-empty)
                          :account-type account-type
                          :holder (some-> holder str str/trim not-empty)
                          :currency currency
                          :status :active
                          :linked-at (store/now)}
                   fingerprint (assoc :number-last4 (:last4 fingerprint)
                                      :number-digest (:digest fingerprint)))]
      (store/transact! assoc-in (account-path id) record)
      record)))

(defn account
  "One account, only when it belongs to this session's organization. Returns nil
  rather than throwing, so a caller can distinguish 'not linked' from 'refused'."
  [session account-id]
  (let [record (get-in (store/snapshot) (account-path account-id))]
    (when (and record (= (:organization-id session) (:organization-id record)))
      record)))

(defn accounts
  "Every account linked to this session's organization, oldest first."
  [session]
  (->> (vals (get-in (store/snapshot) [:funding :accounts] {}))
       (filter #(= (:organization-id session) (:organization-id %)))
       (sort-by :linked-at)
       vec))

(defn close-account!
  "Mark an account closed. Kept rather than deleted: a settled payment refers to
  the account it was drawn on, and deleting the account would leave that record
  pointing at nothing."
  [session account-id]
  (let [record (account session account-id)]
    (when-not record
      (refuse :funding/account-not-found "funding account が見つかりません"))
    (let [closed (assoc record :status :closed :closed-at (store/now))]
      (store/transact! assoc-in (account-path account-id) closed)
      closed)))

;; ---------------------------------------------------------------------------
;; balances
;; ---------------------------------------------------------------------------

(def balance-sources
  "Where a figure came from. Recorded because it is the difference between a
  number someone read off a bank screen and a number someone remembered."
  #{:owner-attested :statement :api})

(defn record-balance!
  "Record what this account held, as of the instant the BANK stated.

  `:as-of` is required and is not defaulted to now. A balance whose age is
  unknown cannot be judged fresh, and `freshness` will call it stale -- which is
  the honest outcome, not a bug to paper over by stamping the current time."
  [session account-id {:keys [amount-minor currency as-of source source-detail]}]
  (let [record (account session account-id)
        source (some-> source keyword)]
    (when-not record
      (refuse :funding/account-not-found "funding account が見つかりません"))
    (when-not (= :active (:status record))
      (refuse :funding/account-inactive "閉鎖済みの口座には残高を記録できません"))
    (when-not (integer? amount-minor)
      (refuse :funding/amount-invalid
              "amount-minor は最小通貨単位の整数です（JPY は円、USD はセント）"))
    (when (neg? amount-minor)
      (refuse :funding/amount-invalid "amount-minor が負の値です"))
    (let [currency (or (some-> currency str str/upper-case not-empty)
                       (:currency record))]
      (when-not (= currency (:currency record))
        (refuse :funding/currency-mismatch
                (str "口座の通貨は " (:currency record) " です: " currency)))
      (when-not (contains? balance-sources source)
        (refuse :funding/source-invalid
                (str "source は " (str/join " / " (map name (sort balance-sources)))
                     " のいずれかです")))
      (when (nil? (instant-of as-of))
        (refuse :funding/as-of-invalid
                "as-of は銀行が示した時刻の ISO-8601 instant です（省略不可）"))
      (let [balance {:schema "cloud.itonami.app.funding.balance.v1"
                     :account-id account-id
                     :organization-id (:organization-id record)
                     :amount-minor amount-minor
                     :currency currency
                     :exponent (get currency-exponents currency)
                     :as-of as-of
                     :source source
                     :source-detail (some-> source-detail str str/trim not-empty)
                     :recorded-by (:user-id session)
                     :recorded-at (store/now)}]
        (store/transact! assoc-in (balance-path account-id) balance)
        balance))))

(defn balance
  "The last recorded balance for an account this organization owns, or nil."
  [session account-id]
  (when (account session account-id)
    (get-in (store/snapshot) (balance-path account-id))))

(defn max-age-seconds
  [configuration]
  (or (get-in configuration [:authorities :payment :balance-max-age-seconds])
      default-balance-max-age-seconds))

(defn account-view
  "One account with its balance and how usable that balance currently is.

  `:balance` is nil when nothing was ever recorded -- not 0. A UI that renders a
  missing balance as ¥0 is stating a fact nobody established."
  [configuration session record]
  ;; Read through `balance`, which re-checks ownership, rather than reaching for
  ;; the path directly. `snapshot` has already filtered by organization, so this
  ;; is redundant -- and redundant is the right posture for the read that decides
  ;; whether a spend gate opens.
  (let [b (balance session (:id record))]
    {:account record
     :balance b
     :freshness (freshness b (store/now) (max-age-seconds configuration))}))

(defn snapshot
  "The funding read model for this session's organization."
  [configuration session]
  (let [records (accounts session)]
    {:schema "cloud.itonami.app.funding.snapshot.v1"
     :organization-id (:organization-id session)
     :balance-max-age-seconds (max-age-seconds configuration)
     :currencies currency-exponents
     :accounts (mapv #(account-view configuration session %) records)}))
