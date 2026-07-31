(ns cloud.itonami.app.payment-tools-test
  "The agent surface for funding and settlement.

  Two properties carry this namespace, and neither is about a feature:

  1. WITHOUT A REAL SESSION THERE ARE NO TOOLS. Not tools that fail on call --
     absent from the manifest, and refusing if called anyway.
  2. NO TOOL CAN APPROVE. The Passkey stages have no descriptor and no branch,
     so an agent cannot consent on a human's behalf even by guessing a name."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority.api :as authority-api]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mcp :as mcp]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.payment-tools :as payment-tools]
            [cloud.itonami.app.store :as store]))

(def unset-env
  "A config whose token env var is deliberately not set anywhere."
  {:mcp {:human-session-token-env "CLOUD_ITONAMI_TEST_TOKEN_DELIBERATELY_UNSET"}
   :authorities {:payment {:enabled? true :endpoint nil}}})

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- seed-identity!
  "One organization, one owner, and whether that owner has a Passkey."
  [passkey?]
  (store/transact!
   #(assoc %
           :identity
           {:organizations {"org-1" {:id "org-1" :organization-id "jk-corp"
                                     :name "JK株式会社" :status :active}}
            :users {"user-1" {:id "user-1" :display-name "Owner"
                              :passkey-enrolled? passkey?
                              :status (if passkey? :active :pending-passkey)}}
            :memberships {"m-1" {:id "m-1" :organization-id "org-1"
                                 :user-id "user-1" :role :owner}}
            :sessions {}}
           :authority {:proposals {}}
           :funding {:accounts {} :balances {}}))
  (:token (identity/issue-session! "user-1")))

(defn- linked!
  "A session with a funded account, and the account id."
  [session amount-minor]
  (let [{:keys [id]} (funding/link-account!
                      session {:institution "PayPay銀行" :account-type :current
                               :holder "JK株式会社" :number "1234567"
                               :currency "JPY"})]
    (when amount-minor
      (funding/record-balance! session id {:amount-minor amount-minor
                                           :currency "JPY"
                                           :as-of (store/now)
                                           :source :owner-attested}))
    id))

(def payee {:name "税理士法人TOTAL" :institution "三井住友銀行"
            :account-type "ordinary" :number "7654321"})

;; ---------------------------------------------------------------------------
;; no session, no surface
;; ---------------------------------------------------------------------------

(deftest without-a-session-there-are-no-tools-at-all
  (is (false? (payment-tools/available? unset-env)))
  (is (nil? (payment-tools/session unset-env)))
  (testing "and none of them appear in the MCP manifest"
    (let [names (set (map :name (mcp/published-tools unset-env)))]
      (doseq [t payment-tools/tools]
        (is (not (contains? names (:name t)))
            (str (:name t) " must not be published without a session")))))
  (testing "and calling one anyway refuses rather than acting unscoped"
    (is (= :mcp/session-unavailable
           (refuses #(payment-tools/call-tool unset-env "funding_accounts" {}))))))

(deftest a-user-without-a-passkey-does-not-get-a-session
  (let [token (seed-identity! false)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (is (nil? (payment-tools/session unset-env))
          "the token is valid but its user never enrolled -- the app's own gate")
      (is (false? (payment-tools/available? unset-env))))))

(deftest a-revoked-session-stops-working-immediately
  (let [token (seed-identity! true)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (is (some? (payment-tools/session unset-env)))
      (store/transact! update-in [:identity :sessions]
                       (fn [ss] (into {} (map (fn [[k v]]
                                                [k (assoc v :revoked? true)]))
                                      ss)))
      (is (nil? (payment-tools/session unset-env))
          "the token is cached for the process; the SESSION is re-resolved every
           time, so revocation takes effect at once"))))

;; ---------------------------------------------------------------------------
;; no tool can approve
;; ---------------------------------------------------------------------------

(deftest no-tool-can-consent-on-a-humans-behalf
  (let [names (set (map :name payment-tools/tools))]
    (doseq [forbidden ["payment_approve" "payment_approve_start"
                       "payment_approve_finish" "payment_sign"
                       "payment_passkey" "payment_settle"]]
      (is (not (contains? names forbidden))
          (str forbidden " must not exist -- consent is a WebAuthn assertion an
                agent cannot produce")))
    (testing "and the dispatch has no branch for one either, so guessing a name
              gets an unknown-tool refusal rather than a hidden path"
      (let [token (seed-identity! true)]
        (with-redefs [agent-session/human-session-token (constantly token)]
          (is (= :mcp/unknown-tool
                 (refuses #(payment-tools/call-tool
                            unset-env "payment_approve_finish" {})))))))))

(deftest commit-cannot-reach-a-proposal-a-human-has-not-approved
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session 100000)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (let [p (payment-tools/call-tool
               unset-env "payment_review"
               {:funding-account-id account-id :amount-minor 38500
                :currency "JPY" :reference "03356-20260730" :payee payee})]
        (is (= :awaiting-passkey (:status p)))
        (is (= :authority/proposal-not-found
               (refuses #(payment-tools/call-tool
                          unset-env "payment_commit" {:proposal-id (:id p)})))
            "the spine only commits from :approved, so the consent stage cannot
             be stepped over")))))

;; ---------------------------------------------------------------------------
;; the gates are the same ones a human hits
;; ---------------------------------------------------------------------------

(deftest an-agent-proposing-an-unaffordable-payment-is-refused-before-a-human-is-asked
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session 10000)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (is (= :payment/insufficient-funds
             (refuses #(payment-tools/call-tool
                        unset-env "payment_review"
                        {:funding-account-id account-id :amount-minor 38500
                         :currency "JPY" :reference "03356-20260730"
                         :payee payee}))))
      (is (empty? (:proposals (authority-api/proposals unset-env session :payment)))
          "nothing was recorded, so no human is ever shown it"))))

(deftest an-unrecorded-balance-refuses-rather-than-reading-as-zero-or-unlimited
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session nil)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (testing "the read reports it as null with a status, never as 0"
        (let [account (first (:accounts (payment-tools/call-tool
                                         unset-env "funding_accounts" {})))]
          (is (= account-id (:funding-account-id account)))
          (is (nil? (:balance-minor account)))
          (is (= :never-recorded (:balance-status account)))
          (is (= "4567" (:number-last4 account)))))
      (is (= :payment/balance-unknown
             (refuses #(payment-tools/call-tool
                        unset-env "payment_review"
                        {:funding-account-id account-id :amount-minor 1
                         :currency "JPY" :reference "r1" :payee payee})))))))

(deftest an-agent-cannot-record-a-balance-without-the-instant-the-bank-stated
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session nil)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (is (= :funding/as-of-invalid
             (refuses #(payment-tools/call-tool
                        unset-env "funding_record_balance"
                        {:funding-account-id account-id :amount-minor 100000
                         :currency "JPY" :source :owner-attested}))))
      (testing "and with one, it records with its provenance"
        (let [b (payment-tools/call-tool
                 unset-env "funding_record_balance"
                 {:funding-account-id account-id :amount-minor 100000
                  :currency "JPY" :as-of "2026-07-30T09:00:00Z"
                  :source :owner-attested})]
          (is (= 100000 (:balance-minor b)))
          (is (= "2026-07-30T09:00:00Z" (:as-of b))))))))

(deftest the-payee-account-number-does-not-survive-into-the-proposal
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session 100000)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (let [p (payment-tools/call-tool
               unset-env "payment_review"
               {:funding-account-id account-id :amount-minor 38500
                :currency "JPY" :reference "03356-20260730" :payee payee
                :memo "令和8年7月分 未納顧問料"})]
        (is (= "4321" (get-in p [:value :payee :number-last4])))
        (is (not (re-find #"7654321" (pr-str p))))
        (testing "and the nested payee object arrived from JSON with string keys
                  yet still bound every field into the digest"
          (is (= "税理士法人TOTAL" (get-in p [:value :payee :name])))
          (is (= "三井住友銀行" (get-in p [:value :payee :institution])))
          (is (= :ordinary (get-in p [:value :payee :account-type]))))))))

(deftest an-agent-cannot-fund-from-another-organizations-account
  (let [token (seed-identity! true)]
    (store/transact! assoc-in [:identity :organizations "org-2"]
                     {:id "org-2" :organization-id "other" :status :active})
    (let [theirs (linked! {:user-id "user-9" :organization-id "org-2"} 10000000)]
      (with-redefs [agent-session/human-session-token (constantly token)]
        (is (= :payment/account-not-linked
               (refuses #(payment-tools/call-tool
                          unset-env "payment_review"
                          {:funding-account-id theirs :amount-minor 38500
                           :currency "JPY" :reference "r1" :payee payee}))))))))

(def ^:private balance-notice
  "Real text, from message 19f9923559cfbaf5 (2026-07-25)."
  {:subject "【残高不足】引落予定のご案内"
   :body (str "本メールは、残高不足により引き落としができない恐れのあるお客さまにお送りしています。\n"
              "引落予定日：2026/07/27\n合計引落予定額：496131円\n口座残高：435180円\n")})

(deftest an-agent-records-the-banks-own-figure-instead-of-retyping-one
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session nil)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (let [r (payment-tools/call-tool
               unset-env "paypay_ingest_balance_notice"
               (merge balance-notice {:funding-account-id account-id
                                      :received-at "2026-07-25T11:54:23Z"}))]
        (is (true? (:recorded? r)))
        (is (= 435180 (:balance-minor r)))
        (is (= :statement (:source r)) "published to us, not queried by us")
        (is (= "2026-07-25T11:54:23Z" (:as-of r))
            "the mail's arrival time, not the time of this call")
        (is (= 60951 (:shortfall-minor r)))
        (is (seq (:caveat r))
            "the reply must carry the warning-feed caveat, or an agent will
             report a quiet inbox as a healthy balance")))))

(deftest a-mail-that-is-not-a-balance-notice-records-nothing-rather-than-zero
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session nil)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (doseq [m [{:subject "ポイント受取設定完了のお知らせ" :body "..."}
                 {:subject "【残高不足】引落予定のご案内" :body "壊れた本文"}]]
        (let [r (payment-tools/call-tool
                 unset-env "paypay_ingest_balance_notice"
                 (merge m {:funding-account-id account-id
                           :received-at "2026-07-25T11:54:23Z"}))]
          (is (false? (:recorded? r)) (pr-str m))
          (is (seq (:reason r)) (pr-str m))))
      (is (nil? (funding/balance session account-id))
          "still never-recorded -- a parser that fell back to 0 would look like
           it worked and refuse every payment forever")
      (is (= :payment/balance-unknown
             (refuses #(payment-tools/call-tool
                        unset-env "payment_review"
                        {:funding-account-id account-id :amount-minor 1
                         :currency "JPY" :reference "r1" :payee payee})))))))

(deftest the-ingested-balance-then-drives-the-funds-gate
  (let [token (seed-identity! true)
        session (identity/session token)
        account-id (linked! session nil)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (payment-tools/call-tool
       unset-env "paypay_ingest_balance_notice"
       (merge balance-notice {:funding-account-id account-id
                              :received-at (store/now)}))
      (testing "¥435,180 covers the ¥38,500 advisory fee"
        (is (= :awaiting-passkey
               (:status (payment-tools/call-tool
                         unset-env "payment_review"
                         {:funding-account-id account-id :amount-minor 38500
                          :currency "JPY" :reference "03356-20260730"
                          :payee payee})))))
      (testing "and does not cover ¥500,000 -- the same figure, both directions"
        (is (= :payment/insufficient-funds
               (refuses #(payment-tools/call-tool
                          unset-env "payment_review"
                          {:funding-account-id account-id :amount-minor 500000
                           :currency "JPY" :reference "other" :payee payee}))))))))

;; ---------------------------------------------------------------------------
;; the manifest
;; ---------------------------------------------------------------------------

(deftest with-a-session-the-tools-are-published-and-each-is-well-formed
  (let [token (seed-identity! true)]
    (with-redefs [agent-session/human-session-token (constantly token)]
      (let [published (mcp/published-tools unset-env)
            names (set (map :name published))]
        (is (= #{"funding_accounts" "funding_link_account"
                 "funding_record_balance" "paypay_ingest_balance_notice"
                 "payment_review" "payment_proposals" "payment_commit"
                 "payment_reject"}
               names))
        (testing "the fleet tools are absent because that capability is off --
                  the two groups gate independently"
          (is (not (contains? names "fleet_search"))))
        (doseq [{:keys [name description parameters]} published]
          (is (seq description) (str name " needs a description"))
          (is (= "object" (:type parameters)) (str name " schema")))))))
