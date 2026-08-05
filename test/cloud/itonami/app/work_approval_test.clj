(ns cloud.itonami.app.work-approval-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.work-approval :as approval]
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

(def session {:user-id "human-1" :organization-id "org-1"})

(defn seed! [status]
  (runtime/put-performer!
   {:performer/id "person-1" :performer/user-id "human-1"
    :performer/kind :person :performer/organization "org-1"})
  (runtime/put-assignment!
   {:org.assignment/id "assignment-1"
    :org.assignment/organization "org-1"
    :org.assignment/performer "person-1"
    :org.assignment/position :engineering
    :org.assignment/roles #{:approver :reviewer}})
  (runtime/put-approval-policy!
   {:approval.policy/id :repo-change
    :approval.policy/organization "org-1"
    :approval.policy/capability :repository/change
    :approval.policy/eligible-roles #{:approver}})
  (runtime/put-work-item!
   {:work.item/id "work-1" :work.item/organization "org-1"
    :work.item/project "project-1" :work.item/title "Change"
    :work.item/capability :repository/change
    :work.item/yakuwari :implementer
    :work.item/content-hash "sha256:content" :work.item/status status
    :work.item/agent-run (when (= :review status) "run-1")
    :work.item/submitted-by "another-person"
    :work.item/verification-policy
    {:eligible-reviewer-roles #{:reviewer}}}))

(deftest approval-is-recorded-from-server-bound-passkey-context
  (seed! :ready)
  (let [context (atom nil)]
    (with-redefs [passkey/start-authorization!
                  (fn [_ value _ _]
                    (reset! context value) {:transaction-id "tx"})]
      (is (= "tx" (:transaction-id
                    (approval/start! session "work-1" :approved
                                     "localhost" "http://localhost")))))
    (with-redefs [passkey/finish-authorization!
                  (fn [_ _] {:user-id "human-1" :credential-id "credential"
                             :verified? true
                             :authorization-context @context})]
      (let [decision (approval/finish! session "work-1" "tx" {})]
        (is (:approval.decision/user-verified? decision))
        (is (= "person-1" (:approval.decision/actor decision)))
        (is (= :webauthn
               (get-in decision [:approval.decision/authentication
                                 :method])))))))

(deftest independent-review-is-also-passkey-bound
  (seed! :review)
  (let [context (approval/review-context session "work-1")]
    (with-redefs [passkey/finish-authorization!
                  (fn [_ _] {:user-id "human-1" :credential-id "credential"
                             :verified? true
                             :authorization-context context})]
      (let [receipt (approval/finish-review! session "work-1" "tx" {})]
        (is (= :review (:verification.receipt/kind receipt)))
        (is (:verification.receipt/user-verified? receipt))))))
