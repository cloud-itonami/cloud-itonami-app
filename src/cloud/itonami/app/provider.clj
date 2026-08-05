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
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 120))
                     (.header "Accept" "application/json")
                     (.header "Content-Type" "application/json"))
         _ (when api-key (.header builder "Authorization" (str "Bearer " api-key)))
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

(defn list-models [provider]
  (case (:kind provider)
    :ollama
    (mapv (fn [model]
            {:id (:name model) :object "model" :owned_by (:id provider)
             :provider (:id provider)})
          (:models (request-json :get (str (:base-url provider) "/api/tags") nil)))

    :openai-compatible
    (mapv (fn [model]
            {:id (:id model) :object "model" :owned_by (:id provider)
             :provider (:id provider)})
          (:data (request-json :get (str (str/replace (:base-url provider) #"/$" "")
                                         "/models")
                               nil (config/env-secret provider))))
    []))

(defn chat
  [provider {:keys [model messages temperature]}]
  (case (:kind provider)
    :ollama
    (let [result (request-json
                  :post (str (:base-url provider) "/api/chat")
                  {:model model :messages messages :stream false
                   :options {:temperature (or temperature 0.7)}})]
      {:content (get-in result [:message :content])
       :usage {:prompt_tokens (get result :prompt_eval_count 0)
               :completion_tokens (get result :eval_count 0)
               :total_tokens (+ (get result :prompt_eval_count 0)
                                (get result :eval_count 0))}})

    :openai-compatible
    (let [result (request-json
                  :post (str (str/replace (:base-url provider) #"/$" "")
                             "/chat/completions")
                  {:model model :messages messages :stream false
                   :temperature (or temperature 0.7)}
                  (config/env-secret provider))]
      {:content (get-in result [:choices 0 :message :content])
       :usage (:usage result)})

    (throw (ex-info "unsupported provider kind" {:provider provider}))))

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

(defn agent-turn
  "One non-streaming tool-capable model turn, normalized for Agent Control."
  [provider {:keys [model messages tools temperature]}]
  (let [body {:model model
              :messages (mapv provider-message messages)
              :tools (mapv tool-definition tools)
              :stream false
              :temperature (or temperature 0.2)}]
    (case (:kind provider)
      :ollama
      (let [result (request-json :post (str (:base-url provider) "/api/chat")
                                 (-> body
                                     (dissoc :temperature)
                                     (assoc :options {:temperature
                                                      (or temperature 0.2)})))
            message (:message result)]
        {:content (:content message)
         :tool-calls (normalize-tool-calls (:tool_calls message))})

      :openai-compatible
      (let [result (request-json
                    :post
                    (str (str/replace (:base-url provider) #"/$" "")
                         "/chat/completions")
                    body (config/env-secret provider))
            message (get-in result [:choices 0 :message])]
        {:content (:content message)
         :tool-calls (normalize-tool-calls (:tool_calls message))})

      (throw (ex-info "unsupported provider kind" {:provider provider})))))

(defn- streaming-response [url body api-key]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 120))
                    (.header "Accept" "*/*")
                    (.header "Content-Type" "application/json"))
        _ (when api-key
            (.header builder "Authorization" (str "Bearer " api-key)))
        request (-> builder
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str body)))
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "model provider streaming request failed"
                      {:status (.statusCode response) :url url})))
    response))

(defn- emit! [on-delta content]
  (when (and (string? content) (seq content))
    (on-delta content)
    content))

(defn chat-stream!
  "Stream provider deltas to `on-delta` and return the complete result."
  [provider {:keys [model messages temperature]} on-delta]
  (let [content (StringBuilder.)
        usage (volatile! nil)]
    (case (:kind provider)
      :ollama
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

      :openai-compatible
      (let [response
            (streaming-response
             (str (str/replace (:base-url provider) #"/$" "")
                  "/chat/completions")
             {:model model :messages messages :stream true
              :stream_options {:include_usage true}
              :temperature (or temperature 0.7)}
             (config/env-secret provider))]
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

      (throw (ex-info "unsupported provider kind" {:provider provider})))
    {:content (.toString content) :usage @usage}))
