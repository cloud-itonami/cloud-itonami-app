(ns cloud.itonami.app.paypay-bank
  "Read-only parsers for the notifications PayPay銀行 already sends by mail.

  This is the balance feed that exists TODAY. The bank's direct API needs an
  application, roughly seven business days, an API usage agreement, and possibly
  registration as a 電子決済等代行業者 under the Banking Act -- none of which can be
  obtained by writing code. Meanwhile the bank emails, unprompted, a notice that
  states the account balance in plain text.

  ## The one thing a reader must not get wrong

  **This is a WARNING feed, not a balance feed.** PayPay銀行 sends
  『【残高不足】引落予定のご案内』 only when it expects a direct debit to fail. A
  quiet inbox therefore means one of two things -- the balance is comfortable, or
  nothing is scheduled -- and this namespace cannot tell them apart. So:

    NO NOTICE IS NOT EVIDENCE OF A HEALTHY BALANCE.

  Everything here feeds `cloud.itonami.app.funding`, whose `freshness` already
  ages a recorded balance out to `:stale`. That aging is what keeps this honest:
  a balance learned from a warning notice is true when stated and decays like
  any other reading, rather than standing indefinitely because no worse news
  arrived.

  ## Read-only, by construction

  There is no credential in this namespace and no request out of it. It takes
  text that something else already fetched -- the app's Gmail connection, or an
  operator pasting a mail -- and turns it into a number with a date. It cannot
  log in, and it cannot move money. That is not a policy that could be relaxed
  later by adding a flag; there is simply nothing here to relax.

  ## Failing closed

  Every parser returns nil when it cannot find what it needs. None of them
  returns 0, and none substitutes the current time for a missing timestamp. A
  mail whose format the bank changed will stop producing balances, which surfaces
  as `:never-recorded` / `:stale` at the payment gate -- the correct outcome. The
  wrong outcome, and the easy one to write by accident, is a parser that yields
  a plausible zero and refuses every payment forever while looking like it works."
  (:require [clojure.string :as str]))

(def schema "cloud.itonami.app.paypay-bank.v1")

;; ---------------------------------------------------------------------------
;; normalisation
;; ---------------------------------------------------------------------------

(def ^:private fullwidth-offset
  "U+FF01..U+FF5E map onto ASCII U+0021..U+007E at a constant distance."
  (- (int \！) (int \!)))

(defn normalize
  "Fold the FULL-WIDTH ASCII block (U+FF01..U+FF5E) to ASCII, plus the ideographic
  space.

  The bank is not consistent with itself. The balance notice writes `口座残高：`
  with a full-width colon; the failure notice writes `収納企業:` with a half-width
  one. Subjects say `【Ｖｉｓａデビット】` in full-width Latin while amounts arrive
  in half-width digits. Folding once here means every parser below is written
  against one form, rather than each carrying its own alternation -- which is
  exactly where a missed variant returns nil and a balance silently stops being
  recorded. (Measured: folding only digits left `Ｖｉｓａ` unmatched, so every
  Visa debit mail was classified as unknown.)

  This is deliberately LOSSY for display: `ミツイスミトモカ－ド` comes out with an
  ASCII hyphen, and `ＣＬＯＵＤＦＬＡＲＥ` comes out as `CLOUDFLARE`. That loss is
  the point for a merchant name -- `ＣＬＯＵＤＦＬＡＲＥ` will never match an invoice
  from `Cloudflare` and `CLOUDFLARE` will. The original text is always
  recoverable from the mail itself, which is why every ledger record keeps the
  message id it came from."
  [text]
  (when (string? text)
    (-> text
        (str/replace #"[！-～]"
                     (fn [c] (str (char (- (int (first c)) fullwidth-offset)))))
        (str/replace "　" " "))))

(defn- find-long
  "The integer captured by `re`, or nil. Never 0 as a fallback."
  [text re]
  (when-let [[_ v] (re-find re text)]
    (try (Long/parseLong (str/replace v "," ""))
         (catch NumberFormatException _ nil))))

(defn- find-str [text re]
  (when-let [[_ v] (re-find re text)]
    (not-empty (str/trim v))))

;; ---------------------------------------------------------------------------
;; kinds
;; ---------------------------------------------------------------------------

(def kinds
  "Subject fragment -> what the mail is. An allowlist: a subject that matches
  nothing is `nil`, not a guess."
  [[#"残高不足.*引落予定"       :balance-notice]
   [#"残高不足により口座振替"    :debit-failure]
   [#"Visa.?デビット.*ご返金"    :visa-debit-refund]
   [#"Visa.?デビット"            :visa-debit]
   [#"お引き落としのご連絡"      :account-debit]
   [#"振込入金"                  :incoming-transfer]
   [#"振り込みのご確認"          :outgoing-transfer]
   [#"カードローン"              :card-loan]])

(defn notice-kind
  [subject]
  (let [s (normalize (str subject))]
    (some (fn [[re k]] (when (re-find re s) k)) kinds)))

;; ---------------------------------------------------------------------------
;; the one that matters: the balance
;; ---------------------------------------------------------------------------

(defn parse-balance-notice
  "The balance stated in a 【残高不足】引落予定のご案内.

  Returns nil unless the balance line itself was found. The scheduled-debit
  fields are extra context and their absence does not invalidate the balance --
  but the balance's absence invalidates everything, so it is the only required
  capture.

  `:shortfall-minor` is computed rather than parsed, and only when both inputs
  are present. A shortfall is the whole reason the mail was sent, and deriving
  it from two numbers we actually read is safer than trusting a third."
  [body]
  (when-let [text (normalize body)]
    (when-let [balance (find-long text #"口座残高\s*:?\s*([0-9,]+)\s*円")]
      (let [scheduled (find-long text #"合計引落予定額\s*:?\s*([0-9,]+)\s*円")
            date (find-str text #"引落予定日\s*:?\s*([0-9]{4}/[0-9]{2}/[0-9]{2})")]
        (cond-> {:schema schema
                 :kind :balance-notice
                 :amount-minor balance
                 :currency "JPY"}
          scheduled (assoc :scheduled-debit-total-minor scheduled)
          date (assoc :scheduled-debit-date (str/replace date "/" "-"))
          (and scheduled (>= scheduled 0))
          (assoc :shortfall-minor (max 0 (- scheduled balance))))))))

;; ---------------------------------------------------------------------------
;; the rest -- context, not balances
;; ---------------------------------------------------------------------------

(defn parse-debit-failure
  "A 口座振替 that could not be collected. Carries no balance: the mail says the
  balance was short, not what it was."
  [body]
  (when-let [text (normalize body)]
    (when (re-find #"残高不足により" text)
      (let [collector (find-str text #"収納企業\s*:?\s*(.+)")
            handled (find-str text #"取扱日時\s*:?\s*([0-9]{4}年[0-9]{2}月[0-9]{2}日)")]
        (cond-> {:schema schema :kind :debit-failure}
          collector (assoc :collector collector)
          handled (assoc :handled-on
                         (-> handled
                             (str/replace "年" "-") (str/replace "月" "-")
                             (str/replace "日" ""))))))))

(defn parse-visa-debit
  "One Visa debit settlement. `:refund? true` when it is the refund notice --
  same shape, opposite sign, and conflating them would double-count."
  [body]
  (when-let [text (normalize body)]
    (let [refund? (boolean (re-find #"ご返金" text))
          amount (or (find-long text #"ご利用金額\s*:?\s*([0-9,]+)\s*円")
                     (find-long text #"ご返金額\s*:?\s*([0-9,]+)\s*円"))]
      (when amount
        (let [merchant (or (find-str text #"ご利用のショップ名\s*:?\s*(.+)")
                           (find-str text #"加盟店名\s*:?\s*(.+)"))
              reference (find-str text #"取引明細番号\s*:?\s*(\S+)")
              at (or (find-str text #"引落日時\s*:?\s*([0-9/]+\s[0-9:]+)")
                     (find-str text #"ご返金日時\s*:?\s*([0-9/]+\s[0-9:]+)"))]
          (cond-> {:schema schema
                   :kind (if refund? :visa-debit-refund :visa-debit)
                   :amount-minor amount
                   :currency "JPY"
                   :refund? refund?}
            merchant (assoc :merchant merchant)
            reference (assoc :reference reference)
            at (assoc :occurred-at-local at)))))))

(defn parse-account-debit
  "A 口座振替 that DID collect."
  [body]
  (when-let [text (normalize body)]
    (when-let [amount (find-long text #"お引落金額\s*:?\s*([0-9,]+)\s*円")]
      (let [collector (find-str text #"委託者名\s*:?\s*(.+)")
            at (find-str text #"お引落日時\s*:?\s*([0-9/]+\s[0-9:]+)")]
        (cond-> {:schema schema :kind :account-debit
                 :amount-minor amount :currency "JPY"}
          collector (assoc :collector collector)
          at (assoc :occurred-at-local at))))))

(defn parse
  "Dispatch on the subject, parse with the matching parser.

  Returns nil for a mail this namespace does not understand, including one whose
  subject matches but whose body does not parse. Both are the same answer to the
  caller -- there is nothing here to record -- and neither is an error worth
  raising: the bank sends many notification types and most are not balances."
  [{:keys [subject body]}]
  (let [kind (notice-kind subject)]
    (case kind
      :balance-notice (parse-balance-notice body)
      :debit-failure (parse-debit-failure body)
      (:visa-debit :visa-debit-refund) (parse-visa-debit body)
      :account-debit (parse-account-debit body)
      nil)))

;; ---------------------------------------------------------------------------
;; turning a notice into a funding balance
;; ---------------------------------------------------------------------------

(defn balance-record
  "The `funding/record-balance!` argument map for a parsed balance notice, or nil.

  `received-at` is REQUIRED and is the instant the mail arrived. It is not
  defaulted to now, and this function will not invent it, because it becomes the
  balance's `:as-of` -- the answer to 'how old is this figure?'. The notice
  itself never states when the balance was measured, so the mail's own arrival
  time is the best available upper bound on its age, and saying so is more honest
  than a timestamp that quietly means 'whenever this was processed'.

  `:source` is `:statement` rather than `:api`: a figure the bank published to
  us, not one we queried. When the direct API is eventually provisioned, that
  path records `:api` and the two remain distinguishable in the ledger."
  [parsed received-at]
  (when (and (= :balance-notice (:kind parsed))
             (integer? (:amount-minor parsed))
             (string? received-at)
             (seq received-at))
    {:amount-minor (:amount-minor parsed)
     :currency "JPY"
     :as-of received-at
     :source :statement
     :source-detail
     (str "PayPay銀行【残高不足】引落予定のご案内"
          (when-let [d (:scheduled-debit-date parsed)]
            (str "（引落予定日 " d
                 (when-let [t (:scheduled-debit-total-minor parsed)]
                   (str " / 予定額 " t "円"))
                 "）")))}))
