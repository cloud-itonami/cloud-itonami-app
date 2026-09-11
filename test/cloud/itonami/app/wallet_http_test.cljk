(ns cloud.itonami.app.wallet-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "wallet-csrf")
(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :wallet {:chains [{:chain-id 1 :name "Ethereum"}]}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434"
                :reviewed? true :enabled? true}]})
(defonce ^:private client (HttpClient/newHttpClient))
(defonce ^:private current-session (atom nil))

(defn- call [method path body with-csrf?]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:"
                                      (.getPort (.getAddress @server/server)) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin))
        builder (if with-csrf?
                  (.header builder "X-CLOUD-ITONAMI-CSRF" csrf)
                  builder)
        request (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-wallet-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (reset! current-session {:csrf csrf :user-id "alice" :organization-id "org-1"
                               :kind :passkey :authn-level :phishing-resistant})
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] @current-session)
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (f) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(deftest wallet-snapshot-declares-the-custody-boundary
  (with-server
    (fn []
      (let [response (call :get "/api/wallet" nil false)]
        (is (= 200 (:status response)))
        (is (= "passkey-smart-account" (get-in response [:body :custody])))
        (is (= "EIP-6963 / EIP-1193 (optional)"
               (get-in response [:body :external-wallet-provider])))
        (is (false? (get-in response [:body :private-keys-stored?])))))))

(deftest wallet-snapshot-resolves-public-bots-through-the-owner-gate
  (with-server
    (fn []
      (let [created (bots/create! config @current-session
                                  {:name "owned-wallet-bot" :connectors []})
            response (call :get "/api/wallet" nil false)]
        (is (= 200 (:status response)))
        (is (= (:bot/id created) (get-in response [:body :bots 0 :id])))
        (is (= "passkey-required"
               (get-in response [:body :bots 0 :wallet :status])))))))

(deftest wallet-writes-require-csrf
  (with-server
    (fn []
      (testing "another origin cannot silently bind an address"
        (is (= 403 (:status
                   (call :post "/api/wallet/connect/start"
                         {:address "0x0000000000000000000000000000000000000001"
                          :chain-id 1}
                         false))))))))

(deftest owner-plan-is-human-csrf-gated-and-does-not-claim-submission-readiness
  (with-server
    (fn []
      (store/transact!
       (fn [state]
         (-> state
             (assoc-in [:identity :users "alice"]
                       {:id "alice" :principal-id "urn:kotoba:principal:alice"})
             (assoc-in [:identity :passkeys "cred-initial"]
                       {:id "cred-initial" :credential-id "cred-initial"
                        :user-id "alice"
                        :public-key-b64
                        "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
                        :user-verified? true :rp-id "localhost"
                        :registration-origin origin
                        :created-at "2026-08-28T00:00:00Z"})
             (assoc-in [:identity :passkeys "cred-kotobase"]
                       {:id "cred-kotobase" :credential-id "cred-kotobase"
                        :user-id "alice"
                        :public-key-b64
                        "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E"
                        :user-verified? true :rp-id "kotobase.net"
                        :registration-origin "https://auth.kotobase.net"
                        :created-at "2026-08-28T00:01:00Z"}))))
      (is (= 403 (:status
                  (call :post "/api/wallet/owners/plan"
                        {:credential-id "cred-kotobase"} false))))
      (let [response (call :post "/api/wallet/owners/plan"
                           {:credential-id "cred-kotobase"} true)]
        (is (= 200 (:status response)))
        (is (= "kotobase.net"
               (get-in response [:body :candidate-owner :rp-id])))
        (is (= "awaiting-current-owner-authorization"
               (get-in response [:body :status])))
        (is (false? (get-in response [:body :user-operation-ready?])))
        (is (.startsWith ^String
                         (get-in response [:body :contract-call :calldata])
                         "0x2c2abd1e")))
      (testing "the state-changing path has its own CSRF gate"
        (is (= 403 (:status
                    (call :post "/api/wallet/owners/authorize/start"
                          {:credential-id "cred-kotobase" :chain-id 1}
                          false))))
        (is (= 503 (:status
                    (call :post "/api/wallet/owners/authorize/start"
                          {:credential-id "cred-kotobase" :chain-id 1}
                          true))))
        (is (= "user-operation-not-configured"
               (get-in (call :post "/api/wallet/owners/authorize/start"
                             {:credential-id "cred-kotobase" :chain-id 1}
                             true)
                       [:body :error :type])))))))

(deftest agent-sessions-cannot-manage-wallets
  (with-server
    (fn []
      (reset! current-session {:user-id "alice" :organization-id "org-1"
                               :kind :agent})
      (is (= 403 (:status (call :get "/api/wallet" nil false)))))))
