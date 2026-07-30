(ns cloud.itonami.app.authority.posture-test
  "The cross-domain invariant, which is the reason the three authorities were
  integrated instead of shipped separately:

    an eSIM ownership transfer lowers the same subject's card authorization
    posture.

  Tested three ways: the pure rule, the card adapter refusing because of it, and
  the whole sequence an attacker would actually run."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.card :as card-adapter]
            [cloud.itonami.app.authority.esim :as esim-adapter]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.store :as store])
  (:import [java.time Instant]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-1"})

(def eid "89049032000000000000000000000001")
(def iccid-a "8981012345678901230")
(def card-ref "4111111111111111")

(def now "2026-07-30T12:00:00Z")

(defn- at [seconds-ago]
  (str (.minusSeconds (Instant/parse now) (long seconds-ago))))

(defn- transfer [& {:keys [status created-at id]
                    :or {status :committed created-at (at 60) id "p-xfer"}}]
  {:id id :authority :esim :op :ownership/transfer
   :status status :created-at created-at :user-id "user-1"})

(defn- reset-proposals! []
  (store/transact! assoc :authority {:proposals {}}))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- ok-commit [_c _s p] {:authority/ok? true :authority/record {:recorded (:id p)}})

;; ---------------------------------------------------------------------------
;; the pure rule
;; ---------------------------------------------------------------------------

(deftest no-transfer-means-a-normal-posture
  (is (= :normal (:authority/posture (posture/for-subject [] now))))
  (testing "and unrelated proposals do not restrict"
    (is (= :normal (:authority/posture
                    (posture/for-subject
                     [{:id "p1" :authority :card :op :card/lifecycle
                       :status :committed :created-at (at 60)}
                      {:id "p2" :authority :esim :op :profile/lifecycle
                       :status :committed :created-at (at 60)}]
                     now))))))

(deftest a-recent-transfer-restricts
  (let [p (posture/for-subject [(transfer)] now)]
    (is (= :restricted (:authority/posture p)))
    (is (= :esim/ownership-transfer (:authority/reason p)))
    (is (= ["p-xfer"] (:authority/signals p)))))

(deftest a-transfer-outside-the-window-does-not-restrict
  (let [window 3600]
    (is (= :restricted (:authority/posture
                        (posture/for-subject [(transfer :created-at (at 1800))]
                                             now window))))
    (is (= :normal (:authority/posture
                    (posture/for-subject [(transfer :created-at (at 7200))]
                                         now window))))
    (testing "the boundary is inclusive"
      (is (= :restricted (:authority/posture
                          (posture/for-subject [(transfer :created-at (at 3600))]
                                               now window))))
      (is (= :normal (:authority/posture
                      (posture/for-subject [(transfer :created-at (at 3601))]
                                           now window)))))))

(deftest an-attempted-transfer-counts-even-when-it-did-not-succeed
  (testing "waiting for a transfer to succeed before restricting hands the
            attacker exactly the window this rule closes"
    (doseq [status [:awaiting-passkey :approved :committed :authority-refused]]
      (is (= :restricted
             (:authority/posture (posture/for-subject [(transfer :status status)] now)))
          (str status " must count as a signal"))))
  (testing "only a human REJECTION clears it -- that is the subject saying they
            did not request it, the strongest evidence it was not them"
    (is (= :normal
           (:authority/posture (posture/for-subject [(transfer :status :rejected)] now))))))

(deftest an-unreadable-timestamp-fails-closed
  (testing "if we cannot tell when a transfer happened, treating it as old would
            silently drop the restriction"
    (doseq [ts [nil "" "not-a-timestamp" "2026-13-45"]]
      (is (= :restricted
             (:authority/posture (posture/for-subject [(transfer :created-at ts)] now)))
          (str "timestamp " (pr-str ts) " must not clear the restriction"))))
  (testing "and an unreadable NOW also fails closed"
    (is (= :restricted
           (:authority/posture (posture/for-subject [(transfer)] "garbage"))))))

(deftest restricted-ops-are-exactly-spend-and-issuance
  (is (= #{:authorization/decide :card/issue} posture/restricted-ops))
  (let [r (posture/for-subject [(transfer)] now)]
    (is (posture/refuses? r :authorization/decide))
    (is (posture/refuses? r :card/issue))
    (testing "a lifecycle transition or a dispute is NOT restricted -- blocking a
              compromised card and disputing a charge are things a victim needs to
              be able to do DURING a takeover"
      (is (not (posture/refuses? r :card/lifecycle)))
      (is (not (posture/refuses? r :dispute/initiate))))
    (testing "and a normal posture refuses nothing"
      (let [n (posture/for-subject [] now)]
        (doseq [op [:authorization/decide :card/issue :card/lifecycle]]
          (is (not (posture/refuses? n op))))))))

;; ---------------------------------------------------------------------------
;; the card adapter honours it, and cannot be asked to skip it
;; ---------------------------------------------------------------------------

(def ^:private auth-request
  {:op :authorization/decide :card-reference card-ref
   :amount 5000 :daily-limit 10000 :spent-today 0})

(deftest a-restricted-posture-refuses-spend-and-issuance
  (let [restricted (posture/for-subject [(transfer)] now)]
    (is (= :card/sim-swap-hold
           (refuses #(card-adapter/pre-check {} session
                                              (assoc auth-request :posture restricted)))))
    (is (= :card/sim-swap-hold
           (refuses #(card-adapter/pre-check {} session
                                              {:op :card/issue :cardholder-id "ch1"
                                               :posture restricted}))))
    (testing "while a lifecycle transition still passes -- a victim must be able
              to block the card while restricted"
      (is (nil? (refuses #(card-adapter/pre-check {} session
                                                   {:op :card/lifecycle
                                                    :card-reference card-ref
                                                    :event :block :state :active
                                                    :posture restricted})))))))

(deftest the-invariant-cannot-be-bypassed-by-not-asking
  (testing "an ABSENT posture refuses, exactly as an absent daily limit does --
            otherwise the whole rule is opt-in for the caller"
    (is (= :card/posture-unknown
           (refuses #(card-adapter/pre-check {} session auth-request))))
    (is (= :card/posture-unknown
           (refuses #(card-adapter/pre-check {} session
                                              {:op :card/issue :cardholder-id "ch1"})))))
  (testing "and an empty map is not a posture"
    (is (= :card/posture-unknown
           (refuses #(card-adapter/pre-check {} session
                                              (assoc auth-request :posture {})))))))

(deftest a-normal-posture-passes-and-is-recorded-in-the-material
  (let [normal (posture/for-subject [] now)
        v (card-adapter/pre-check {} session (assoc auth-request :posture normal))]
    (is (= :normal (:posture v)))
    (testing "the posture is part of the digest material, so a proposal reviewed
              under :normal cannot be committed unchanged after a SIM swap moved
              the subject to :restricted"
      (is (not= (card-adapter/material (assoc v :posture :normal))
                (card-adapter/material (assoc v :posture :restricted)))))))

;; ---------------------------------------------------------------------------
;; the whole sequence, over the one shared partition
;; ---------------------------------------------------------------------------

(deftest the-attack-sequence-is-stopped-across-domains
  (reset-proposals!)
  (testing "before anything, spend is allowed"
    (let [p (posture/subject-posture session)]
      (is (= :normal (:authority/posture p)))
      (is (nil? (refuses #(card-adapter/pre-check {} session
                                                   (assoc auth-request :posture p)))))))

  (testing "the attacker proposes an eSIM ownership transfer -- it is not even
            approved yet, only proposed"
    (esim-adapter/review! ok-commit {} session
                          {:op :ownership/transfer :eid eid :iccid iccid-a
                           :from-subject "did:key:zVictim"
                           :to-subject "did:key:zAttacker"})
    (is (= 1 (count (authority/proposals session :esim)))))

  (testing "and the CARD authority now refuses spend, from the same partition"
    (let [p (posture/subject-posture session)]
      (is (= :restricted (:authority/posture p)))
      (is (= :card/sim-swap-hold
             (refuses #(card-adapter/pre-check {} session
                                                (assoc auth-request :posture p)))))))

  (testing "this is the whole point of D4: the eSIM proposal and the card
            pre-check meet in one store, keyed by the same subject"
    (is (= 1 (count (posture/subject-proposals session))))
    (is (every? #(= "user-1" (:user-id %)) (posture/subject-proposals session)))))

(deftest one-subjects-transfer-does-not-restrict-another
  (reset-proposals!)
  (esim-adapter/review! ok-commit {} other-session
                        {:op :ownership/transfer :eid eid :iccid iccid-a
                         :from-subject "did:key:zA" :to-subject "did:key:zB"})
  (testing "the other subject is restricted"
    (is (= :restricted (:authority/posture (posture/subject-posture other-session)))))
  (testing "and this one is not -- the join key is the subject, not the store"
    (is (= :normal (:authority/posture (posture/subject-posture session))))
    (is (empty? (posture/subject-proposals session)))))

(deftest the-window-comes-from-configuration
  (reset-proposals!)
  (esim-adapter/review! ok-commit {} session
                        {:op :ownership/transfer :eid eid :iccid iccid-a
                         :from-subject "did:key:zA" :to-subject "did:key:zB"})
  (testing "the default window restricts a just-created transfer"
    (is (= :restricted (:authority/posture (posture/subject-posture session {})))))
  (testing "and a zero window still restricts it, because the transfer is NOW --
            a zero window is not a way to switch the rule off"
    (is (= :restricted
           (:authority/posture
            (posture/subject-posture
             session {:authorities {:card {:sim-swap-window-seconds 0}}}))))))
