(ns cloud.itonami.app.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.provider :as provider]))

(defn- private-fn [name]
  (some-> (ns-resolve 'cloud.itonami.app.provider name) deref))

(deftest agent-turn-reserves-output-after-reasoning
  (let [body ((private-fn 'agent-request-body)
              {:model "murakumo-main"
               :messages [{:role "user" :content "hello"}]
               :tools []})]
    (is (= 2048 (:max_tokens body)))
    (is (false? (:stream body)))
    (testing "the ordinary provider fields are preserved"
      (is (= "murakumo-main" (:model body)))
      (is (= [{:role "user" :content "hello"}] (:messages body))))))

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
