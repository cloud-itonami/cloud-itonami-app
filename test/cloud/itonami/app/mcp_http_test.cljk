(ns cloud.itonami.app.mcp-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.oauth-resource :as oauth-resource]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def configuration
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0
            :public-origin "https://itonami.cloud"
            :webauthn-rp-id "itonami.cloud"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :agent-control {:fleet {:enabled? false}}
   :providers []
   :mcp {:allowed-origins []
         :oauth {:authorization-servers ["https://auth.itonami.cloud"]}}})

(defn- fixture []
  (assoc (store/initial-state) :identity
         {:organizations {"org-a" {:id "org-a" :organization-id "acme"}}
          :users {"user-a" {:id "user-a" :passkey-enrolled? true}}
          :memberships {"membership-a"
                        {:id "membership-a" :user-id "user-a"
                         :organization-id "org-a" :role :owner}}
          :sessions {} :tenant-connections {}}))

(defn- request [method path token body headers]
  (let [port (.getPort (.getAddress @server/server))
        builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:" port path)))]
    (doseq [[name value] headers] (.header builder name value))
    (when token (.header builder "Authorization" (str "Bearer " token)))
    (let [builder (case method
                    :get (.GET builder)
                    :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                          (json/write-str body))))
          response (.send (HttpClient/newHttpClient) (.build builder)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :headers (.map (.headers response))
       :body (when-not (empty? (.body response))
               (json/read-str (.body response) :key-fn keyword))})))

(def mcp-headers
  {"Accept" "application/json, text/event-stream"
   "Content-Type" "application/json"
   "MCP-Protocol-Version" "2025-11-25"})

(deftest streamable-http-mcp-is-authenticated-and-discoverable
  (let [previous @store/state
        temporary (java.nio.file.Files/createTempDirectory
                   "mcp-http-"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (reset! store/state (fixture))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/configure! (fn [_] nil)]
        (let [{:keys [token]} (identity/issue-session!
                              "user-a" {:kind :agent :label "mcp-http"})]
          (server/stop!)
          (server/start! configuration)
          (testing "RFC 9728 metadata names the exact MCP resource"
            (let [response (request :get
                                    "/.well-known/oauth-protected-resource/mcp"
                                    nil nil {})]
              (is (= 200 (:status response)))
              (is (= "https://itonami.cloud/mcp"
                     (get-in response [:body :resource])))
              (is (= ["https://auth.itonami.cloud"]
                     (get-in response [:body :authorization_servers])))))
          (testing "unauthenticated requests receive a discovery challenge"
            (let [response (request :post "/mcp" nil
                                    {"jsonrpc" "2.0" "id" 1
                                     "method" "initialize"}
                                    mcp-headers)]
              (is (= 401 (:status response)))
              (is (re-find #"oauth-protected-resource/mcp"
                           (first (get-in response
                                          [:headers "www-authenticate"]))))))
          (testing "initialize negotiates the stable protocol over JSON"
            (let [response
                  (request :post "/mcp" token
                           {"jsonrpc" "2.0" "id" 1
                            "method" "initialize"
                            "params" {"protocolVersion" "2025-11-25"}}
                           mcp-headers)]
              (is (= 200 (:status response)))
              (is (= "2025-11-25"
                     (get-in response [:body :result :protocolVersion])))
              (is (some #(= "tenant_repository_write" (:name %))
                        (get-in (request
                                 :post "/mcp" token
                                 {"jsonrpc" "2.0" "id" 2
                                  "method" "tools/list"}
                                 mcp-headers)
                                [:body :result :tools])))))
          (testing "notifications are accepted without an illegal response"
            (is (= 202
                   (:status
                    (request :post "/mcp" token
                             {"jsonrpc" "2.0"
                              "method" "notifications/initialized"}
                             mcp-headers)))))
          (testing "browser origins are validated even with a bearer token"
            (is (= 403
                   (:status
                    (request :post "/mcp" token
                             {"jsonrpc" "2.0" "id" 3
                              "method" "tools/list"}
                             (assoc mcp-headers "Origin"
                                    "https://evil.example"))))))
          (testing "an audience-bound OAuth token reaches the canonical tenant API"
            (with-redefs [oauth-resource/*introspect*
                          (fn [_ _]
                            {:active true :sub "user-a"
                             :client_id "codex"
                             :aud ["https://itonami.cloud/mcp"]
                             :scope (str "mcp:tools tenant:connect "
                                         "repository:read repository:write")})]
              (let [response
                    (request :post "/mcp" "external-oauth-token"
                             {"jsonrpc" "2.0" "id" 4
                              "method" "tools/call"
                              "params" {"name" "tenant_list"
                                        "arguments" {}}}
                             mcp-headers)]
                (is (= 200 (:status response)))
                (is (= "acme"
                       (get-in response
                               [:body :result :structuredContent
                                :tenants 0 :organization-id]))))))
          (testing "a tool call missing its narrower scope is an HTTP 403"
            (with-redefs [oauth-resource/*introspect*
                          (fn [_ _]
                            {:active true :sub "user-a"
                             :client_id "codex"
                             :aud ["https://itonami.cloud/mcp"]
                             :scope "mcp:tools"})]
              (let [response
                    (request :post "/mcp" "under-scoped-token"
                             {"jsonrpc" "2.0" "id" 5
                              "method" "tools/call"
                              "params" {"name" "tenant_list"
                                        "arguments" {}}}
                             mcp-headers)]
                (is (= 403 (:status response)))
                (is (re-find #"tenant:connect"
                             (first (get-in response
                                            [:headers "www-authenticate"])))))))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))
