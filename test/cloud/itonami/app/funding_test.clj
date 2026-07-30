(ns cloud.itonami.app.funding-test
  "The funding model, tested on the three things it exists to get right:

  1. an unknown balance never reads as a number;
  2. an account number is never stored, only fingerprinted;
  3. an account belongs to an organization, so another organization cannot read
     or spend from it."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.store :as store]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-2"})

(def paypay
  {:label "JK株式会社 PayPay銀行"
   :institution "PayPay銀行"
   :branch "ビジネス営業部"
   :account-type :current
   :holder "JK株式会社"
   :number "1234567"
   :currency "JPY"})

(defn- reset-funding! []
  (store/transact! assoc :funding {:accounts {} :balances {}}))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

;; ---------------------------------------------------------------------------
;; freshness -- pure, and the reason the payment gate can be trusted
;; ---------------------------------------------------------------------------

(def ^:private t0 "2026-07-30T00:00:00Z")

(deftest a-balance-that-was-never-recorded-is-not-zero
  (testing "nil, an empty map and a map without an amount are all :never-recorded
            -- none of them is a number, and none of them is 0"
    (doseq [b [nil {} {:as-of t0} {:amount-minor nil :as-of t0}]]
      (is (= :never-recorded (:funding/status (funding/freshness b t0)))
          (pr-str b))))
  (testing "and :never-recorded is not fresh, so nothing may be spent against it"
    (is (not (funding/fresh? (funding/freshness nil t0))))))

(deftest a-zero-balance-is-a-real-recorded-fact
  (testing "0 is a number someone established, so it records and reads as fresh
            -- it refuses payments later, at the funds gate, for the right reason"
    (let [f (funding/freshness {:amount-minor 0 :as-of t0} t0)]
      (is (= :fresh (:funding/status f)))
      (is (zero? (:funding/age-seconds f))))))

(deftest freshness-is-measured-from-the-instant-the-bank-stated
  (let [as-of "2026-07-30T00:00:00Z"
        one-hour-later "2026-07-30T01:00:00Z"
        two-days-later "2026-08-01T00:00:00Z"
        b {:amount-minor 100 :as-of as-of}]
    (is (= :fresh (:funding/status (funding/freshness b one-hour-later))))
    (is (= 3600 (:funding/age-seconds (funding/freshness b one-hour-later))))
    (is (= :stale (:funding/status (funding/freshness b two-days-later)))
        "past the 24h default it can no longer answer 'will this clear?'")
    (testing "and the window is configurable, not baked in"
      (is (= :fresh (:funding/status
                     (funding/freshness b two-days-later (* 7 24 60 60))))))))

(deftest an-unreadable-or-impossible-as-of-is-stale-not-fresh
  (testing "missing, unparseable and FUTURE timestamps all fail closed -- a
            balance whose age cannot be established must not open a spend gate"
    (doseq [as-of [nil "" "yesterday" "2026-13-45T99:00:00Z"
                   "2027-01-01T00:00:00Z"]]
      (let [f (funding/freshness {:amount-minor 100 :as-of as-of} t0)]
        (is (= :stale (:funding/status f)) (pr-str as-of))
        (is (not (funding/fresh? f)) (pr-str as-of)))))
  (testing "and an unreadable `now` is stale too, rather than trusting the record"
    (is (= :stale (:funding/status
                   (funding/freshness {:amount-minor 100 :as-of t0} "not-a-time"))))))

;; ---------------------------------------------------------------------------
;; account numbers are fingerprinted, never stored
;; ---------------------------------------------------------------------------

(deftest an-account-number-reduces-to-last-four-and-a-digest
  (let [f (funding/account-fingerprint "1234567")]
    (is (= "4567" (:last4 f)))
    (is (string? (:digest f)))
    (is (not (re-find #"1234567" (:digest f)))
        "the digest must not contain the number it came from"))
  (testing "formatting is normalised away, so the same account matches however
            it was typed"
    (is (= (:digest (funding/account-fingerprint "1234567"))
           (:digest (funding/account-fingerprint "123-4567"))
           (:digest (funding/account-fingerprint " 1234567 ")))))
  (testing "a short number does not blow up on the last-four window"
    (is (= "12" (:last4 (funding/account-fingerprint "12")))))
  (testing "an absent number fingerprints to nil -- 'unknown', never 'matches'"
    (doseq [n [nil "" "   " "no-digits-here"]]
      (is (nil? (funding/account-fingerprint n)) (pr-str n)))))

(deftest an-unknown-number-never-matches
  (is (funding/same-account? (funding/account-fingerprint "1234567") "1234567"))
  (is (not (funding/same-account? (funding/account-fingerprint "1234567") "7654321")))
  (testing "neither side may be absent"
    (is (not (funding/same-account? nil "1234567")))
    (is (not (funding/same-account? (funding/account-fingerprint "1234567") nil)))
    (is (not (funding/same-account? {} nil)))))

(deftest the-stored-record-does-not-contain-the-account-number
  (reset-funding!)
  (let [record (funding/link-account! session paypay)
        persisted (get-in (store/snapshot) [:funding :accounts (:id record)])]
    (is (= "4567" (:number-last4 persisted)))
    (is (string? (:number-digest persisted)))
    (is (not (contains? persisted :number)))
    (testing "and it is absent from the whole serialised partition, which is what
              actually lands in state.edn"
      (is (not (re-find #"1234567"
                        (pr-str (get-in (store/snapshot) [:funding]))))))))

;; ---------------------------------------------------------------------------
;; linking
;; ---------------------------------------------------------------------------

(deftest linking-refuses-what-it-cannot-record-honestly
  (reset-funding!)
  (is (= :funding/institution-missing
         (refuses #(funding/link-account! session (dissoc paypay :institution)))))
  (is (= :funding/account-type-invalid
         (refuses #(funding/link-account! session (assoc paypay :account-type :chequing))))
      "an unrecognised account type refuses rather than being stored as-is")
  (is (= :funding/account-type-invalid
         (refuses #(funding/link-account! session (dissoc paypay :account-type)))))
  (is (= :funding/currency-unsupported
         (refuses #(funding/link-account! session (assoc paypay :currency "XBT"))))
      "an unknown currency has no minor-unit exponent, and guessing one is how a
       figure becomes wrong by 100x")
  (testing "and a session with no organization cannot link anything"
    (is (= :identity/unauthenticated
           (refuses #(funding/link-account! {:user-id "u"} paypay))))))

(deftest a-linked-account-belongs-to-the-organization-not-the-person
  (reset-funding!)
  (let [record (funding/link-account! session paypay)]
    (is (= "org-1" (:organization-id record)))
    (is (= "user-1" (:linked-by record)) "who linked it is recorded, not owning")
    (is (= :active (:status record)))
    (is (= "JPY" (:currency record)))
    (testing "another organization cannot read it"
      (is (nil? (funding/account other-session (:id record))))
      (is (empty? (funding/accounts other-session))))
    (testing "and cannot record a balance on it"
      (is (= :funding/account-not-found
             (refuses #(funding/record-balance!
                        other-session (:id record)
                        {:amount-minor 1 :currency "JPY" :as-of t0
                         :source :owner-attested})))))))

;; ---------------------------------------------------------------------------
;; recording a balance
;; ---------------------------------------------------------------------------

(deftest recording-a-balance-requires-the-instant-the-bank-stated
  (reset-funding!)
  (let [{:keys [id]} (funding/link-account! session paypay)]
    (is (= :funding/as-of-invalid
           (refuses #(funding/record-balance! session id
                                              {:amount-minor 100 :currency "JPY"
                                               :source :owner-attested})))
        "as-of is not defaulted to now -- a figure copied from a three-day-old
         statement is three days old however recently it was typed in")
    (is (= :funding/as-of-invalid
           (refuses #(funding/record-balance! session id
                                              {:amount-minor 100 :currency "JPY"
                                               :as-of "sometime yesterday"
                                               :source :owner-attested}))))
    (is (= :funding/source-invalid
           (refuses #(funding/record-balance! session id
                                              {:amount-minor 100 :currency "JPY"
                                               :as-of t0 :source :vibes}))))
    (is (= :funding/amount-invalid
           (refuses #(funding/record-balance! session id
                                              {:amount-minor 100.5 :currency "JPY"
                                               :as-of t0 :source :owner-attested})))
        "minor units are integers; a float would silently round somewhere else")
    (is (= :funding/amount-invalid
           (refuses #(funding/record-balance! session id
                                              {:amount-minor -1 :currency "JPY"
                                               :as-of t0 :source :owner-attested}))))
    (is (= :funding/currency-mismatch
           (refuses #(funding/record-balance! session id
                                              {:amount-minor 100 :currency "USD"
                                               :as-of t0 :source :owner-attested}))))))

(deftest a-recorded-balance-carries-its-provenance
  (reset-funding!)
  (let [{:keys [id]} (funding/link-account! session paypay)
        b (funding/record-balance! session id
                                   {:amount-minor 38500 :currency "JPY"
                                    :as-of t0 :source :owner-attested
                                    :source-detail "PayPay銀行 残高照会"})]
    (is (= 38500 (:amount-minor b)))
    (is (= 0 (:exponent b)) "JPY has no minor unit, so 38500 is ¥38,500")
    (is (= t0 (:as-of b)))
    (is (= :owner-attested (:source b)))
    (is (= "PayPay銀行 残高照会" (:source-detail b)))
    (is (= "user-1" (:recorded-by b)))
    (is (string? (:recorded-at b)))
    (is (not= (:as-of b) (:recorded-at b))
        "when the bank said it and when we wrote it down are different facts")
    (testing "and re-recording replaces it, so there is one current balance"
      (funding/record-balance! session id
                               {:amount-minor 50000 :currency "JPY"
                                :as-of "2026-07-31T00:00:00Z" :source :statement})
      (is (= 50000 (:amount-minor (funding/balance session id)))))))

(deftest a-closed-account-keeps-its-history-but-takes-no-new-balance
  (reset-funding!)
  (let [{:keys [id]} (funding/link-account! session paypay)
        closed (funding/close-account! session id)]
    (is (= :closed (:status closed)))
    (is (some? (funding/account session id))
        "kept, not deleted -- a settled payment refers to the account it was
         drawn on")
    (is (= :funding/account-inactive
           (refuses #(funding/record-balance! session id
                                              {:amount-minor 1 :currency "JPY"
                                               :as-of t0 :source :owner-attested}))))))

;; ---------------------------------------------------------------------------
;; the read model
;; ---------------------------------------------------------------------------

(deftest the-snapshot-reports-a-missing-balance-as-nil-not-as-zero
  (reset-funding!)
  (let [{:keys [id]} (funding/link-account! session paypay)
        view (first (:accounts (funding/snapshot {} session)))]
    (is (= id (get-in view [:account :id])))
    (is (nil? (:balance view))
        "a UI that renders this as ¥0 would be stating a fact nobody established")
    (is (= :never-recorded (get-in view [:freshness :funding/status])))))

(deftest the-snapshot-is-scoped-to-the-session-organization
  (reset-funding!)
  (funding/link-account! session paypay)
  (funding/link-account! other-session (assoc paypay :institution "別銀行"))
  (is (= 1 (count (:accounts (funding/snapshot {} session)))))
  (is (= "PayPay銀行"
         (get-in (first (:accounts (funding/snapshot {} session)))
                 [:account :institution])))
  (is (= 1 (count (:accounts (funding/snapshot {} other-session))))))

(deftest the-configured-window-flows-through-to-the-read
  (is (= funding/default-balance-max-age-seconds (funding/max-age-seconds {})))
  (is (= 60 (funding/max-age-seconds
             {:authorities {:payment {:balance-max-age-seconds 60}}}))))
