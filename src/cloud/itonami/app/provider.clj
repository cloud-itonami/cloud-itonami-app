(ns cloud.itonami.app.provider
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.cli-runner :as cli-runner]
            [cloud.itonami.app.config :as config])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Base64]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 4))
      .build))

(defn- request-json
  ([method url body] (request-json method url body nil))
  ([method url body api-key]
   (request-json method url body api-key 120))
  ([method url body api-key timeout-seconds]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds timeout-seconds))
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

    :cli (cli-runner/list-models provider)
    []))

(declare normalized-tool-calls merge-tool-call-deltas
         finalize-tool-call-deltas openai-message ollama-message)

(defn chat
  [provider {:keys [model messages temperature tools tool-choice]}]
  (case (:kind provider)
    :ollama
    (let [result (request-json
                  :post (str (:base-url provider) "/api/chat")
                  (cond-> {:model model
                           :messages (mapv ollama-message messages)
                           :stream false
                           :options {:temperature (or temperature 0.7)}}
                    (seq tools) (assoc :tools tools)))]
      {:content (get-in result [:message :content])
       :tool-calls
       (normalized-tool-calls (get-in result [:message :tool_calls]))
       :usage {:prompt_tokens (get result :prompt_eval_count 0)
               :completion_tokens (get result :eval_count 0)
               :total_tokens (+ (get result :prompt_eval_count 0)
                                (get result :eval_count 0))}})

    :openai-compatible
    (let [result (request-json
                  :post (str (str/replace (:base-url provider) #"/$" "")
                             "/chat/completions")
                  (cond-> {:model model
                           :messages (mapv openai-message messages)
                           :stream false
                           :temperature (or temperature 0.7)}
                    (seq tools) (assoc :tools tools)
                    tool-choice (assoc :tool_choice tool-choice))
                  (config/env-secret provider))]
      {:content (get-in result [:choices 0 :message :content])
       :tool-calls
       (normalized-tool-calls
        (get-in result [:choices 0 :message :tool_calls]))
       :usage (:usage result)})

    :cli
    (cli-runner/chat provider {:model model :messages messages
                               :temperature temperature})

    (throw (ex-info "unsupported provider kind" {:provider provider}))))

(defn- image-data-url [{:keys [image-path media-type]}]
  (str "data:" (or media-type "image/png") ";base64,"
       (.encodeToString (Base64/getEncoder)
                        (java.nio.file.Files/readAllBytes
                         (.toPath (io/file image-path))))))

(defn- openai-message [{:keys [role content tool-calls tool-call-id]}]
  (case role
    "assistant"
    (cond-> {:role role :content (or content "")}
      (seq tool-calls)
      (assoc :tool_calls
             (mapv (fn [{:keys [id name input]}]
                     {:id id :type "function"
                      :function {:name name
                                 :arguments (json/write-str (or input {}))}})
                   tool-calls)))

    "tool" {:role role :tool_call_id tool-call-id
            :content (if (map? content)
                       (or (:text content) "")
                       (str content))}

    {:role role :content content}))

(defn- openai-messages [messages]
  (vec
   (mapcat
    (fn [message]
      (let [converted (openai-message message)
            content (:content message)]
        (if (and (= "tool" (:role message))
                 (map? content) (:image-path content))
          [converted
           {:role "user"
            :content [{:type "text" :text "The tool screenshot follows."}
                      {:type "image_url"
                       :image_url {:url (image-data-url content)}}]}]
          [converted])))
    messages)))

(defn- ollama-message [{:keys [role content tool-calls]}]
  (cond-> {:role role
           :content (if (map? content) (or (:text content) "") (or content ""))}
    (seq tool-calls)
    (assoc :tool_calls
           (mapv (fn [{:keys [name input]}]
                   {:function {:name name :arguments (or input {})}})
                 tool-calls))
    (and (= "tool" role) (map? content) (:image-path content))
    (assoc :images
           [(.encodeToString
             (Base64/getEncoder)
             (java.nio.file.Files/readAllBytes
              (.toPath (io/file (:image-path content)))))])))

(defn- tool-definition [{:keys [name description parameters]}]
  {:type "function"
   :function {:name name :description description :parameters parameters}})

(defn- parse-tool-input [value]
  (cond
    (map? value) value
    (and (string? value) (not (str/blank? value)))
    (json/read-str value :key-fn keyword)
    :else {}))

(defn- normalized-tool-calls [calls]
  (mapv
   (fn [index call]
     (let [function (:function call)]
       {:id (or (:id call)
                (str "call-" (System/nanoTime) "-" index))
        :name (:name function)
        :input (parse-tool-input (:arguments function))}))
   (range) calls))

(defn- merge-tool-call-deltas [current calls]
  (reduce
   (fn [current call]
     (let [index (long (or (:index call) 0))
           expanded (vec (concat current
                                 (repeat (max 0 (- (inc index)
                                                   (count current)))
                                         {})))
           prior (get expanded index {})
           function (:function call)]
       (assoc expanded index
              (cond-> prior
                (:id call) (assoc :id (:id call))
                (:name function) (assoc :name (:name function))
                (:arguments function)
                (update :arguments str (:arguments function))))))
   (vec current) calls))

(defn- finalize-tool-call-deltas [calls]
  (mapv
   (fn [index call]
     {:id (or (:id call) (str "call-" (System/nanoTime) "-" index))
      :name (:name call)
      :input (parse-tool-input (:arguments call))})
   (range) calls))

(defn- normalize-agent-result [result]
  (let [message (or (get-in result [:choices 0 :message]) (:message result))
        calls (normalized-tool-calls (:tool_calls message))]
    {:content (or (:content message) "")
     :tool-calls calls
     :usage (:usage result)}))

(defn agent-turn
  "One non-streaming model turn with portable function tools."
  [provider {:keys [model messages tools temperature]}]
  (let [tools (mapv tool-definition tools)
        result
        (case (:kind provider)
          :ollama
          (request-json
           :post (str (:base-url provider) "/api/chat")
           {:model model :messages (mapv ollama-message messages)
            :tools tools :stream false
            :options {:temperature (or temperature 0.2)}}
           nil 300)

          :openai-compatible
          (request-json
           :post (str (str/replace (:base-url provider) #"/$" "")
                      "/chat/completions")
           {:model model :messages (openai-messages messages)
            :tools tools :tool_choice "auto" :stream false
            :temperature (or temperature 0.2)}
           (config/env-secret provider) 300)

          (throw (ex-info "unsupported provider kind" {:provider provider})))]
    (normalize-agent-result result)))

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
  [provider {:keys [model messages temperature tools tool-choice]} on-delta]
  (let [content (StringBuilder.)
        usage (volatile! nil)
        calls (volatile! [])]
    (case (:kind provider)
      :ollama
      (let [response
            (streaming-response
             (str (:base-url provider) "/api/chat")
             (cond-> {:model model
                      :messages (mapv ollama-message messages) :stream true
                      :options {:temperature (or temperature 0.7)}}
               (seq tools) (assoc :tools tools))
             nil)]
        (with-open [reader (BufferedReader.
                            (InputStreamReader. (.body response)))]
          (doseq [line (line-seq reader)
                  :when (not (str/blank? line))]
            (let [chunk (json/read-str line :key-fn keyword)
                  delta (get-in chunk [:message :content])]
              (when-let [emitted (emit! on-delta delta)]
                (.append content emitted))
              (when-let [tool-calls
                         (seq (get-in chunk [:message :tool_calls]))]
                (vreset! calls (normalized-tool-calls tool-calls)))
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
             (cond-> {:model model
                      :messages (mapv openai-message messages) :stream true
                      :stream_options {:include_usage true}
                      :temperature (or temperature 0.7)}
               (seq tools) (assoc :tools tools)
               tool-choice (assoc :tool_choice tool-choice))
             (config/env-secret provider))]
        (with-open [reader (BufferedReader.
                            (InputStreamReader. (.body response)))]
          (doseq [line (line-seq reader)
                  :let [data (when (str/starts-with? line "data:")
                               (str/trim (subs line 5)))]
                  :when (and data (not= data "[DONE]"))]
            (let [chunk (json/read-str data :key-fn keyword)
                  choice (get-in chunk [:choices 0])
                  delta (get-in choice [:delta :content])]
              (when-let [emitted (emit! on-delta delta)]
                (.append content emitted))
              (when-let [tool-calls
                         (seq (get-in choice [:delta :tool_calls]))]
                (vswap! calls merge-tool-call-deltas tool-calls))
              (when-let [chunk-usage (:usage chunk)]
                (vreset! usage chunk-usage))))))

      :cli
      (let [result (cli-runner/chat
                    provider {:model model :messages messages
                              :temperature temperature})]
        (when-let [emitted (emit! on-delta (:content result))]
          (.append content emitted))
        (vreset! usage (:usage result)))

      (throw (ex-info "unsupported provider kind" {:provider provider})))
    {:content (.toString content)
     :tool-calls (if (= :openai-compatible (:kind provider))
                   (finalize-tool-call-deltas @calls)
                   @calls)
     :usage @usage}))
