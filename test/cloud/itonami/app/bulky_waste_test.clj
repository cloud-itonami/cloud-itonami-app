(ns cloud.itonami.app.bulky-waste-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.bulky-waste :as bulky]
            [cloud.itonami.app.human-work :as human-work]
            [cloud.itonami.app.store :as store]))

(def owner "person-owner")
(def worker "person-worker")
(def other-worker "person-other-worker")
(def facility "person-facility-operator")
(def verifier "person-verifier")
(def organization "org-gftd")

(use-fixtures :each
  (fn [run]
    (with-redefs [store/transact! (fn [f & args]
                                    (apply swap! store/state f args))]
      (reset! store/state (store/initial-state))
      (run))))

(def pickup-window
  {:start "2026-09-07T01:00:00Z" :end "2026-09-07T03:00:00Z"})

(def worker-profile
  {:service-areas ["shibuya-jingumae"]
   :categories ["bedding"]
   :capacity-grams 120000
   :availability [{:start "2026-09-07T00:00:00Z"
                   :end "2026-09-07T05:00:00Z"}]
   :evidence {:vehicle "vehicle:kei-truck-1"
              :insurance "evidence:insurance-1"
              :waste-carrier "evidence:carrier-attestation-1"
              :service-location "evidence:service-location-1"}
   :country "JP"
   :region "13"})

(def job-request
  {:organization-id organization
   :service-area "shibuya-jingumae"
   :country "JP"
   :region "13"
   :pickup-address "東京都渋谷区神宮前2丁目"
   :access-notes "予約後に建物への入り方を表示"
   :pickup-window pickup-window
   :items [{:category "bedding" :description "寝具用すのこ"
            :quantity 6 :unit-weight-grams 8000}]
   :facility-id "mrf-shibuya-1"
   :facility-operator-id facility
   :facility-permit-evidence-ref "evidence:facility-permit-1"})

(defn- error-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- open-job! []
  (let [job (bulky/create-job! job-request owner)]
    (bulky/publish! (:id job) owner)))

(defn- verify-worker! [worker-id]
  (let [verification {:decision "verified"
                      :valid-until "2027-01-01T00:00:00Z"
                      :evidence-ref "evidence:organization-check"}]
    (human-work/verify-location! worker-id "bulky-waste-service-area"
                                 verification verifier organization)
    (doseq [credential-id ["bulky-waste-carrier-license"
                           "bulky-waste-vehicle-insurance"
                           "bulky-waste-collection-vehicle"]]
      (human-work/verify-credential! worker-id credential-id verification
                                     verifier organization))))

(defn- register-and-verify!
  ([worker-id] (register-and-verify! worker-profile worker-id))
  ([profile worker-id]
   (bulky/register-worker! profile worker-id)
   (verify-worker! worker-id)))

(deftest request-to-recovery-is-one-audited-chain
  (register-and-verify! worker)
  (let [job (open-job!) id (:id job)]
    (is (= [worker] (mapv :worker-id (:items (bulky/matches id owner)))))
    (is (= "booked" (:status (bulky/book! id worker))))
    (is (= "checked-in"
           (:status (bulky/check-in! id {:presence-proof-ref "proof:arrival"}
                                    worker))))
    (is (= "collected"
           (:status (bulky/collect! id {:manifest-id "manifest:3811:1"
                                        :actual-weight-grams 50000
                                        :collection-proof-ref "proof:pickup"}
                                    worker))))
    (is (= "delivered"
           (:status (bulky/deliver! id {:facility-receipt-ref "receipt:facility:1"
                                        :batch-id "batch:3830:1"
                                        :accepted-weight-grams 50000}
                                    facility))))
    (let [done (bulky/recover! id {:recovery-receipt-ref "receipt:recovery:1"
                                   :recovered-weight-grams 42000
                                   :disposed-weight-grams 8000
                                   :outputs [{:material "wood" :weight-grams 42000}]}
                               facility)]
      (is (= "recovered" (:status done)))
      (is (= ["created" "published" "booked" "checked-in" "collected"
              "delivered" "recovered"]
             (mapv :action (:audit done))))
      (is (= "batch:3830:1" (get-in done [:delivery :batch-id])))
      (is (= 42000 (get-in done [:recovery :recovered-weight-grams]))))))

(deftest matching-is-capability-based-and-address-is-private-until-booking
  (register-and-verify! worker)
  (register-and-verify! (assoc worker-profile :capacity-grams 47000)
                        other-worker)
  (let [job (open-job!) id (:id job)
        visible (first (:items (bulky/jobs worker)))]
    (is (= [worker] (mapv :worker-id (:items (bulky/matches id owner))))
        "the under-capacity worker is not a candidate")
    (is (nil? (:pickup-address visible)))
    (is (nil? (:access-notes visible)))
    (is (= :bulky-waste/forbidden
           (error-type #(bulky/matches id worker))))
    (bulky/book! id worker)
    (is (= (:pickup-address job)
           (:pickup-address (first (:items (bulky/jobs worker))))))))

(deftest overlapping-booking-removes-a-worker-from-the-next-match
  (register-and-verify! worker)
  (let [first-job (open-job!)]
    (bulky/book! (:id first-job) worker)
    (let [second-job (open-job!)]
      (is (= [] (:items (bulky/matches (:id second-job) owner))))
      (is (= :bulky-waste/not-eligible
             (error-type #(bulky/book! (:id second-job) worker)))))))

(deftest safety-and-chain-of-custody-fail-closed
  (testing "hazardous or unmodelled categories never enter the workflow"
    (is (= :bulky-waste/unsupported-category
           (error-type
            #(bulky/create-job!
              (assoc job-request :items [{:category "battery"
                                          :description "battery"
                                          :quantity 1
                                          :unit-weight-grams 1000}])
              owner)))))
  (testing "worker attestations are mandatory and remain ineligible until verified"
    (is (= :bulky-waste/evidence-required
           (error-type #(bulky/register-worker!
                         (update worker-profile :evidence dissoc :insurance)
                         worker))))
    (bulky/register-worker! worker-profile worker)
    (let [job (open-job!)]
      (is (= [] (:items (bulky/matches (:id job) owner)))))
    (verify-worker! worker))
  (let [job (open-job!) id (:id job)]
    (bulky/book! id worker)
    (is (= :bulky-waste/evidence-required
           (error-type #(bulky/check-in! id {} worker))))
    (bulky/check-in! id {:presence-proof-ref "proof:arrival"} worker)
    (is (= :bulky-waste/capacity-exceeded
           (error-type #(bulky/collect! id {:manifest-id "manifest:too-heavy"
                                            :actual-weight-grams 120001
                                            :collection-proof-ref "proof:pickup"}
                                        worker))))
    (bulky/collect! id {:manifest-id "manifest:ok"
                        :actual-weight-grams 50000
                        :collection-proof-ref "proof:pickup"} worker)
    (is (= :bulky-waste/forbidden
           (error-type #(bulky/deliver! id {:facility-receipt-ref "receipt:x"
                                            :batch-id "batch:x"
                                            :accepted-weight-grams 50000}
                                        worker))))
    (bulky/deliver! id {:facility-receipt-ref "receipt:facility"
                        :batch-id "batch:3830"
                        :accepted-weight-grams 49000} facility)
    (is (= :bulky-waste/invalid-weight
           (error-type #(bulky/recover! id {:recovery-receipt-ref "receipt:r"
                                            :recovered-weight-grams 40000
                                            :disposed-weight-grams 8000
                                            :outputs []}
                                        facility))))
    (is (= :bulky-waste/invalid-weight
           (error-type #(bulky/recover! id {:recovery-receipt-ref "receipt:r"
                                            :recovered-weight-grams 40000
                                            :disposed-weight-grams 9000
                                            :outputs [{:material "wood"
                                                       :weight-grams 39000}]}
                                        facility))))))

(deftest chain-of-custody-references-cannot-be-replayed
  (register-and-verify!
   (assoc worker-profile :availability
          [{:start "2026-09-07T00:00:00Z" :end "2026-09-07T05:00:00Z"}
           {:start "2026-09-07T06:00:00Z" :end "2026-09-07T10:00:00Z"}])
   worker)
  (let [first-job (open-job!) first-id (:id first-job)]
    (bulky/book! first-id worker)
    (bulky/check-in! first-id {:presence-proof-ref "proof:first"} worker)
    (bulky/collect! first-id {:manifest-id "manifest:one-use"
                              :actual-weight-grams 50000
                              :collection-proof-ref "proof:first-pickup"} worker)
    (let [second-request (assoc job-request :pickup-window
                                {:start "2026-09-07T07:00:00Z"
                                 :end "2026-09-07T09:00:00Z"})
          second-job (bulky/create-job! second-request owner)
          second-id (:id (bulky/publish! (:id second-job) owner))]
      (bulky/book! second-id worker)
      (bulky/check-in! second-id {:presence-proof-ref "proof:second"} worker)
      (is (= :bulky-waste/invalid-transition
             (error-type #(bulky/collect!
                           second-id {:manifest-id "manifest:one-use"
                                      :actual-weight-grams 50000
                                      :collection-proof-ref "proof:second-pickup"}
                           worker)))))))

(deftest only-draft-or-open-jobs-can-be-cancelled
  (register-and-verify! worker)
  (let [job (open-job!) id (:id job)]
    (is (= :bulky-waste/forbidden
           (error-type #(bulky/cancel! id worker))))
    (bulky/book! id worker)
    (is (= :bulky-waste/invalid-transition
           (error-type #(bulky/cancel! id owner))))))
