(ns cloud.itonami.app.authority.api-test
  "The request layer: that a disabled authority has no surface, that an unknown
  authority cannot be reached by a typo, that the transport records why a hand-off
  could not happen, and -- the one that matters most -- that a CLIENT CANNOT
  SUPPLY ITS OWN POSTURE."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.api :as api]
            [cloud.itonami.app.authority.esim :as esim-adapter]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [cloud.itonami.app.store :as store]))

(def session {:user-id "user-1" :organization-id "org-1"})

(def eid "89049032000000000000000000000001")
(def iccid-a "8981012345678901230")
(def iccid-b "8981012345678909993")
(def card-ref "4111111111111111")

(def all-off
  "What defaults.edn ships: every authority off, no endpoints."
  {:authorities {:esim {:enabled? false :endpoint nil}
                 :card {:enabled? false :endpoint nil}
                 :payment {:enabled? false :endpoint nil}
                 :voice {:enabled? false :endpoint nil}}})

(defn- on
  "Config with one or more authorities enabled, still without endpoints unless
  given."
  [& ks]
  (reduce (fn [c k] (assoc-in c [:authorities k :enabled?] true)) all-off ks))

(defn- reset-proposals! []
  (store/transact! assoc :authority {:proposals {}}))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(def ^:private lifecycle-request
  {:op :profile/lifecycle :eid eid :iccid iccid-a :operation :disable
   :profiles [{:esim/iccid iccid-a :esim/state :enabled}]})

;; ---------------------------------------------------------------------------
;; a disabled authority has no surface at all
;; ---------------------------------------------------------------------------

(deftest every-stage-refuses-while-the-authority-is-disabled
  (reset-proposals!)
  (is (= :authority/disabled (refuses #(api/review! all-off session :esim lifecycle-request))))
  (is (= :authority/disabled (refuses #(api/start-approval! all-off session :esim "p" "localhost" "http://localhost:1338"))))
  (is (= :authority/disabled (refuses #(api/finish-approval! all-off session :esim "p" "t" {}))))
  (is (= :authority/disabled (refuses #(api/reject! all-off session :esim "p"))))
  (is (= :authority/disabled (refuses #(api/commit! all-off session :esim "p"))))
  (testing "including the read -- answering as though a disabled authority might
            have proposals is worse than refusing"
    (is (= :authority/disabled (refuses #(api/proposals all-off session :esim)))))
  (is (empty? (authority/proposals session))))

(deftest the-shipped-default-is-off-for-every-authority
  (doseq [k (keys api/adapters)]
    (is (not (transport/enabled? all-off k)) (str k " must ship disabled"))
    (is (not (transport/enabled? {} k))
        (str k ": absent config must read as off, not as on"))))

(deftest an-unknown-authority-is-refused-rather-than-defaulted
  (is (= :authority/disabled (refuses #(api/review! all-off session :esmi lifecycle-request)))
      "a typo hits the enabled check first, which is also a refusal")
  (testing "and even when enabled, an unknown key does not resolve to an adapter"
    (let [cfg (assoc-in all-off [:authorities :esmi :enabled?] true)]
      (is (= :authority/unknown-authority
             (refuses #(api/review! cfg session :esmi lifecycle-request)))))))

;; ---------------------------------------------------------------------------
;; the posture is the server's, not the client's
;; ---------------------------------------------------------------------------

(deftest a-client-cannot-supply-its-own-posture
  (reset-proposals!)
  (let [cfg (on :esim :card)]
    ;; The subject has an eSIM ownership transfer on record, so the real posture
    ;; is :restricted.
    (esim-adapter/review! (fn [_ _ p] {:authority/ok? true :authority/record {:id (:id p)}})
                          cfg session
                          {:op :ownership/transfer :eid eid :iccid iccid-a
                           :from-subject "did:key:zVictim"
                           :to-subject "did:key:zAttacker"})
    (is (= :restricted (:authority/posture (posture/subject-posture session cfg))))

    (testing "a request claiming :normal is OVERWRITTEN, not merged -- otherwise
              the whole invariant is advisory and an attacker just sends :normal"
      (is (= :card/sim-swap-hold
             (refuses #(api/review! cfg session :card
                                    {:op :authorization/decide
                                     :card-reference card-ref
                                     :amount 100 :daily-limit 10000 :spent-today 0
                                     :posture {:authority/posture :normal}})))))

    (testing "and so is a claimed posture on :card/issue"
      (is (= :card/sim-swap-hold
             (refuses #(api/review! cfg session :card
                                    {:op :card/issue :cardholder-id "ch1"
                                     :posture {:authority/posture :normal}})))))

    (testing "an op that is not posture-restricted is untouched by this"
      (is (nil? (refuses #(api/review! cfg session :card
                                       {:op :card/lifecycle
                                        :card-reference card-ref
                                        :event :block :state :active})))))))

(deftest with-no-transfer-the-server-supplies-a-normal-posture-so-the-caller-need-not
  (reset-proposals!)
  (let [cfg (on :card)
        p (api/review! cfg session :card
                       {:op :authorization/decide :card-reference card-ref
                        :amount 100 :daily-limit 10000 :spent-today 0})]
    (is (= :awaiting-passkey (:status p)))
    (is (= :normal (get-in p [:value :posture]))
        "the caller sent no posture at all and the server filled it in")))

;; ---------------------------------------------------------------------------
;; the transport records WHY a hand-off could not happen
;; ---------------------------------------------------------------------------

(deftest the-transport-refuses-a-disabled-authority
  (let [f (transport/commit-fn :esim)
        out (f all-off session {:id "p1"})]
    (is (false? (:authority/ok? out)))
    (is (= :authority-disabled (get-in out [:authority/refusal :rule])))))

(deftest an-enabled-authority-with-no-endpoint-still-cannot-commit
  (let [f (transport/commit-fn :esim)
        out (f (on :esim) session {:id "p1"})]
    (is (false? (:authority/ok? out)))
    (is (= :endpoint-not-configured (get-in out [:authority/refusal :rule]))
        "enabled is not the same as reachable")))

(deftest an-empty-endpoint-string-counts-as-unconfigured
  (let [cfg (-> (on :esim) (assoc-in [:authorities :esim :endpoint] ""))
        out ((transport/commit-fn :esim) cfg session {:id "p1"})]
    (is (= :endpoint-not-configured (get-in out [:authority/refusal :rule])))))

(deftest an-unreachable-endpoint-refuses-rather-than-throwing
  (let [cfg (-> (on :esim)
                ;; Port 1 on loopback: refused immediately, no network wait.
                (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1/commit"))
        out ((transport/commit-fn :esim) cfg session {:id "p1"})]
    (is (false? (:authority/ok? out)))
    (is (= :transport-failed (get-in out [:authority/refusal :rule]))
        "a transport problem is an outcome to record, not an exception to leak
         into a route")))

(deftest a-failed-handoff-lands-as-authority-refused-not-as-an-error
  (reset-proposals!)
  (let [cfg (on :esim)
        p (api/review! cfg session :esim lifecycle-request)]
    ;; Stand in for the Passkey stage; the consent path is covered elsewhere.
    (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
    (let [out (api/commit! cfg session :esim (:id p))]
      (is (= :authority-refused (:status out)))
      (is (= :endpoint-not-configured (get-in out [:authority-refusal :rule])))
      (is (nil? (:authority-record out)))
      (is (authority/terminal? out)
          "an unreachable authority is terminal for this proposal -- the consent
           was for this content and does not carry over to a retry"))))

;; ---------------------------------------------------------------------------
;; reads
;; ---------------------------------------------------------------------------

(deftest the-overview-shows-a-disabled-authority-instead-of-refusing
  (reset-proposals!)
  (let [o (api/overview all-off session)]
    (is (= #{:esim :card :payment :voice} (set (keys (:authorities o)))))
    (doseq [[k v] (:authorities o)]
      (is (false? (:enabled? v)) (str k))
      (is (false? (:endpoint-configured? v)) (str k))
      (is (not (contains? v :proposals))
          (str k ": a disabled authority has no proposals to list")))
    (testing "and the posture travels with the read so a UI cannot derive it
              differently"
      (is (= :normal (:authority/posture (:posture o)))))))

(deftest the-overview-distinguishes-enabled-from-reachable
  (let [cfg (-> (on :esim)
                (assoc-in [:authorities :esim :endpoint] "https://esim.example/commit"))
        o (api/overview cfg session)]
    (is (true? (get-in o [:authorities :esim :enabled?])))
    (is (true? (get-in o [:authorities :esim :endpoint-configured?])))
    (testing "an enabled authority with no endpoint is reported as such, because a
              settings screen showing only 'enabled' would mislead"
      (let [o2 (api/overview (on :card) session)]
        (is (true? (get-in o2 [:authorities :card :enabled?])))
        (is (false? (get-in o2 [:authorities :card :endpoint-configured?])))))))

(deftest the-per-authority-read-is-scoped-and-carries-the-posture
  (reset-proposals!)
  (let [cfg (on :esim)]
    (api/review! cfg session :esim lifecycle-request)
    (let [r (api/proposals cfg session :esim)]
      (is (= :esim (:authority r)))
      (is (true? (:enabled? r)))
      (is (= 1 (count (:proposals r))))
      (is (contains? (:posture r) :authority/posture)))
    (testing "and it does not leak another authority's proposals"
      (let [cfg2 (on :esim :voice)]
        (is (zero? (count (:proposals (api/proposals cfg2 session :voice)))))))))

(deftest a-second-eSIM-op-is-still-refused-on-its-own-merits
  (testing "enabling an authority does not weaken its adapter's pre-check"
    (reset-proposals!)
    (let [cfg (on :esim)]
      (is (= :esim/transition-unreachable
             (refuses #(api/review! cfg session :esim
                                    (assoc lifecycle-request
                                           :iccid iccid-b
                                           :operation :enable
                                           :profiles [{:esim/iccid iccid-a :esim/state :enabled}
                                                      {:esim/iccid iccid-b :esim/state :disabled}])))))
      (is (empty? (authority/proposals session))))))
