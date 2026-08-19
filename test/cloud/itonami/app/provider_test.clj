(ns cloud.itonami.app.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [cloud.itonami.app.provider :as provider])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn- private-fn [name]
  (some-> (ns-resolve 'cloud.itonami.app.provider name) deref))

(deftest a-capped-budget-turns-reasoning-off
  ;; A reasoning model spends output tokens on `thinking` before it emits any
  ;; text, so a tight cap does not shorten the answer -- it removes it.
  ;; Measured 2026-08-18 against murakumo-main, one realistic resident payload,
  ;; same cap both ways: thinking on -> stop=max_tokens, 4656 thinking chars,
  ;; ZERO text; thinking off -> stop=end_turn, 2150 chars of text.
  ;;
  ;; That is what 11 consecutive resident ticks of one Bot looked like from
  ;; the outside between 2026-08-15 and 2026-08-18: "Provider returned no final
  ;; answer ... completed as a safe no-op", every time.
  (testing "the caller's disable-thinking? reaches the wire"
    (let [body ((private-fn 'agent-request-body)
                {:kind :openai-compatible}
                {:model "murakumo-main"
                 :messages [{:role "user" :content "hello"}]
                 :tools []
                 :max-output-tokens 1024
                 :disable-thinking? true})]
      (is (= 1024 (:max_tokens body)))
      (is (= {:enable_thinking false} (:chat_template_kwargs body))
          "without this the cap is spent on thinking and no text block is reached")))
  (testing "an uncapped turn keeps reasoning -- this is not a global kill switch"
    (let [body ((private-fn 'agent-request-body)
                {:kind :openai-compatible}
                {:model "murakumo-main"
                 :messages [{:role "user" :content "hello"}]
                 :tools []})]
      (is (nil? (:chat_template_kwargs body))
          "interactive turns have budget for both, and reasoning is worth having"))))

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

;; ── a slow model and a broken host are different problems ───────────────
;;
;; `java.net.http` throws `HttpTimeoutException` with no ex-data, so every
;; timeout used to be filed as `:internal-error` -- the same value a genuine
;; bug in this application produces. Measured 2026-08-19: 7 of 24 resident
;; runs that day were timeouts wearing that name, and separating them meant
;; reading the event log of each run by hand.

(deftest a-request-that-runs-out-of-time-is-a-provider-timeout
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/chat"
     (reify HttpHandler
       (handle [_ exchange]
         ;; Answer far too late. The point is the class of the failure, not
         ;; the duration, so the limit is moved rather than the wait.
         (Thread/sleep 4000)
         (let [bytes (.getBytes "{\"ok\":true}" "UTF-8")]
           (.sendResponseHeaders exchange 200 (alength bytes))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))))))
    (.start server)
    (try
      (let [url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/chat")]
        (with-redefs [provider/request-timeout-seconds 1]
          (try
            ((private-fn 'request-json) :post url {:x 1})
            (is false "a request past the limit must fail")
            (catch clojure.lang.ExceptionInfo error
              (testing "it is not an unclassified internal error"
                (is (= :provider/timeout (:type (ex-data error)))))
              (testing "and it carries the limit it exceeded"
                (is (= 1 (:timeout-seconds (ex-data error)))))
              (testing "the original is kept rather than replaced"
                (is (instance? java.net.http.HttpTimeoutException
                               (.getCause error))))))))
      (finally (.stop server 0)))))

(deftest the-classifier-keeps-three-failures-apart
  ;; Tested on the classifier rather than through a socket, because the
  ;; condition cannot be produced reliably from here: a `com.sun.net.httpserver`
  ;; that has been stopped does not REFUSE the connection on this platform, it
  ;; hangs, and the JDK reports a plain `HttpTimeoutException`. Measured
  ;; 2026-08-19 while trying to write the socket version of this test.
  ;;
  ;; `HttpConnectTimeoutException` extends `HttpTimeoutException`, so an
  ;; `instance?` check in the wrong order reports an unreachable provider as a
  ;; slow model -- the same conflation this whole change exists to undo, one
  ;; layer down. Order is the assertion.
  (let [classify (private-fn 'timeout->typed)
        thrown (fn [error]
                 (try (classify error "http://example.invalid/chat")
                      (catch Exception caught caught)))]
    (testing "a model too slow to answer is a capacity problem"
      (let [e (thrown (java.net.http.HttpTimeoutException. "request timed out"))]
        (is (= :provider/timeout (:type (ex-data e))))
        (is (= provider/request-timeout-seconds
               (:timeout-seconds (ex-data e))))))
    (testing "a provider that cannot be reached is a different one"
      (let [e (thrown (java.net.http.HttpConnectTimeoutException. "connect timed out"))]
        (is (= :provider/unreachable (:type (ex-data e))))))
    (testing "anything else passes through untouched"
      (let [original (java.io.IOException. "broken pipe")
            e (thrown original)]
        (is (identical? original e))))))
