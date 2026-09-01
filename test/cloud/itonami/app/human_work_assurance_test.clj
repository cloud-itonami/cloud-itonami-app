(ns cloud.itonami.app.human-work-assurance-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.human-work :as human-work]
            [cloud.itonami.app.human-work-assurance :as assurance]
            [cloud.itonami.app.store :as store]))

(def worker "worker-1")
(def organization "org-1")

(use-fixtures
  :each
  (fn [run]
    (with-redefs [store/transact! (fn [f & args] (apply swap! store/state f args))]
      (binding [human-work/*now* (constantly "2026-09-01T00:00:00Z")
                human-work/*new-id* (fn [prefix] (str prefix "-id"))]
        (reset! store/state (store/initial-state))
        (human-work/register-worker!
         {:display-name "Worker"
          :locations [{:location-id "remote" :country "JP"
                       :work-modes ["remote"] :service-areas []
                       :evidence-ref "worker:location"}]
          :availability [{:start "2026-09-01T00:00:00Z"
                          :end "2026-10-01T00:00:00Z"}]
          :credentials [{:credential-id "licence" :type "license"
                         :name "Licence" :issuer "Authority"
                         :jurisdiction {:country "JP"}
                         :scopes ["inspect"] :evidence-ref "worker:licence"}]}
         worker)
        (run)))))

(def config
  {:human-work
   {:assurance
    {:providers [{:id "registry" :kind :credential-registry :enabled? true
                  :endpoint "https://authority.example/check"
                  :credential-types ["license"]}
                 {:id "identity" :kind :identity :enabled? true
                  :endpoint "https://identity.example/check"}]}}})

(deftest online-results-must-bind-the-exact-claim-and-organization
  (let [claim (first (:credentials (human-work/worker-profile worker)))]
    (binding [assurance/*online-check!*
              (fn [_ request]
                (is (nil? (:private-details request)))
                {:worker-id worker :credential-id "licence"
                 :claim-version (:claim-version claim)
                 :organization-id organization :decision "verified"
                 :reference "authority-receipt-1"
                 :valid-until "2026-10-01T00:00:00Z"})]
      (let [result (assurance/check-credential!
                    config {:worker-id worker :credential-id "licence"
                            :provider-id "registry" :organization-id organization
                            :verifier-id "admin-1"})]
        (is (= "verified" (:status result)))
        (is (= "provider:registry:authority-receipt-1"
               (:evidence-ref result)))))))

(deftest identity-provider-leaves-only-an-assurance-receipt
  (binding [assurance/*online-check!*
            (fn [_ _]
              {:worker-id worker :organization-id organization
               :status "verified" :level "substantial"
               :reference "identity-receipt-1"
               :checked-at "2026-09-01T00:00:00Z"
               :valid-until "2026-10-01T00:00:00Z"})]
    (assurance/check-identity!
     config {:worker-id worker :provider-id "identity"
             :organization-id organization})
    (let [record (first (:identity-assurances
                         (human-work/worker-profile worker)))]
      (is (= "verified" (:status record)))
      (is (= "identity-receipt-1" (:provider-reference record)))
      (is (nil? (:document record)))
      (is (nil? (:birth-date record))))))
