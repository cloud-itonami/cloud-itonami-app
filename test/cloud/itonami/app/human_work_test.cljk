(ns cloud.itonami.app.human-work-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.human-work :as human-work]
            [cloud.itonami.app.human-work-tools :as human-work-tools]
            [cloud.itonami.app.store :as store]))

(def organization "org-gftd")
(def requester "person-requester")
(def worker "person-worker")
(def other-worker "person-other-worker")
(def verifier "person-verifier")

(use-fixtures
  :each
  (fn [run]
    (let [counter (atom 0)]
      (with-redefs [store/transact! (fn [f & args]
                                      (apply swap! store/state f args))]
        (binding [human-work/*now* (constantly "2026-09-01T00:00:00Z")
                  human-work/*new-id* (fn [prefix]
                                        (str prefix "-" (swap! counter inc)))]
          (reset! store/state (store/initial-state))
          (run))))))

(def worker-input
  {:display-name "Qualified Worker"
   :payout-address "0x1111111111111111111111111111111111111111"
   :locations [{:location-id "tokyo-onsite"
                :country "JP" :region "13" :locality "Shibuya"
                :service-areas ["shibuya-jingumae"]
                :work-modes ["onsite"]
                :evidence-ref "evidence:location:worker"}]
   :availability [{:start "2026-09-07T00:00:00Z"
                   :end "2026-09-07T06:00:00Z"}]
   :credentials
   [{:credential-id "waste-license"
     :type "license" :name "Waste collection licence"
     :code "LIC-001" :issuer "Tokyo authority"
     :jurisdiction {:country "JP" :region "13"}
     :scopes ["bulky-waste-collection"]
     :issued-at "2026-01-01T00:00:00Z"
     :expires-at "2026-09-30T00:00:00Z"
     :evidence-ref "evidence:license:001"}
    {:credential-id "safety-training"
     :type "qualification" :name "Safe lifting"
     :issuer "Training body"
     :jurisdiction {:country "JP" :region "13"}
     :scopes ["safe-lifting"]
     :evidence-ref "evidence:qualification:001"}]})

(def request-input
  {:organization-id organization
   :title "Collect six bed frames"
   :summary "Onsite collection and handoff"
   :category "bulky-waste"
   :work-mode "onsite"
   :location {:country "JP" :region "13"
              :service-area "shibuya-jingumae"
              :minimum-verification "verified"}
   :work-window {:start "2026-09-07T01:00:00Z"
                 :end "2026-09-07T03:00:00Z"}
   :requirements
   {:credentials
    [{:type "license" :scopes ["bulky-waste-collection"]
      :jurisdiction {:country "JP" :region "13"}}
     {:type "qualification" :scopes ["safe-lifting"]
      :jurisdiction {:country "JP" :region "13"}}]}
   :private-details {:address "東京都渋谷区神宮前2丁目"
                     :access-note "accepted worker only"}
   :evidence-contract ["completion-proof" "handoff-receipt"]})

(def verification
  {:decision "verified"
   :evidence-ref "evidence:verifier-check"
   :valid-until "2026-09-10T00:00:00Z"})

(defn- register-and-verify! [worker-id]
  (human-work/register-worker! worker-input worker-id)
  (human-work/verify-location! worker-id "tokyo-onsite" verification
                               verifier organization)
  (human-work/verify-credential! worker-id "waste-license" verification
                                 verifier organization)
  (human-work/verify-credential! worker-id "safety-training" verification
                                 verifier organization))

(defn- open-request! []
  (let [request (human-work/create-request! request-input requester)]
    (human-work/publish! (:id request) requester)))

(defn- error-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error (ex-data error))))

(deftest a-qualified-person-completes-one-evidenced-human-work-chain
  (register-and-verify! worker)
  (let [request (open-request!) id (:id request)
        candidate (first (:items (human-work/matches id requester)))]
    (is (= worker (:worker-id candidate)))
    (is (true? (get-in candidate [:eligibility :eligible?])))
    (is (= #{"waste-license" "safety-training"}
           (set (map :credential-id
                     (get-in candidate [:eligibility :credentials])))))
    (human-work/accept! id worker)
    (is (= "東京都渋谷区神宮前2丁目"
           (get-in (human-work/request id) [:private-details :address])))
    (human-work/start! id {:presence-evidence-ref "proof:arrival"} worker)
    (human-work/submit! id
                        {:summary "Collected and handed off"
                         :evidence {:completion-proof "proof:done"
                                    :handoff-receipt "receipt:facility"}}
                        worker)
    (let [done (human-work/review-submission!
                id {:decision "verified"
                    :verification-evidence-ref "receipt:requester-review"}
                requester)]
      (is (= "verified" (:status done)))
      (is (= ["created" "published" "accepted" "started" "submitted"
              "submission-verified"]
             (mapv :action (:audit done)))))))

(deftest self-attested-expired-or-wrong-place-claims-do-not-match
  (testing "self-attestation is visible but not eligible"
    (human-work/register-worker! worker-input worker)
    (let [request (open-request!)]
      (is (= [] (:items (human-work/matches (:id request) requester))))))
  (testing "a verifier decision that expires before work ends is insufficient"
    (human-work/register-worker! worker-input worker)
    (let [short (assoc verification :valid-until "2026-09-07T02:00:00Z")]
      (human-work/verify-location! worker "tokyo-onsite" short verifier organization)
      (human-work/verify-credential! worker "waste-license" short verifier organization)
      (human-work/verify-credential! worker "safety-training" short verifier organization))
    (let [request (open-request!)]
      (is (= [] (:items (human-work/matches (:id request) requester))))))
  (testing "verified claims in another region do not satisfy location"
    (register-and-verify! worker)
    (let [request (human-work/create-request!
                   (assoc-in request-input [:location :region] "14") requester)]
      (human-work/publish! (:id request) requester)
      (is (= [] (:items (human-work/matches (:id request) requester))))))
  (testing "a credential issued after work starts is not eligible"
    (human-work/register-worker!
     (assoc-in worker-input [:credentials 0 :issued-at]
               "2026-09-08T00:00:00Z")
     worker)
    (human-work/verify-location! worker "tokyo-onsite" verification
                                 verifier organization)
    (human-work/verify-credential! worker "waste-license" verification
                                   verifier organization)
    (human-work/verify-credential! worker "safety-training" verification
                                   verifier organization)
    (let [request (open-request!)]
      (is (= [] (:items (human-work/matches (:id request) requester)))))))

(deftest verification-is-version-bound-and-cannot-be-self-issued
  (human-work/register-worker! worker-input worker)
  (is (= :human-work/self-verification
         (:type (error-data
                 #(human-work/verify-credential!
                   worker "waste-license" verification worker organization)))))
  (register-and-verify! worker)
  (let [before (open-request!)]
    (is (= [worker] (mapv :worker-id
                          (:items (human-work/matches (:id before) requester)))))
    (human-work/register-worker!
     (assoc-in worker-input [:credentials 0 :code] "LIC-002") worker)
    (let [after (open-request!)]
      (is (= [] (:items (human-work/matches (:id after) requester))))
      (is (= [] (get-in (human-work/worker-profile worker)
                        [:credentials 0 :verifications]))))))

(deftest private-details-stay-hidden-before-acceptance-and-overlap-is-refused
  (register-and-verify! worker)
  (let [first-request (open-request!) first-id (:id first-request)
        visible (first (:items (human-work/requests worker organization)))]
    (is (nil? (:private-details visible)))
    (human-work/accept! first-id worker)
    (let [second-request (open-request!) second-id (:id second-request)]
      (is (= [] (:items (human-work/matches second-id requester))))
      (is (= :human-work/not-eligible
             (:type (error-data #(human-work/accept! second-id worker))))))))

(deftest bot-tools-link-the-request-without-making-the-bot-a-person
  (let [bot {:bot/id "bot-1" :bot/owner requester
             :bot/organization organization}
        created
        (human-work-tools/call-tool!
         bot "human_work_request_create"
         {:title "Physical check" :summary "Inspect one site"
          :category "inspection" :work_mode "onsite"
          :location {:country "JP" :region "13"
                     :service_area "shibuya-jingumae"}
          :work_window {:start "2026-09-07T01:00:00Z"
                        :end "2026-09-07T03:00:00Z"}
          :requirements {:credentials []}
          :private_details {:address "private"}
          :evidence_contract ["completion-proof"]
          :goal_id "goal-1" :goal_step_id "step-2"})]
    (is (= requester (:requester-id created)))
    (is (= "bot-1" (get-in created [:source :bot-id])))
    (is (= "goal-1" (get-in created [:source :goal-id])))
    (is (= "person" (:performer-kind
                     (human-work/register-worker! worker-input worker))))
    (is (human-work-tools/write-tool? "human_work_request_publish"))
    (is (not (human-work-tools/write-tool? "human_work_matches")))))

(deftest public-compensated-work-is-redacted-and-cannot-start-unfunded
  (register-and-verify! worker)
  (human-work/record-identity-assurance!
   worker {:provider-id "identity-provider"
           :provider-reference "person-1"
           :level "substantial" :status "verified"
           :checked-at "2026-09-01T00:00:00Z"
           :valid-until "2026-10-01T00:00:00Z"
           :evidence-ref "identity:receipt:1"})
  (let [request (human-work/create-request!
                 (assoc request-input
                        :visibility "public"
                        :compensation {:amount-atomic "12000000"
                                       :network "eip155:8453"
                                       :platform-fee-bps 1000})
                 requester)
        id (:id request)]
    (human-work/publish! id requester)
    (let [listing (first (:items (human-work/public-requests)))]
      (is (= id (:id listing)))
      (is (= "substantial"
             (get-in listing [:requirements :identity :minimum-level])))
      (is (nil? (:requester-id listing)))
      (is (nil? (:private-details listing)))
      (is (nil? (:source listing)))
      (is (= "unfunded"
             (get-in listing [:compensation :settlement-status]))))
    (human-work/accept! id worker)
    (is (= :human-work/payment-required
           (:type (error-data #(human-work/start!
                               id {:presence-evidence-ref "proof:arrival"}
                               worker)))))))
