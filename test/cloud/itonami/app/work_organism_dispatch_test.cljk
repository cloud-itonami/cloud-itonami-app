(ns cloud.itonami.app.work-organism-dispatch-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.organism-gateway :as gateway]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.work-organism-dispatch :as adapter]))

(defn isolated-state [test-fn]
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [store/transact!
                    (fn [f & args] (apply swap! store/state f args))]
        (test-fn))
      (finally (reset! store/state previous)))))

(use-fixtures :each isolated-state)

(def item
  {:work.item/id "work-1" :work.item/organization "org-1"
   :work.item/title "External task" :work.item/capability :intent/submit
   :work.item/yakuwari :organism-role :work.item/worker "ao-1"
   :work.item/content-hash "sha256:content"
   :work.item/lease {:lease/id "lease-12345678"
                     :lease/owner "reconciler"
                     :lease/fencing-token 7 :lease/expires-at 9999999999999}
   :work.item/dispatch-record {:dispatch/id "lease-12345678"
                               :dispatch/agent-run "run-12345678"}})

(deftest admission-is-held-until-the-external-supervisor-emits-an-outcome
  (with-redefs [gateway/submit-intent!
                (fn [_ intent _]
                  (is (= 7 (get-in intent [:intent/payload :fencing-token])))
                  {:receipt/intent (:intent/id intent)
                   :receipt/status :admitted
                   :receipt/effect-status :not-executed})]
    (is (= :held (:agent.run/status (adapter/dispatch {} item)))))
  (with-redefs [gateway/receipts
                (fn [_]
                  {:items [{:receipt/intent "intent-lease-12345678"
                            :receipt/evidence {:run-status "succeeded"}}]})]
    (is (= :succeeded (:agent.run/status (adapter/observe! item))))
    (is (= :succeeded
           (:agent.run/status (agent-control/run-by-id "run-12345678"))))))
