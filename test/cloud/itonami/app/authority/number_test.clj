(ns cloud.itonami.app.authority.number-test
  "The numbering adapter and the outbound half of the voice adapter, tested on
  the two things an adapter can get wrong: refusing exactly what the library
  refuses, and binding every field that changes what would happen into the
  digest material.

  Plus the one thing only this layer can get wrong -- letting a caller supply a
  fact that decides whether they are allowed."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.api :as api]
            [cloud.itonami.app.authority.number :as number]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.voice :as voice]
            [cloud.itonami.app.numbers :as numbers]
            [cloud.itonami.app.store :as store]
            [kotoba.phone.numbering :as numbering]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-1"})

(def normal-posture {:authority/posture :normal})
(def restricted-posture
  {:authority/posture :restricted
   :authority/reason :esim/ownership-transfer
   :authority/signals ["p-1"]})

(def blk {:id "JP-1" :first "+819012340000" :last "+819012340099" :kind :mobile})

(def configuration
  {:authorities
   {:number {:enabled? true :blocks [blk]}
    :voice {:enabled? true
            :home-country-code "81"
            :allow-premium-rate? false
            :rate-card {:domestic 20 :international 90}
            :daily-limit-minor 100000}}})

(defn- reset-state! []
  (store/transact! assoc :authority {:proposals {} :numbers {}}))

(defn- ok-commit [_config _session p]
  {:authority/ok? true :authority/record {:recorded (:id p)}})

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- held [msisdn state & {:keys [subject]}]
  (assoc (numbering/record msisdn state :subject (or subject "user-1"))
         :owner-user-id (or subject "user-1")))

(defn- facts [request & {:keys [records posture]}]
  (merge {:records (or records []) :blocks (numbers/blocks configuration)
          :now-ms 1800000000000 :subject "user-1"
          :posture (or posture normal-posture)}
         request))

;; ---------------------------------------------------------------------------
;; the spine's contract
;; ---------------------------------------------------------------------------

(deftest number-domain-is-valid
  (is (authority/valid-domain? (number/domain ok-commit)))
  (is (contains? (set (keys api/adapters)) :number)))

(deftest unknown-ops-are-refused-not-defaulted
  (is (= :number/op-unsupported
         (refuses #(number/pre-check configuration session (facts {:op :number/steal})))))
  (testing "a lifecycle operation that has its own op cannot be smuggled through :number/lifecycle"
    (is (= :number/operation-unsupported
           (refuses #(number/pre-check configuration session
                                       (facts {:op :number/lifecycle
                                               :msisdn "+819012340000"
                                               :operation :assign})))))))

;; ---------------------------------------------------------------------------
;; allocation (払い出し)
;; ---------------------------------------------------------------------------

(deftest allocation-test
  (testing "a free number in a held block is allocatable, as TWO recorded decisions"
    (let [v (number/pre-check configuration session
                              (facts {:op :number/allocate :msisdn "+819012340010"}))]
      (is (= ["reserve" "assign"] (:plan v)))
      (is (= "+819012340010" (:msisdn v)))))
  (testing "outside every block"
    (is (= :number/allocation-refused
           (refuses #(number/pre-check configuration session
                                       (facts {:op :number/allocate :msisdn "+819099990000"}))))))
  (testing "a number still aging cannot be allocated"
    (let [r (assoc (held "+819012340011" :quarantined)
                   :phone/released-at-ms (- 1800000000000 (* 10 24 60 60 1000)))]
      (is (= :number/allocation-refused
             (refuses #(number/pre-check configuration session
                                         (facts {:op :number/allocate :msisdn "+819012340011"}
                                                :records [r]))))))))

;; ---------------------------------------------------------------------------
;; the cross-domain invariant, on the number plane
;; ---------------------------------------------------------------------------

(deftest posture-gates-port-out-and-assignment
  (testing "the ops a restricted posture refuses on this authority"
    (is (posture/restricts? :number :number/port-out))
    (is (posture/restricts? :number :number/assign))
    (is (not (posture/restricts? :number :number/lifecycle))))
  (testing "an absent posture refuses rather than passing"
    (is (= :number/posture-unknown
           (refuses #(number/pre-check configuration session
                                       (assoc (facts {:op :number/port-out
                                                      :msisdn "+819012340000"
                                                      :donor "us" :recipient "them"})
                                              :posture nil))))))
  (testing "a SIM swap holds the port-out -- the second step of the same attack"
    (is (= :number/control-change-hold
           (refuses #(number/pre-check configuration session
                                       (facts {:op :number/port-out :msisdn "+819012340000"
                                               :donor "us" :recipient "them"}
                                              :records [(held "+819012340000" :active)]
                                              :posture restricted-posture))))))
  (testing "an unrestricted op is unaffected"
    (is (some? (number/pre-check configuration session
                                 (facts {:op :number/lifecycle :msisdn "+819012340000"
                                         :operation :suspend}
                                        :records [(held "+819012340000" :active)]
                                        :posture restricted-posture))))))

;; ---------------------------------------------------------------------------
;; portability
;; ---------------------------------------------------------------------------

(deftest port-out-subject-mismatch-is-refused-before-consent
  (let [records [(held "+819012340000" :active :subject "user-1")]]
    (is (some? (number/pre-check configuration session
                                 (facts {:op :number/port-out :msisdn "+819012340000"
                                         :donor "us" :recipient "them"}
                                        :records records))))
    (is (= :number/port-out-refused
           (refuses #(number/pre-check
                      configuration other-session
                      (assoc (facts {:op :number/port-out :msisdn "+819012340000"
                                     :donor "us" :recipient "them"}
                                    :records records)
                             ;; api/review! puts the SESSION's subject here; this
                             ;; is what that looks like for a different session.
                             :subject "user-2"))))
        "a port-out naming a subject other than the holder must not reach a human")))

(deftest port-in-cannot-collide-with-a-live-number
  (is (= :number/port-in-refused
         (refuses #(number/pre-check configuration session
                                     (facts {:op :number/port-in :msisdn "+819012340000"
                                             :donor "them" :recipient "us"}
                                            :records [(held "+819012340000" :active)]))))))

;; ---------------------------------------------------------------------------
;; digest material
;; ---------------------------------------------------------------------------

(deftest material-covers-every-field-that-changes-the-outcome
  (let [base {:op :number/port-out :msisdn "+819012340000" :subject "user-1"
              :donor "us" :recipient "them" :port-id "P1" :posture :normal}]
    (doseq [[label changed] [[:msisdn (assoc base :msisdn "+819012340001")]
                             [:subject (assoc base :subject "user-2")]
                             [:recipient (assoc base :recipient "someone-else")]
                             [:posture (assoc base :posture :restricted)]
                             [:op (assoc base :op :number/port-in)]]]
      (is (not= (number/material base) (number/material changed))
          (str label " must change the consent-bound material")))))

(deftest allocation-material-binds-bot-route-and-recurring-price
  (let [base {:op :number/allocate :msisdn "+815012340001"
              :subject "did:key:owner" :assignee "did:web:bot.example"
              :assignee-kind :bot :route "did:web:bot.example"
              :capabilities [:voice :sms] :provider :telnyx
              :quote {:provider :telnyx :upfront "1.00" :monthly "2.00"
                      :currency "USD" :observed-at 1800000000000}
              :plan ["reserve" "assign"] :posture :normal}]
    (doseq [[label changed]
            [[:assignee (assoc base :assignee "did:web:other.example")]
             [:route (assoc base :route "topic:other")]
             [:capabilities (assoc base :capabilities [:voice])]
             [:provider (assoc base :provider :other)]
             [:monthly (assoc-in base [:quote :monthly] "9.00")]
             [:observed-at (assoc-in base [:quote :observed-at] 1800000000001)]]]
      (is (not= (number/material base) (number/material changed))
          (str label " must change the Passkey-bound material")))))

;; ---------------------------------------------------------------------------
;; outbound calling
;; ---------------------------------------------------------------------------

(deftest originate-test
  (let [records [(held "+819012340000" :active)]
        base {:op :call/originate :destination "+819098765432"
              :calling-number "+819012340000" :subject "user-1"
              :records records :posture normal-posture
              :estimated-minutes 10 :rate-minor-per-minute 20
              :daily-limit-minor 100000 :spent-today-minor 0}]
    (testing "a call from a number the subject holds and has active"
      (let [v (voice/pre-check configuration session base)]
        (is (= :domestic (:destination-class v)))
        (is (= 200 (:estimate-minor v)))))
    (testing "an emergency number is never the destination"
      (is (= :voice/origination-refused
             (refuses #(voice/pre-check configuration session (assoc base :destination "110"))))))
    (testing "a calling number the subject does not hold is refused"
      (is (= :voice/origination-refused
             (refuses #(voice/pre-check configuration session
                                        (assoc base :calling-number "+819012340099"))))))
    (testing "an absent limit refuses -- unknown is not unlimited"
      (is (= :voice/origination-refused
             (refuses #(voice/pre-check configuration session
                                        (assoc base :daily-limit-minor nil))))))
    (testing "a restricted posture stops outbound spend"
      (is (= :voice/control-change-hold
             (refuses #(voice/pre-check configuration session
                                        (assoc base :posture restricted-posture))))))
    (testing "the inbound ops are untouched by the posture gate"
      (is (some? (voice/pre-check configuration session
                                  {:op :call/answer-authority
                                   :caller-number "+819098765432"
                                   :posture restricted-posture}))))
    (testing "the outbound material binds the destination, not the caller field"
      (is (not= (voice/material (voice/pre-check configuration session base))
                (voice/material (voice/pre-check configuration session
                                                 (assoc base :destination "+819098765431"))))))))

;; ---------------------------------------------------------------------------
;; the facts a caller does not get a say in
;; ---------------------------------------------------------------------------

(deftest server-computed-facts-overwrite-the-request
  (reset-state!)
  (testing "records supplied by the caller are replaced by the store's"
    (let [refused (refuses #(api/review! configuration session :number
                                         {:op :number/port-out
                                          :msisdn "+819012340000"
                                          :donor "us" :recipient "them"
                                          ;; the caller claims to hold it
                                          :records [(held "+819012340000" :active)]}))]
      (is (= :number/port-out-refused refused)
          "the store has no such number, so the claim must not survive")))
  (testing "and so is the subject of an allocation"
    (let [p (api/review! configuration session :number
                         {:op :number/allocate :msisdn "+819012340010"
                          :subject "user-2"})]
      (is (= "user-1" (get-in p [:value :subject]))))))

(deftest only-a-committed-proposal-enters-the-read-model
  (reset-state!)
  (let [p (api/review! configuration session :number
                       {:op :number/allocate :msisdn "+819012340020"})]
    (testing "a proposal the authority refused writes nothing"
      ;; This is what a real commit does today: the :number actor does not exist,
      ;; so the transport has no endpoint and the outcome is a refusal.
      (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
      (let [outcome (api/commit! configuration session :number (:id p))]
        (is (= :authority-refused (:status outcome)))
        (is (nil? (numbers/record session "+819012340020"))
            "a refused proposal must not grant a claim")))
    (testing "a committed one grants a claim -- as :assigned, never :active"
      (api/record-number-outcome! session (assoc p :status :committed))
      (is (= :assigned (:phone/state (numbers/record session "+819012340020")))
          "an allocation grants a claim; activation is its own decision"))
    (testing "and that claim is not yet enough to present the number on a call"
      (is (= :voice/origination-refused
             (refuses #(api/review! configuration session :voice
                                    {:op :call/originate
                                     :destination "+819098765432"
                                     :calling-number "+819012340020"
                                     :estimated-minutes 1})))))))

(deftest spend-accumulates-across-proposals
  (reset-state!)
  (numbers/admit! session "+819012340030")
  (numbers/apply-transition! session "+819012340030" :activate)
  (let [call #(api/review! configuration session :voice
                           {:op :call/originate :destination "+819098765432"
                            :calling-number "+819012340030" :estimated-minutes 2000})]
    ;; 2000 minutes * 20 = 40,000 minor units, against a 100,000 limit.
    (is (some? (call)))
    (is (some? (call)))
    ;; The third would take the total past the limit, and the two pending ones
    ;; count: money the operator has not decided on is money that may still leave.
    (is (= :voice/origination-refused (refuses call)))))
