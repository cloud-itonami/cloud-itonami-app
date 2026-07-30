(ns cloud.itonami.app.service
  (:require [clojure.string :as str]
            [cloud.itonami.app.agent-eval :as agent-eval]
            [cloud.itonami.app.agent-loop :as agent-loop]
            [cloud.itonami.app.agent-workspace :as agent-workspace]
            [cloud.itonami.app.approval-broker :as approval-broker]
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
                  :usage (:usage result)
                  :agent-run (:agent-run result)}]
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

(defn- continuation-messages [provider-messages cycle max-cycles]
  [(first provider-messages)
   {:role "user"
    :content
    (str "Continue the same objective in agent loop cycle " cycle " of "
         max-cycles ". Inspect the current worktree and git diff, verify the "
         "actual result, fix remaining gaps, and leave concrete artifacts. "
         "Do not merely repeat the previous report. Stop only when the "
         "objective is verifiably complete or human authority is required.")}])

(defn- combine-usage [usages]
  (reduce
   (fn [total usage]
     (merge-with (fn [left right]
                   (if (and (number? left) (number? right))
                     (+ left right)
                     right))
                 total (or usage {})))
   {} usages))

(defn run-chat-stream!
  ([config request on-delta]
   (run-chat-stream! config request on-delta nil))
  ([config request on-delta on-event]
   (let [{:keys [selected chosen-model provider-messages temperature
                 session-id runner-session-id mode guardrail effort cwd]
          :as prepared}
         (prepare-chat! config request)
         provider-events (atom [])
         started-at (System/currentTimeMillis)
         workspace (atom nil)
         runner-session (atom runner-session-id)
         cycle-results (atom [])
         loop-context
         (agent-loop/start!
          {:session-id session-id
           :objective (get-in request [:messages 0 :content])
           :provider (:id selected) :model chosen-model :effort effort
           :mode mode :guardrail guardrail :emit on-event})
         provider-event!
         (fn [event]
           (swap! provider-events conj event)
           (agent-loop/provider-event! loop-context event))]
     (try
       (when (and (= mode :agent)
                  (get-in config [:agent-runtime :isolate-writes?]))
         (let [prepared
               (agent-workspace/prepare!
                (or cwd (System/getProperty "user.dir"))
                session-id (:run-id loop-context)
                {:max-worktrees
                 (get-in config [:agent-runtime :max-worktrees])})]
           (reset! workspace prepared)
           (agent-loop/provider-event!
            loop-context {:type :workspace/prepared
                          :workspace (:path prepared)
                          :isolation (:isolation prepared)
                          :branch (:branch prepared)
                          :changed-files (:changed-files prepared)
                          :commits (:commits prepared)
                          :reused? (:reused? prepared)
                          :status :active})))
       (when (= mode :agent)
         (agent-loop/phase! loop-context :execute))
       (let [agent? (= mode :agent)
             min-cycles (if agent?
                          (max 1 (long (or (get-in config
                                                  [:agent-runtime :min-cycles])
                                           1)))
                          1)
             max-cycles (if agent?
                          (max min-cycles
                               (long (or (get-in config
                                                 [:agent-runtime :max-cycles])
                                         min-cycles)))
                          1)
             final-verification
             (loop [cycle 1]
               (agent-loop/provider-event!
                loop-context {:type :cycle/started :cycle cycle
                              :max-cycles max-cycles :status :running})
               (agent-loop/provider-event!
                loop-context {:type :model/started :provider (:id selected)
                              :model chosen-model :effort effort :cycle cycle})
               (when (> cycle 1)
                 (when on-delta (on-delta "\n\n---\n\n")))
               (let [cycle-messages
                     (if (= cycle 1)
                       provider-messages
                       (continuation-messages provider-messages cycle
                                              max-cycles))
                     provider-request
                     {:model chosen-model :messages cycle-messages
                      :temperature temperature
                      :session-id session-id
                      :runner-session-id @runner-session
                      :mode mode :guardrail guardrail :effort effort
                      :cwd (or (:path @workspace) cwd)
                      :transport (get-in config
                                         [:agent-runtime :codex-transport])
                      :approval-handler
                      (fn [{:keys [kind summary reason cwd params]}]
                        (approval-broker/request!
                         {:run-id (:run-id loop-context)
                          :session-id session-id :kind kind
                          :summary summary :reason reason :cwd cwd
                          :private-request params
                          :timeout-ms
                          (get-in config
                                  [:agent-runtime :approval-timeout-ms])
                          :on-event provider-event!}))}
                     result
                     (if on-event
                       (provider/chat-stream!
                        selected provider-request on-delta provider-event!)
                       (provider/chat-stream!
                        selected provider-request on-delta))
                     _ (swap! cycle-results conj result)
                     _ (when-let [id (:runner-session-id result)]
                         (reset! runner-session id))
                     _ (agent-loop/provider-event!
                        loop-context
                        {:type :model/completed :provider (:id selected)
                         :model chosen-model :usage (:usage result)
                         :cycle cycle})
                     verification
                     (agent-loop/verify! loop-context result @provider-events)
                     continue?
                     (and agent? (< cycle max-cycles)
                          (or (< cycle min-cycles)
                              (not (:passed? verification))))]
                 (agent-loop/provider-event!
                  loop-context {:type :cycle/completed :cycle cycle
                                :max-cycles max-cycles
                                :continue? (boolean continue?)
                                :status (:status verification)})
                 (if continue?
                   (recur (inc cycle))
                   verification)))
             combined-result
             (let [contents (map :content @cycle-results)]
               (assoc (or (last @cycle-results) {})
                      :content (str/join "\n\n---\n\n" contents)
                      :usage (combine-usage (map :usage @cycle-results))
                      :runner-session-id @runner-session
                      :cycles (count @cycle-results)))
             _ (agent-loop/phase! loop-context :review)
             evaluation
             (agent-eval/record!
              (:run-id loop-context)
              (agent-eval/evaluate
               {:verification final-verification
                :provider-events @provider-events
                :result combined-result
                :duration-ms (- (System/currentTimeMillis) started-at)}))
             _ (agent-loop/provider-event!
                loop-context
                (assoc evaluation :type :evaluation/completed))]
         (agent-loop/phase! loop-context :reflect)
         (when @workspace
           (agent-workspace/release! (:run-id loop-context) :ready-for-review)
           (agent-loop/provider-event!
            loop-context {:type :workspace/released
                          :workspace (:path @workspace)
                          :isolation (:isolation @workspace)
                          :branch (:branch @workspace)
                          :status :idle}))
         (agent-loop/complete! loop-context final-verification combined-result)
         (finish-chat!
          prepared
          (assoc combined-result :agent-run
                 {:id (:run-id loop-context)
                  :status (:status final-verification)
                  :cycles (:cycles combined-result)
                  :verification final-verification
                  :evaluation evaluation
                  :workspace
                  (some-> (agent-workspace/session-workspace session-id)
                          (select-keys [:id :path :repo-root :isolation
                                        :branch :changed-files :commits]))})))
       (catch Exception error
         (when @workspace
           (agent-workspace/release! (:run-id loop-context) :failed))
         (agent-loop/fail! loop-context error)
         (throw error))))))

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
