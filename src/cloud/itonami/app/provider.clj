(ns cloud.itonami.app.provider
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 4))
      .build))

(defn- request-json
  ([method url body] (request-json method url body nil))
  ([method url body api-key]
   (request-json method url body api-key nil))
  ([method url body api-key headers]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 120))
                     (.header "Accept" "application/json")
                     (.header "Content-Type" "application/json"))
         _ (when api-key (.header builder "Authorization" (str "Bearer " api-key)))
         _ (doseq [[header value] headers :when (some? value)]
             (.header builder (name header) (str value)))
         request (case method
                   :get (.GET builder)
                   :post (.POST builder
                                (HttpRequest$BodyPublishers/ofString
                                 (json/write-str body))))
         response (.send client (.build request)
                         (HttpResponse$BodyHandlers/ofString))
         status (.statusCode response)
         parsed (try (json/read-str (.body response) :key-fn keyword)
                     (catch Exception _ {:raw (.body response)}))]
     (if (<= 200 status 299)
       parsed
       (throw (ex-info "model provider request failed"
                       {:status status :url url :response parsed}))))))

(defn- openai-shaped? [provider]
  (contains? #{:openai-compatible :xai} (:kind provider)))

(defn- openai-url [provider path]
  (str (str/replace (:base-url provider) #"/$" "") path))

(defn- xai-headers [provider request]
  (when (and (= :xai (:kind provider)) (:conversation-id request))
    {"x-grok-conv-id" (:conversation-id request)}))

(defn list-models [provider]
  (cond
    (openai-shaped? provider)
    (mapv (fn [model]
            {:id (:id model) :object "model" :owned_by (:id provider)
             :provider (:id provider)})
          (:data (request-json :get (openai-url provider "/models")
                               nil (config/env-secret provider))))

    (= :ollama (:kind provider))
    (mapv (fn [model]
            {:id (:name model) :object "model" :owned_by (:id provider)
             :provider (:id provider)})
          (:models (request-json :get (str (:base-url provider) "/api/tags") nil)))

    :else []))

(defn chat
  [provider {:keys [model messages temperature] :as request}]
  (cond
    (= :ollama (:kind provider))
    (let [result (request-json
                  :post (str (:base-url provider) "/api/chat")
                  {:model model :messages messages :stream false
                   :options {:temperature (or temperature 0.7)}})]
      {:content (get-in result [:message :content])
       :usage {:prompt_tokens (get result :prompt_eval_count 0)
               :completion_tokens (get result :eval_count 0)
               :total_tokens (+ (get result :prompt_eval_count 0)
                                (get result :eval_count 0))}})

    (openai-shaped? provider)
    (let [result (request-json
                  :post (openai-url provider "/chat/completions")
                  (cond-> {:model model :messages messages :stream false
                           :temperature (or temperature 0.7)}
                    (= :xai (:kind provider))
                    (assoc :max_tokens (or (:max-output-tokens provider) 8192)
                           :reasoning_effort (or (:reasoning-effort request)
                                                 (:reasoning-effort provider)
                                                 "medium")))
                  (config/env-secret provider)
                  (xai-headers provider request))]
      {:content (get-in result [:choices 0 :message :content])
       :usage (:usage result)})

    :else (throw (ex-info "unsupported provider kind" {:provider provider}))))

(defn- tool-definition [{:keys [name description parameters]}]
  {:type "function"
   :function {:name name :description description :parameters parameters}})

(defn- provider-message [message]
  (let [calls (:tool-calls message)]
    (cond-> (dissoc message :tool-calls :tool-call-id)
      (seq calls)
      (assoc :tool_calls
             (mapv (fn [{:keys [id name input]}]
                     {:id id :type "function"
                      :function {:name name
                                 :arguments (json/write-str input)}})
                   calls))

      (:tool-call-id message)
      (assoc :tool_call_id (:tool-call-id message)))))

(defn- parse-arguments [value]
  (cond
    (map? value) value
    (str/blank? (str value)) {}
    :else (try
            (json/read-str value :key-fn keyword)
            (catch Exception error
              (throw (ex-info "model returned invalid tool arguments"
                              {:type :provider/invalid-tool-arguments}
                              error))))))

(defn- normalize-tool-calls [calls]
  (mapv (fn [index call]
          {:id (or (:id call) (str "tool-call-" index))
           :name (get-in call [:function :name])
           :input (parse-arguments (get-in call [:function :arguments]))})
        (range) (or calls [])))

(def ^:private default-agent-max-tokens
  ;; api.murakumo.cloud routes murakumo-main to a reasoning model. When this is
  ;; omitted the public gateway supplies 512, and the model can spend the whole
  ;; allowance on reasoning: HTTP 200, finish_reason=length, content="". A Bot
  ;; then appears to accept the person's message without answering. 2048 is the
  ;; gateway's documented public ceiling and leaves room for the visible reply.
  2048)

(defonce ^:private active-agent-streams (atom {}))

(defn cancel-agent-stream!
  "Close the provider body owned by `thread`, if headers have already arrived."
  [thread]
  (when-let [stream (get @active-agent-streams thread)]
    (try (.close ^java.io.Closeable stream) (catch Exception _ nil)))
  true)

(defn- with-active-agent-reader [response consume!]
  (let [thread (Thread/currentThread)
        stream (.body response)]
    (swap! active-agent-streams assoc thread stream)
    (try
      (with-open [input stream
                  reader (BufferedReader. (InputStreamReader. input))]
        (consume! reader))
      (finally (swap! active-agent-streams dissoc thread)))))

(defn- agent-request-body
  [provider {:keys [model messages tools temperature reasoning-effort]}]
  (cond-> {:model model
           :messages (mapv provider-message messages)
           :tools (mapv tool-definition tools)
           :stream false
           :temperature (or temperature 0.2)
           :max_tokens (or (:max-output-tokens provider)
                           default-agent-max-tokens)}
    (= :xai (:kind provider))
    ;; Cloud Itonami's authority model admits, runs and audits one effect at a
    ;; time. Grok defaults to parallel calls, so leaving this implicit would
    ;; either discard calls or create a batch approval authority we do not have.
    (assoc :parallel_tool_calls false
           :reasoning_effort (or reasoning-effort
                                 (:reasoning-effort provider)
                                 "medium"))))

(defn- agent-result
  ([message finish-reason] (agent-result message finish-reason nil))
  ([message finish-reason usage]
  (let [result (cond-> {:content (:content message)
                        :tool-calls (normalize-tool-calls (:tool_calls message))}
                 usage (assoc :usage usage))]
    (when (and (str/blank? (:content result))
               (empty? (:tool-calls result)))
      (throw (ex-info
              "モデルが回答本文を返しませんでした。もう一度送ってください。"
              {:type :provider/empty-response
               :finish-reason finish-reason})))
    result)))

(defn agent-turn
  "One non-streaming tool-capable model turn, normalized for Agent Control."
  [provider request]
  (let [body (agent-request-body provider request)]
    (cond
      (= :ollama (:kind provider))
      (let [result (request-json :post (str (:base-url provider) "/api/chat")
                                 (-> body
                                     (dissoc :temperature :max_tokens
                                             :parallel_tool_calls :reasoning_effort)
                                     (assoc :options {:temperature
                                                      (or (:temperature request) 0.2)
                                                      :num_predict (or (:max-output-tokens provider)
                                                                       default-agent-max-tokens)})))
            message (:message result)]
        (agent-result message (:done_reason result)
                      {:prompt_tokens (get result :prompt_eval_count 0)
                       :completion_tokens (get result :eval_count 0)
                       :total_tokens (+ (get result :prompt_eval_count 0)
                                        (get result :eval_count 0))}))

      (openai-shaped? provider)
      (let [result (request-json
                    :post
                    (openai-url provider "/chat/completions")
                    body (config/env-secret provider)
                    (xai-headers provider request))
            message (get-in result [:choices 0 :message])]
        (agent-result message (get-in result [:choices 0 :finish_reason])
                      (:usage result)))

      :else (throw (ex-info "unsupported provider kind" {:provider provider})))))

(defn- streaming-response
  ([url body api-key] (streaming-response url body api-key nil))
  ([url body api-key headers]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 120))
                     (.header "Accept" "*/*")
                     (.header "Content-Type" "application/json"))
         _ (when api-key
             (.header builder "Authorization" (str "Bearer " api-key)))
         _ (doseq [[header value] headers :when (some? value)]
             (.header builder (name header) (str value)))
         request (-> builder
                     (.POST (HttpRequest$BodyPublishers/ofString
                             (json/write-str body)))
                     .build)
         response (.send client request (HttpResponse$BodyHandlers/ofInputStream))]
     (when-not (<= 200 (.statusCode response) 299)
       (throw (ex-info "model provider streaming request failed"
                       {:status (.statusCode response) :url url})))
     response)))

(defn- emit! [on-delta content]
  (when (and (string? content) (seq content))
    (on-delta content)
    content))

(defn chat-stream!
  "Stream provider deltas to `on-delta` and return the complete result."
  [provider {:keys [model messages temperature] :as request} on-delta]
  (let [content (StringBuilder.)
        usage (volatile! nil)]
    (cond
      (= :ollama (:kind provider))
      (let [response
            (streaming-response
             (str (:base-url provider) "/api/chat")
             {:model model :messages messages :stream true
              :options {:temperature (or temperature 0.7)}}
             nil)]
        (with-open [reader (BufferedReader.
                            (InputStreamReader. (.body response)))]
          (doseq [line (line-seq reader)
                  :when (not (str/blank? line))]
            (let [chunk (json/read-str line :key-fn keyword)
                  delta (get-in chunk [:message :content])]
              (when-let [emitted (emit! on-delta delta)]
                (.append content emitted))
              (when (:done chunk)
                (vreset! usage
                         {:prompt_tokens (get chunk :prompt_eval_count 0)
                          :completion_tokens (get chunk :eval_count 0)
                          :total_tokens (+ (get chunk :prompt_eval_count 0)
                                           (get chunk :eval_count 0))}))))))

      (openai-shaped? provider)
      (let [response
            (streaming-response
             (openai-url provider "/chat/completions")
             (cond-> {:model model :messages messages :stream true
                      :stream_options {:include_usage true}
                      :temperature (or temperature 0.7)}
               (= :xai (:kind provider))
               (assoc :max_tokens (or (:max-output-tokens provider) 8192)
                      :reasoning_effort (or (:reasoning-effort request)
                                            (:reasoning-effort provider)
                                            "medium")))
             (config/env-secret provider)
             (xai-headers provider request))]
        (with-open [reader (BufferedReader.
                            (InputStreamReader. (.body response)))]
          (doseq [line (line-seq reader)
                  :let [data (when (str/starts-with? line "data:")
                               (str/trim (subs line 5)))]
                  :when (and data (not= data "[DONE]"))]
            (let [chunk (json/read-str data :key-fn keyword)
                  delta (get-in chunk [:choices 0 :delta :content])]
              (when-let [emitted (emit! on-delta delta)]
                (.append content emitted))
              (when-let [chunk-usage (:usage chunk)]
                (vreset! usage chunk-usage))))))

      :else (throw (ex-info "unsupported provider kind" {:provider provider})))
    {:content (.toString content) :usage @usage}))

(defn- append-fragment [current fragment]
  (str (or current "") (or fragment "")))

(defn- merge-tool-fragment [current call]
  (let [function (:function call)]
    {:id (or (:id current) (:id call))
     :type "function"
     :function {:name (append-fragment (get-in current [:function :name])
                                       (:name function))
                :arguments (append-fragment (get-in current [:function :arguments])
                                             (:arguments function))}}))

(defn agent-turn-stream!
  "Stream a tool-capable model turn. Text deltas are visible immediately;
  fragmented OpenAI tool calls are assembled before Agent Control sees them."
  [provider request on-delta]
  (let [content (StringBuilder.)
        calls (atom {})
        finish-reason (volatile! nil)
        usage (volatile! nil)
        body (assoc (agent-request-body provider request) :stream true)]
    (cond
      (= :ollama (:kind provider))
      (let [response (streaming-response
                      (str (:base-url provider) "/api/chat")
                      (-> body
                          (dissoc :temperature :max_tokens :parallel_tool_calls
                                  :reasoning_effort)
                          (assoc :options {:temperature (or (:temperature request) 0.2)
                                           :num_predict (or (:max-output-tokens provider)
                                                            default-agent-max-tokens)}))
                      nil)]
        (with-active-agent-reader
          response
          (fn [reader]
            (doseq [line (line-seq reader) :when (not (str/blank? line))]
              (let [chunk (json/read-str line :key-fn keyword)
                    message (:message chunk)
                    delta (:content message)]
                (when-let [emitted (emit! on-delta delta)] (.append content emitted))
                (when-let [tool-calls (seq (:tool_calls message))]
                  (reset! calls (into {} (map-indexed vector tool-calls))))
                (when (:done chunk)
                  (vreset! finish-reason (:done_reason chunk))
                  (vreset! usage
                           {:prompt_tokens (get chunk :prompt_eval_count 0)
                            :completion_tokens (get chunk :eval_count 0)
                            :total_tokens (+ (get chunk :prompt_eval_count 0)
                                             (get chunk :eval_count 0))})))))))

      (openai-shaped? provider)
      (let [response (streaming-response
                      (openai-url provider "/chat/completions")
                      (assoc body :stream_options {:include_usage true})
                      (config/env-secret provider)
                      (xai-headers provider request))]
        (with-active-agent-reader
          response
          (fn [reader]
            (doseq [line (line-seq reader)
                    :let [data (when (str/starts-with? line "data:")
                                 (str/trim (subs line 5)))]
                    :when (and data (not= data "[DONE]"))]
              (let [chunk (json/read-str data :key-fn keyword)
                    choice (get-in chunk [:choices 0])
                    delta (:delta choice)]
                (when-let [emitted (emit! on-delta (:content delta))]
                  (.append content emitted))
                (doseq [[fallback call] (map-indexed vector (:tool_calls delta))]
                  (let [index (or (:index call) fallback)]
                    (swap! calls update index merge-tool-fragment call)))
                (when-let [reason (:finish_reason choice)]
                  (vreset! finish-reason reason))
                (when-let [chunk-usage (:usage chunk)]
                  (vreset! usage chunk-usage)))))))

      :else (throw (ex-info "unsupported provider kind" {:provider provider})))
    (agent-result {:content (.toString content)
                   :tool_calls (mapv val (sort-by key @calls))}
                  @finish-reason @usage)))
