(ns cloud.itonami.app.provider
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.cli-runner :as cli-runner]
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
    :cli
    (cli-runner/list-models provider)
    []))

(defn chat
  [provider {:keys [model messages temperature session-id runner-session-id
                    mode guardrail effort cwd]}]
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

    :cli
    (cli-runner/chat provider
                     {:messages messages
                      :model model
                      :session-id session-id
                      :runner-session-id runner-session-id
                      :mode mode :guardrail guardrail
                      :effort effort :cwd cwd})

    (throw (ex-info "unsupported provider kind" {:provider provider}))))

(defn- tool-call [value]
  (let [function (or (:function value) value)
        arguments (:arguments function)
        input (if (string? arguments)
                (try (json/read-str arguments :key-fn keyword)
                     (catch Exception _
                       {:unparsed arguments}))
                (or arguments {}))]
    {:id (or (:id value) (str "tool-" (java.util.UUID/randomUUID)))
     :name (:name function)
     :input input}))

(defn agent-turn
  "One tool-capable model turn for the governed `agent-control` runtime.

  CLI coding agents own their internal tool loop and use `cli-runner`; this
  function is for local/OpenAI-compatible providers whose tool calls are
  executed by Cloud Itonami's capability and HIL boundary."
  [provider {:keys [model messages tools temperature]}]
  (let [tool-specs
        (mapv (fn [tool]
                {:type "function"
                 :function {:name (:name tool)
                            :description (:description tool)
                            :parameters (:parameters tool)}})
              tools)
        result
        (case (:kind provider)
          :ollama
          (request-json :post (str (:base-url provider) "/api/chat")
                        {:model model :messages messages :stream false
                         :tools tool-specs
                         :options {:temperature (or temperature 0.2)}})

          :openai-compatible
          (request-json
           :post
           (str (str/replace (:base-url provider) #"/$" "")
                "/chat/completions")
           {:model model :messages messages :tools tool-specs
            :temperature (or temperature 0.2)}
           (config/env-secret provider))

          :cli
          (throw (ex-info "CLI provider owns its internal tool loop."
                          {:type :provider/cli-tool-loop-owned}))

          (throw (ex-info "unsupported tool-capable provider"
                          {:provider provider})))
        message (if (= :ollama (:kind provider))
                  (:message result)
                  (get-in result [:choices 0 :message]))]
    {:content (or (:content message) "")
     :tool-calls (mapv tool-call (:tool_calls message))
     :usage (if (= :ollama (:kind provider))
              {:prompt_tokens (get result :prompt_eval_count 0)
               :completion_tokens (get result :eval_count 0)
               :total_tokens (+ (get result :prompt_eval_count 0)
                                (get result :eval_count 0))}
              (:usage result))}))

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
  ([provider request on-delta]
   (chat-stream! provider request on-delta nil))
  ([provider {:keys [model messages temperature session-id runner-session-id
                     mode guardrail effort cwd]}
    on-delta on-event]
  (let [content (StringBuilder.)
        usage (volatile! nil)
        cli-session (volatile! nil)]
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

      :cli
      (let [result (cli-runner/chat
                    provider
                    {:messages messages
                     :model model
                     :session-id session-id
                     :runner-session-id runner-session-id
                     :mode mode :guardrail guardrail
                     :effort effort :cwd cwd :on-event on-event})]
        (when-let [emitted (emit! on-delta (:content result))]
          (.append content emitted))
        (vreset! usage (:usage result))
        (when-let [runner-session-id (:runner-session-id result)]
          (vreset! cli-session runner-session-id)))

      (throw (ex-info "unsupported provider kind" {:provider provider})))
    (cond-> {:content (.toString content) :usage @usage}
      @cli-session (assoc :runner-session-id @cli-session)))))
