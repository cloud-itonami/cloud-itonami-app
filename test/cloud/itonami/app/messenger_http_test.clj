(ns cloud.itonami.app.messenger-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.organism-gateway :as organism-gateway]
            [cloud.itonami.app.organism-messenger-transport :as organism-transport]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "messenger-http-csrf")

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- bound-port [] (.getPort (.getAddress @server/server)))

(defn- request [method path body]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" (bound-port) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin)
                    (.header "X-CLOUD-ITONAMI-CSRF" csrf))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                      (json/write-str (or body {})))))
        response (.send client (.build built) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- ao-request [method path body]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" (bound-port) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" "Bearer ao-token"))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                      (json/write-str (or body {})))))
        response (.send client (.build built) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- context []
  {:active-organization-id "organization-record-acme"
   :organization
   {:id "organization-record-acme"
    :organization-id "acme"
    :users [{:id "human:alice" :display-name "Alice"}
            {:id "human:bob" :display-name "Bob"}]}})

(defn- with-server [actor body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-messenger-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state
              (assoc (store/initial-state) :identity
                     {:organizations
                      {"organization-record-acme"
                       {:id "organization-record-acme" :organization-id "acme"}}
                      :users {"human:alice" {:id "human:alice" :display-name "Alice"}
                              "human:bob" {:id "human:bob" :display-name "Bob"}}
                      :memberships
                      {"membership-alice" {:id "membership-alice"
                                           :organization-id "organization-record-acme"
                                           :user-id "human:alice"}
                       "membership-bob" {:id "membership-bob"
                                         :organization-id "organization-record-acme"
                                         :user-id "human:bob"}}}))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session
                    (fn [_] {:id (str @actor "-session")
                             :csrf csrf :user-id @actor :kind :passkey})
                    local-identity/public-state (fn [_] (context))
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)
                    agent-session/sessions (fn [] [])
                    organism-gateway/directory
                    (fn [_] {:items [{:ao.worker/id "ao:acme:reviewer"
                                      :ao.worker/subject "did:key:reviewer"
                                      :ao.worker/status :active}]})
                    organism-transport/authenticate
                    (fn [token]
                      (when (= token "ao-token")
                        {:organization "acme" :worker-id "ao:acme:reviewer"
                         :principal "organism:ao:acme:reviewer"}))]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))

(deftest mailbox-admission-is-enforced-over-http
  (let [actor (atom "human:alice")]
    (with-server actor
      (fn []
        (let [created (request :post "/api/messenger/conversations"
                               {:kind "direct" :title "Alice and Bob"
                                :members ["human:bob"]})
              conversation-id (get-in created [:body :id])]
          (is (= 201 (:status created)))
          (is (string? conversation-id))
          (is (= 202 (:status
                      (request :post
                               (str "/api/messenger/conversations/"
                                    conversation-id "/messages")
                               {:content "untrusted body"}))))
          (reset! actor "human:bob")
          (testing "unknown sender content is absent from the recipient projection"
            (is (= [] (get-in (request :get
                                       (str "/api/messenger/conversations/"
                                            conversation-id "/messages") nil)
                              [:body :items])))
            (let [quarantine (request :get "/api/messenger/quarantine" nil)]
              (is (= 1 (get-in quarantine [:body :count])))
              (is (false? (get-in quarantine
                                  [:body :items 0 :content-exposed?])))
              (is (nil? (get-in quarantine [:body :items 0 :content])))))
          (testing "an exact allow promotes that sender's quarantined delivery"
            (is (= 200 (:status (request :post "/api/messenger/trust"
                                         {:sender-id "human:alice"
                                          :allowed? true}))))
            (is (= "untrusted body"
                   (get-in (request :get
                                    (str "/api/messenger/conversations/"
                                         conversation-id "/messages") nil)
                           [:body :items 0 :content])))))))))

(deftest external-organism-polls-acks-and-replies-as-its-own-mailbox
  (let [actor (atom "human:alice")]
    (with-server actor
      (fn []
        (let [created (request :post "/api/messenger/conversations"
                               {:kind "direct" :title "Alice and reviewer"
                                :members ["organism:ao:acme:reviewer"]})
              conversation-id (get-in created [:body :id])]
          (is (= 201 (:status created)))
          (request :post (str "/api/messenger/conversations/" conversation-id "/messages")
                   {:content "review this"})
          (is (= 200 (:status (ao-request :post "/api/ao/messenger/trust"
                                          {:sender-id "human:alice" :allowed? true}))))
          (let [polled (ao-request :get "/api/ao/messenger/poll?limit=20" nil)
                message-id (get-in polled [:body :items 0 :id])]
            (is (= "review this" (get-in polled [:body :items 0 :content])))
            (is (= 1 (get-in (ao-request :post "/api/ao/messenger/ack"
                                         {:message-ids [message-id]})
                             [:body :acknowledged]))))
          (request :post "/api/messenger/trust"
                   {:sender-id "organism:ao:acme:reviewer" :allowed? true})
          (is (= 202 (:status
                      (ao-request :post
                                  (str "/api/ao/messenger/conversations/"
                                       conversation-id "/messages")
                                  {:content "review complete"}))))
          (is (= "review complete"
                 (-> (request :get
                              (str "/api/messenger/conversations/"
                                   conversation-id "/messages") nil)
                     :body :items last :content))))))))
