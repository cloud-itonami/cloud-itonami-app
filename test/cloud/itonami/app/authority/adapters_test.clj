(ns cloud.itonami.app.authority.adapters-test
  "The three adapters, tested on the two things an adapter can get wrong:

  1. its pre-check must refuse exactly what the library refuses, and must not be
     MORE permissive than the governor that will see the proposal next. A
     pre-check that waves work through wastes a human's approval.
  2. its digest material must cover every field that changes what would happen.
     A field left out of the material is a field an attacker may edit after the
     human consented."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.card :as card-adapter]
            [cloud.itonami.app.authority.esim :as esim-adapter]
            [cloud.itonami.app.authority.payment :as payment-adapter]
            [cloud.itonami.app.authority.voice :as voice-adapter]
            [cloud.itonami.app.store :as store]
            [kotoba.card.lifecycle :as card-lc]
            [kotoba.esim.lifecycle :as esim-lc]))

(def session {:user-id "user-1" :organization-id "org-1"})

(def eid "89049032000000000000000000000001")
(def iccid-a "8981012345678901230")
(def iccid-b "8981012345678909993")
(def iccid-bad "8981012345678901231")

;; A synthetic 16-digit issuer card reference with a real Luhn check digit,
;; matching what cardissuing.registry/assign-card-reference constructs.
(def card-ref "4111111111111111")

;; :authorization/decide and :card/issue now require a cross-domain posture
;; (ADR-2607300300 D4, cloud.itonami.app.authority.posture). An absent posture
;; refuses, which is what makes the invariant non-bypassable -- so these tests
;; state a normal one explicitly. posture_test.clj covers the restricted side.
(def normal-posture {:authority/posture :normal})

(def demo-profiles
  [{:esim/iccid iccid-a :esim/state :enabled}
   {:esim/iccid iccid-b :esim/state :disabled}])

(defn- reset-proposals! []
  (store/transact! assoc :authority {:proposals {}}))

(defn- ok-commit [_config _session p]
  {:authority/ok? true :authority/record {:recorded (:id p)}})

(defn- refuses [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

;; ---------------------------------------------------------------------------
;; every adapter shares the spine's contract
;; ---------------------------------------------------------------------------

(deftest every-adapter-is-a-valid-domain
  (doseq [[label d] [[:esim (esim-adapter/domain ok-commit)]
                     [:card (card-adapter/domain ok-commit)]
                     [:payment (payment-adapter/domain ok-commit)]
                     [:voice (voice-adapter/domain ok-commit)]]]
    (is (authority/valid-domain? d) (str label " must satisfy the spine's contract"))))

(deftest each-op-allowlist-refuses-an-unknown-op
  (is (= :esim/op-unsupported
         (refuses #(esim-adapter/pre-check {} session {:op :profile/nuke}))))
  (is (= :card/op-unsupported
         (refuses #(card-adapter/pre-check {} session {:op :card/nuke}))))
  (is (= :voice/op-unsupported
         (refuses #(voice-adapter/pre-check {} session {:op :call/nuke}))))
  (is (= :payment/op-unsupported
         (refuses #(payment-adapter/pre-check {} session {:op :payment/nuke}))))
  (testing "and every declared op maps to a distinct context type, so one
            domain's consent cannot be replayed as another op"
    (doseq [[label ops] [[:esim esim-adapter/ops] [:card card-adapter/ops]
                         [:payment payment-adapter/ops] [:voice voice-adapter/ops]]]
      (is (= (count ops) (count (set (vals ops))))
          (str label " context types must be distinct"))))
  (testing "and no context type is shared ACROSS adapters"
    (let [all (concat (vals esim-adapter/ops) (vals card-adapter/ops)
                      (vals payment-adapter/ops) (vals voice-adapter/ops))]
      (is (= (count all) (count (set all)))))))

;; ---------------------------------------------------------------------------
;; eSIM
;; ---------------------------------------------------------------------------

(deftest esim-pre-check-delegates-identifier-validity
  (is (= :esim/identifier-invalid
         (refuses #(esim-adapter/pre-check {} session
                                           {:op :profile/download :eid "123"
                                            :iccid iccid-b :profiles []}))))
  (testing "a tampered check digit is caught -- the checksum is really recomputed"
    (is (= :esim/identifier-invalid
           (refuses #(esim-adapter/pre-check {} session
                                             {:op :profile/download :eid eid
                                              :iccid iccid-bad :profiles []}))))))

(deftest esim-pre-check-is-never-more-permissive-than-the-library
  (testing "for every state/operation pair, the adapter admits exactly what
            kotoba.esim.lifecycle admits"
    (doseq [state [:absent :disabled :enabled :deleted]
            op [:download :enable :disable :delete]]
      (let [profiles [{:esim/iccid iccid-a :esim/state state}]
            library-ok? (:esim/ok? (esim-lc/apply-operation profiles iccid-a op))
            adapter-ok? (nil? (refuses #(esim-adapter/pre-check
                                         {} session
                                         {:op :profile/lifecycle :eid eid
                                          :iccid iccid-a :operation op
                                          :profiles profiles})))]
        (is (= library-ok? adapter-ok?)
            (str "state " state " / op " op
                 ": library says " library-ok? ", adapter says " adapter-ok?))))))

(deftest esim-refuses-enabling-a-second-profile
  (is (= :esim/transition-unreachable
         (refuses #(esim-adapter/pre-check {} session
                                           {:op :profile/lifecycle :eid eid
                                            :iccid iccid-b :operation :enable
                                            :profiles demo-profiles})))))

(deftest esim-refuses-a-duplicate-install-and-a-self-transfer
  (is (= :esim/profile-already-installed
         (refuses #(esim-adapter/pre-check {} session
                                           {:op :profile/download :eid eid
                                            :iccid iccid-a
                                            :profiles demo-profiles}))))
  (is (= :esim/transfer-invalid
         (refuses #(esim-adapter/pre-check {} session
                                           {:op :ownership/transfer :eid eid
                                            :iccid iccid-a
                                            :from-subject "did:key:zA"
                                            :to-subject "did:key:zA"})))))

(deftest esim-material-covers-every-field-that-changes-the-outcome
  (let [base {:op :profile/lifecycle :eid eid :iccid iccid-a
              :operation :disable :from :enabled :to :disabled
              :from-subject "did:key:zA" :to-subject "did:key:zB"}
        m (esim-adapter/material base)]
    (doseq [[k v] {:op :ownership/transfer :eid "89049032000000000000000000000009"
                   :iccid iccid-b :operation :delete :from :disabled :to :deleted
                   :from-subject "did:key:zX" :to-subject "did:key:zY"}]
      (is (not= m (esim-adapter/material (assoc base k v)))
          (str "changing " k " must change the digest material")))))

;; ---------------------------------------------------------------------------
;; card
;; ---------------------------------------------------------------------------

(deftest card-pre-check-recomputes-the-reference-checksum
  (is (= :card/reference-invalid
         (refuses #(card-adapter/pre-check {} session
                                            {:op :card/lifecycle
                                             :card-reference "4111111111111112"
                                             :event :activate :state :issued})))))

(deftest card-pre-check-is-never-more-permissive-than-the-library
  (testing "for every state/event pair, the adapter admits exactly what
            kotoba.card.lifecycle admits -- and that table mirrors the issuer
            governor's own allowlist"
    (doseq [state [:intake :issued :active :blocked :closed]
            event [:activate :block :reissue :close]]
      (let [library-ok? (:card/ok? (card-lc/apply-event state event))
            adapter-ok? (nil? (refuses #(card-adapter/pre-check
                                         {} session
                                         {:op :card/lifecycle
                                          :card-reference card-ref
                                          :event event :state state})))]
        (is (= library-ok? adapter-ok?)
            (str "state " state " / event " event
                 ": library says " library-ok? ", adapter says " adapter-ok?))))))

(deftest card-surfaces-that-a-reissue-mints-a-successor
  (let [v (card-adapter/pre-check {} session
                                   {:op :card/lifecycle :card-reference card-ref
                                    :event :reissue :state :blocked})]
    (is (= :active (:to v)))
    (is (true? (:mints-successor? v))
        "a consent that does not say a new card is created understates what the
         human is agreeing to"))
  (testing "and a non-reissue does not claim to"
    (let [v (card-adapter/pre-check {} session
                                     {:op :card/lifecycle :card-reference card-ref
                                      :event :activate :state :issued})]
      (is (not (contains? v :mints-successor?)))))
  (testing "which also means the material differs between them"
    (is (not= (card-adapter/material {:op :card/lifecycle :mints-successor? true})
              (card-adapter/material {:op :card/lifecycle})))))

(deftest card-daily-limit-is-a-deterministic-refusal
  (let [req {:op :authorization/decide :card-reference card-ref
             :amount 5000 :daily-limit 10000 :spent-today 4000
             :posture normal-posture}]
    (testing "within the limit it passes"
      (is (nil? (refuses #(card-adapter/pre-check {} session req)))))
    (testing "over the limit it refuses BEFORE a human is asked"
      (is (= :card/daily-limit-exceeded
             (refuses #(card-adapter/pre-check {} session
                                                (assoc req :spent-today 6000))))))
    (testing "exactly at the limit passes; one over does not"
      (is (nil? (refuses #(card-adapter/pre-check {} session
                                                   (assoc req :spent-today 5000)))))
      (is (= :card/daily-limit-exceeded
             (refuses #(card-adapter/pre-check {} session
                                                (assoc req :spent-today 5001))))))
    (testing "an UNKNOWN limit refuses -- an absent limit is not an unlimited one"
      (is (= :card/limit-unknown
             (refuses #(card-adapter/pre-check {} session
                                                (dissoc req :daily-limit))))))
    (testing "a non-positive amount refuses"
      (is (= :card/amount-invalid
             (refuses #(card-adapter/pre-check {} session (assoc req :amount 0))))))))

(deftest card-material-covers-every-field-that-changes-the-outcome
  (let [base {:op :authorization/decide :card-reference card-ref :event :activate
              :from :issued :to :active :amount 100 :currency "USD"
              :transaction-id "t1" :cardholder-id "ch1" :reason :fraud}
        m (card-adapter/material base)]
    (doseq [[k v] {:op :card/issue :card-reference "4111111111111129" :event :close
                   :from :active :to :closed :amount 101 :currency "JPY"
                   :transaction-id "t2" :cardholder-id "ch2" :reason :duplicate}]
      (is (not= m (card-adapter/material (assoc base k v)))
          (str "changing " k " must change the digest material")))))

;; ---------------------------------------------------------------------------
;; voice
;; ---------------------------------------------------------------------------

(deftest voice-delegates-number-validity-and-canonicalises
  (is (= :voice/caller-invalid
         (refuses #(voice-adapter/pre-check {} session
                                            {:op :call/answer-authority
                                             :caller-number "nope"}))))
  (testing "the record keeps the canonical form, not the caller's input"
    (is (= "+819012345678"
           (:caller-number (voice-adapter/pre-check
                            {} session {:op :call/answer-authority
                                        :caller-number "+81 90-1234-5678"}))))))

(deftest voice-an-empty-allowlist-allows-nothing
  (let [config {:authorities {:voice {:allowed-callers []}}}]
    (is (= :voice/caller-not-allowed
           (refuses #(voice-adapter/pre-check config session
                                               {:op :call/answer-authority
                                                :caller-number "+819012345678"})))
        "an empty policy must not read as a permissive one"))
  (testing "nil means no allowlist is configured, so none is applied"
    (is (nil? (refuses #(voice-adapter/pre-check
                         {:authorities {:voice {:allowed-callers nil}}} session
                         {:op :call/answer-authority
                          :caller-number "+819012345678"})))))
  (testing "and a listed caller passes while an unlisted one does not"
    (let [config {:authorities {:voice {:allowed-callers ["+819012345678"]}}}]
      (is (nil? (refuses #(voice-adapter/pre-check config session
                                                    {:op :call/answer-authority
                                                     :caller-number "+819012345678"}))))
      (is (= :voice/caller-not-allowed
             (refuses #(voice-adapter/pre-check config session
                                                 {:op :call/answer-authority
                                                  :caller-number "+819099999999"})))))))

(deftest voice-recording-retention-needs-consent
  (is (= :voice/recording-consent-missing
         (refuses #(voice-adapter/pre-check {} session
                                             {:op :call/answer-authority
                                              :caller-number "+819012345678"
                                              :retain-recording? true})))
      "denwaban's G1: retention needs explicit up-front consent")
  (testing "with consent it passes, and the flag is recorded"
    (let [v (voice-adapter/pre-check {} session
                                      {:op :call/answer-authority
                                       :caller-number "+819012345678"
                                       :retain-recording? true
                                       :caller-consented-to-recording? true})]
      (is (true? (:retain-recording? v)))))
  (testing "and the request is refused rather than silently downgraded to
            transient -- answering a different request than the one asked is how
            a consent stops meaning anything"
    (is (some? (refuses #(voice-adapter/pre-check {} session
                                                   {:op :call/answer-authority
                                                    :caller-number "+819012345678"
                                                    :retain-recording? true}))))))

(deftest voice-booking-is-delegated-never-owned
  (let [v (voice-adapter/pre-check {} session
                                    {:op :call/booking-delegate
                                     :caller-number "+819012345678"
                                     :slot "2026-08-01T10:00"})]
    (is (= "yotei" (:booking-owner v)) "denwaban G2: yotei owns the booking"))
  (is (= :voice/slot-missing
         (refuses #(voice-adapter/pre-check {} session
                                             {:op :call/booking-delegate
                                              :caller-number "+819012345678"})))))

(deftest voice-material-covers-every-field-that-changes-the-outcome
  (let [base {:op :call/answer-authority :caller-number "+819012345678"
              :retain-recording? false :slot "s1" :booking-owner "yotei"}
        m (voice-adapter/material base)]
    (doseq [[k v] {:op :call/booking-delegate :caller-number "+819099999999"
                   :retain-recording? true :slot "s2" :booking-owner "other"}]
      (is (not= m (voice-adapter/material (assoc base k v)))
          (str "changing " k " must change the digest material")))))

;; ---------------------------------------------------------------------------
;; the adapters ride the spine end to end
;; ---------------------------------------------------------------------------

(deftest a-refused-pre-check-never-becomes-a-proposal
  (reset-proposals!)
  (doseq [[label f] [[:esim #(esim-adapter/review! ok-commit {} session
                                                    {:op :profile/download :eid eid
                                                     :iccid iccid-bad :profiles []})]
                     [:card #(card-adapter/review! ok-commit {} session
                                                    {:op :authorization/decide
                                                     :card-reference card-ref
                                                     :amount 99999
                                                     :daily-limit 100
                                                     :spent-today 0
                                                     :posture normal-posture})]
                     [:voice #(voice-adapter/review! ok-commit {} session
                                                      {:op :call/answer-authority
                                                       :caller-number "nope"})]]]
    (is (some? (refuses f)) (str label " must refuse"))
    (is (empty? (authority/proposals session))
        (str label ": nothing may be stored, because a stored proposal is exactly
             what start-approval! accepts"))))

(deftest an-accepted-pre-check-becomes-a-proposal-with-a-digest
  (reset-proposals!)
  (let [p (esim-adapter/review! ok-commit {} session
                                 {:op :profile/lifecycle :eid eid :iccid iccid-a
                                  :operation :disable :profiles demo-profiles})]
    (is (= :awaiting-passkey (:status p)))
    (is (= :esim (:authority p)))
    (is (string? (:digest p)))
    (is (= 1 (count (authority/proposals session :esim))))
    (is (zero? (count (authority/proposals session :card))))))

(deftest each-adapter-partitions-its-own-proposals
  (reset-proposals!)
  (esim-adapter/review! ok-commit {} session
                        {:op :profile/lifecycle :eid eid :iccid iccid-a
                         :operation :disable :profiles demo-profiles})
  (card-adapter/review! ok-commit {} session
                        {:op :card/lifecycle :card-reference card-ref
                         :event :activate :state :issued})
  (voice-adapter/review! ok-commit {} session
                         {:op :call/answer-authority
                          :caller-number "+819012345678"})
  (is (= 3 (count (authority/proposals session))))
  (doseq [k [:esim :card :voice]]
    (is (= 1 (count (authority/proposals session k))) (str k " partition"))))
