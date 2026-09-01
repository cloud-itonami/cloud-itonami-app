(ns cloud.itonami.app.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.app-client :as client]
            [cloud.itonami.app.bot-tools :as bot-tools]
            [cloud.itonami.app.cli :as cli]
            [cloud.itonami.app.west-kotoba-refactor :as west-refactor]))

(deftest tenant-cli-is-an-http-client-of-the-versioned-api
  (let [calls (atom [])
        fake-call (fn [_ method path request]
                    (swap! calls conj [method path request])
                    {:status 200 :body {:ok true}})]
    (with-redefs [agent-session/session-token (constantly "agent-token")
                  client/call fake-call]
      (is (= {:ok true}
             (cli/run {} ["tenant" "connect" "--tenant" "acme"
                          "--cap" "workspace.read,actor.invoke"
                          "--ttl-seconds" "600"
                          "--idempotency-key" "loop-1"])))
      (is (= :post (ffirst @calls)))
      (is (= "/v1/tenant-connections" (second (first @calls))))
      (is (= ["workspace.read" "actor.invoke"]
             (get-in (first @calls) [2 :body :capabilities])))
      (is (= "agent-token" (get-in (first @calls) [2 :token]))))))

(deftest tenant-cli-requires-an-explicit-connection-handle
  (with-redefs [agent-session/session-token (constantly "agent-token")]
    (is (= :cli/missing-connection
           (:type
            (ex-data
             (try (cli/run {} ["tenant" "status"])
                  (catch clojure.lang.ExceptionInfo e e))))))))

(deftest hosted-api-url-is-shared-by-cli-and-mcp-clients
  (binding [client/*environment*
            (fn [name]
              (when (= name "CLOUD_ITONAMI_API_URL")
                "https://itonami.cloud/"))]
    (is (= "https://itonami.cloud" (client/base-url {}))))
  (binding [client/*environment*
            (fn [name]
              (when (= name "CLOUD_ITONAMI_API_URL")
                "http://itonami.cloud"))]
    (is (= :app-client/insecure-api-url
           (:type (ex-data
                   (try (client/base-url {})
                        (catch clojure.lang.ExceptionInfo e e))))))))

(deftest bots-cli-submits-a-bounded-long-running-task
  (let [seen (atom nil)]
    (with-redefs [client/request-with-timeout!
                  (fn [_ method path seconds body]
                    (reset! seen [method path seconds body])
                    {:messages [{:text "done"}]})]
      (is (= {:messages [{:text "done"}]}
             (cli/run {} ["bots" "task" "--id" "bot-1"
                          "--text" "repo を確認して"])))
      (is (= [:post "/api/agent-bots/bot-1/messages" 660
              {:text "repo を確認して"}]
             @seen)))))

(deftest bots-cli-selects-only-the-inference-route
  (let [seen (atom nil)]
    (with-redefs [client/request!
                  (fn [_ method path body]
                    (reset! seen [method path body])
                    {:bot {:id "bot-1"
                           :provider-id "murakumo"
                           :model "qwen3.8-27b-throughput-5090"}})]
      (is (= "qwen3.8-27b-throughput-5090"
             (get-in (cli/run {} ["bots" "model"
                                  "--id" "bot-1"
                                  "--provider" "murakumo"
                                  "--model" "qwen3.8-27b-throughput-5090"])
                     [:bot :model])))
      (is (= [:post "/api/agent-bots/bot-1/model"
              {:provider-id "murakumo"
               :model "qwen3.8-27b-throughput-5090"}]
             @seen)))))

(deftest hermes-cli-keeps-profile-and-run-wire-shapes
  (let [seen (atom [])]
    (with-redefs [client/request!
                  (fn
                    ([_ method path]
                     (swap! seen conj [method path])
                     {:object "list"})
                    ([_ method path body]
                     (swap! seen conj [method path body])
                     {:run_id "run-1" :status "started"}))]
      (is (= "list" (:object (cli/run {} ["hermes" "profile" "list"]))))
      (is (= "list" (:object (cli/run {} ["hermes" "session" "list"
                                           "--profile" "bot/one"]))))
      (is (= "started"
             (:status (cli/run {} ["hermes" "run" "--profile" "bot/one"
                                   "--input" "inspect" "--goal" "true"]))))
      (is (= [[:get "/api/profiles"]
              [:get "/p/bot%2Fone/api/sessions"]
              [:post "/p/bot%2Fone/v1/runs"
               {:input "inspect" :goal true}]]
             @seen)))))

(deftest west-refactor-inspection-does-not-start-the-server
  (is (false? (cli/needs-server? ["bots" "refactor" "scan" "--root" "/tmp/ws"])))
  (is (false? (cli/needs-server? ["bots" "refactor" "inspect" "--root" "/tmp/ws"
                                  "--repo" "example"])))
  (is (true? (cli/needs-server? ["bots" "refactor" "start" "--root" "/tmp/ws"
                                 "--repo" "example" "--id" "bot-1"]))))

(deftest west-refactor-start-requires-the-exact-admitted-repository
  (let [inspection {:project {:name "example" :checkout "/tmp/example"}
                    :candidates [{:path "src/core.clj" :bytes 20}]
                    :verification ["clojure -M:test"]}]
    (with-redefs [west-refactor/inspect-project (fn [& _] inspection)
                  cli/bot-list (fn [_] {:bots [{:id "bot-1" :coding? true
                                                :workspace "/tmp/another"
                                                :virtual-shell-ready? true}]})]
      (is (= :west-refactor/workspace-mismatch
             (:type (ex-data (try (cli/bot-refactor-start
                                   {} {:id "bot-1" :repo "example" :root "/tmp/ws"})
                                  (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest west-refactor-start-submits-the-fixed-contract
  (let [seen (atom nil)
        inspection {:project {:name "example" :checkout "/tmp/example"}
                    :candidates [{:path "src/core.clj" :bytes 20}]
                    :verification ["clojure -M:test"]}]
    (with-redefs [west-refactor/inspect-project (fn [& _] inspection)
                  cli/bot-list (fn [_] {:bots [{:id "bot-1" :coding? true
                                                :workspace "/tmp/example"
                                                :virtual-shell-ready? true}]})
                  client/request-with-timeout!
                  (fn [_ method path seconds body]
                    (reset! seen [method path seconds body])
                    {:accepted true})]
      (is (= {:accepted true}
             (cli/bot-refactor-start {} {:id "bot-1" :repo "example" :root "/tmp/ws"})))
      (is (= :post (first @seen)))
      (is (= "/api/agent-bots/bot-1/messages" (second @seen)))
      (is (re-find #"parity test" (get-in @seen [3 :text]))))))

(deftest the-agent-surface-can-make-a-registry-edit-live
  ;; Owner directive 2026-08-18. Before it, an objective edited in
  ;; loop-yakuwari could not reach a running Bot without a person opening the
  ;; browser, and this installation had been running a three-day-old projection
  ;; for exactly that reason -- the registry said one thing and 70 Bots carried
  ;; another, with nothing reporting the gap.
  (testing "workforce_provision posts to the agent surface, not the browser one"
    (let [seen (atom nil)]
      (with-redefs [client/request-with-timeout!
                    (fn [_ method path seconds body]
                      (reset! seen [method path seconds body])
                      {:businesses 8 :bots 70})]
        (bot-tools/call-tool {} "workforce_provision" {})
        (is (= [:post "/api/agent-bots/workforce/provision" 120 {}] @seen)
            "/api/bots/... is the browser surface and keeps its CSRF check"))))
  (testing "the tool is advertised, or nothing can call it"
    (is (bot-tools/tool? "workforce_provision")))
  (testing "creating a Bot and widening a grant did NOT move"
    ;; The whole argument for letting provisioning cross is that the caller
    ;; names nothing. These do name things, so they stay where they were.
    (is (not (bot-tools/tool? "bot_create")))
    (is (not (bot-tools/tool? "bot_update")))))

(deftest bots-cli-reads-the-resident-workforce-without-configuring-it
  (let [seen (atom nil)]
    (with-redefs [client/request!
                  (fn [_ method path]
                    (reset! seen [method path])
                    {:businesses 8 :bots 70 :enabled 70})]
      (is (= {:businesses 8 :bots 70 :enabled 70}
             (cli/run {} ["bots" "workforce"])))
      (is (= [:get "/api/agent-bots/workforce"] @seen)))))

(deftest a-failure-reaches-the-operator-under-the-name-it-was-recorded-with
  ;; Both directions, and the literal is pinned: this assertion exists to fail
  ;; when `provider/http-error` reaches an operator as `http-error`, which is
  ;; what it did until 2026-08-30. `bots workforce` reported
  ;; `{"http-error": 40}` for 40 runs whose recorded type was
  ;; `:provider/http-error`, and nothing in that answer said whether the model
  ;; provider or this application had refused.
  (testing "the namespace survives the printer, as a map key"
    (is (= "{\"counts\":{\"provider/http-error\":40}}"
           (json/write-str (cli/qualified-names {:counts {:provider/http-error 40}})
                           :escape-slash false))))
  (testing "and as a map value"
    (is (= "{\"outcome\":\"provider/timeout\"}"
           (json/write-str (cli/qualified-names {:outcome :provider/timeout})
                           :escape-slash false))))
  (testing "and inside a vector, which :key-fn and :value-fn do not reach"
    (is (= "{\"seen\":[\"provider/timeout\",\"internal-error\"]}"
           (json/write-str (cli/qualified-names
                            {:seen [:provider/timeout :internal-error]})
                           :escape-slash false))))
  (testing "a keyword with no namespace is unchanged, so this is not a rename"
    (is (= "{\"window\":50}"
           (json/write-str (cli/qualified-names {:window 50})))))
  (testing "the printer without it is the defect, stated so the test can fail"
    (is (= "{\"counts\":{\"http-error\":40}}"
           (json/write-str {:counts {:provider/http-error 40}}))
        "clojure.data.json renders a keyword as its name alone; that is why qualified-names exists")))
