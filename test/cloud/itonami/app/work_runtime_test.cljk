(ns cloud.itonami.app.work-runtime-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.work-runtime :as runtime]))

(defn isolated-state [test-fn]
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [store/transact!
                    (fn [f & args] (apply swap! store/state f args))]
        (test-fn))
      (finally (reset! store/state previous)))))

(use-fixtures :each isolated-state)

(deftest organization-studio-graph-is-replaced-as-one-tenant-projection
  (let [base {:org/id "org-1" :org/name "Acme"
              :org/units
              [{:org.unit/id "root" :org.unit/organization "org-1"
                :org.unit/name "Acme" :org.unit/kind :organization}
               {:org.unit/id "engineering" :org.unit/organization "org-1"
                :org.unit/name "Engineering" :org.unit/parent "root"}]
              :org/positions
              [{:org.position/id "engineer"
                :org.position/organization "org-1"
                :org.position/unit "engineering" :org.position/name "Engineer"}]
              :org/roles
              [{:org.role/id :implementer :org.role/organization "org-1"
                :org.role/name "Implementer"}]
              :org/performers
              [{:performer/id "agent-1" :performer/organization "org-1"
                :performer/kind :system
                :performer/actor {:actor/kind :agent :actor/id "session-1"}}]
              :org/assignments
              [{:org.assignment/id "assignment-1"
                :org.assignment/organization "org-1"
                :org.assignment/performer "agent-1"
                :org.assignment/position "engineer"
                :org.assignment/roles #{:implementer}}]
              :org/reporting-lines []}]
    (runtime/put-organization! base)
    (let [view (runtime/organization-view "org-1")]
      (is (= 2 (count (:organization-units view))))
      (is (= :agent (get-in view [:performers 0 :performer/actor :actor/kind])))
      (is (= #{:implementer}
             (set (map :org.role/id (:organization-roles view))))))
    (runtime/put-organization!
     (assoc base :org/units [(first (:org/units base))]
            :org/positions [] :org/roles [] :org/assignments []))
    (let [view (runtime/organization-view "org-1")]
      (is (= ["root"] (mapv :org.unit/id (:organization-units view))))
      (is (empty? (:positions view)))
      (is (empty? (:assignments view))))))

(deftest organization-role-removal-cannot-orphan-an-approval-policy
  (let [graph {:org/id "org-1"
               :org/units [{:org.unit/id "root"
                            :org.unit/organization "org-1"
                            :org.unit/name "Root"}]
               :org/roles [{:org.role/id :reviewer
                            :org.role/organization "org-1"
                            :org.role/name "Reviewer"}]}]
    (runtime/put-organization! graph)
    (is (= :work-runtime/unknown-approval-role
           (:type (ex-data
                   (try (runtime/put-approval-policy!
                         {:approval.policy/id "bad"
                          :approval.policy/organization "org-1"
                          :approval.policy/capability :repository/change
                          :approval.policy/eligible-roles #{:owner}})
                        (catch Exception error error))))))
    (runtime/put-approval-policy!
     {:approval.policy/id "review"
      :approval.policy/organization "org-1"
      :approval.policy/capability :repository/change
      :approval.policy/eligible-roles #{:reviewer}})
    (is (= :work-runtime/organization-policy-role-conflict
           (:type (ex-data
                   (try (runtime/put-organization! (assoc graph :org/roles []))
                        (catch Exception error error))))))))

(def role
  {:yakuwari/id :implementer
   :yakuwari/project "project-1"
   :yakuwari/objective "Implement admitted work"
   :yakuwari/runners [{:runner :local}]
   :yakuwari/scale {:min 0 :desired 1 :max 1}
   :yakuwari/policy {:repository/change :autonomous}})

(def item
  {:work.item/id "work-1"
   :work.item/organization "org-1"
   :work.item/project "project-1"
   :work.item/title "Implement the adapter"
   :work.item/capability :repository/change
   :work.item/yakuwari :implementer
   :work.item/content-hash "sha256:content-v1"
   :work.item/status :ready})

(deftest reconcile-leases-dispatches-and-records-a-bound-receipt
  (runtime/put-role! role)
  (runtime/put-work-item! item)
  (let [calls (atom [])
        result (runtime/reconcile-once!
                {:work-governance {:enabled? true :lease-ms 60000
                                   :github-writeback-enabled? false}}
                {:now-ms 1000
                 :dispatch (fn [_ leased]
                             (swap! calls conj leased)
                             {:agent.run/id
                              (get-in leased [:work.item/dispatch-record
                                              :dispatch/agent-run])
                              :agent.run/status :succeeded
                              :agent/result "verified output"})})
        stored (get-in (runtime/ledger) [:work-items "work-1"])
        receipt (first (:execution-receipts (runtime/ledger)))
        run-id (:work.item/agent-run stored)]
    (is (= :ok (:status result)))
    (is (= 1 (count @calls)))
    (is (= :leased (:work.item/status (first @calls))))
    (is (= :review (:work.item/status stored)))
    (is (= run-id (get-in stored [:work.item/dispatch-record
                                  :dispatch/agent-run])))
    (is (runtime/valid-receipt?
         stored {:agent.run/id run-id :agent.run/status :succeeded
                 :agent/result "verified output"}
         receipt))
    (is (= "sha256:content-v1" (:execution.receipt/content-hash receipt)))
    (is (= (:lease/id (:work.item/lease stored))
           (:execution.receipt/lease receipt)))))

(deftest production-dispatch-creates-a-durable-bounded-agent-run
  (runtime/put-role! role)
  (runtime/put-work-item! item)
  (let [configuration
        {:work-governance {:enabled? true :lease-ms 60000
                           :github-writeback-enabled? false}
         :agent-control {:enabled? true :max-turns 2 :max-tool-calls 2
                         :browser {:enabled? true}
                         :computer {:enabled? false}}
         :routing {:default-provider "ollama" :default-model "test-model"
                   :cloud-enabled? false}
         :privacy {:allow-cloud-without-review? false}
         :providers [{:id "ollama" :kind :ollama :local? true
                      :base-url "http://127.0.0.1:11434"
                      :reviewed? true :enabled? true}]}
        result (with-redefs [provider/agent-turn
                             (fn [_ request]
                               (is (= "test-model" (:model request)))
                               {:content "completed" :tool-calls []})]
                 (runtime/reconcile-once! configuration {:now-ms 1000}))
        run-id (get-in result [:dispatched 0 :agent-run])
        persisted (get-in @store/state [:agent-control :runs run-id])]
    (is (= :succeeded (:agent.run/status persisted)))
    (is (= :implementer (:agent.run/yakuwari persisted)))
    (is (= "work-1" (:agent.run/work-item persisted)))
    (is (= :review (get-in (runtime/ledger)
                           [:work-items "work-1" :work.item/status])))))

(deftest lease-refuses-a-stale-planner-view
  (runtime/put-work-item! item)
  (is (= :stale-content
         (:reason (runtime/lease! "work-1" "worker"
                                  "sha256:older" nil 1000 60000))))
  (is (= :ready (get-in (runtime/ledger)
                        [:work-items "work-1" :work.item/status]))))

(deftest receipt-tampering-is-detected
  (runtime/put-work-item! item)
  (let [{leased :work-item} (runtime/lease! "work-1" "worker"
                                            "sha256:content-v1" nil
                                            1000 60000)
        run {:agent.run/id "run-1" :agent.run/status :succeeded}
        value (runtime/receipt leased run
                               (:work.item/lease leased) 1001)]
    (is (runtime/valid-receipt? leased run value))
    (is (not (runtime/valid-receipt?
              leased (assoc value :execution.receipt/content-hash
                            "sha256:tampered"))))
    (is (not (runtime/valid-receipt?
              leased (assoc run :agent.run/status :failed) value)))))

(deftest prepared-dispatch-recovers-with-the-same-run-id-after-a-crash
  (runtime/put-role! role)
  (runtime/put-work-item! item)
  (let [configuration {:work-governance
                       {:enabled? true :lease-ms 60000
                        :github-writeback-enabled? false}}
        ids (atom [])]
    (is (thrown? Error
                 (runtime/reconcile-once!
                  configuration
                  {:now-ms 1000
                   :dispatch
                   (fn [_ leased]
                     (swap! ids conj
                            (get-in leased [:work.item/dispatch-record
                                            :dispatch/agent-run]))
                     (throw (Error. "simulated process death")))})))
    (let [result (runtime/reconcile-once!
                  configuration
                  {:now-ms 1001
                   :dispatch
                   (fn [_ leased]
                     (let [id (get-in leased [:work.item/dispatch-record
                                              :dispatch/agent-run])]
                       (swap! ids conj id)
                       {:agent.run/id id :agent.run/status :succeeded
                        :agent/result "recovered"}))})]
      (is (= 1 (count (:recovered result))))
      (is (= 1 (count (distinct @ids))))
      (is (= :review (get-in (runtime/ledger)
                             [:work-items "work-1" :work.item/status]))))))

(deftest done-requires-test-and-passkey-review-receipts
  (runtime/put-role! role)
  (runtime/put-work-item! item)
  (runtime/reconcile-once!
   {:work-governance {:enabled? true :github-writeback-enabled? false}}
   {:now-ms 1000
    :dispatch (fn [_ leased]
                {:agent.run/id
                 (get-in leased [:work.item/dispatch-record
                                 :dispatch/agent-run])
                 :agent.run/status :succeeded :agent/result "result"})})
  (let [stored (get-in (runtime/ledger) [:work-items "work-1"])
        base {:verification.receipt/work-item "work-1"
              :verification.receipt/agent-run (:work.item/agent-run stored)
              :verification.receipt/content-hash
              (:work.item/content-hash stored)
              :verification.receipt/verifier "verifier"}]
    (is (= :work-runtime/verification-required
           (:type (ex-data
                   (try (runtime/complete-work! "work-1" 1100)
                        (catch Exception error error))))))
    (runtime/record-verification!
     (merge base {:verification.receipt/id "test-1"
                  :verification.receipt/kind :test
                  :verification.receipt/evidence-hash "sha256:test"}))
    (runtime/record-verification!
     (merge base {:verification.receipt/id "review-1"
                  :verification.receipt/kind :review
                  :verification.receipt/evidence-hash "sha256:review"
                  :verification.receipt/user-verified? true}))
    (is (= :done (:work.item/status
                  (runtime/complete-work! "work-1" 1200))))))

(deftest github-writeback-refuses-nonterminal-runs-before-transport
  (runtime/put-role! role)
  (runtime/put-work-item!
   (assoc item :work.item/source
          {:kind :github-projects-v2
           :project-id "PVT" :item-id "PVTI" :field-id "FIELD"
           :field-name "Status"
           :write-capability :github.projects/status-write
           :basis {:project-id "PVT" :item-id "PVTI" :field-id "FIELD"
                   :option-id "todo" :updated-at "v1"}
           :target-option-ids {:held "held"}}))
  (let [transport-calls (atom 0)]
    (runtime/reconcile-once!
     {:work-governance
      {:enabled? true :github-writeback-enabled? true
       :github-write-capabilities #{:github.projects/status-write}}}
     {:now-ms 1000
      :transport (fn [_] (swap! transport-calls inc))
      :dispatch (fn [_ leased]
                  {:agent.run/id
                   (get-in leased [:work.item/dispatch-record
                                   :dispatch/agent-run])
                   :agent.run/status :held :agent/result "admitted"})})
    (is (zero? @transport-calls))
    (is (= :work-runtime/nonterminal-writeback
           (get-in (runtime/ledger)
                   [:work-items "work-1" :work.item/writeback :type])))))

(deftest dispatch-exceptions-become-durable-failed-agent-runs
  (runtime/put-role! role)
  (runtime/put-work-item! item)
  (let [result (runtime/reconcile-once!
                {:work-governance {:enabled? true
                                   :github-writeback-enabled? false}}
                {:now-ms 1000
                 :dispatch (fn [_ _]
                             (throw (ex-info "executor unavailable"
                                             {:type :executor/offline})))})
        run-id (get-in result [:dispatched 0 :agent-run])]
    (is (= :dispatch-failed (get-in result [:dispatched 0 :status])))
    (is (= :failed (get-in @store/state
                           [:agent-control :runs run-id :agent.run/status])))
    (is (= run-id (get-in (runtime/ledger)
                          [:work-items "work-1" :work.item/agent-run])))
    (is (= :failed (get-in (runtime/ledger)
                           [:work-items "work-1" :work.item/status])))))

(deftest terminal-evidence-and-explicit-capability-enable-verified-writeback
  (runtime/put-role! role)
  (runtime/put-work-item!
   (assoc item :work.item/source
          {:kind :github-projects-v2 :project-id "PVT" :item-id "PVTI"
           :field-id "FIELD" :field-name "Status"
           :write-capability :github.projects/status-write
           :basis {:project-id "PVT" :item-id "PVTI" :field-id "FIELD"
                   :option-id "todo" :updated-at "v1"}
           :target-option-ids {:review "review"}}))
  (let [calls (atom 0)
        transport
        (fn [_]
          (case (swap! calls inc)
            1 {:data {:node {:id "PVTI" :updatedAt "v1"
                             :project {:id "PVT"}
                             :fieldValueByName
                             {:optionId "todo" :field {:id "FIELD"}}}}}
            2 {:data {:updateProjectV2ItemFieldValue
                      {:projectV2Item {:id "PVTI" :updatedAt "v2"}}}}
            {:data {:node {:id "PVTI" :updatedAt "v2"
                           :project {:id "PVT"}
                           :fieldValueByName
                           {:optionId "review" :field {:id "FIELD"}}}}}))]
    (runtime/reconcile-once!
     {:work-governance
      {:enabled? true :github-writeback-enabled? true
       :receipt-signature-required? false
       :github-write-capabilities #{:github.projects/status-write}}}
     {:now-ms 1000 :transport transport
      :dispatch (fn [_ leased]
                  {:agent.run/id
                   (get-in leased [:work.item/dispatch-record
                                   :dispatch/agent-run])
                   :agent.run/status :succeeded :agent/result "result"})})
    (is (= 3 @calls))
    (is (= :written (get-in (runtime/ledger)
                            [:work-items "work-1"
                             :work.item/writeback :status])))
    (is (= "review" (get-in (runtime/ledger)
                            [:source-bases "work-1" :option-id])))))
