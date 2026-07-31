(ns cloud.itonami.app.compat
  "Pure request/response adapters for public model API profiles."
  (:require [clojure.data.json :as json]))

(defn- content-text [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (keep (fn [part]
                 (when (contains? #{"input_text" "text"} (:type part))
                   (:text part))))
         (apply str))
    :else (str (or content ""))))

(defn- response-message [item]
  (if (string? item)
    {:role "user" :content item}
    {:role (or (:role item) "user")
     :content (content-text (:content item))}))

(defn responses-request [request]
  {:messages
   (let [input (:input request)]
     (cond
       (string? input) [{:role "user" :content input}]
       (sequential? input) (mapv response-message input)
       :else []))
   :model (:model request)
   :provider-id (:provider request)
   :session-id (or (:session_id request) "responses")
   :temperature (:temperature request)
   :tools
   (mapv
    (fn [tool]
      {:type "function"
       :function {:name (:name tool)
                  :description (:description tool)
                  :parameters (or (:parameters tool)
                                  {:type "object" :properties {}})}})
    (filter #(= "function" (:type %)) (:tools request)))
   :tool-choice (:tool_choice request)})

(defn responses-response [response]
  (let [message (:message response)
        text-item
        (when (seq (:content message))
          {:id (str "msg_" (:id message))
           :type "message" :status "completed" :role "assistant"
           :content [{:type "output_text" :text (:content message)
                      :annotations []}]})
        tool-items
        (mapv
         (fn [{:keys [id name input]}]
           {:id (str "fc_" id) :type "function_call"
            :status "completed" :call_id id :name name
            :arguments (json/write-str (or input {}))})
         (:tool-calls message))]
    {:id (str "resp_" (:id response))
     :object "response" :created_at (:created response)
     :status "completed" :model (:model response)
     :output (cond-> [] text-item (conj text-item) true (into tool-items))
     :usage (:usage response)
     :error nil}))

(defn- anthropic-message [message]
  (let [content (:content message)]
    (if (string? content)
      {:role (:role message) :content content}
      (let [texts (filter #(= "text" (:type %)) content)
            tool-results (filter #(= "tool_result" (:type %)) content)]
        (cond
          (seq tool-results)
          {:role "tool"
           :tool-call-id (:tool_use_id (first tool-results))
           :content (content-text (:content (first tool-results)))}

          :else
          {:role (:role message)
           :content (apply str (map :text texts))
           :tool-calls
           (mapv
            (fn [part]
              {:id (:id part) :name (:name part)
               :input (or (:input part) {})})
            (filter #(= "tool_use" (:type %)) content))})))))

(defn anthropic-request [request]
  {:messages
   (cond-> []
     (:system request) (conj {:role "system"
                              :content (content-text (:system request))})
     true (into (map anthropic-message (:messages request))))
   :model (:model request)
   :provider-id (:provider request)
   :session-id (or (:session_id request) "anthropic")
   :temperature (:temperature request)
   :tools
   (mapv
    (fn [tool]
      {:type "function"
       :function {:name (:name tool)
                  :description (:description tool)
                  :parameters (or (:input_schema tool)
                                  {:type "object" :properties {}})}})
    (:tools request))
   :tool-choice
   (when-let [choice (:tool_choice request)]
     (case (:type choice)
       "auto" "auto"
       "any" "required"
       "tool" {:type "function"
                :function {:name (:name choice)}}
       nil))})

(defn anthropic-response [response]
  (let [message (:message response)
        text-blocks (cond-> []
                      (seq (:content message))
                      (conj {:type "text" :text (:content message)}))
        tool-blocks
        (mapv (fn [{:keys [id name input]}]
                {:type "tool_use" :id id :name name
                 :input (or input {})})
              (:tool-calls message))]
    {:id (str "msg_" (:id response))
     :type "message" :role "assistant" :model (:model response)
     :content (into text-blocks tool-blocks)
     :stop_reason (if (seq tool-blocks) "tool_use" "end_turn")
     :stop_sequence nil
     :usage {:input_tokens (get-in response [:usage :prompt_tokens] 0)
             :output_tokens (get-in response [:usage :completion_tokens] 0)}}))
