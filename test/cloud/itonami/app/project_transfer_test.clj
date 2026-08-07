(ns cloud.itonami.app.project-transfer-test
  "Moving a project between two of one person's tenants (ADR-0024)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.project-repository :as project-repository]
            [cloud.itonami.app.project-transfer :as project-transfer]
            [cloud.itonami.app.store :as store]))

(defn- identity-state [destination-role]
  (let [now (store/now)]
    {:organizations
     {"org-personal" {:id "org-personal" :tenant/kind :personal
                      :organization-id "owner" :name "owner" :status :active}
      "org-etzhayyim" {:id "org-etzhayyim" :tenant/kind :organization
                       :organization-id "etzhayyim" :name "Etzhayyim"
                       :status :active}}
     :users {"user-1" {:id "user-1" :account-id "owner" :display-name "Owner"
                       :passkey-enrolled? true}}
     :memberships
     {"membership-personal" {:id "membership-personal"
                             :organization-id "org-personal"
                             :user-id "user-1" :role :owner :created-at now}
      "membership-etzhayyim" {:id "membership-etzhayyim"
                              :organization-id "org-etzhayyim"
                              :user-id "user-1" :role destination-role
                              :created-at now}}}))

(defn- seed!
  "A project in the personal tenant, on disk and in the store."
  [temporary destination-role project]
  (reset! store/state
          (-> (store/initial-state)
              (assoc :identity (identity-state destination-role))
              (assoc-in [:chat-projects ["org-personal" "notes"]]
                        (merge {:project-id "notes" :project-slug "notes"
                                :title "Notes" :publication-state :none
                                :sync-state :local-only}
                               project))
              (assoc-in [:project-workspaces ["org-personal" "notes"]]
                        {:organization-id "org-personal" :project-id "notes"
                         :issues {} :repositories []})
              (assoc-in [:drive-artifacts
                         ["org-personal" "user-1" "notes" :project "a1"]]
                        {:item-id "item-1"})))
  (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
    (let [paths (project-repository/project-paths
                 {:organization-id "org-personal" :user-id "user-1"
                  :project-id "notes"})]
      (.mkdirs (io/file (:project-directory paths) ".itonami"))
      (spit (:metadata-file paths)
            (pr-str {:schema "cloud.itonami.app.project.v1"
                     :project/id "notes"
                     :organization/storage-id (:organization-storage-id paths)}))
      (.mkdirs (:workspace-directory paths))
      (spit (io/file (:workspace-directory paths) "conversation.edn") "{}")
      paths)))

(defn- session [kind]
  {:id "session-1" :user-id "user-1" :kind kind
   :issued-via (if (= :passkey kind) :passkey :local-ownership)
   :authn-level (when (= :passkey kind) :phishing-resistant)
   :organization-id "org-personal" :membership-id "membership-personal"})

(defmacro ^:private with-store [& body]
  `(let [previous# @store/state]
     (try ~@body (finally (reset! store/state previous#)))))

(deftest a-project-moves-between-two-tenants-of-one-person
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-project-transfer"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-store
      (let [from (seed! temporary :owner {})]
        (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
          (let [receipt (project-transfer/transfer-project!
                         (session :passkey)
                         {:project-id "notes" :to-tenant "etzhayyim"})
                to (project-repository/project-paths
                    {:organization-id "org-etzhayyim" :user-id "user-1"
                     :project-id "notes"})
                state (store/snapshot)]
            (testing "the receipt names both tenants and what moved"
              (is (= "personal" (get-in receipt [:from :kind])))
              (is (= "organization" (get-in receipt [:to :kind])))
              (is (= ["project" "workspace"] (:moved receipt))))
            (testing "the store now addresses it under the destination"
              (is (nil? (get-in state [:chat-projects ["org-personal" "notes"]])))
              (is (some? (get-in state [:chat-projects
                                        ["org-etzhayyim" "notes"]])))
              (is (= "org-etzhayyim"
                     (get-in state [:project-workspaces
                                    ["org-etzhayyim" "notes"]
                                    :organization-id])))
              (is (some? (get-in state [:drive-artifacts
                                        ["org-etzhayyim" "user-1" "notes"
                                         :project "a1"]]))))
            (testing "and so does the disk, metadata included"
              (is (not (.exists (:project-directory from))))
              (is (not (.exists (:workspace-directory from))))
              (is (.isDirectory (:project-directory to)))
              (is (.isFile (io/file (:workspace-directory to)
                                    "conversation.edn")))
              (is (= (:organization-storage-id to)
                     (:organization/storage-id
                      (edn/read-string (slurp (:metadata-file to)))))))))))))

(deftest a-move-needs-authority-on-both-sides-and-a-browser
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-project-transfer-refusals"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-store
      (testing "a member of the destination may not move work into it"
        (let [from (seed! temporary :member {})]
          (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"owner または admin"
                 (project-transfer/transfer-project!
                  (session :passkey)
                  {:project-id "notes" :to-tenant "etzhayyim"})))
            (is (.isDirectory (:project-directory from))
                "a refused move leaves the disk alone"))))
      (testing "an agent session may not change who owns something"
        (seed! temporary :owner {})
        (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                      local-identity/require-passkey! identity]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"Passkey session"
               (project-transfer/transfer-project!
                (session :agent)
                {:project-id "notes" :to-tenant "etzhayyim"})))))
      (testing "a published project is refused: its ciphertext is keyed to the
                storage owner it is leaving"
        (seed! temporary :owner {:publication-state :published
                                 :published-at (store/now)})
        (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"公開済み"
               (project-transfer/transfer-project!
                (session :passkey)
                {:project-id "notes" :to-tenant "etzhayyim"})))))
      (testing "a tenant the caller does not belong to is not a destination"
        (seed! temporary :owner {})
        (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"membership"
               (project-transfer/transfer-project!
                (session :passkey)
                {:project-id "notes" :to-tenant "somebody-else"}))))))))

(deftest what-does-not-move-is-reported-rather-than-discovered
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-project-transfer-mail"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-store
      (seed! temporary :owner {})
      (store/transact!
       (fn [state]
         (-> state
             (assoc-in [:mail :project-assignments "org-personal"]
                       {"message-1" {"notes" {:project-id "notes"}}
                        "message-2" {"other" {:project-id "other"}}})
             (assoc-in [:mail :project-rules "org-personal"]
                       [{:rule/id "rule-1" :rule/project "notes"}
                        {:rule/id "rule-2" :rule/project "other"}]))))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (let [receipt (project-transfer/transfer-project!
                       (session :passkey)
                       {:project-id "notes" :to-tenant "etzhayyim"})]
          (is (= {:filed-messages 1 :filing-rules 1} (:stayed-behind receipt)))
          (testing "the filings themselves stay in the tenant that made them"
            (is (= 2 (count (get-in (store/snapshot)
                                    [:mail :project-rules "org-personal"]))))
            (is (empty? (get-in (store/snapshot)
                                [:mail :project-rules "org-etzhayyim"])))))))))
