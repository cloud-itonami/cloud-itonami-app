(ns cloud.itonami.app.email-login-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.email-login :as email-login]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private configuration
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :identity {:account-domain "cloud-itonami.app"}
   :email-login {:enabled? true
                 :delivery-endpoint "https://mailer.example.test/login"
                 :access-token-env "TEST_EMAIL_LOGIN_TOKEN"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]
   :business {:workspace-root nil}})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- bound-port [] (.getPort (.getAddress @server/server)))

(defn- post! [path body]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" (bound-port) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin)
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str body)))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :headers (.map (.headers response))
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- with-server [deliveries body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-email-login-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (store/transact!
       assoc :identity
       {:users {"user-1" {:id "user-1" :display-name "Owner"
                           :email "owner@cloud-itonami.app"
                           :contact-email "owner@example.jp"
                           :status :active :passkey-enrolled? true}}
        :organizations {"org-1" {:id "org-1" :name "Personal"}}
        :memberships {"m-1" {:id "m-1" :user-id "user-1"
                              :organization-id "org-1" :role :owner}}
        :passkeys {"credential-1" {:id "credential-1" :user-id "user-1"}}
        :sessions {}})
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    email-login/configured? (constantly true)
                    email-login/deliver! (fn [_ delivery]
                                           (swap! deliveries conj delivery)
                                           true)
                    identity/provider-config
                    (fn [provider]
                      {:provider provider :name (name provider)
                       :configured? false :scopes []})]
        (server/stop!)
        (server/start! configuration)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))

(deftest email-link-is-private-expiring-single-use-and-session-rooted
  (let [deliveries (atom [])]
    (with-server
      deliveries
      (fn []
        (testing "start has the same public answer for known and unknown users"
          (is (= 202 (:status (post! "/api/email-authenticate/start"
                                    {:email "nobody@example.jp"}))))
          (is (empty? @deliveries))
          (is (= 202 (:status (post! "/api/email-authenticate/start"
                                    {:email "OWNER@example.jp"}))))
          (is (= 1 (count @deliveries)))
          (is (= 202 (:status (post! "/api/email-authenticate/start"
                                    {:email "owner@example.jp"}))))
          (is (= 1 (count @deliveries))
              "the cooldown cannot be used to mail-bomb a known address"))
        (let [link (:magic-link (first @deliveries))
              token (second (re-find #"#email-login=(.+)$" link))
              persisted (pr-str (store/snapshot))]
          (is (= "owner@example.jp" (:to (first @deliveries))))
          (is (string? token))
          (is (not (str/includes? persisted token))
              "state stores only the token digest")
          (let [finish (post! "/api/email-authenticate/finish" {:token token})]
            (is (= 200 (:status finish)))
            (is (= "email" (get-in finish [:body :session :kind])))
            (is (= "email-magic-link"
                   (get-in finish [:body :session :issued-via])))
            (is (= "single-factor"
                   (get-in finish [:body :session :authn-level])))
            (is (true? (get-in finish [:body :may-act?])))
            (is (some #(str/starts-with? % "cloud_itonami_identity=")
                      (get-in finish [:headers "set-cookie"]))))
          (is (= 400 (:status
                      (post! "/api/email-authenticate/finish" {:token token})))
              "the same link cannot mint a second session"))))))

(deftest email-does-not-bootstrap-an-unrooted-user
  (let [deliveries (atom [])]
    (with-server
      deliveries
      (fn []
        (store/transact!
         (fn [state]
           (-> state
               (assoc-in [:identity :users "user-1" :status] :pending-passkey)
               (assoc-in [:identity :users "user-1" :passkey-enrolled?] false))))
        (is (= 202 (:status (post! "/api/email-authenticate/start"
                                  {:email "owner@example.jp"}))))
        (is (empty? @deliveries)
            "email is a sign-in proof, not a replacement identity root")))))

(deftest expired-and-forged-email-proofs-fail-closed
  (let [deliveries (atom [])]
    (with-server
      deliveries
      (fn []
        (is (false? (identity/may-act? {:kind :email :user-id "user-1"}))
            "a caller cannot label its own session as email-authenticated")
        (post! "/api/email-authenticate/start" {:email "owner@example.jp"})
        (let [token (second
                     (re-find #"#email-login=(.+)$"
                              (:magic-link (first @deliveries))))
              transaction-id
              (-> (store/snapshot) :identity :email-login-transactions keys first)]
          (store/transact!
           assoc-in [:identity :email-login-transactions transaction-id
                     :identity.email-challenge/expires-at]
           0)
          (is (= 400 (:status
                      (post! "/api/email-authenticate/finish" {:token token}))))
          (is (empty? (get-in (store/snapshot) [:identity :sessions]))))))))
