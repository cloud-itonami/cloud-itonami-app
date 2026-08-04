(ns cloud.itonami.app.card-statement-test
  "The card-statement plane, tested on the four things ADR-2608041200 says must
  be true before this counts as implemented:

  1. the same export imported twice records nothing the second time;
  2. an unknown scheduled debit does NOT collapse into `available = balance`;
  3. a client cannot supply its own schedule, any more than its own balance;
  4. a header row that does not match the declared columns refuses, loudly,
     instead of guessing a column and producing a smaller number.

  (4) stands in for the one condition this suite CANNOT meet: no real export has
  been run, because getting one means logging into the issuer's console, which
  is the owner's to do and not this agent's. So the column map is declared and
  unverified -- and the test that matters is that an unverified map fails closed
  rather than silently mis-parsing. When a real export exists, its header row
  replaces `header` below and this paragraph goes away."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority.api :as api]
            [cloud.itonami.app.authority.payment :as payment]
            [cloud.itonami.app.card-statement :as cs]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.store :as store]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def outsider {:user-id "user-9" :organization-id "org-2"})

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- reset-state! []
  (store/transact! #(assoc %
                           :card-statement {:cards {} :transactions {} :cycles {}}
                           :funding {:accounts {} :balances {}}
                           :authority {:proposals {}})))

(def header "利用日,利用店名,利用金額,カード番号下4桁,取引ID")

(defn- csv [& lines] (str/join "\n" (cons header lines)))

(def mf-card
  {:label "MF ビジネスカード"
   :issuer "マネーフォワード"
   :holder "JK株式会社"
   :number "4111111111111111"
   :currency "JPY"})

;; ---------------------------------------------------------------------------
;; CSV -- pure
;; ---------------------------------------------------------------------------

(deftest csv-parsing-survives-the-shapes-an-export-actually-has
  (testing "quoted fields keep their commas, and \"\" is one literal quote"
    (is (= [["a" "b,c" "d\"e"]]
           (cs/parse-csv "a,\"b,c\",\"d\"\"e\""))))
  (testing "CRLF and LF are both row separators"
    (is (= [["h"] ["1"] ["2"]] (cs/parse-csv "h\r\n1\n2"))))
  (testing "a trailing newline is not a trailing empty transaction"
    (is (= [["h"] ["1"]] (cs/parse-csv "h\n1\n"))))
  (testing "a BOM does not stick to the first header and make one column unmatchable"
    (is (= [["利用日" "利用店名"]] (cs/parse-csv "﻿利用日,利用店名")))))

(deftest a-header-that-does-not-match-refuses-and-says-what-it-saw
  (testing "the declared column map is UNVERIFIED, so the only safe behaviour is
            to stop -- a parser that fell back to positions would one day read
            the wrong column as the amount and pass the funds gate with it"
    (let [e (try (cs/parse-transactions "日付,店,金額\n2026/08/01,X,100"
                                        {:card-digest "d" :currency "JPY"})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :card-statement/columns-missing (:type (ex-data e))))
      (testing "and the message names both sides, so the mapping is fixable in one step"
        (is (str/includes? (ex-message e) "利用日"))
        (is (str/includes? (ex-message e) "日付"))))))

(deftest amounts-refuse-rather-than-defaulting-to-zero
  (testing "the shapes a JPY export uses"
    (is (= 1234 (cs/parse-amount-minor "1,234" 0)))
    (is (= 1234 (cs/parse-amount-minor "¥1,234" 0)))
    (is (= 1234 (cs/parse-amount-minor "1234円" 0)))
    (is (= -500 (cs/parse-amount-minor "-500" 0))
        "a refund is a real row; dropping it would overstate the spend"))
  (testing "minor units are the currency's, not a guess"
    (is (= 1234 (cs/parse-amount-minor "12.34" 2)))
    (is (nil? (cs/parse-amount-minor "12.345" 2))
        "a third decimal in a 2-exponent currency is not a rounding question,
         it is the wrong column -- so it refuses instead of rounding"))
  (testing "an unreadable cell is nil, never 0"
    (doseq [v ["" "  " "N/A" "一二三" nil]]
      (is (nil? (cs/parse-amount-minor v 0)) (pr-str v))))
  (testing "and a row with one refuses the whole ingest -- a skipped row is a
            transaction that is silently missing from the total someone reads"
    (is (= :card-statement/amount-invalid
           (refuses #(cs/parse-transactions (csv "2026/08/01,カフェ,N/A,1111,")
                                            {:card-digest "d" :currency "JPY"}))))))

;; ---------------------------------------------------------------------------
;; identity
;; ---------------------------------------------------------------------------

(deftest a-transaction-id-makes-the-key-exact-and-its-absence-is-admitted
  (let [with-id (cs/parse-transactions
                 (csv "2026/08/01,カフェ,500,1111,TXN-1")
                 {:card-digest "d" :currency "JPY"})
        without (cs/parse-transactions
                 (csv "2026/08/01,カフェ,500,1111,")
                 {:card-digest "d" :currency "JPY"})]
    (is (not= (get-in with-id [:transactions 0 :external-reference])
              (get-in without [:transactions 0 :external-reference]))
        "the two key shapes are different keys, which is honest: one is exact")
    (is (not (get-in with-id [:transactions 0 :ambiguous?])))
    (is (not (get-in without [:transactions 0 :ambiguous?]))
        "a single row without an id is not ambiguous -- nothing collided with it")))

(deftest indistinguishable-rows-are-marked-rather-than-merged-or-invented
  (let [{:keys [transactions]}
        (cs/parse-transactions
         (csv "2026/08/01,カフェ,500,1111,"
              "2026/08/01,カフェ,500,1111,")
         {:card-digest "d" :currency "JPY"})]
    (testing "two identical rows with no id and no time: every field the export
              gave us matched, so they cannot be told apart"
      (is (= 2 (count transactions)))
      (is (every? :ambiguous? transactions))
      (is (= [:indistinguishable-rows :indistinguishable-rows]
             (mapv :ambiguous-reason transactions))))
    (testing "they still get distinct references -- the money was really spent
              twice -- separated by occurrence, not by row number"
      (is (= 2 (count (distinct (map :external-reference transactions))))))))

(deftest the-same-transaction-listed-twice-collapses-when-it-has-an-id
  (let [{:keys [transactions]}
        (cs/parse-transactions
         (csv "2026/08/01,カフェ,500,1111,TXN-1"
              "2026/08/01,カフェ,500,1111,TXN-1")
         {:card-digest "d" :currency "JPY"})]
    (is (= 1 (count (distinct (map :external-reference transactions))))
        "one transaction, listed twice -- not two transactions")
    (is (not-any? :ambiguous? transactions)
        "nothing is ambiguous here: the issuer told us they are the same row")))

;; ---------------------------------------------------------------------------
;; ingest
;; ---------------------------------------------------------------------------

(deftest a-card-is-fingerprinted-never-stored
  (reset-state!)
  (let [card (cs/link-card! session mf-card)]
    (is (= "1111" (:number-last4 card)))
    (is (string? (:number-digest card)))
    (testing "the number itself is nowhere in the stored state"
      (is (not (str/includes? (pr-str (store/snapshot)) "4111111111111111"))))
    (testing "and another organization cannot read the card at all"
      (is (nil? (cs/card outsider (:id card)))))))

(deftest importing-the-same-export-twice-records-nothing-the-second-time
  (reset-state!)
  (let [card (cs/link-card! session mf-card)
        text (csv "2026/08/01,カフェ,500,1111,TXN-1"
                  "2026/08/02,書店,2400,1111,TXN-2")
        opts {:as-of "2026-08-03T09:00:00Z"}
        first-run (cs/ingest! session (:id card) text opts)
        second-run (cs/ingest! session (:id card) text
                               {:as-of "2026-08-04T09:00:00Z"})]
    (is (= {:parsed 2 :recorded 2 :duplicates 0} (select-keys first-run [:parsed :recorded :duplicates])))
    (testing "re-exporting the same period is a safe thing for a person to do"
      (is (= {:parsed 2 :recorded 0 :duplicates 2}
             (select-keys second-run [:parsed :recorded :duplicates]))))
    (is (= 2 (count (cs/transactions session (:id card))))
        "and the store holds two transactions, not four")))

(deftest an-overlapping-export-records-only-what-is-new
  (reset-state!)
  (let [card (cs/link-card! session mf-card)
        opts {:as-of "2026-08-03T09:00:00Z"}]
    (cs/ingest! session (:id card) (csv "2026/08/01,カフェ,500,1111,TXN-1") opts)
    (let [r (cs/ingest! session (:id card)
                        (csv "2026/08/01,カフェ,500,1111,TXN-1"
                             "2026/08/02,書店,2400,1111,TXN-2")
                        opts)]
      (is (= 1 (:recorded r)))
      (is (= 1 (:duplicates r)))
      (is (= 2 (count (cs/transactions session (:id card))))))))

(deftest an-ingest-must-say-when-the-issuer-showed-this
  (reset-state!)
  (let [card (cs/link-card! session mf-card)]
    (is (= :card-statement/as-of-required
           (refuses #(cs/ingest! session (:id card)
                                 (csv "2026/08/01,カフェ,500,1111,TXN-1") {})))
        "an export copied from a three-day-old screen is three days old however
         recently it was uploaded")))

;; ---------------------------------------------------------------------------
;; billing cycles
;; ---------------------------------------------------------------------------

(deftest a-confirmed-cycle-must-say-when-it-debits
  (reset-state!)
  (let [account (funding/link-account!
                 session {:institution "PayPay銀行" :account-type :current
                          :number "1234567" :currency "JPY"})
        card (cs/link-card! session (assoc mf-card :funding-account-id (:id account)))]
    (is (= :card-statement/debit-date-required
           (refuses #(cs/record-billing-cycle!
                      session {:card-id (:id card) :closing-date "2026-08-31"
                               :amount-minor 120000 :status :confirmed
                               :as-of "2026-09-01T00:00:00Z"})))
        "without a debit date it cannot be compared against a balance's own date,
         so it could not tell an upcoming debit from a past one")
    (testing "a provisional cycle needs no debit date -- nothing is fixed yet"
      (is (nil? (refuses #(cs/record-billing-cycle!
                           session {:card-id (:id card) :closing-date "2026-09-30"
                                    :amount-minor 30000 :status :provisional
                                    :as-of "2026-09-05T00:00:00Z"})))))))

;; ---------------------------------------------------------------------------
;; scheduled debit -- pure, and the reason the second funds gate can be trusted
;; ---------------------------------------------------------------------------

(def balance {:amount-minor 100000 :currency "JPY" :as-of "2026-08-10T00:00:00Z"})

(defn- cycle-of [& {:as m}]
  (merge {:id "cycle-1" :status :confirmed :amount-minor 50000
          :debit-date "2026-08-27"} m))

(deftest no-cycle-recorded-is-never-recorded-and-never-zero
  (let [s (funding/scheduled-debit balance [])]
    (is (= :never-recorded (:funding/status s)))
    (testing "AND available declines to answer rather than answering `balance`.
              This is the test the whole decision rests on: the fallback that
              looks harmless is the one that restores the gap silently, in the
              case that looks most normal"
      (is (nil? (funding/available balance s)))
      (is (not= (:amount-minor balance) (funding/available balance s))))))

(deftest a-confirmed-upcoming-debit-is-subtracted
  (let [s (funding/scheduled-debit balance [(cycle-of)])]
    (is (= :known (:funding/status s)))
    (is (= 50000 (:funding/amount-minor s)))
    (is (= 50000 (funding/available balance s)))))

(deftest a-provisional-cycle-is-not-a-scheduled-debit
  (testing "this month's running total is fixed by nobody, so counting it would
            make the gate tighten with the issuer's calendar rather than our money"
    (let [s (funding/scheduled-debit balance [(cycle-of :status :provisional)])]
      (is (= :known (:funding/status s)))
      (is (zero? (:funding/amount-minor s)))
      (is (= 100000 (funding/available balance s))))))

(deftest a-debit-older-than-the-balance-is-unreconciled-not-subtracted-twice
  (let [s (funding/scheduled-debit balance [(cycle-of :debit-date "2026-08-01")])]
    (testing "a balance stated on the 10th already reflects a debit on the 1st"
      (is (zero? (:funding/amount-minor s)))
      (is (= 100000 (funding/available balance s))))
    (testing "but it is reported rather than dropped -- nobody has said whether
              it actually went through"
      (is (= ["cycle-1"] (:funding/unreconciled s)))
      (is (empty? (:funding/cycles s))))))

(deftest a-debit-on-the-balances-own-date-still-counts
  (testing "the balance was stated at 00:00 and the debit happens that day, so
            the money has not left yet as far as anybody has recorded"
    (let [s (funding/scheduled-debit balance [(cycle-of :debit-date "2026-08-10")])]
      (is (= 50000 (:funding/amount-minor s))))))

;; ---------------------------------------------------------------------------
;; the gate, end to end through the request layer
;; ---------------------------------------------------------------------------

(def all-off
  {:authorities {:esim {:enabled? false} :card {:enabled? false}
                 :payment {:enabled? false} :voice {:enabled? false}}})

(def payment-on (assoc-in all-off [:authorities :payment :enabled?] true))

(defn- settle-request [account & {:as overrides}]
  (merge {:op :payment/settle
          :amount-minor 60000
          :currency "JPY"
          :payee {:name "税理士法人TOTAL" :institution "三井住友銀行"
                  :account-type :ordinary :number "7654321"}
          :reference (str "INV-" (rand-int 1000000))
          :funding-account-id (:id account)}
         overrides))

(defn- setup-account! []
  (let [account (funding/link-account!
                 session {:institution "PayPay銀行" :account-type :current
                          :number "1234567" :currency "JPY"})]
    (funding/record-balance! session (:id account)
                             {:amount-minor 100000 :as-of (store/now)
                              :source :owner-attested})
    account))

(deftest a-client-cannot-supply-its-own-schedule
  (reset-state!)
  (let [account (setup-account!)
        card (cs/link-card! session (assoc mf-card :funding-account-id (:id account)))]
    ;; ¥120,000 confirmed against a ¥100,000 balance, debiting today.
    (cs/record-billing-cycle! session
                              {:card-id (:id card) :closing-date "2026-08-31"
                               :debit-date (subs (store/now) 0 10)
                               :amount-minor 120000 :status :confirmed
                               :as-of (store/now)})
    (testing "a request claiming nothing is scheduled is OVERWRITTEN, not merged
              -- otherwise the forward-looking gate is advisory and a caller
              turns it off by saying so, exactly as with the balance"
      (is (= :payment/insufficient-available-funds
             (refuses #(api/review! payment-on session :payment
                                    (settle-request
                                     account
                                     :scheduled-debit {:funding/status :known
                                                       :funding/amount-minor 0}))))))))

(deftest the-forward-gate-refuses-a-payment-the-balance-alone-would-allow
  (reset-state!)
  (let [account (setup-account!)
        card (cs/link-card! session (assoc mf-card :funding-account-id (:id account)))]
    (testing "with nothing scheduled, ¥60,000 out of ¥100,000 passes"
      (is (nil? (refuses #(api/review! payment-on session :payment
                                       (settle-request account))))))
    (cs/record-billing-cycle! session
                              {:card-id (:id card) :closing-date "2026-08-31"
                               :debit-date (subs (store/now) 0 10)
                               :amount-minor 50000 :status :confirmed
                               :as-of (store/now)})
    (testing "with ¥50,000 of card billing already fixed, the same payment is
              refused -- ¥100,000 - ¥50,000 = ¥50,000 < ¥60,000. This is the
              June 2026 failure arriving through the card instead of the invoice"
      (is (= :payment/insufficient-available-funds
             (refuses #(api/review! payment-on session :payment
                                    (settle-request account))))))
    (testing "and a payment that fits inside what is left still goes through"
      (is (nil? (refuses #(api/review! payment-on session :payment
                                       (settle-request account :amount-minor 50000))))))))

(deftest a-short-balance-is-reported-as-a-short-balance
  (reset-state!)
  (let [account (setup-account!)
        card (cs/link-card! session (assoc mf-card :funding-account-id (:id account)))]
    (cs/record-billing-cycle! session
                              {:card-id (:id card) :closing-date "2026-08-31"
                               :debit-date (subs (store/now) 0 10)
                               :amount-minor 50000 :status :confirmed
                               :as-of (store/now)})
    (testing "when BOTH gates would refuse, the simpler one answers -- telling
              someone about a scheduled debit when they are simply out of money
              sends them to the wrong problem"
      (is (= :payment/insufficient-funds
             (refuses #(api/review! payment-on session :payment
                                    (settle-request account :amount-minor 200000))))))))

(deftest a-proposal-records-which-of-the-two-gates-actually-ran
  (reset-state!)
  (let [account (setup-account!)
        p (api/review! payment-on session :payment (settle-request account))]
    (testing "no card statement has ever been imported, so the forward gate did
              not run -- and the proposal says so rather than looking as though
              it did"
      (is (= :never-recorded (get-in p [:value :scheduled-debit-at-review :status])))
      (is (nil? (get-in p [:value :scheduled-debit-at-review :available-minor]))))))

(deftest the-schedule-must-be-stated-even-when-it-is-unknown
  (reset-state!)
  (let [account (setup-account!)
        request (dissoc (settle-request account) :scheduled-debit)]
    (testing "the adapter is a pure function of its inputs, and a caller that
              simply omitted the fact must be refused rather than defaulted"
      (is (= :payment/scheduled-debit-unstated
             (refuses #(payment/pre-check
                        {} session
                        (assoc request
                               :funding-account (funding/account session (:id account))
                               :balance (funding/balance session (:id account))
                               :balance-freshness {:funding/status :fresh}
                               :already-settled? false
                               :posture {:authority/posture :normal}))))))))
