(ns cloud.itonami.app.tenant-repository-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.repository-storage :as repository]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.tenant-connection :as connection]
            [cloud.itonami.app.tenant-repository :as tenant-repository]))

(def user-id "user-a")
(def tenant-id "org-a")
(def agent-session
  {:id "agent-session" :kind :agent :user-id user-id :label "loop"})
;; A Passkey session as a real ceremony mints it — `:kind :passkey` alone is
;; what every browser token carries by default, and `may-act?` also requires
;; the assurance markers.
(def human {:id "human-session" :kind :passkey :user-id user-id
            :issued-via :passkey :authn-level :phishing-resistant})

(defn- fixture []
  (assoc (store/initial-state) :identity
         {:organizations {tenant-id {:id tenant-id :organization-id "acme"}}
          :users {user-id {:id user-id :passkey-enrolled? true}}
          :memberships {"membership-a"
                        {:id "membership-a" :user-id user-id
                         :organization-id tenant-id :role :owner}}
          :tenant-connections {}}))

(defn- active-connection [storage-budget]
  (let [requested (connection/request!
                   agent-session {:tenant-id "acme"
                          :capabilities ["repository.query" "repository.write"]
                          :budget {:max-operations 20
                                   :max-storage-bytes storage-budget}})]
    (connection/approve! human (:id requested))))

(deftest direct-edn-write-is-cid-safe-and-budgeted
  (let [previous @store/state
        temporary (java.nio.file.Files/createTempDirectory
                   "tenant-repository-"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (reset! store/state (fixture))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))]
        (binding [tenant-repository/*environment* (constantly nil)]
          (let [id (:id (active-connection 10000))
                initial "{:datoms [[\"e-1\" :note/title \"first\"]]}"
                created (tenant-repository/write! agent-session id {:state_edn initial})
                read-back (tenant-repository/read! agent-session id)
                updated (tenant-repository/write!
                         agent-session id
                         {:state_edn "{:datoms [[\"e-1\" :note/title \"second\"]]}"
                          :expected_cid (:semantic-cid created)})]
            (is (= (:semantic-cid created) (:semantic-cid read-back)))
            (is (not= (:semantic-cid created) (:semantic-cid updated)))
            (is (= (:storage-used-bytes updated)
                   (:storage-used-bytes (connection/connection agent-session id))))
            (testing "stale updates do not consume another operation"
              (let [before (:operations-used
                            (connection/connection agent-session id))]
                (is (= :repository-storage/edit-conflict
                       (:type
                        (ex-data
                         (try (tenant-repository/write!
                               agent-session id {:state_edn initial
                                         :expected_cid "sha256:stale"})
                              (catch clojure.lang.ExceptionInfo e e))))))
                (is (= before
                       (:operations-used
                        (connection/connection agent-session id)))))))))
      (finally (reset! store/state previous)))))

(deftest storage-budget-refuses-before-initialization
  (let [previous @store/state
        temporary (java.nio.file.Files/createTempDirectory
                   "tenant-repository-budget-"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (reset! store/state (fixture))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))]
        (binding [tenant-repository/*environment* (constantly nil)]
          (let [id (:id (active-connection 8))
                owner (tenant-repository/owner-id agent-session tenant-id)]
            (is (= :tenant-connection/storage-budget-exhausted
                   (:type
                    (ex-data
                     (try (tenant-repository/write!
                           agent-session id {:state_edn "{:datoms []}"})
                          (catch clojure.lang.ExceptionInfo e e))))))
            (is (nil? (repository/workspace-snapshot
                       (.getPath (java.io.File. (.toFile temporary) "workspace"))
                       owner))))))
      (finally (reset! store/state previous)))))
