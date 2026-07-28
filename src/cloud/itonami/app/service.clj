(ns cloud.itonami.app.service
  (:require [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.store :as store]))

(defn find-agent [state agent-id]
  (or (some #(when (= agent-id (:id %)) %) (:agents state))
      (first (:agents state))))

(defn available-models [config]
  (vec
   (mapcat
    (fn [candidate]
      (when (policy/provider-allowed? config candidate)
        (try
          (provider/list-models candidate)
          (catch Exception _ []))))
    (:providers config))))

(defn- prepare-chat!
  [config {:keys [messages model provider-id session-id agent-id temperature]}]
  (let [selected (policy/select-provider config provider-id)
        _ (when-not selected
            (throw (ex-info "provider denied or unavailable"
                            {:provider-id provider-id :type :provider/denied})))
        session-id (or session-id "default")
        max-messages (get-in config [:memory :max-session-messages] 40)
        context-limit (get-in config [:memory :max-context-messages] 20)
        current-agent (find-agent (store/snapshot) (or agent-id "local"))
        incoming (vec messages)
        _ (doseq [message incoming]
            (store/append-message! session-id message max-messages))
        context (vec (take-last context-limit (store/session-messages session-id)))
        provider-messages
        (into [{:role "system" :content (:system-prompt current-agent)}]
              (map #(select-keys % [:role :content]) context))
        chosen-model (or model (get-in config [:routing :default-model]))]
    {:selected selected :session-id session-id :max-messages max-messages
     :chosen-model chosen-model :provider-messages provider-messages
     :temperature temperature}))

(defn- finish-chat!
  [{:keys [selected session-id max-messages chosen-model]} result]
  (let [assistant (store/append-message!
                   session-id {:role "assistant" :content (:content result)}
                   max-messages)
        response {:id (store/new-id "chatcmpl")
                  :created (quot (System/currentTimeMillis) 1000)
                  :provider (:id selected)
                  :model chosen-model
                  :session-id session-id
                  :message assistant
                  :usage (:usage result)}]
    (store/record-response! response)
    response))

(defn run-chat!
  [config request]
  (let [{:keys [selected chosen-model provider-messages temperature] :as prepared}
        (prepare-chat! config request)
        result (provider/chat selected {:model chosen-model
                                        :messages provider-messages
                                        :temperature temperature})]
    (finish-chat! prepared result)))

(defn run-chat-stream!
  [config request on-delta]
  (let [{:keys [selected chosen-model provider-messages temperature] :as prepared}
        (prepare-chat! config request)
        result (provider/chat-stream!
                selected
                {:model chosen-model :messages provider-messages
                 :temperature temperature}
                on-delta)]
    (finish-chat! prepared result)))

(defn openai-response [response]
  {:id (:id response)
   :object "chat.completion"
   :created (:created response)
   :model (:model response)
   :choices [{:index 0
              :message (select-keys (:message response) [:role :content])
              :finish_reason "stop"}]
   :usage (:usage response)})
