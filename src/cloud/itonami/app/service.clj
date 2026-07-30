(ns cloud.itonami.app.service
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.memory :as memory]
            [cloud.itonami.app.policy :as policy]
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

(defn- tool-input [value]
  (cond
    (map? value) value
    (string? value)
    (try (json/read-str value :key-fn keyword)
         (catch Exception _ {}))
    :else {}))

(defn- normalize-message [message]
  (cond-> (-> message
              (dissoc :tool_calls :tool_call_id)
              (cond-> (:tool_call_id message)
                (assoc :tool-call-id (:tool_call_id message))))
    (seq (:tool_calls message))
    (assoc
     :tool-calls
     (mapv
      (fn [call]
        {:id (:id call)
         :name (get-in call [:function :name])
         :input (tool-input (get-in call [:function :arguments]))})
      (:tool_calls message)))))

(defn- context-message [message]
  (select-keys message [:role :content :tool-calls :tool-call-id]))

(defn- prepare-chat!
  [config {:keys [messages provider-id session-id agent-id temperature
                  response-id tools tool-choice]
           :as request}]
  (let [selected (policy/select-provider config provider-id)
        _ (when-not selected
            (throw (ex-info "provider denied or unavailable"
                            {:provider-id provider-id :type :provider/denied})))
        session-id (or session-id "default")
        max-messages (get-in config [:memory :max-session-messages] 40)
        max-memory-messages
        (get-in config [:memory :max-memory-messages] 500)
        context-limit (get-in config [:memory :max-context-messages] 20)
        relevant-limit (get-in config [:memory :relevant-messages] 4)
        capsule-limit (get-in config [:memory :relevant-capsules] 2)
        current-agent (find-agent (store/snapshot) (or agent-id "local"))
        incoming (mapv normalize-message messages)
        _ (doseq [message incoming]
            (store/append-message!
             session-id message max-messages max-memory-messages))
        context (vec (take-last context-limit (store/session-messages session-id)))
        recent-ids (into #{} (map :id) context)
        query (->> incoming
                   (filter #(= "user" (:role %)))
                   (map :content)
                   (remove nil?)
                   (str/join "\n"))
        recalled (memory/relevant
                  (store/session-memory session-id)
                  query recent-ids relevant-limit)
        recalled-capsules
        (memory/relevant-capsules session-id query capsule-limit)
        provider-messages
        (into
         (cond-> [{:role "system" :content (:system-prompt current-agent)}]
           (seq recalled-capsules)
           (conj (memory/capsule-context-message recalled-capsules))
           (seq recalled)
           (conj (memory/context-message recalled)))
         (map context-message context))
        chosen-model (chosen-model config request)]
    {:config config :selected selected :session-id session-id
     :max-messages max-messages :max-memory-messages max-memory-messages
     :chosen-model chosen-model :provider-messages provider-messages
     :temperature temperature :response-id response-id
     :tools tools :tool-choice tool-choice}))

(defn- finish-chat!
  [{:keys [config selected session-id max-messages max-memory-messages
           chosen-model response-id]}
   result]
  (let [assistant (store/append-message!
                   session-id
                   (cond-> {:role "assistant" :content (:content result)}
                     (seq (:tool-calls result))
                     (assoc :tool-calls (:tool-calls result)))
                   max-messages max-memory-messages)
        response {:id (or response-id (store/new-id "chatcmpl"))
                  :created (quot (System/currentTimeMillis) 1000)
                  :provider (:id selected)
                  :model chosen-model
                  :session-id session-id
                  :message assistant
                  :usage (:usage result)}]
    (store/record-response! response)
    (memory/maybe-distill! config session-id)
    response))

(defn run-chat!
  [config request]
  (let [{:keys [selected chosen-model provider-messages temperature
                tools tool-choice] :as prepared}
        (prepare-chat! config request)
        result (provider/chat selected {:model chosen-model
                                        :messages provider-messages
                                        :temperature temperature
                                        :tools tools
                                        :tool-choice tool-choice})]
    (finish-chat! prepared result)))

(defn run-chat-stream!
  [config request on-delta]
  (let [{:keys [selected chosen-model provider-messages temperature
                tools tool-choice] :as prepared}
        (prepare-chat! config request)
        result (provider/chat-stream!
                selected
                {:model chosen-model :messages provider-messages
                 :temperature temperature :tools tools
                 :tool-choice tool-choice}
                on-delta)]
    (finish-chat! prepared result)))

(defn openai-response [response]
  {:id (:id response)
   :object "chat.completion"
   :created (:created response)
   :model (:model response)
   :choices [{:index 0
              :message
              (cond->
               (select-keys (:message response) [:role :content])
                (seq (get-in response [:message :tool-calls]))
                (assoc
                 :tool_calls
                 (mapv
                  (fn [{:keys [id name input]}]
                    {:id id :type "function"
                     :function {:name name
                                :arguments (json/write-str (or input {}))}})
                  (get-in response [:message :tool-calls]))))
              :finish_reason
              (if (seq (get-in response [:message :tool-calls]))
                "tool_calls" "stop")}]
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
