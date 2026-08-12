(ns cloud.itonami.app.identity-lifecycle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(defn- seeded-state []
  (assoc (store/initial-state) :identity
         {:users {"user-1" {:id "user-1" :status :active
                            :passkey-enrolled? true
                            :authentication-roots #{[:google "subject-1"]}}
                  "user-2" {:id "user-2" :status :active
                            :passkey-enrolled? true}}
          :organizations {"org-1" {:id "org-1" :name "Personal"}}
          :memberships {"m-1" {:id "m-1" :user-id "user-1"
                                 :organization-id "org-1" :role :owner}
                        "m-2" {:id "m-2" :user-id "user-2"
                                 :organization-id "org-1" :role :member}}
          :login-identities
          {[:google "subject-1"]
           {:id [:google "subject-1"] :provider :google
            :subject "subject-1" :user-id "user-1"
            :email "one@example.test"}}
          :sessions {}}))

(defmacro with-identity-state [& body]
  `(let [previous# @store/state]
     (try
       (reset! store/state (seeded-state))
       ~@body
       (finally (reset! store/state previous#)))))

(defn- sso-session! [user-id provider]
  (let [issued (identity/issue-session!
                user-id {:kind :sso :issued-via :sso
                         :authn-provider provider
                         :authn-level :single-factor
                         :authn-decision :authenticated
                         :authn-factors [:oauth]})]
    [issued (identity/session (:token issued))]))

(deftest public-sso-client-needs-no-distributed-secret
  (with-redefs [identity/provider-config
                (fn [provider]
                  {:provider provider :name "Google" :client-id nil
                   :client-secret nil :configured? false})]
    (identity/configure!
     {:auth {:sso-providers [:google]
             :sso-clients {:google {:client-id "desktop-public-client"
                                    :public-client? true}}}})
    (let [config (identity/sso-provider-config :google)]
      (is (true? (:configured? config)))
      (is (= "desktop-public-client" (:client-id config)))
      (is (nil? (:client-secret config)))
      (is (not (str/includes?
                (#'cloud.itonami.app.identity/form-body
                 {:client_id (:client-id config)
                  :client_secret (:client-secret config)})
                "client_secret"))))))

(deftest github-web-flow-is-not-misreported-as-a-secretless-public-client
  (with-redefs [identity/provider-config
                (fn [provider]
                  {:provider provider :name "GitHub" :client-id nil
                   :client-secret nil :configured? false})]
    (identity/configure!
     {:auth {:sso-providers [:github]
             :sso-clients {:github {:client-id "public-looking-client"
                                    :public-client? true}}}})
    (is (false? (:configured? (identity/sso-provider-config :github))))))

(deftest sessions-are-user-scoped-and-revocable
  (with-identity-state
    (let [[first-issued current] (sso-session! "user-1" :google)
          [second-issued _] (sso-session! "user-1" :github)
          [foreign-issued _] (sso-session! "user-2" :google)
          listed (identity/user-sessions current)]
      (is (= #{(:session-id first-issued) (:session-id second-issued)}
             (set (map :id listed))))
      (is (not (str/includes? (pr-str listed) "token-digest")))
      (is (not (str/includes? (pr-str listed) "csrf")))
      (testing "another User's opaque id cannot be revoked"
        (try
          (identity/revoke-session! current (:session-id foreign-issued))
          (is false)
          (catch clojure.lang.ExceptionInfo error
            (is (= :identity/session-not-found (:type (ex-data error)))))))
      (identity/revoke-session! current (:session-id second-issued))
      (is (nil? (identity/session (:token second-issued))))
      (is (some? (identity/session (:token first-issued)))))))

(deftest unlink-keeps-at-least-one-login-root
  (with-identity-state
    (let [[_ current] (sso-session! "user-1" :google)]
      (is (true? (:unlinked
                  (identity/unlink-login-identity!
                   current {:provider "google" :subject "subject-1"}))))
      (is (empty? (get-in (store/snapshot) [:identity :login-identities]))))
    (store/transact!
     (fn [state]
       (-> state
           (assoc-in [:identity :users "user-1" :passkey-enrolled?] false)
           (assoc-in [:identity :login-identities [:google "subject-1"]]
                     {:id [:google "subject-1"] :provider :google
                      :subject "subject-1" :user-id "user-1"}))))
    (let [[_ current] (sso-session! "user-1" :google)]
      (try
        (identity/unlink-login-identity!
         current {:provider :google :subject "subject-1"})
        (is false)
        (catch clojure.lang.ExceptionInfo error
          (is (= :identity/last-login-method (:type (ex-data error)))))))))

(defn- central-state []
  (->> (get-in (store/snapshot) [:identity :central-auth-transactions])
       (keep (fn [[state transaction]] (when-not (:used? transaction) state)))
       first))

(defn- finish-central! [subject]
  (let [state (central-state)]
    (with-redefs-fn
      {#'cloud.itonami.app.identity/central-exchange-code!
       (fn [_ _ code]
         (is (= "one-time-code" code))
         {:access_token "central-access-token"})
       #'cloud.itonami.app.identity/central-userinfo!
       (fn [_ token]
         (is (= "central-access-token" token))
         {:iss "https://auth.itonami.cloud"
          :sub subject
          :client_id "cloud-itonami-app-native"
          :scope "identity:read"
          :acr "phishing-resistant"
          :amr ["webauthn"]})}
      #(identity/complete-central-authentication!
        {:state state :code "one-time-code"}))))

(deftest central-auth-pkce-bootstraps-once-and-never-persists-its-token
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (identity/configure! {})
      (let [started (identity/start-central-authentication! nil)
            state (central-state)
            transaction (get-in (store/snapshot)
                                [:identity :central-auth-transactions state])]
        (is (str/starts-with? (:url started)
                              "https://auth.itonami.cloud/authorize?"))
        (is (str/includes? (:url started) "code_challenge_method=S256"))
        (is (= "http://127.0.0.1:1338/api/auth/itonami/callback"
               (:redirect-uri transaction)))
        (is (not (str/includes? (pr-str transaction) "access-token")))
        (let [finished (finish-central! "did:web:kotobase.net:person:one")
              session (identity/session (:token finished))]
          (is (= :federated (:kind session)))
          (is (= :itonami-cloud (:issued-via session)))
          (is (true? (identity/may-act? session)))
          (is (not (str/includes? (pr-str (store/snapshot))
                                  "central-access-token")))
          (try
            (identity/complete-central-authentication!
             {:state state :code "one-time-code"})
            (is false "state replay must fail")
            (catch clojure.lang.ExceptionInfo error
              (is (= :central-auth/invalid-state (:type (ex-data error))))))))
      (testing "an unbound DID cannot take over an existing install"
        (identity/start-central-authentication! nil)
        (try
          (finish-central! "did:web:kotobase.net:person:two")
          (is false "existing installs require an authenticated link")
          (catch clojure.lang.ExceptionInfo error
            (is (= :central-auth/link-required (:type (ex-data error)))))))
      (finally
        (reset! store/state previous)))))
