(ns cloud.itonami.app.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.provider :as provider]))

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
