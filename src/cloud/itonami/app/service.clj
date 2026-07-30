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

(defn chosen-model
  "The model a request will run on, answerable before it runs.

  A streamed completion has to name its model in every chunk, including the
  first one — which is written before the provider has been asked anything. So
  the defaulting rule lives here rather than inside `prepare-chat!`, where the
  transport could only reach it by repeating it."
  [config request]
  (or (:model request) (get-in config [:routing :default-model])))

(defn- prepare-chat!
  [config {:keys [messages provider-id session-id agent-id temperature
                  response-id mode guardrail effort cwd]
           :as request}]
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
        chosen-model (chosen-model config request)
        runner-session-id (store/runner-session session-id (:id selected))]
    {:selected selected :session-id session-id :max-messages max-messages
     :chosen-model chosen-model :provider-messages provider-messages
     :temperature temperature :response-id response-id
     :mode mode :guardrail guardrail :effort effort :cwd cwd
     :runner-session-id (:id runner-session-id)}))

(defn- finish-chat!
  [{:keys [selected session-id max-messages chosen-model response-id]} result]
  (when-let [runner-session-id (:runner-session-id result)]
    (store/record-runner-session!
     session-id (:id selected) runner-session-id))
  (let [assistant (store/append-message!
                   session-id {:role "assistant" :content (:content result)}
                   max-messages)
        response {:id (or response-id (store/new-id "chatcmpl"))
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
  (let [{:keys [selected chosen-model provider-messages temperature
                session-id runner-session-id mode guardrail effort cwd]
         :as prepared}
        (prepare-chat! config request)
        result (provider/chat selected {:model chosen-model
                                        :messages provider-messages
                                        :temperature temperature
                                        :session-id session-id
                                        :runner-session-id runner-session-id
                                        :mode mode :guardrail guardrail
                                        :effort effort :cwd cwd})]
    (finish-chat! prepared result)))

(defn run-chat-stream!
  [config request on-delta]
  (let [{:keys [selected chosen-model provider-messages temperature
                session-id runner-session-id mode guardrail effort cwd]
         :as prepared}
        (prepare-chat! config request)
        result (provider/chat-stream!
                selected
                {:model chosen-model :messages provider-messages
                 :temperature temperature
                 :session-id session-id
                 :runner-session-id runner-session-id
                 :mode mode :guardrail guardrail :effort effort :cwd cwd}
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

;; The streamed form of the same completion. `chat.completion.chunk` repeats
;; `id`, `created` and `model` in every chunk, so those are fixed once in an
;; envelope and each chunk only carries what changed — a client that saw a
;; different id per delta could not tell one completion from several.

(defn stream-envelope [response-id model]
  {:id response-id
   :object "chat.completion.chunk"
   :created (quot (System/currentTimeMillis) 1000)
   :model model})

(defn openai-chunk
  "One chunk of a streamed completion: `delta` for choice 0, plus the
  `finish_reason` that ends it (`nil` on every chunk but the last)."
  [envelope delta finish-reason]
  (assoc envelope
         :choices [{:index 0 :delta delta :finish_reason finish-reason}]))

(defn openai-usage-chunk
  "The usage-only chunk that closes a stream, sent only when the caller asked
  for it with `stream_options.include_usage`. Its `choices` is empty by
  design: usage belongs to the completion, not to a choice, and a client that
  folds deltas by index must not find a fabricated one here."
  [envelope usage]
  (assoc envelope :choices [] :usage usage))
