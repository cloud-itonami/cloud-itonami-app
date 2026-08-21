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

(defn- finish-central!
  ([subject] (finish-central! subject {:acr "phishing-resistant" :amr ["webauthn"]}))
  ([subject assurance] (finish-central! subject assurance nil))
  ([subject assurance callback-session]
  (let [state (central-state)]
    (with-redefs-fn
      {#'cloud.itonami.app.identity/central-exchange-code!
       (fn [_ _ code]
         (is (= "one-time-code" code))
         {:access_token "central-access-token"})
       #'cloud.itonami.app.identity/central-userinfo!
       (fn [_ token]
         (is (= "central-access-token" token))
         (merge {:iss "https://auth.itonami.cloud"
          :sub subject
          :client_id "cloud-itonami-app-native"
          :scope "identity:read"}
                assurance))}
      #(identity/complete-central-authentication!
        {:state state :code "one-time-code"} callback-session)))))

(deftest central-auth-pkce-bootstraps-once-and-never-persists-its-token
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (identity/configure! {})
      (let [started (identity/start-central-authentication! nil "http://localhost:1338")
            state (central-state)
            transaction (get-in (store/snapshot)
                                [:identity :central-auth-transactions state])]
        (is (str/starts-with? (:url started)
                              "https://auth.itonami.cloud/authorize?"))
        (is (str/includes? (:url started) "code_challenge_method=S256"))
        ;; The callback belongs to the origin the person is ON. It was pinned
        ;; to 127.0.0.1 while this app serves localhost, so the session was
        ;; created in a cookie jar the app could not read and every following
        ;; request was rejected by `require-origin!`. That is what "signin does
        ;; not work" was.
        (is (= "http://localhost:1338/api/auth/itonami/callback"
               (:redirect-uri transaction)))
        (is (not (str/includes? (:url started) "127.0.0.1"))
            "the callback must not point at a different origin than the app")
        (is (not (str/includes? (pr-str transaction) "access-token")))
        (let [finished (finish-central! "did:web:kotobase.net:person:one")
              session (identity/session (:token finished))]
          (is (= :federated (:kind session)))
          (is (= :itonami-cloud (:issued-via session)))
          (is (true? (identity/may-act? session)))
          (is (= "did:web:kotobase.net:person:one"
                 (get-in (store/snapshot)
                         [:identity :users (:user-id finished) :did]))
              "hosted DID is the User DID, not a later Passkey")
          (is (not (str/includes? (pr-str (store/snapshot))
                                  "central-access-token")))
          (try
            (identity/complete-central-authentication!
             {:state state :code "one-time-code"})
            (is false "state replay must fail")
            (catch clojure.lang.ExceptionInfo error
              (is (= :central-auth/invalid-state (:type (ex-data error))))))
          (testing "a linked central provider remains single-factor locally"
            (identity/start-central-authentication! session "http://localhost:1338")
            (let [provider-finished
                  (finish-central! "did:web:kotobase.net:person:one"
                                   {:acr "single-factor" :amr ["google"]})
                  central-session (identity/session (:token provider-finished))]
              (is (= :single-factor (:authn-level central-session)))
              (is (= [:google] (:authn-factors central-session)))
              (is (not= :phishing-resistant (:authn-level central-session)))))))
      (testing "an unbound DID cannot take over an existing install"
        (identity/start-central-authentication! nil "http://localhost:1338")
        (try
          (finish-central! "did:web:kotobase.net:person:two")
          (is false "existing installs require an authenticated link")
          (catch clojure.lang.ExceptionInfo error
            (is (= :central-auth/link-required (:type (ex-data error)))))))
      (finally
        (reset! store/state previous)))))

(deftest an-authenticated-callback-browser-links-the-native-handoff
  ;; The native window has no local cookie. The system browser does: it is
  ;; already signed in to the existing User visible on the Settings page.
  ;; The callback must use that browser session as link authority and then
  ;; make the separately-held native claim ready.
  (let [previous @store/state]
    (try
      (reset! store/state (seeded-state))
      (identity/configure! {})
      (let [browser-issued
            (identity/issue-session!
             "user-1" {:kind :passkey
                       :issued-via :passkey
                       :authn-level :phishing-resistant
                       :authn-decision :authenticated
                       :authn-factors [:passkey]})
            browser-session (identity/session (:token browser-issued))
            started (identity/start-central-authentication!
                     nil "http://localhost:1338" {:handoff? true})
            finished (finish-central!
                      "did:web:kotobase.net:person:native"
                      {:acr "phishing-resistant" :amr ["webauthn"]}
                      browser-session)
            claimed (identity/claim-session-handoff!
                     (:handoff started) {:origin-trusted? true})
            native-session (identity/session (:token claimed))]
        (is (true? (:linked? finished)))
        (is (= "user-1" (:user-id finished)))
        (is (= "user-1"
               (get-in (store/snapshot)
                       [:identity :login-identities
                        [:itonami-cloud "did:web:kotobase.net:person:native"]
                        :user-id])))
        (is (= 2 (count (get-in (store/snapshot) [:identity :users])))
            "linking through the browser must not create a third local User")
        (is (true? (:ready? claimed)))
        (is (true? (:linked? claimed)))
        (is (= "user-1" (:user-id native-session)))
        (is (true? (identity/may-act? native-session))))
      (finally
        (reset! store/state previous)))))

(deftest a-session-handoff-crosses-exactly-one-cookie-jar-exactly-once
  ;; The native window sends the authorization request to the system browser
  ;; (RFC 8252, and independently because the embedded webview cannot do
  ;; WebAuthn), so the callback's cookie is set in a jar it cannot read. The
  ;; claim token is how it gets a session of its own — and it is the entire
  ;; authority on an endpoint reached WITHOUT one, so what it refuses matters
  ;; more than what it allows.
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (identity/configure! {})
      (testing "a browser that does not ask is never handed a claim token"
        ;; It has no use for one, and a bearer secret that mints sessions is
        ;; not something to issue on the ordinary path just because it fits.
        (let [started (identity/start-central-authentication!
                       nil "http://localhost:1338")]
          (is (nil? (:handoff started)))
          (is (empty? (get-in (store/snapshot)
                              [:identity :central-auth-handoffs])))))
      (reset! store/state (store/initial-state))
      (identity/configure! {})
      (let [started (identity/start-central-authentication!
                     nil "http://localhost:1338" {:handoff? true})
            claim (:handoff started)]
        (is (string? claim))
        (testing "the claim token is not the OAuth state and is not in the URL"
          ;; `state` is published — address bar, provider logs, redirect. A
          ;; claim endpoint keyed on it would hand a session to whoever read
          ;; one.
          (is (not= claim (central-state)))
          (is (not (str/includes? (:url started) claim))))
        (testing "the claim token is never stored"
          ;; Same rule as `issue-session!`: digests at rest, never the secret.
          (is (not (str/includes? (pr-str (store/snapshot)) claim))))
        (testing "claiming before anybody authenticated mints nothing"
          ;; Without this the token issued above would already BE a session,
          ;; and starting a sign-in would equal finishing one.
          (is (= {:ready? false}
                 (identity/claim-session-handoff!
                  claim {:origin-trusted? true}))))
        (finish-central! "did:web:kotobase.net:person:one")
        (testing "an untrusted origin mints nothing even when ready"
          (is (= {:ready? false}
                 (identity/claim-session-handoff!
                  claim {:origin-trusted? false}))))
        (testing "every refusal is the same answer on the wire"
          ;; An unknown token, a wrong one and a blank one must be
          ;; indistinguishable from a claim that is merely early — otherwise
          ;; the endpoint confirms guesses by the shape of its rejection.
          (doseq [guess [nil "" "not-a-real-claim-token" (central-state)]]
            (is (= {:ready? false}
                   (identity/claim-session-handoff!
                    guess {:origin-trusted? true}))
                (str "a refusal was distinguishable for " (pr-str guess)))))
        (let [claimed (identity/claim-session-handoff!
                       claim {:origin-trusted? true})
              session (identity/session (:token claimed))]
          (testing "the window that started the flow gets a session of its own"
            (is (true? (:ready? claimed)))
            (is (true? (identity/may-act? session)))
            ;; The facts came from the callback, not from the claim: the claim
            ;; record carries no user, provider or authentication level, so
            ;; there is nothing here that could have chosen them.
            (is (= :federated (:kind session)))
            (is (= :itonami-cloud (:issued-via session)))
            (is (= :phishing-resistant (:authn-level session))))
          (testing "it is a second session, not a copy of the browser's"
            ;; Two agents, two sessions, each revocable without killing the
            ;; other — and no raw session token had to be stored to do it.
            (is (= 2 (count (identity/user-sessions session))))
            (is (not (str/includes? (pr-str (store/snapshot))
                                    (:token claimed)))))
          (testing "a claim is spent exactly once"
            (is (= {:ready? false}
                   (identity/claim-session-handoff!
                    claim {:origin-trusted? true}))))))
      (finally
        (reset! store/state previous)))))

(deftest public-auth-methods-name-hosted-enrolment
  (identity/configure! {})
  (let [methods (identity/public-auth-methods)]
    (is (true? (get-in methods [:central :configured?])))
    (is (= "https://auth.itonami.cloud" (get-in methods [:central :issuer])))
    ;; The /ja/ route: itonami.cloud is English-default multilingual and this
    ;; app's UI is Japanese, so the ceremony must not switch language mid-way.
    (is (= "https://itonami.cloud/ja/signin/"
           (get-in methods [:central :enrolment-url])))))
