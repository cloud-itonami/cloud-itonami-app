(ns cloud.itonami.app.cli-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.app-client :as client]
            [cloud.itonami.app.cli :as cli]))

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

(deftest bots-cli-reads-the-resident-workforce-without-configuring-it
  (let [seen (atom nil)]
    (with-redefs [client/request!
                  (fn [_ method path]
                    (reset! seen [method path])
                    {:businesses 8 :bots 70 :enabled 70})]
      (is (= {:businesses 8 :bots 70 :enabled 70}
             (cli/run {} ["bots" "workforce"])))
      (is (= [:get "/api/agent-bots/workforce"] @seen)))))
