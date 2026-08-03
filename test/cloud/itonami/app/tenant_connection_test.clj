(ns cloud.itonami.app.tenant-connection-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.tenant-connection :as connection]))

(def tenant-id "org-tenant-a")
(def membership-id "membership-a")
(def user-id "user-a")
(def agent-session {:id "session-agent" :kind :agent :label "loop-a"
                    :user-id user-id :membership-id membership-id
                    :organization-id tenant-id})
(def human-session {:id "session-human" :kind :passkey :user-id user-id
                    :membership-id membership-id :organization-id tenant-id})

(defn- state []
  (assoc (store/initial-state) :identity
         {:organizations {tenant-id {:id tenant-id :organization-id "acme"
                                     :did "did:web:acme.itonami.cloud"
                                     :name "Acme" :domain "acme.itonami.cloud"}}
          :users {user-id {:id user-id :passkey-enrolled? true}}
          :memberships {membership-id {:id membership-id :user-id user-id
                                       :organization-id tenant-id :role :owner}}
          :tenant-connections {}}))

(deftest connection-is-tenant-bound-approved-and-budgeted
  (let [previous @store/state]
    (try
      (reset! store/state (state))
      (let [requested (connection/request!
                       agent-session
                       {:tenant-id "acme" :agent-id "did:key:agent"
                        :capabilities ["workspace.read" "actor.invoke"]
                        :ttl-seconds 600
                        :budget {:max-operations 1 :max-storage-bytes 1024}
                        :idempotency-key "loop-1"})
            id (:id requested)]
        (is (= :pending-approval (:status requested)))
        (is (= requested
               (connection/request!
                agent-session
                {:tenant-id "acme" :capabilities ["workspace.read"]
                 :idempotency-key "loop-1"})))
        (testing "an agent cannot approve its own request"
          (is (= :tenant-connection/human-approval-required
                 (:type (ex-data
                         (try (connection/approve! agent-session id)
                              (catch clojure.lang.ExceptionInfo e e)))))))
        (is (= :active (:status (connection/approve! human-session id))))
        (testing "an active lease cannot be approved again without renewal"
          (is (= :tenant-connection/invalid-state
                 (:type (ex-data
                         (try (connection/approve! human-session id)
                              (catch clojure.lang.ExceptionInfo e e)))))))
        (is (= tenant-id
               (:tenant-id (connection/context! agent-session id
                                                  "workspace.read"))))
        (testing "the fixed budget is enforced"
          (is (= :tenant-connection/budget-exhausted
                 (:type (ex-data
                         (try (connection/context! agent-session id
                                                   "workspace.read")
                              (catch clojure.lang.ExceptionInfo e e)))))))
        (testing "another agent session cannot borrow the handle"
          (is (= :tenant-connection/forbidden
                 (:type (ex-data
                         (try (connection/revoke! (assoc agent-session :id "other") id)
                              (catch clojure.lang.ExceptionInfo e e))))))))
      (finally (reset! store/state previous)))))

(deftest tenant-list-is-derived-only-from-memberships
  (let [previous @store/state]
    (try
      (reset! store/state (state))
      (with-redefs [identity/require-passkey! identity]
        (is (= ["acme"]
               (mapv :organization-id (:tenants (connection/tenants agent-session))))))
      (finally (reset! store/state previous)))))

(deftest request-validation-and-idempotency-are-safe
  (let [previous @store/state]
    (try
      (reset! store/state (state))
      (testing "a missing tenant id is rejected instead of becoming the string nil"
        (is (= :tenant-connection/tenant-required
               (:type (ex-data
                       (try (connection/request!
                             agent-session
                             {:capabilities ["workspace.read"]})
                            (catch clojure.lang.ExceptionInfo e e)))))))
      (testing "concurrent retries mint one connection"
        (let [request {:tenant-id "acme"
                       :capabilities ["workspace.read"]
                       :idempotency-key "concurrent-loop"}
              ids (->> (repeatedly 12
                                   #(future (:id (connection/request!
                                                   agent-session request))))
                       doall
                       (mapv deref))]
          (is (= 1 (count (set ids))))
          (is (= 1 (count (get-in (store/snapshot)
                                  [:identity :tenant-connections]))))))
      (finally (reset! store/state previous)))))

(deftest ciphertext-publication-storage-is-retry-idempotent
  (let [previous @store/state]
    (try
      (reset! store/state (state))
      (let [requested (connection/request!
                       agent-session
                       {:tenant-id "acme"
                        :capabilities ["repository.write"]
                        :budget {:max-operations 10 :max-storage-bytes 1000}})
            id (:id requested)]
        (connection/approve! human-session id)
        (connection/context! agent-session id "repository.write"
                             {:storage-bytes 100})
        (connection/context! agent-session id "repository.write"
                             {:publication-id "tx:one"
                              :published-byte-delta 200})
        (connection/context! agent-session id "repository.write"
                             {:publication-id "tx:one"
                              :published-byte-delta 200})
        (let [record (connection/connection agent-session id)]
          (is (= 100 (:workspace-bytes record)))
          (is (= 200 (:published-bytes record)))
          (is (= 300 (:storage-used-bytes record))))
        (is (= :tenant-connection/storage-budget-exhausted
               (:type
                (ex-data
                 (try (connection/context!
                       agent-session id "repository.write"
                       {:publication-id "tx:two"
                        :published-byte-delta 800})
                      (catch clojure.lang.ExceptionInfo e e)))))))
      (finally (reset! store/state previous)))))
