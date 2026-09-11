(ns cloud.itonami.app.tenant-connection-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin "http://localhost:1338"
            :webauthn-rp-id "localhost"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers []})

(def session {:id "agent-session" :kind :agent :label "loop"
              :user-id "user-a" :membership-id "membership-a"
              :organization-id "org-a"})

(defn- fixture-state []
  (assoc (store/initial-state) :identity
         {:organizations {"org-a" {:id "org-a" :organization-id "acme"
                                    :name "Acme"}}
          :users {"user-a" {:id "user-a" :passkey-enrolled? true}}
          :memberships {"membership-a" {:id "membership-a" :user-id "user-a"
                                         :organization-id "org-a" :role :owner}}
          :tenant-connections {}}))

(defn- request [method path body]
  (let [port (.getPort (.getAddress @server/server))
        builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" port path)))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" "Bearer test-token"))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                      (json/write-str (or body {})))))
        response (.send (HttpClient/newHttpClient) (.build built)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(deftest versioned-api-creates-a-pending-tenant-bound-connection
  (let [previous @store/state
        temporary (java.nio.file.Files/createTempDirectory
                   "tenant-connection-http-"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (reset! store/state (fixture-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [token] (when (= token "test-token") session))
                    identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (let [tenants (request :get "/v1/tenants" nil)
              created (request :post "/v1/tenant-connections"
                               {:tenant_id "acme"
                                :capabilities ["workspace.read"]
                                :ttl_seconds 600
                                :idempotency_key "http-loop"})
              id (get-in created [:body :id])
              fetched (request :get (str "/v1/tenant-connections/" id) nil)]
          (is (= 200 (:status tenants)))
          (is (= "acme" (get-in tenants [:body :tenants 0 :organization-id])))
          (is (= 202 (:status created)))
          (is (= "pending-approval" (get-in created [:body :status])))
          (is (= id (get-in fetched [:body :id])))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))
