(ns cloud.itonami.app.provider-agent-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.provider :as provider]))

(deftest openai-tool-calls-are-normalized-for-agent-control
  (let [captured (atom nil)]
    (with-redefs-fn
      {#'cloud.itonami.app.provider/request-json
       (fn [_ _ body & _]
         (reset! captured body)
         {:choices [{:message {:content nil
                               :tool_calls
                               [{:id "call-1"
                                 :function {:name "browser_open"
                                            :arguments "{\"url\":\"http://localhost:1338\"}"}}]}}]
          :usage {:total_tokens 12}})}
      #(let [result
             (provider/agent-turn
              {:id "openai" :kind :openai-compatible :base-url "http://example/v1"}
              {:model "model" :messages [{:role "user" :content "open"}]
               :tools [{:name "browser_open" :description "Open"
                        :parameters {:type "object"}}]})]
         (is (= "browser_open" (get-in result [:tool-calls 0 :name])))
         (is (= "http://localhost:1338"
                (get-in result [:tool-calls 0 :input :url])))
         (is (= "browser_open"
                (get-in @captured [:tools 0 :function :name])))))))
