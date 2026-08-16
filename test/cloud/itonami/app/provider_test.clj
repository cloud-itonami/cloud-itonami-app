(ns cloud.itonami.app.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [cloud.itonami.app.provider :as provider])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn- private-fn [name]
  (some-> (ns-resolve 'cloud.itonami.app.provider name) deref))

(deftest agent-turn-reserves-output-after-reasoning
  (let [body ((private-fn 'agent-request-body)
              {:kind :openai-compatible}
              {:model "murakumo-main"
               :messages [{:role "user" :content "hello"}]
               :tools []})]
    (is (= 2048 (:max_tokens body)))
    (is (false? (:stream body)))
    (testing "the ordinary provider fields are preserved"
      (is (= "murakumo-main" (:model body)))
      (is (= [{:role "user" :content "hello"}] (:messages body))))))

(deftest agent-turn-honors-a-narrower-request-envelope
  (let [body ((private-fn 'agent-request-body)
              {:kind :openai-compatible :max-output-tokens 2048}
              {:model "murakumo-main" :messages [] :tools []
               :max-output-tokens 1024})]
    (is (= 1024 (:max_tokens body))
        "a resident request can be bounded without shrinking human turns")))

(deftest transient-json-provider-failures-retry-once
  (let [attempts (atom 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/chat"
     (reify HttpHandler
       (handle [_ exchange]
         (let [attempt (swap! attempts inc)
               [status payload] (if (= 1 attempt)
                                  [502 {:error "temporary"}]
                                  [200 {:ok true}])
               bytes (.getBytes (json/write-str payload) "UTF-8")]
           (.sendResponseHeaders exchange status (alength bytes))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))))))
    (.start server)
    (try
      (let [url (str "http://127.0.0.1:"
                     (.getPort (.getAddress server)) "/chat")]
        (is (= {:ok true} ((private-fn 'request-json) :post url {:x 1})))
        (is (= 2 @attempts)))
      (finally (.stop server 0)))))

(deftest permanent-json-provider-failures-do-not-loop
  (let [attempts (atom 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/chat"
     (reify HttpHandler
       (handle [_ exchange]
         (swap! attempts inc)
         (let [bytes (.getBytes "{\"error\":\"bad request\"}" "UTF-8")]
           (.sendResponseHeaders exchange 400 (alength bytes))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))))))
    (.start server)
    (try
      (let [url (str "http://127.0.0.1:"
                     (.getPort (.getAddress server)) "/chat")]
        (try
          ((private-fn 'request-json) :post url {:x 1})
          (is false "HTTP 400 must fail")
          (catch clojure.lang.ExceptionInfo error
            (is (= 400 (:status (ex-data error))))
            (is (= 1 (:attempts (ex-data error))))))
        (is (= 1 @attempts)))
      (finally (.stop server 0)))))

(deftest grok-agent-turn-keeps-one-effect-authority
  (let [body ((private-fn 'agent-request-body)
              {:kind :xai :reasoning-effort "high"
               :max-output-tokens 4096}
              {:model "grok-4.6"
               :messages [{:role "user" :content "check mail"}]
               :tools [{:name "gmail_search_messages"
                        :description "Search mail"
                        :parameters {:type "object"}}]})]
    (is (= "grok-4.6" (:model body)))
    (is (= 4096 (:max_tokens body)))
    (is (= "high" (:reasoning_effort body)))
    (is (false? (:parallel_tool_calls body)))
    (is (= "function" (get-in body [:tools 0 :type]))))
  (testing "a Bot id becomes xAI's opaque conversation correlation header"
    (is (= {"x-grok-conv-id" "bot-123"}
           ((private-fn 'xai-headers)
            {:kind :xai} {:conversation-id "bot-123"}))))
  (testing "the xAI-only header is never sent to another provider"
    (is (nil? ((private-fn 'xai-headers)
               {:kind :openai-compatible} {:conversation-id "bot-123"})))))

(deftest openai-compatible-agent-turn-keeps-one-tool-continuation
  (let [body ((private-fn 'agent-request-body)
              {:kind :openai-compatible :max-output-tokens 512}
              {:model "murakumo-main"
               :messages [{:role "user" :content "inspect the repository"}]
               :tools [{:name "workspace_list"
                        :description "List workspace files"
                        :parameters {:type "object"}}]})]
    (is (false? (:parallel_tool_calls body)))
    (is (= 512 (:max_tokens body)))
    (is (nil? (:reasoning_effort body))
        "xAI-specific reasoning policy is not sent to Murakumo")))

(deftest an-empty-finished-turn-is-not-a-silent-answer
  (let [normalize (private-fn 'agent-result)]
    (try
      (normalize {:content ""} "length")
      (is false "an empty non-tool turn must be rejected")
      (catch clojure.lang.ExceptionInfo error
        (is (= :provider/empty-response (:type (ex-data error))))
        (is (= "length" (:finish-reason (ex-data error))))))
    (testing "a tool call may correctly have no prose"
      (is (= "gmail_search_messages"
             (get-in (normalize
                      {:content nil
                       :tool_calls [{:id "call-1"
                                     :function {:name "gmail_search_messages"
                                                :arguments "{}"}}]}
                      "tool_calls")
                     [:tool-calls 0 :name]))))))

(deftest normalized-agent-results-preserve-provider-usage
  (let [normalize (private-fn 'agent-result)
        usage {:prompt_tokens 120 :completion_tokens 30 :total_tokens 150}]
    (is (= usage
           (:usage (normalize {:content "done"} "stop" usage))))))

(deftest streamed-tool-call-fragments-become-one-normalized-call
  (let [merge-fragment (private-fn 'merge-tool-fragment)
        normalize (private-fn 'agent-result)
        first-part (merge-fragment nil
                                   {:id "call-1"
                                    :function {:name "workspace_"
                                               :arguments "{\"path\":\"READ"}})
        complete (merge-fragment first-part
                                 {:function {:name "read"
                                             :arguments "ME.md\"}"}})]
    (is (= {:id "call-1" :name "workspace_read"
            :input {:path "README.md"}}
           (first (:tool-calls
                   (normalize {:content "" :tool_calls [complete]}
                              "tool_calls")))))))
