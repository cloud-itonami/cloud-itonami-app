(ns cloud.itonami.app.work-organism-dispatch
  "Typed WorkItem intents for externally supervised OrganismWorkers."
  (:require [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.organism-gateway :as gateway]))

(def schema "cloud.itonami.app.work-organism-dispatch.v1")

(defn dispatch [configuration item]
  (let [record (:work.item/dispatch-record item)
        lease (:work.item/lease item)
        run-id (:dispatch/agent-run record)
        worker-id (:work.item/worker item)
        now-ms (System/currentTimeMillis)
        intent-id (str "intent-" (:dispatch/id record))
        intent {:intent/id intent-id
                :intent/organization (:work.item/organization item)
                :intent/worker worker-id
                :intent/capability (or (:work.item/organism-capability item)
                                       (:work.item/capability item))
                :intent/issued-by (:lease/owner lease)
                :intent/expires-at (:lease/expires-at lease)
                :intent/payload
                {:type :work-item/execute
                 :work-item (:work.item/id item)
                 :agent-run run-id
                 :fencing-token (:lease/fencing-token lease)
                 :content-hash (:work.item/content-hash item)
                 :summary (:work.item/title item)}}
        admission (gateway/submit-intent! worker-id intent now-ms)]
    (agent-control/record-external-admission!
     run-id {:goal (:work.item/title item)
             :yakuwari (:work.item/yakuwari item)
             :work-item (:work.item/id item)
             :actor worker-id}
     admission)))

(defn observe!
  "Fold a terminal external supervisor receipt into the durable AgentRun."
  [item]
  (let [worker-id (:work.item/worker item)
        run-id (or (:work.item/agent-run item)
                   (get-in item [:work.item/dispatch-record
                                 :dispatch/agent-run]))
        intent-id (str "intent-" (get-in item [:work.item/dispatch-record
                                               :dispatch/id]))
        receipt (some #(when (= intent-id (:receipt/intent %)) %)
                      (:items (gateway/receipts worker-id)))
        run-status (some-> receipt :receipt/evidence :run-status keyword)
        terminal (when (#{:succeeded :failed :rejected :cancelled} run-status)
                   run-status)]
    (when terminal
      (agent-control/record-external-outcome!
       run-id terminal
       {:receipt receipt :schema schema}))))
