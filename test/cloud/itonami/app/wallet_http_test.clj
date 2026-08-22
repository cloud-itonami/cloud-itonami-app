(ns cloud.itonami.app.wallet-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
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
        (is (= "external-wallet" (get-in response [:body :custody])))
        (is (false? (get-in response [:body :private-keys-stored?])))))))

(deftest wallet-writes-require-csrf
  (with-server
    (fn []
      (testing "another origin cannot silently bind an address"
        (is (= 403 (:status
                   (call :post "/api/wallet/connect/start"
                         {:address "0x0000000000000000000000000000000000000001"
                          :chain-id 1}
                         false))))))))

(deftest agent-sessions-cannot-manage-wallets
  (with-server
    (fn []
      (reset! current-session {:user-id "alice" :organization-id "org-1"
                               :kind :agent})
      (is (= 403 (:status (call :get "/api/wallet" nil false)))))))
