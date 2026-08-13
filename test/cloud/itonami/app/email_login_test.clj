(ns cloud.itonami.app.email-login-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
   :providers [{:id "ollama" :kind :ollama :local? true :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]
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

(defn- with-server
  ([deliveries body] (with-server deliveries configuration body))
  ([deliveries server-configuration body]
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
        (server/start! server-configuration)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous))))))

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

(deftest verified-email-can-create-a-personal-user-when-signup-is-enabled
  (let [deliveries (atom [])]
    (with-server
      deliveries
      (assoc configuration :auth {:allow-signup? true :sso-providers []})
      (fn []
        (is (= 202 (:status (post! "/api/email-authenticate/start"
                                  {:email "new@example.jp"}))))
        (let [token (second
                     (re-find #"#email-login=(.+)$"
                              (:magic-link (first @deliveries))))
              finish (post! "/api/email-authenticate/finish" {:token token})]
          (is (= 200 (:status finish)))
          (is (true? (get-in finish [:body :authenticated?])))
          (is (true? (get-in finish [:body :may-act?])))
          (is (= "email" (get-in finish [:body :session :kind])))
          (is (= "new@example.jp" (get-in finish [:body :user :contact-email])))
          (is (false? (get-in finish [:body :user :passkey-enrolled?])))
          (is (= "personal" (get-in finish [:body :organization :kind]))))))))

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

;; ---------------------------------------------------------------------------
;; the shipped profile
;; ---------------------------------------------------------------------------
;;
;; ADR-0012 left delivery to "a deployment-owned HTTPS endpoint" and no such
;; endpoint existed, so `:email-login` stayed disabled and the form was never
;; shown: a complete feature nothing could reach. `profiles/itonami.edn` is what
;; makes it reachable, which means a typo in that file reproduces exactly the
;; state it was written to end -- and reproduces it silently, because a disabled
;; feature looks identical to a feature nobody used.

(defn- profile [name]
  (edn/read-string (slurp (io/file "profiles" (str name ".edn")))))

(deftest the-shipped-defaults-keep-email-sign-in-off
  (let [defaults (edn/read-string
                  (slurp (io/resource "cloud-itonami-app.defaults.edn")))]
    (is (false? (get-in defaults [:email-login :enabled?]))
        "a tenant-neutral install must not mail through somebody else's sending reputation")
    (is (nil? (get-in defaults [:email-login :delivery-endpoint])))))

(deftest the-itonami-profile-actually-turns-delivery-on
  (let [p (profile "itonami")]
    (is (true? (get-in p [:email-login :enabled?])))
    (testing "the endpoint passes the adapter's own safety rule"
      ;; `configured?` also requires the bearer, which a JVM cannot put into its
      ;; own environment. Naming the env var is what the profile is responsible
      ;; for; holding the secret is the operator's.
      (is (email-login/configured?
           (assoc-in p [:email-login :access-token-env] "PATH"))
          "https endpoint, no userinfo, no fragment"))
    (is (= "CLOUD_ITONAMI_EMAIL_LOGIN_TOKEN"
           (get-in p [:email-login :access-token-env])))))

(deftest a-profile-cannot-smuggle-a-plaintext-token
  ;; The adapter reads the bearer from the environment at call time. A profile
  ;; that carried the secret itself would put it in git and in every backup.
  (doseq [name ["itonami" "gftd"]]
    (is (nil? (get-in (profile name) [:email-login :access-token]))
        name)))
