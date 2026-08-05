(ns cloud.itonami.app.work-governance-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.work-governance :as governance]))

(def human
  {:performer/id "person-alice" :performer/organization "org-1"
   :performer/kind :person})

(def robot
  {:performer/id "ao-tamaki" :performer/organization "org-1"
   :performer/kind :system :performer/persona {:name "Tamaki"}})

(def approver-assignment
  {:org.assignment/id "assignment-alice"
   :org.assignment/organization "org-1"
   :org.assignment/performer "person-alice"
   :org.assignment/position :engineering-manager
   :org.assignment/roles #{:merge-approver}})

(def robot-assignment
  {:org.assignment/id "assignment-tamaki"
   :org.assignment/organization "org-1"
   :org.assignment/performer "ao-tamaki"
   :org.assignment/position :implementation-worker
   :org.assignment/roles #{:merge-approver :implementer}})

(def policy
  {:approval.policy/id "merge-policy"
   :approval.policy/organization "org-1"
   :approval.policy/capability :project/merge
   :approval.policy/eligible-roles #{:merge-approver}
   :approval.policy/minimum 1})

(def item
  {:work.item/id "card-1" :work.item/organization "org-1"
   :work.item/project "project-1" :work.item/title "Apply reviewed patch"
   :work.item/capability :project/merge :work.item/yakuwari :implementer
   :work.item/content-hash "sha256:patch-1" :work.item/submitted-by "ao-tamaki"
   :work.item/status :ready :work.item/priority 1 :work.item/created-at 10})

(def role
  {:yakuwari/id :implementer :yakuwari/project "project-1"
   :yakuwari/objective "Move reviewed cards to done"
   :yakuwari/scale {:min 0 :desired 1 :max 2}
   :yakuwari/runners [{:runner :codex}]
   :yakuwari/policy {:project/merge :approval-required}})

(def approval
  {:approval.decision/work-item "card-1"
   :approval.decision/actor "person-alice"
   :approval.decision/content-hash "sha256:patch-1"
   :approval.decision/decision :approved
   :approval.decision/user-verified? true})

(deftest performer-kinds-do-not-launder-a-system-into-a-person
  (is (= #{:dodaf/performer :dodaf/person}
         (:performer/dodaf-types (governance/performer human))))
  (is (= #{:dodaf/performer :dodaf/system}
         (:performer/dodaf-types (governance/performer robot))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"cannot acquire person authority"
       (governance/performer
        (assoc robot :performer/dodaf-types #{:dodaf/person})))))

(deftest organization-graph-is-structural-not-an-approval-policy
  (let [result
        (governance/organization-graph
         {:org/id "org-1" :org/performers [human robot]
          :org/assignments [approver-assignment robot-assignment]
          :org/reporting-lines
          [{:reporting/manager "assignment-alice"
            :reporting/report "assignment-tamaki"}]})]
    (is (:ok? result))
    (is (not (contains? (:graph result) :approval/policies))))
  (is (= :self-reporting-line
         (-> (governance/organization-graph
              {:org/id "org-1" :org/performers [human]
               :org/assignments [approver-assignment]
               :org/reporting-lines
               [{:reporting/manager "assignment-alice"
                 :reporting/report "assignment-alice"}]})
             :problems first :problem))))

(deftest organization-studio-validates-nested-units-positions-roles-and-actors
  (let [graph {:org/id "org-1"
               :org/units
               [{:org.unit/id "company" :org.unit/organization "org-1"
                 :org.unit/name "Company" :org.unit/kind :organization}
                {:org.unit/id "platform" :org.unit/organization "org-1"
                 :org.unit/name "Platform" :org.unit/kind :department
                 :org.unit/parent "company"}]
               :org/positions
               [{:org.position/id "platform-manager"
                 :org.position/organization "org-1"
                 :org.position/unit "platform" :org.position/name "Manager"}]
               :org/roles
               [{:org.role/id :merge-approver :org.role/organization "org-1"
                 :org.role/name "Merge approver"
                 :org.role/capabilities #{:project/merge}}]
               :org/performers
               [(assoc human :performer/actor
                       {:actor/kind :user :actor/id "user-alice"})]
               :org/assignments
               [(assoc approver-assignment
                       :org.assignment/position "platform-manager")]
               :org/reporting-lines []}
        result (governance/organization-graph graph)]
    (is (:ok? result))
    (is (= :user (get-in result [:graph :org/performers 0
                                 :performer/actor :actor/kind]))))
  (is (= :work-governance/actor-performer-conflict
         (:type (ex-data
                 (try (governance/performer
                       (assoc robot :performer/actor
                              {:actor/kind :user :actor/id "user-alice"}))
                      (catch Exception error error)))))))

(deftest organization-studio-rejects-cycles-and-unknown-structure
  (let [result
        (governance/organization-graph
         {:org/id "org-1"
          :org/units
          [{:org.unit/id "a" :org.unit/organization "org-1"
            :org.unit/name "A" :org.unit/parent "b"}
           {:org.unit/id "b" :org.unit/organization "org-1"
            :org.unit/name "B" :org.unit/parent "a"}]
          :org/positions
          [{:org.position/id "missing-position"
            :org.position/organization "org-1"
            :org.position/unit "missing" :org.position/name "Missing"}]
          :org/performers [human]
          :org/assignments [approver-assignment]})
        problem-types (set (map :problem (:problems result)))]
    (is (not (:ok? result)))
    (is (contains? problem-types :cyclic-unit))
    (is (contains? problem-types :unknown-position-unit))
    (is (contains? problem-types :unknown-position))))

(deftest only-a-qualified-verified-person-can-approve
  (testing "the human decision is content-bound and accepted"
    (is (= :approved
           (:approval/status
            (governance/approval-state
             policy item [human robot]
             [(governance/assignment approver-assignment)
              (governance/assignment robot-assignment)]
             [approval])))))
  (testing "a system cannot approve even if assigned the approver role"
    (let [decision (assoc approval :approval.decision/actor "ao-tamaki")
          result (governance/approval-state
                  policy item [human robot]
                  [(governance/assignment approver-assignment)
                   (governance/assignment robot-assignment)]
                  [decision])]
      (is (= :pending (:approval/status result)))
      (is (= [decision] (:approval/ignored result)))))
  (testing "a stale approval for different bytes cannot travel with the card"
    (is (= :pending
           (:approval/status
            (governance/approval-state
             policy item [human]
             [(governance/assignment approver-assignment)]
             [(assoc approval :approval.decision/content-hash "sha256:old")]))))))

(deftest submitter-and-approver-are-separated-by-default
  (let [human-item (assoc item :work.item/submitted-by "person-alice")]
    (is (= :pending
           (:approval/status
            (governance/approval-state
             policy human-item [human]
             [(governance/assignment approver-assignment)] [approval]))))))

(deftest kanban-transitions-are-explicit-and-rejections-are-terminal
  (is (= :ready (:work.item/status
                 (governance/transition-work
                  (assoc item :work.item/status :backlog) :ready 20 {}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (governance/transition-work item :done 20 {})))
  (is (thrown? clojure.lang.ExceptionInfo
               (governance/transition-work
                (assoc item :work.item/status :rejected) :ready 20 {}))))

(deftest missing-policy-fails-closed-and-human-approval-releases-the-card
  (is (= :block (:action (governance/route-item
                          role item nil [human] [approver-assignment] []))))
  (is (= :hold (:action (governance/route-item
                         role item policy [human] [approver-assignment] []))))
  (is (= :dispatch (:action
                    (governance/route-item
                     role item policy [human] [approver-assignment] [approval])))))

(deftest reconciliation-joins-board-demand-to-yakuwari-capacity
  (let [second-item (assoc item :work.item/id "card-2"
                           :work.item/content-hash "sha256:patch-2"
                           :work.item/priority 2)
        second-approval (assoc approval
                               :approval.decision/work-item "card-2"
                               :approval.decision/content-hash "sha256:patch-2")
        plan (governance/reconcile-plan
              {:role role :items [second-item item] :runs [] :now-ms 100
               :approval-policy policy :performers [human robot]
               :assignments [(governance/assignment approver-assignment)]
               :decisions [approval second-approval]})]
    (is (= 1 (get-in plan [:capacity :spawn])))
    (is (= [{:action :dispatch :work-item "card-1"}
            {:action :wait-capacity :work-item "card-2"}]
           (mapv #(select-keys % [:action :work-item]) (:actions plan))))))
