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
