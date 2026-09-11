(ns cloud.itonami.app.payment-settlement-actor-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.authority.transport :as transport]
            [cloud.itonami.app.payment-settlement-actor :as actor]))

(def proposal
  {:id "p-1"
   :organization-id "org-1"
   :authority :payment
   :op :payment/settle
   :status :approved
   :approved-at "2026-07-30T06:00:00Z"
   :passkey-credential-id "cred-1"
   :digest "digest-1"
   :value {:reference "03356-20260730"
           :amount-minor 38500
           :currency "JPY"
           :funding-account-id "funding-1"
           :payee {:name "税理士法人TOTAL"
                   :institution "bank"
                   :number-last4 "4321"
                   :number-digest "sha256:account"}}})

(deftest only-approved-passkey-bound-payment-proposals-pass
  (doseq [[patch rule]
          [[{:status :awaiting-passkey} :status-not-approved]
           [{:passkey-credential-id nil} :passkey-evidence-missing]
           [{:organization-id nil} :organization-missing]
           [{:authority :card} :authority-mismatch]
           [{:op :payment/wire} :op-mismatch]
           [{:digest nil} :digest-missing]
           [{:value (assoc (:value proposal) :amount-minor 0)} :amount-invalid]
           [{:value (assoc-in (:value proposal) [:payee :number-digest] nil)}
            :payee-account-missing]]]
    (is (= rule (ffirst (actor/proposal-issues (merge proposal patch))))
        (str patch))))

(deftest a-record-is-safe-idempotent-and-reference-scoped
  (let [[s1 out1] (actor/decide (actor/initial-state) proposal "t1")
        [s2 out2] (actor/decide s1 proposal "t2")
        [_ conflict] (actor/decide
                      s1 (assoc proposal :id "p-2" :digest "digest-2") "t3")
        record (:record out1)]
    (is (= "committed" (:status out1)))
    (is (= out1 out2) "identical retry returns the original record")
    (is (= s1 s2) "identical retry writes no second event")
    (is (= "duplicate-reference" (get-in conflict [:refusal :rule])))
    (is (= false (:money-moved? record)))
    (is (= :record-only (:effect record)))
    (is (nil? (get-in record [:payee :number]))
        "the actor never receives or stores a raw payee account number")))

(deftest a-proposal-id-cannot-change-content
  (let [[state _] (actor/decide (actor/initial-state) proposal "t1")
        [_ out] (actor/decide state (assoc proposal :digest "changed") "t2")]
    (is (= "held" (:status out)))
    (is (= "proposal-id-conflict" (get-in out [:refusal :rule])))))

(deftest actor-capability-comparison-is-fail-closed
  (is (actor/token-matches? "secret" "secret"))
  (is (not (actor/token-matches? "secret" "different")))
  (is (not (actor/token-matches? "secret" nil)))
  (is (not (actor/token-matches? nil "secret"))))

(deftest transport-carries-organization-scope-but-not-session-secrets
  (let [wire (:proposal
              (transport/proposal-envelope
               (assoc proposal :session-token "never-send"
                               :csrf "never-send")))]
    (is (= "org-1" (:organization-id wire)))
    (is (= "p-1" (:id wire)))
    (is (not (contains? wire :session-token)))
    (is (not (contains? wire :csrf)))))
