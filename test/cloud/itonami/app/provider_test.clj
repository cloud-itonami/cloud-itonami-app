(ns cloud.itonami.app.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cloud.itonami.app.provider :as provider])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn- private-fn [name]
  (some-> (ns-resolve 'cloud.itonami.app.provider name) deref))

(deftest model-context-is-exact-or-discovered-from-provider-metadata
  (testing "operator metadata wins without a network lookup"
    (is (= 32768
           (provider/model-context-window
            {:id "fixed" :context-window-tokens {"qwen" 32768}}
            "qwen"))))
  (testing "Ollama's family-specific model_info key is understood"
    (is (= 131072
           ((private-fn 'context-window-from-model-info)
            {:model_info {:gemma3.context_length 131072
                          :gemma3.block_count 34}}))))
  (testing "an OpenAI-shaped direct field is understood"
    (is (= 500000
           ((private-fn 'context-window-from-model-info)
            {:context_length 500000})))))

(deftest ollama-context-is-discovered-from-show-not-guessed-by-model-name
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/api/show"
     (reify HttpHandler
       (handle [_ exchange]
         (let [bytes (.getBytes
                      (json/write-str
                       {:model_info {:custom-family.context_length 65536}})
                      "UTF-8")]
           (.sendResponseHeaders exchange 200 (alength bytes))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))))))
    (.start server)
    (try
      (is (= 65536
             (provider/model-context-window
              {:id (str "ollama-fixture-" (random-uuid))
               :kind :ollama
               :base-url (str "http://127.0.0.1:"
                              (.getPort (.getAddress server)))}
              "mutable-local-tag")))
      (finally (.stop server 0)))))

(deftest ollama-is-asked-to-allocate-the-window-the-bot-accounted
  (let [options (private-fn 'ollama-agent-options)]
    (is (= {:temperature 0.2 :num_predict 512 :num_ctx 131072}
           (options {:max-output-tokens 2048}
                    {:max-output-tokens 512
                     :context-window-tokens 131072})))
    (is (= {:temperature 0.2 :num_predict 2048}
           (options {} {}))
        "unknown model metadata does not invent a local allocation")))

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

(deftest an-agent-turn-without-tools-omits-the-tool-protocol
  (let [body ((private-fn 'agent-request-body)
              {:kind :openai-compatible}
              {:model "qwen3.8-27b-throughput-5090"
               :messages [{:role "user" :content "hello"}]
               :tools []})]
    (is (not (contains? body :tools))
        "vLLM rejects an explicitly empty tools array")
    (is (not (contains? body :parallel_tool_calls))
        "parallel tool configuration is meaningless without a tool")))

(deftest agent-turn-honors-a-narrower-request-envelope
  (let [body ((private-fn 'agent-request-body)
              {:kind :openai-compatible :max-output-tokens 2048}
              {:model "murakumo-main" :messages [] :tools []
               :max-output-tokens 1024})]
    (is (= 1024 (:max_tokens body))
        "a resident request can be bounded without shrinking human turns")))

(deftest provider-request-bound-can-cover-a-runpod-cold-start
  (let [timeout (private-fn 'provider-timeout-seconds)]
    (is (= 420 (timeout {:model-request-timeout-seconds {"runpod" 420}}
                        "runpod")))
    (is (= provider/request-timeout-seconds
           (timeout {:model-request-timeout-seconds {"runpod" 420}}
                    "another-model")))
    (is (= provider/request-timeout-seconds (timeout {})))))

(deftest a-stable-alias-can-admit-only-its-observed-concrete-model
  (let [assert-model! (private-fn 'assert-response-model!)
        p {:assert-response-model? true
           :accepted-response-models
           {"murakumo-main" #{"Qwen3.8-27B-Q4_K_M.gguf"}}}]
    (is (= {:model "Qwen3.8-27B-Q4_K_M.gguf"}
           (assert-model! p "murakumo-main"
                          {:model "Qwen3.8-27B-Q4_K_M.gguf"})))
    (try
      (assert-model! p "murakumo-main" {:model "some-other-checkpoint"})
      (is false "an unreviewed alias target must be rejected")
      (catch clojure.lang.ExceptionInfo error
        (is (= :provider/model-mismatch (:type (ex-data error))))))))

(deftest a-failed-5090-request-falls-back-without-relabeling-the-result
  (let [requested (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/v1/chat/completions"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/read-str (slurp (.getRequestBody exchange))
                                      :key-fn keyword)
               model (:model request)
               _ (swap! requested conj model)
               [status payload]
               (if (= "qwen3.8-27b-throughput-5090" model)
                 [502 {:error "cold origin unavailable"}]
                 [200 {:model "murakumo-main"
                       :choices [{:finish_reason "stop"
                                  :message {:content "fallback-ok"}}]}])
               bytes (.getBytes (json/write-str payload) "UTF-8")]
           (.sendResponseHeaders exchange status (alength bytes))
           (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try
      (let [provider {:kind :openai-compatible
                      :base-url (str "http://127.0.0.1:"
                                     (.getPort (.getAddress server)) "/v1")
                      :max-transient-retries 0
                      :assert-response-model? true
                      :model-fallbacks
                      {"qwen3.8-27b-throughput-5090" "murakumo-main"}}
            result (provider/agent-turn
                    provider {:model "qwen3.8-27b-throughput-5090"
                              :messages [] :tools []})]
        (is (= "fallback-ok" (:content result)))
        (is (= "murakumo-main" (:model result)))
        (is (= "qwen3.8-27b-throughput-5090" (:requested-model result)))
        (is (true? (:fallback? result)))
        (is (= ["qwen3.8-27b-throughput-5090" "murakumo-main"]
               @requested)
            "the bounded accelerator request precedes one explicit fallback"))
      (finally (.stop server 0)))))

(deftest an-unready-5090-falls-back-before-generation
  (let [requested (atom [])
        readiness-auth (atom :unobserved)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/ready"
     (reify HttpHandler
       (handle [_ exchange]
         (reset! readiness-auth
                 (.getFirst (.getRequestHeaders exchange) "Authorization"))
         (let [bytes (.getBytes
                      (json/write-str {:ok false
                                       :hosted-models
                                       {"qwen3.8-27b-throughput-5090"
                                        {:idle 0 :running 1 :queued 2}}})
                      "UTF-8")]
           (.sendResponseHeaders exchange 503 (alength bytes))
           (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.createContext
     server "/v1/chat/completions"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/read-str (slurp (.getRequestBody exchange))
                                      :key-fn keyword)
               model (:model request)
               _ (swap! requested conj model)
               bytes (.getBytes
                      (json/write-str
                       {:model model
                        :choices [{:finish_reason "stop"
                                   :message {:content "ready-fallback-ok"}}]})
                      "UTF-8")]
           (.sendResponseHeaders exchange 200 (alength bytes))
           (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try
      (let [origin (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            provider {:kind :openai-compatible
                      :base-url (str origin "/v1")
                      :max-transient-retries 0
                      :assert-response-model? true
                      :model-readiness
                      {"qwen3.8-27b-throughput-5090"
                       {:url (str origin "/ready") :timeout-seconds 1}}
                      :model-fallbacks
                      {"qwen3.8-27b-throughput-5090" "murakumo-main"}}
            result (provider/agent-turn
                    provider {:model "qwen3.8-27b-throughput-5090"
                              :messages [] :tools []})]
        (is (= "ready-fallback-ok" (:content result)))
        (is (= "murakumo-main" (:model result)))
        (is (= "qwen3.8-27b-throughput-5090" (:requested-model result)))
        (is (true? (:fallback? result)))
        (is (= :provider/model-unready (:fallback-error-type result)))
        (is (= ["murakumo-main"] @requested)
            "unready 5090 never enters its six-minute generation path")
        (is (nil? @readiness-auth)
            "the public readiness probe receives no inference credential"))
      (finally (.stop server 0)))))

(deftest exact-hosted-model-readiness-survives-aggregate-503
  (let [requested (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/ready"
     (reify HttpHandler
       (handle [_ exchange]
         (let [bytes (.getBytes
                      (json/write-str
                       {:ok false
                        :inference {:ok false}
                        :hosted-models
                        {"qwen3.8-27b-throughput-5090"
                         {:ok true :idle 1 :running 1
                          :generation-ok true}}})
                      "UTF-8")]
           (.sendResponseHeaders exchange 503 (alength bytes))
           (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.createContext
     server "/v1/chat/completions"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/read-str (slurp (.getRequestBody exchange))
                                      :key-fn keyword)
               model (:model request)
               _ (swap! requested conj model)
               bytes (.getBytes
                      (json/write-str
                       {:model model
                        :choices [{:finish_reason "stop"
                                   :message {:content "5090-primary-ok"}}]})
                      "UTF-8")]
           (.sendResponseHeaders exchange 200 (alength bytes))
           (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try
      (let [origin (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            provider {:kind :openai-compatible
                      :base-url (str origin "/v1")
                      :max-transient-retries 0
                      :assert-response-model? true
                      :model-readiness
                      {"qwen3.8-27b-throughput-5090"
                       {:url (str origin "/ready") :timeout-seconds 1}}
                      :model-fallbacks
                      {"qwen3.8-27b-throughput-5090" "murakumo-main"}}
            result (provider/agent-turn
                    provider {:model "qwen3.8-27b-throughput-5090"
                              :messages [] :tools []})]
        (is (= "5090-primary-ok" (:content result)))
        (is (= "qwen3.8-27b-throughput-5090" (:model result)))
        (is (false? (:fallback? result)))
        (is (= ["qwen3.8-27b-throughput-5090"] @requested)))
      (finally (.stop server 0)))))

(deftest streamed-5090-fallback-is-verified-before-it-is-emitted
  (let [requested (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/v1/chat/completions"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/read-str (slurp (.getRequestBody exchange))
                                      :key-fn keyword)
               model (:model request)
               _ (swap! requested conj model)]
           (if (= "qwen3.8-27b-throughput-5090" model)
             (let [bytes (.getBytes "{\"error\":\"cold origin unavailable\"}" "UTF-8")]
               (.sendResponseHeaders exchange 502 (alength bytes))
               (with-open [out (.getResponseBody exchange)] (.write out bytes)))
             (let [payload (str "data: "
                                (json/write-str
                                 {:model "murakumo-main"
                                  :choices [{:finish_reason "stop"
                                             :delta {:content "stream-ok"}}]})
                                "\n\ndata: [DONE]\n\n")
                   bytes (.getBytes payload "UTF-8")]
               (.getResponseHeaders exchange)
               (.add (.getResponseHeaders exchange) "Content-Type"
                     "text/event-stream")
               (.sendResponseHeaders exchange 200 0)
               (with-open [out (.getResponseBody exchange)] (.write out bytes))))))))
    (.start server)
    (try
      (let [provider {:kind :openai-compatible
                      :base-url (str "http://127.0.0.1:"
                                     (.getPort (.getAddress server)) "/v1")
                      :assert-response-model? true
                      :model-fallbacks
                      {"qwen3.8-27b-throughput-5090" "murakumo-main"}}
            deltas (atom [])
            result (provider/agent-turn-stream!
                    provider {:model "qwen3.8-27b-throughput-5090"
                              :messages [] :tools []}
                    #(swap! deltas conj %))]
        (is (= ["stream-ok"] @deltas))
        (is (= "stream-ok" (:content result)))
        (is (= "murakumo-main" (:model result)))
        (is (true? (:fallback? result)))
        (is (= ["qwen3.8-27b-throughput-5090" "murakumo-main"] @requested)))
      (finally (.stop server 0)))))

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

(deftest text-only-agent-turn-omits-the-tool-protocol-entirely
  (let [body ((private-fn 'agent-request-body)
              {:kind :openai-compatible :max-output-tokens 512}
              {:model "murakumo-main"
               :messages [{:role "user" :content "answer only"}]
               :tools []
               :text-only? true})]
    (is (not (contains? body :tools)))
    (is (not (contains? body :parallel_tool_calls)))
    (is (= false (:stream body)))))

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

(deftest invalid-tool-arguments-report-what-they-were
  ;; 19 resident turns failed as :provider/invalid-tool-arguments (root
  ;; ADR-2608197700) and not one recorded the arguments, so a probe with the
  ;; real tool schemas afterwards could not reproduce it -- 15 attempts, 0
  ;; failures. The reason was in the value the error dropped.
  (let [parse (private-fn 'parse-arguments)]
    (testing "the offending string survives into ex-data"
      (let [error (try (parse "workspace_write_file" "{\"path\":\"REA")
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= :provider/invalid-tool-arguments (:type (ex-data error))))
        (is (= "{\"path\":\"REA" (:arguments-sample (ex-data error))))
        (is (= 12 (:arguments-length (ex-data error))))))

    (testing "and so does the name of the tool that was mis-called"
      ;; Measured 2026-08-28: this became the most common live failure, and
      ;; `:turn/tool` was nil for all 138 of them. The caller knew the name;
      ;; this function had not asked for it.
      (let [error (try (parse "workspace_write_file" "{\"path\":\"REA")
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= "workspace_write_file" (:tool-name (ex-data error)))))
      (let [error (try (parse "goal_plan" "[\"Osaka\"]")
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= "goal_plan" (:tool-name (ex-data error)))
            "on the wrong-shape path too, which is a different throw site")))

    (testing "valid JSON of the wrong shape is refused, not silently emptied"
      ;; A bare array parsed fine before and became the tool's arguments, so
      ;; every (:key args) lookup answered nil and the tool ran with none.
      (doseq [value ["[\"Osaka\"]" "\"Osaka\"" "42"]]
        (let [error (try (parse "some_tool" value)
                         (catch clojure.lang.ExceptionInfo e e))]
          (is (= :provider/invalid-tool-arguments (:type (ex-data error)))
              (str value " must be refused"))
          (is (= value (:arguments-sample (ex-data error)))))))

    (testing "a markdown fence is decoration, not malformation"
      (is (= {:city "Kyoto"} (parse "some_tool" "```json\n{\"city\":\"Kyoto\"}\n```")))
      (is (= {:city "Nara"} (parse "some_tool" "```\n{\"city\":\"Nara\"}\n```"))))

    (testing "what already worked keeps working"
      (is (= {:city "Osaka"} (parse "some_tool" "{\"city\":\"Osaka\"}")))
      (is (= {:city "Osaka"} (parse "some_tool" {:city "Osaka"})))
      (is (= {} (parse "some_tool" ""))))))

;; ── a cut-off tool call and a malformed one are different problems ─────
;;
;; Measured 2026-08-28 on the resident fleet: `:provider/invalid-tool-arguments`
;; was the most common live failure, 20 of 45 failed turns. Every reproduction
;; came from `:max-output-tokens 1024` against `decision_frame`, whose frames
;; need 1350-1676 tokens -- three streamed runs at 1024 were unparseable, three
;; each at 2048/3072/4096 were complete. The model authored nothing malformed;
;; a configured number cut it in half, and the error name sent operators to
;; look at the model.

(deftest a-truncated-json-argument-is-marked-as-having-ended-early
  ;; The signal that needs no agreement with the server about token counts.
  ;; api.murakumo.cloud caps output at 2048 whatever is requested, so an app
  ;; configured at 8192 can never see `completion_tokens` reach its own cap.
  (let [parse (private-fn 'parse-arguments)
        ended-early? (fn [v] (:json-ended-early?
                              (ex-data (try (parse "decision_frame" v)
                                            (catch clojure.lang.ExceptionInfo e e)))))]
    (testing "input that simply ran out"
      (is (true? (ended-early? "{\"scope\": \"club shinshi\", \"facts\": [{\"id")))
      (is (true? (ended-early? "{\"scope\": [1,2,"))))
    (testing "a genuine syntax error is not truncation"
      (is (false? (ended-early? "{\"scope\": tru3}")))
      (is (false? (ended-early? "{\"a\": 1,, \"b\": 2}"))))
    (testing "not every truncation looks like one, and that is why it is not the only signal"
      ;; A cut landing between a key and its colon reads as an ordinary syntax
      ;; error. The token-count and finish_reason signals cover this shape.
      (is (false? (ended-early? "{\"a\": 1, \"b\": [{\"c\""))))))

(deftest a-tool-call-cut-off-by-the-budget-says-so
  (let [normalize (private-fn 'agent-result)
        cut {:id "call-1"
             :function {:name "decision_frame"
                        :arguments "{\"scope\": \"club shinshi\", \"facts\": [{\"id\": \"f1"}}]
    (testing "the failure is named for the budget, not the model"
      (let [error (try (normalize {:content "" :tool_calls [cut]}
                                  "tool_calls"
                                  {:completion_tokens 1024} 1024)
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= :provider/output-budget-exhausted (:type (ex-data error))))
        (is (= 1024 (:max-output-tokens (ex-data error))))
        (is (= 1024 (:completion-tokens (ex-data error))))))

    (testing "and everything the original error carried survives the rename"
      ;; The tool name and the offending string are the whole point of
      ;; `parse-arguments` keeping them; renaming the failure must not drop
      ;; them, or this trades one blind error for another.
      (let [error (try (normalize {:content "" :tool_calls [cut]}
                                  "length" {:completion_tokens 1024} 1024)
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= "decision_frame" (:tool-name (ex-data error))))
        (is (str/starts-with? (:arguments-sample (ex-data error)) "{\"scope\""))))

    (testing "a call the SERVER cut below our own cap is still the budget"
      ;; The case that made the count unreliable. api.murakumo.cloud enforces a
      ;; 2048-token ceiling of its own: measured 2026-08-28, requests for 3072,
      ;; 4096, 8192 and 16384 all returned completion_tokens 2048. An app
      ;; configured at 8192 therefore never sees its own cap reached, and if
      ;; the provider also words the stop as `tool_calls` -- four of six
      ;; measured streamed calls did -- both count and reason go quiet.
      (let [error (try (normalize {:content "" :tool_calls [cut]}
                                  "tool_calls"
                                  {:completion_tokens 2048} 8192)
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= :provider/output-budget-exhausted (:type (ex-data error)))
            "2048 of a requested 8192 is a server ceiling, not a model defect")
        (is (= 2048 (:completion-tokens (ex-data error)))
            "the spent count is reported, since it is not the requested one")))

    (testing "a malformed call inside its budget stays the model's problem"
      (let [malformed {:id "call-2"
                       :function {:name "decision_frame"
                                  :arguments "{\"scope\": tru3}"}}
            error (try (normalize {:content "" :tool_calls [malformed]}
                                  "tool_calls"
                                  {:completion_tokens 40} 2048)
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= :provider/invalid-tool-arguments (:type (ex-data error)))
            "40 of 2048 tokens with intact JSON framing is the model, not a cap")))

    (testing "an exhausted budget does not invent a failure for a call that parsed"
      ;; Running out of room after a COMPLETE tool call is not an error; the
      ;; reclassification must only ever rename one that already failed.
      (is (= {:path "README.md"}
             (get-in (normalize {:content ""
                                 :tool_calls
                                 [{:id "c" :function {:name "workspace_read"
                                                      :arguments "{\"path\":\"README.md\"}"}}]}
                                "length" {:completion_tokens 1024} 1024)
                     [:tool-calls 0 :input]))))))

(deftest the-request-cap-is-computed-once-for-wire-and-verdict
  ;; `agent-request-body` puts this on the wire and `agent-result` compares
  ;; `completion_tokens` against it. Two copies of the `or` chain would make the
  ;; comparison silently wrong the first time either default moved.
  (let [requested (private-fn 'requested-max-tokens)
        body (private-fn 'agent-request-body)]
    (is (= 1024 (requested {:id "p" :max-output-tokens 512}
                           {:max-output-tokens 1024})))
    (is (= 512 (requested {:id "p" :max-output-tokens 512} {}))
        "the provider default applies when the request names none")
    (is (= 2048 (requested {:id "p"} {}))
        "and the shipped default when neither does")
    (is (= (requested {:id "p" :kind :openai-compatible :max-output-tokens 512}
                      {:model "m" :messages []})
           (:max_tokens (body {:id "p" :kind :openai-compatible
                               :max-output-tokens 512}
                              {:model "m" :messages []})))
        "the wire and the verdict read the same number")))
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
      ;; NOT an IOException: a broken pipe used to be the example here, and it
      ;; stopped being "anything else" when the transport branch landed. A
      ;; failure that is not the transport at all is the case this asserts.
      (let [original (IllegalStateException. "a bug here")
            e (thrown original)]
        (is (identical? original e))))))

(deftest a-dropped-connection-is-not-a-fault-in-this-application
  ;; Measured 2026-08-19: a `Connection reset` two tool calls into a resident
  ;; tick was recorded as `:internal-error`, which is where a reader looks for
  ;; OUR bugs. It is a transport failure and belongs with the provider.
  (let [classify (private-fn 'timeout->typed)
        thrown (fn [error]
                 (try (classify error "http://example.invalid/chat")
                      (catch Exception caught caught)))]
    (testing "a reset is the provider's transport, not our code"
      (let [e (thrown (java.net.SocketException. "Connection reset"))]
        (is (= :provider/network-error (:type (ex-data e))))
        (testing "and which transport failure it was is kept"
          (is (= "java.net.SocketException" (:cause-class (ex-data e))))
          (is (str/includes? (.getMessage e) "Connection reset")))))
    (testing "a timeout is still a timeout, not swallowed by the IOException branch"
      ;; HttpTimeoutException IS an IOException, so the order matters here too.
      (let [e (thrown (java.net.http.HttpTimeoutException. "request timed out"))]
        (is (= :provider/timeout (:type (ex-data e))))))
    (testing "something that is not transport at all still passes through"
      (let [original (IllegalStateException. "a bug here")
            e (thrown original)]
        (is (identical? original e))))))
