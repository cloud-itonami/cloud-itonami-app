(ns cloud.itonami.app.authority.payment-test
  "The settlement adapter, tested on what its refusals are worth.

  The gate that matters is the funds check, and the test that matters most is
  that a CLIENT CANNOT SUPPLY ITS OWN BALANCE -- exactly the property
  `api-test/a-client-cannot-supply-its-own-posture` establishes for the
  cross-domain posture, and for the same reason: a fact the caller controls is
  not a gate, it is a suggestion."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.api :as api]
            [cloud.itonami.app.authority.esim :as esim-adapter]
            [cloud.itonami.app.authority.payment :as payment]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.store :as store]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def colleague {:user-id "user-2" :organization-id "org-1"})
(def outsider {:user-id "user-3" :organization-id "org-2"})

(def t0 "2026-07-30T00:00:00Z")
(def eid "89049032000000000000000000000001")
(def iccid-a "8981012345678901230")

(def account
  {:id "funding-1" :organization-id "org-1" :label "JK株式会社 PayPay銀行"
   :institution "PayPay銀行" :account-type :current :currency "JPY"
   :status :active :number-last4 "4567"})

(def payee
  {:name "税理士法人TOTAL" :institution "三井住友銀行" :branch "船橋支店"
   :account-type :ordinary :number "7654321"})

(defn- request
  "A settlement that passes every gate, with the six server-computed facts
  supplied explicitly so the pre-check can be exercised as the pure function it
  is.

  `:scheduled-debit` is `:never-recorded` here rather than absent, because the
  adapter requires the fact to be STATED even when it is unknown. That is the
  distinction ADR-2608041200 D5 draws: an organization with no imported card
  statement may still propose a payment -- judged on the balance alone -- but a
  caller that simply forgot the field is refused. `cloud.itonami.app.card-
  statement-test` covers both halves."
  [& {:as overrides}]
  (merge {:op :payment/settle
          :amount-minor 38500
          :currency "JPY"
          :payee payee
          :reference "03356-20260730"
          :due-date "2026-08-31"
          :memo "令和8年7月分 未納顧問料"
          :funding-account account
          :balance {:amount-minor 100000 :currency "JPY" :as-of t0
                    :source :owner-attested}
          :balance-freshness {:funding/status :fresh :funding/age-seconds 0}
          :already-settled? false
          :scheduled-debit {:funding/status :never-recorded}
          :posture {:authority/posture :normal}}
         overrides))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- check [req] (payment/pre-check {} session req))

(defn- reset-state! []
  (store/transact! #(assoc % :authority {:proposals {}}
                             :funding {:accounts {} :balances {}})))

;; ---------------------------------------------------------------------------
;; the spine's contract
;; ---------------------------------------------------------------------------

(deftest payment-is-a-valid-domain-with-a-distinct-context-type
  (is (authority/valid-domain?
       (payment/domain (fn [_ _ p] {:authority/ok? true
                                    :authority/record {:id (:id p)}}))))
  (is (= :payment/settlement-approval (get payment/ops :payment/settle)))
  (is (= :payment/op-unsupported (refuses #(check (request :op :payment/wire))))
      "an unrecognised op is refused, never defaulted"))

;; ---------------------------------------------------------------------------
;; the funds gate
;; ---------------------------------------------------------------------------

(deftest the-funds-gate-refuses-before-a-human-is-asked
  (testing "a balance that covers it passes"
    (is (nil? (refuses #(check (request))))))
  (testing "exactly enough passes; one short does not"
    (is (nil? (refuses #(check (request :balance {:amount-minor 38500 :as-of t0})))))
    (is (= :payment/insufficient-funds
           (refuses #(check (request :balance {:amount-minor 38499 :as-of t0}))))))
  (testing "and this is the June 2026 direct-debit failure encoded as a gate:
            the payable is refused at review, not discovered at the bank"
    (is (= :payment/insufficient-funds
           (refuses #(check (request :balance {:amount-minor 0 :as-of t0})))))))

(deftest an-unknown-balance-is-neither-zero-nor-unlimited
  (testing "never recorded refuses"
    (is (= :payment/balance-unknown
           (refuses #(check (request :balance nil
                                     :balance-freshness
                                     {:funding/status :never-recorded}))))))
  (testing "stale refuses -- a figure too old to answer 'will this clear?' is not
            an answer"
    (is (= :payment/balance-unknown
           (refuses #(check (request :balance {:amount-minor 10000000 :as-of t0}
                                     :balance-freshness
                                     {:funding/status :stale
                                      :funding/age-seconds 999999}))))
        "and a huge stale balance does not buy its way past"))
  (testing "an ABSENT freshness refuses too, so the fact cannot be skipped by
            simply not sending it"
    (is (= :payment/balance-unknown
           (refuses #(check (dissoc (request) :balance-freshness)))))))

;; ---------------------------------------------------------------------------
;; duplicates
;; ---------------------------------------------------------------------------

(deftest a-settled-reference-refuses-as-a-duplicate-not-as-a-funds-problem
  (is (= :payment/duplicate-settlement
         (refuses #(check (request :already-settled? true)))))
  (testing "even when the balance would also refuse it -- telling someone they are
            short of funds for an invoice they already paid sends them to the
            wrong problem"
    (is (= :payment/duplicate-settlement
           (refuses #(check (request :already-settled? true
                                     :balance {:amount-minor 0 :as-of t0}))))))
  (testing "and an UNSTATED settlement history refuses rather than reading as
            'not settled' -- nil is falsey, which is the trap"
    (is (= :payment/settlement-history-unknown
           (refuses #(check (dissoc (request) :already-settled?)))))
    (is (= :payment/settlement-history-unknown
           (refuses #(check (request :already-settled? nil)))))))

(deftest settled-references-are-organization-scoped-and-committed-only
  (let [proposals [{:authority :payment :organization-id "org-1"
                    :status :committed :value {:reference "A"}}
                   {:authority :payment :organization-id "org-1"
                    :status :approved :value {:reference "B"}}
                   {:authority :payment :organization-id "org-1"
                    :status :authority-refused :value {:reference "C"}}
                   {:authority :payment :organization-id "org-1"
                    :status :rejected :value {:reference "D"}}
                   {:authority :payment :organization-id "org-2"
                    :status :committed :value {:reference "E"}}
                   {:authority :card :organization-id "org-1"
                    :status :committed :value {:reference "F"}}]]
    (is (= #{"A"} (payment/settled-references proposals "org-1")))
    (testing "a proposal that was refused or rejected has settled nothing, so the
              payable is not stranded"
      (is (not (contains? (payment/settled-references proposals "org-1") "C")))
      (is (not (contains? (payment/settled-references proposals "org-1") "D"))))
    (testing "another organization's settlement is not ours"
      (is (= #{"E"} (payment/settled-references proposals "org-2"))))))

;; ---------------------------------------------------------------------------
;; shape
;; ---------------------------------------------------------------------------

(deftest the-shape-checks-refuse-what-cannot-be-recorded-honestly
  (is (= :payment/payee-missing (refuses #(check (request :payee {})))))
  (is (= :payment/payee-missing
         (refuses #(check (request :payee (assoc payee :name "  "))))))
  (is (= :payment/reference-missing (refuses #(check (request :reference "")))))
  (is (= :payment/reference-missing (refuses #(check (request :reference nil))))
      "without a reference there is nothing to deduplicate on")
  (is (= :payment/amount-invalid (refuses #(check (request :amount-minor 0)))))
  (is (= :payment/amount-invalid (refuses #(check (request :amount-minor -1)))))
  (is (= :payment/amount-invalid (refuses #(check (request :amount-minor 385.5))))
      "minor units are integers; a float rounds somewhere the human cannot see")
  (is (= :payment/account-not-linked (refuses #(check (request :funding-account nil)))))
  (is (= :payment/account-inactive
         (refuses #(check (request :funding-account (assoc account :status :closed))))))
  (is (= :payment/currency-mismatch (refuses #(check (request :currency "USD"))))
      "the account is a JPY account"))

(deftest the-payee-account-number-never-reaches-the-proposal
  (let [value (check (request))]
    (is (= "4321" (get-in value [:payee :number-last4])))
    (is (string? (get-in value [:payee :number-digest])))
    (is (not (contains? (:payee value) :number)))
    (is (not (re-find #"7654321" (pr-str value)))
        "a proposal is persisted to state.edn -- a payee account number there
         would be a payment instrument this app has no business holding")))

(deftest the-proposal-records-the-balance-it-was-judged-on
  (let [value (check (request))]
    (is (= 100000 (get-in value [:balance-at-review :amount-minor])))
    (is (= t0 (get-in value [:balance-at-review :as-of])))
    (is (= :owner-attested (get-in value [:balance-at-review :source])))))

;; ---------------------------------------------------------------------------
;; the cross-domain hold reaches payments too
;; ---------------------------------------------------------------------------

(deftest a-sim-swap-holds-a-bank-transfer-just-as-it-holds-card-spend
  (is (= :payment/spend-hold
         (refuses #(check (request :posture {:authority/posture :restricted
                                             :authority/signals ["p1"]}))))
      "'move the line, then spend' does not care whether the spending happens on
       a card or by bank transfer")
  (is (= :payment/posture-unknown (refuses #(check (dissoc (request) :posture))))
      "an absent posture refuses, which is what stops the invariant being
       bypassed by simply not asking"))

;; ---------------------------------------------------------------------------
;; digest material
;; ---------------------------------------------------------------------------

(deftest material-covers-every-field-that-changes-the-outcome
  (let [base (check (request))
        m (payment/material base)]
    (doseq [[k v] {:op :payment/other
                   :funding-account-id "funding-2"
                   :reference "03356-20260801"
                   :amount-minor 38501
                   :currency "USD"
                   :due-date "2026-09-30"
                   :memo "別の摘要"
                   :posture :restricted}]
      (is (not= m (payment/material (assoc base k v)))
          (str "changing " k " must change the digest material")))
    (testing "including every part of the payee -- paying the right amount to the
              wrong account is the substitution this binding exists to stop"
      (doseq [[k v] {:name "別の税理士法人" :institution "みずほ銀行"
                     :branch "本店" :account-type :current
                     :number-digest "other-digest"}]
        (is (not= m (payment/material (assoc-in base [:payee k] v)))
            (str "changing payee " k " must change the digest material"))))
    (testing "and the balance is deliberately NOT bound: an unrelated deposit
              must not invalidate a consent the human already gave"
      (is (= m (payment/material
                (assoc base :balance-at-review {:amount-minor 999999})))))))

;; ---------------------------------------------------------------------------
;; the request layer: the facts are the server's, not the client's
;; ---------------------------------------------------------------------------

(def all-off
  {:authorities {:esim {:enabled? false :endpoint nil}
                 :card {:enabled? false :endpoint nil}
                 :payment {:enabled? false :endpoint nil}
                 :voice {:enabled? false :endpoint nil}}})

(defn- on [& ks]
  (reduce (fn [c k] (assoc-in c [:authorities k :enabled?] true)) all-off ks))

(defn- link! [s]
  (funding/link-account! s {:institution "PayPay銀行" :account-type :current
                            :holder "JK株式会社" :number "1234567"
                            :currency "JPY"}))

(defn- api-request [account-id & {:as overrides}]
  (merge {:op :payment/settle :amount-minor 38500 :currency "JPY"
          :payee payee :reference "03356-20260730"
          :funding-account-id account-id}
         overrides))

(deftest a-client-cannot-supply-its-own-balance
  (reset-state!)
  (let [cfg (on :payment)
        {:keys [id]} (link! session)]
    (testing "with no balance on record, a claimed one is OVERWRITTEN, not merged"
      (is (= :payment/balance-unknown
             (refuses #(api/review! cfg session :payment
                                    (api-request
                                     id
                                     :balance {:amount-minor 99999999 :as-of t0}
                                     :balance-freshness {:funding/status :fresh}))))))
    (testing "and once a real balance is recorded, a claimed larger one still
              does not get past the funds gate"
      (funding/record-balance! session id {:amount-minor 100 :currency "JPY"
                                           :as-of (store/now)
                                           :source :owner-attested})
      (is (= :payment/insufficient-funds
             (refuses #(api/review! cfg session :payment
                                    (api-request
                                     id
                                     :balance {:amount-minor 99999999 :as-of t0}))))))
    (testing "a claimed settlement history is overwritten too"
      (is (= :payment/insufficient-funds
             (refuses #(api/review! cfg session :payment
                                    (api-request id :already-settled? true))))
          "the server's own history says not settled, so the request proceeds to
           the funds gate and refuses there"))))

(deftest a-client-cannot-fund-a-payment-from-another-organizations-account
  (reset-state!)
  (let [cfg (on :payment)
        theirs (link! outsider)]
    (funding/record-balance! outsider (:id theirs)
                             {:amount-minor 10000000 :currency "JPY"
                              :as-of (store/now) :source :owner-attested})
    (is (= :payment/account-not-linked
           (refuses #(api/review! cfg session :payment (api-request (:id theirs)))))
        "the id resolves to nil for this session rather than to their account")))

(deftest a-review-that-passes-records-a-proposal-awaiting-consent
  (reset-state!)
  (let [cfg (on :payment)
        {:keys [id]} (link! session)]
    (funding/record-balance! session id {:amount-minor 100000 :currency "JPY"
                                         :as-of (store/now)
                                         :source :owner-attested})
    (let [p (api/review! cfg session :payment (api-request id))]
      (is (= :awaiting-passkey (:status p)))
      (is (= :payment (:authority p)))
      (is (string? (:digest p)))
      (is (= :normal (get-in p [:value :posture]))
          "the caller sent no posture and the server filled it in")
      (is (= 38500 (get-in p [:value :amount-minor])))
      (testing "and a committed one blocks a second settlement of the same
                reference, from ANY member of the organization"
        (store/transact! assoc-in [:authority :proposals (:id p) :status] :committed)
        (is (= :payment/duplicate-settlement
               (refuses #(api/review! cfg session :payment (api-request id)))))
        (let [{theirs :id} (link! colleague)]
          (funding/record-balance! colleague theirs
                                   {:amount-minor 100000 :currency "JPY"
                                    :as-of (store/now) :source :owner-attested})
          (is (= :payment/duplicate-settlement
                 (refuses #(api/review! cfg colleague :payment
                                        (api-request theirs))))
              "an invoice is owed by the company, so a colleague paying it again
               is the duplicate this check is for"))))))

(deftest the-payment-authority-ships-disabled-like-every-other
  (is (= :authority/disabled
         (refuses #(api/review! all-off session :payment (api-request "funding-1")))))
  (is (= :authority/disabled (refuses #(api/proposals all-off session :payment))))
  (is (contains? (set (keys api/adapters)) :payment)))

(deftest a-sim-swap-recorded-in-the-store-holds-a-real-payment-review
  (reset-state!)
  (let [cfg (on :esim :payment)
        {:keys [id]} (link! session)]
    (funding/record-balance! session id {:amount-minor 100000 :currency "JPY"
                                         :as-of (store/now)
                                         :source :owner-attested})
    (esim-adapter/review! (fn [_ _ p] {:authority/ok? true
                                       :authority/record {:id (:id p)}})
                          cfg session
                          {:op :ownership/transfer :eid eid :iccid iccid-a
                           :from-subject "did:key:zVictim"
                           :to-subject "did:key:zAttacker"})
    (is (= :payment/spend-hold
           (refuses #(api/review! cfg session :payment (api-request id))))
        "the hold is read from the shared proposal partition, which is what
         ADR-2607300300 D4 requires and what makes this expressible at all")))
