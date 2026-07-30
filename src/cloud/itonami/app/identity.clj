(ns cloud.itonami.app.identity
  "Local account, organization membership, and delegated OAuth connections.

  Public state contains metadata and Keychain references only. OAuth access
  and refresh tokens are written to macOS Keychain and never enter state.edn."
  (:require [cloud.itonami.app.did :as did]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.passkey :as passkey]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [identity.directory :as directory]
            [identity.model :as identity]
            [oauth.model :as oauth])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.time Duration Instant]
           [java.util Base64 UUID]
           [java.util.concurrent TimeUnit]))

(def cookie-name "cloud_itonami_identity")
(def session-seconds (* 30 24 60 60))
(def transaction-seconds 600)
(def enrollment-seconds (* 24 60 60))
(def account-id-pattern #"^[a-z0-9](?:[a-z0-9._-]{1,30}[a-z0-9])?$")
(def keychain-service "cloud-itonami-app.oauth")
(def default-identity-profile
  {:account-domain "cloud-itonami.app"
   :organization-domain-suffix "cloud-itonami.app"
   :organization-domain-overrides {}
   :publish-did-web? false})
(defonce runtime-identity-profile (atom default-identity-profile))
(defonce http-client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 8))
                         .build))

(defn configure!
  "Install the distribution/tenant profile for this process."
  [configuration]
  (reset! runtime-identity-profile
          (merge default-identity-profile (:identity configuration))))

(defn identity-profile []
  @runtime-identity-profile)

(defn- account-domain []
  (:account-domain @runtime-identity-profile))

(defn- organization-domain [organization-id]
  (or (get-in @runtime-identity-profile
              [:organization-domain-overrides organization-id])
      (str organization-id "."
           (:organization-domain-suffix @runtime-identity-profile))))

(defn- organization-did [organization-id]
  (when (:publish-did-web? @runtime-identity-profile)
    (str "did:web:" (organization-domain organization-id))))

(def provider-catalog
  {:github
   {:name "GitHub"
    :credential-service "gftd.github"
    :client-id-env "GITHUB_CLIENT_ID"
    :client-secret-env "GITHUB_CLIENT_SECRET"
    :authorization-endpoint "https://github.com/login/oauth/authorize"
    :token-endpoint "https://github.com/login/oauth/access_token"
    :profile-endpoint "https://api.github.com/user"
    :scopes ["read:user" "user:email" "read:org" "read:project"]}
   :google
   {:name "Google Workspace"
    :credential-service "gftd.google"
    :client-id-env "GOOGLE_CLIENT_ID"
    :client-secret-env "GOOGLE_CLIENT_SECRET"
    :authorization-endpoint "https://accounts.google.com/o/oauth2/v2/auth"
    :token-endpoint "https://oauth2.googleapis.com/token"
    :profile-endpoint "https://openidconnect.googleapis.com/v1/userinfo"
    :scopes ["openid" "email" "profile"
             "https://www.googleapis.com/auth/gmail.readonly"
             "https://www.googleapis.com/auth/drive.metadata.readonly"
             "https://www.googleapis.com/auth/calendar.readonly"]
    :authorization-extra {"access_type" "offline"
                          "prompt" "consent"
                          "include_granted_scopes" "true"}}
   :microsoft
   {:name "Microsoft 365"
    :credential-service "gftd.m365"
    :client-id-env "M365_CLIENT_ID"
    :client-secret-env "M365_CLIENT_SECRET"
    :authorization-endpoint "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize"
    :token-endpoint "https://login.microsoftonline.com/organizations/oauth2/v2.0/token"
    :profile-endpoint "https://graph.microsoft.com/v1.0/me"
    :scopes ["openid" "email" "profile" "offline_access"
             "User.Read" "Mail.ReadBasic" "Files.Read" "Calendars.ReadBasic"]}})

(defn- url-encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- random-token [size]
  (let [bytes (byte-array size)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- digest [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str value) StandardCharsets/UTF_8))]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- configured-value [provider suffix]
  (let [config (get provider-catalog provider)
        env-name (get config (keyword (str suffix "-env")))
        environment (some-> env-name System/getenv not-empty)]
    (or environment
        (try
          (let [process (-> (ProcessBuilder.
                             ^java.util.List
                             ["security" "find-generic-password"
                              "-s" (:credential-service config)
                              "-a" (str/upper-case (str/replace suffix "-" "_"))
                              "-w"])
                            (.redirectErrorStream true)
                            .start)
                output (future (slurp (.getInputStream process)))
                completed? (.waitFor process 3 TimeUnit/SECONDS)]
            (when (and completed? (zero? (.exitValue process)))
              (not-empty (str/trim (deref output 500 "")))))
          (catch Exception _ nil)))))

(defn provider-config [provider]
  (when-let [config (get provider-catalog provider)]
    (let [client-id (configured-value provider "client-id")
          client-secret (configured-value provider "client-secret")]
      (assoc config
             :provider provider
             :client-id client-id
             :client-secret client-secret
             :configured? (boolean (and client-id client-secret))))))

(defn- identity-state [state]
  (merge {:organizations {} :users {} :memberships {}
          :connections {} :oauth-transactions {} :sessions {}
          :passkeys {} :enrollments {} :organization-invitations {}
          :webauthn-transactions {}}
         (:identity state)))

(defn- normalize-id [value]
  (some-> value str str/trim str/lower-case))

(defn- normalize-email [value]
  (let [email (some-> value str str/trim str/lower-case)]
    (when (and email (<= 3 (count email) 254)
               (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+" email))
      email)))

(defn- valid-account-id? [value]
  (boolean (and value (re-matches account-id-pattern value))))

(defn- canonical-email [account-id]
  (str account-id "@" (account-domain)))

(defn registered? []
  (boolean (seq (:users (identity-state (store/snapshot))))))

(defn- session-by-token [token]
  (when-not (str/blank? token)
    (let [token-digest (digest token)
          now (Instant/now)]
      (some (fn [[_ session]]
              (when (and (= token-digest (:token-digest session))
                         (not (:revoked? session))
                         (pos? (compare (Instant/parse (:expires-at session)) now)))
                session))
            (:sessions (identity-state (store/snapshot)))))))

(defn session [token]
  (session-by-token token))

(defn issue-session! [user-id]
  (let [state (identity-state (store/snapshot))
        membership (some #(when (= user-id (:user-id %)) %)
                         (vals (:memberships state)))]
    (when-not membership
      (throw (ex-info "組織 membership が見つかりません。"
                      {:type :identity/unauthenticated})))
    (let [token (random-token 32)
          session-id (str "session-" (UUID/randomUUID))
          csrf (random-token 24)
          now (store/now)
          expires-at (str (.plusSeconds (Instant/now) session-seconds))]
      (store/transact!
       assoc-in [:identity :sessions session-id]
       {:id session-id :user-id user-id
        :organization-id (:organization-id membership)
        :membership-id (:id membership) :token-digest (digest token)
        :csrf csrf :created-at now :expires-at expires-at :revoked? false})
      {:token token :expires-at expires-at})))

(defn- public-connection [connection]
  (select-keys connection [:id :provider :status :display-name :email
                           :provider-subject :scopes :connected-at :last-error]))

(defn- public-organization [state membership]
  (let [organization (get-in state [:organizations
                                    (:organization-id membership)])]
    (assoc (select-keys organization [:id :organization-id :did :name :domain
                                      :contact-domain :status])
           :profile-complete? (boolean (:organization-id organization))
           :role (:role membership)
           :active? false)))

(defn- memberships-for-user [state user-id]
  (->> (:memberships state)
       vals
       (filter #(= user-id (:user-id %)))
       (sort-by (juxt :created-at :id))
       vec))

(defn- public-organization-invitations [state user-id]
  (->> (:organization-invitations state)
       vals
       (filter #(and (= user-id (:user-id %))
                     (= :pending (:status %))))
       (sort-by (juxt :created-at :id))
       (mapv
        (fn [invitation]
          (let [organization
                (get-in state [:organizations (:organization-id invitation)])]
            (assoc
             (select-keys invitation [:id :role :status :created-at :expires-at])
             :organization
             (select-keys organization
                          [:id :organization-id :name :domain :did])))))))

(defn- derive-user-did [state user]
  (some (fn [credential]
          (when (= (:id user) (:user-id credential))
            (try
              (did/did-key-from-cose (:public-key-cose credential))
              (catch Exception _ nil))))
        (vals (:passkeys state))))

(defn- migrate-did-links! []
  (let [state (identity-state (store/snapshot))
        missing-user? (some #(and (:passkey-enrolled? %) (nil? (:did %)))
                            (vals (:users state)))
        missing-organization?
        (and (:publish-did-web? @runtime-identity-profile)
             (some #(and (:organization-id %) (nil? (:did %)))
                   (vals (:organizations state))))]
    (when (or missing-user? missing-organization?)
      (store/transact!
       (fn [current]
         (let [identity-state (identity-state current)
               with-users
               (reduce
                (fn [result [user-id user]]
                  (if (and (:passkey-enrolled? user) (nil? (:did user)))
                    (if-let [user-did (derive-user-did identity-state user)]
                      (-> result
                          (assoc-in [:identity :users user-id :did] user-did)
                          (assoc-in
                           [:identity :users user-id :subject]
                           (identity/subject user-did :person
                                             {:did user-did
                                              :labels #{:local :passkey}})))
                      result)
                    result))
                current
                (:users identity-state))]
           (reduce
            (fn [result [organization-id organization]]
              (if (and (:organization-id organization)
                       (nil? (:did organization)))
                (if-let [organization-did
                         (organization-did (:organization-id organization))]
                  (-> result
                      (assoc-in [:identity :organizations organization-id :did]
                                organization-did)
                      (assoc-in
                       [:identity :organizations organization-id :subject]
                       (identity/subject
                        organization-did :organization
                        {:did organization-did
                         :labels #{:local :organization}})))
                  result)
                result))
            with-users
            (:organizations identity-state))))))))

(defn organization-domain-for-did-web
  "The domain whose `did:web` document this deployment should serve, or nil.

  nil in two distinct cases that both mean \"do not serve one\": the profile has
  `:publish-did-web? false`, or no Organization ID has been claimed yet. They are
  collapsed on purpose — a DID document for an organization that does not exist
  would name a key as belonging to nobody.

  Returns the domain of the single configured organization. A deployment hosting
  several organizations serves one document per domain and cannot use this; that
  is production multi-tenant hosting, which `docs/tenant-model.md` already scopes
  out of this application."
  []
  (when (:publish-did-web? @runtime-identity-profile)
    (let [state (identity-state (store/snapshot))]
      (some (fn [organization]
              (when (:organization-id organization)
                (:domain organization)))
            (vals (:organizations state))))))

(defn public-state [token]
  (migrate-did-links!)
  (let [state (identity-state (store/snapshot))
        session (session-by-token token)
        user (get-in state [:users (:user-id session)])
        membership (get-in state [:memberships (:membership-id session)])
        organization (get-in state [:organizations (:organization-id session)])
        organizations (when session
                        (mapv #(assoc (public-organization state %)
                                     :active?
                                     (= (:id %) (:membership-id session)))
                              (memberships-for-user state (:user-id session))))]
    {:schema "cloud.itonami.app.identity.v1"
     :registered? (boolean (seq (:users state)))
     :passkey-required? (and (boolean (seq (:users state)))
                             (empty? (:passkeys state)))
     :authenticated? (boolean session)
     :csrf (:csrf session)
     :user (when session (select-keys user [:id :did :account-id :email
                                            :contact-email :display-name :status
                                            :passkey-enrolled?]))
     :active-organization-id (:id organization)
     :organizations organizations
     :organization-invitations
     (when session (public-organization-invitations state (:user-id session)))
     :organization (when session
                     (assoc (select-keys organization [:id :organization-id
                                                       :did :name :domain
                                                       :contact-domain :status])
                            :profile-complete?
                            (boolean (:organization-id organization))
                            :role (:role membership)
                            :users (->> (:memberships state)
                                        vals
                                        (filter #(= (:organization-id session)
                                                    (:organization-id %)))
                                        (mapv (fn [member]
                                                (let [member-user
                                                      (get-in state [:users (:user-id member)])]
                                                  (assoc
                                                   (select-keys member-user
                                                                [:id :did :account-id :email
                                                                 :contact-email :display-name
                                                                 :status :passkey-enrolled?])
                                                   :role (:role member))))))))
     :connections (when session
                    (->> (:connections state)
                         vals
                         (filter #(= (:organization-id session)
                                     (:organization-id %)))
                         (mapv public-connection)))
     :providers
     (mapv (fn [[provider config]]
             (let [connection (some #(when (= provider (:provider %)) %)
                                    (vals (:connections state)))
                   provider (provider-config provider)]
               {:id (name (:provider provider))
                :name (:name config)
                :configured? (:configured? provider)
                :connected? (= :connected (:status connection))
                :scopes (:scopes config)}))
           provider-catalog)}))

(declare require-passkey!)

(defn create-organization!
  "Create another organization and make the current user its owner.
  The caller's existing memberships remain intact."
  [session {:keys [organization-id organization-name]}]
  (require-passkey! session)
  (let [state (identity-state (store/snapshot))
        user-id (:user-id session)
        organization-slug (normalize-id organization-id)]
    (when-not (valid-account-id? organization-slug)
      (throw (ex-info "有効な Organization ID を入力してください。"
                      {:type :identity/invalid-registration})))
    (when (some #(= organization-slug (:organization-id %))
                (vals (:organizations state)))
      (throw (ex-info "この Organization ID は既に使用されています。"
                      {:type :identity/already-registered})))
    (let [organization-record-id (str "org-" (UUID/randomUUID))
          membership-id (str "membership-" (UUID/randomUUID))
          domain (organization-domain organization-slug)
          organization-did (organization-did organization-slug)
          now (store/now)]
      (store/transact!
       (fn [current]
         (-> current
             (assoc-in
              [:identity :organizations organization-record-id]
              {:id organization-record-id
               :organization-id organization-slug
               :did organization-did
               :name (or (some-> organization-name str str/trim not-empty)
                         organization-slug)
               :domain domain
               :status :active
               :subject
               (identity/subject
                (or organization-did organization-record-id)
                :organization
                {:did organization-did :labels #{:local :organization}})
               :created-at now})
             (assoc-in
              [:identity :memberships membership-id]
              {:id membership-id
               :organization-id organization-record-id
               :user-id user-id
               :role :owner
               :created-at now})
             (update :events conj
                     {:type :identity/organization-created
                      :at now
                      :organization-id organization-record-id
                      :user-id user-id}))))
      {:organization-id organization-record-id
       :membership-id membership-id
       :slug organization-slug
       :domain domain
       :did organization-did})))

(defn switch-organization!
  "Change only this session's active organization after membership proof."
  [session {:keys [organization-id]}]
  (require-passkey! session)
  (let [state (identity-state (store/snapshot))
        memberships (memberships-for-user state (:user-id session))
        membership
        (some (fn [candidate]
                (let [organization
                      (get-in state [:organizations
                                     (:organization-id candidate)])]
                  (when (or (= organization-id (:id organization))
                            (= (normalize-id organization-id)
                               (:organization-id organization)))
                    candidate)))
              memberships)]
    (when-not membership
      (throw (ex-info "この Organization への membership がありません。"
                      {:type :identity/forbidden})))
    (store/transact!
     (fn [current]
       (-> current
           (update-in [:identity :sessions (:id session)]
                      merge
                      {:organization-id (:organization-id membership)
                       :membership-id (:id membership)
                       :updated-at (store/now)})
           (update :events conj
                   {:type :identity/organization-switched
                    :at (store/now)
                    :organization-id (:organization-id membership)
                    :user-id (:user-id session)
                    :session-id (:id session)}))))
    {:organization-id (:organization-id membership)
     :membership-id (:id membership)}))

(defn register!
  "Create a provisional local owner. Profile fields are optional because the
  first trust-bearing operation is Passkey registration."
  [{:keys [organization-name organization-id domain display-name
           account-id contact-email email]}]
  (when (registered?)
    (throw (ex-info "この端末には既に User が登録されています。"
                    {:type :identity/already-registered})))
  (let [account-id (normalize-id
                    (or account-id (some-> email (str/split #"@" 2) first)))
        organization-slug
        (normalize-id (or organization-id
                          (some-> domain (str/split #"\." 2) first)))]
    (when (or (and account-id (not (valid-account-id? account-id)))
              (and organization-slug
                   (not (valid-account-id? organization-slug))))
      (throw (ex-info "Organization ID またはアカウント ID が無効です。"
                      {:type :identity/invalid-registration})))
    (let [canonical-address (if account-id
                              (canonical-email account-id)
                              (str "pending-" (normalize-id (random-token 8))
                                   "@" (account-domain)))
          organization-record-id (str "org-" (UUID/randomUUID))
          user-id (str "user-" (UUID/randomUUID))
          membership-id (str "membership-" (UUID/randomUUID))
          organization-domain (when organization-slug
                                (organization-domain organization-slug))
          organization-did (when organization-slug
                             (organization-did organization-slug))
          contact-domain (some-> domain str str/trim str/lower-case not-empty)
          contact-email (normalize-email (or contact-email email))
          owner-name (or (some-> display-name str/trim not-empty)
                         "Passkey user")
          directory-model (directory/directory organization-record-id
                                                (account-domain))
          directory-user (directory/user user-id canonical-address
                                         {:display-name owner-name
                                          :roles #{:super-admin}})
          organization-subject
          (identity/subject (or organization-did organization-record-id)
                            :organization
                            {:did organization-did
                             :labels #{:local :organization}})
          user-subject (identity/subject user-id :person
                                        {:labels #{:local :owner}})
          user-handle (random-token 32)
          now (store/now)]
      (directory/add-user directory-model directory-user)
      (store/transact!
       (fn [state]
         (-> state
             (assoc-in [:identity :organizations organization-record-id]
                       {:id organization-record-id
                        :organization-id organization-slug
                        :did organization-did
                        :name (or (some-> organization-name str/trim not-empty)
                                  "Personal")
                        :domain organization-domain
                        :contact-domain contact-domain
                        :status (if organization-slug :active :pending-profile)
                        :subject organization-subject :created-at now})
             (assoc-in [:identity :users user-id]
                       {:id user-id :did nil
                        :account-id account-id :email canonical-address
                        :contact-email contact-email
                        :display-name owner-name
                        :user-handle user-handle :passkey-enrolled? false
                        :status :pending-passkey
                        :subject user-subject :created-at now})
             (assoc-in [:identity :memberships membership-id]
                       {:id membership-id
                        :organization-id organization-record-id
                        :user-id user-id :role :owner :created-at now})
             (update :events conj {:type :identity/registered :at now
                                   :organization-id organization-record-id
                                   :user-id user-id}))))
      (assoc (issue-session! user-id)
             :user-id user-id :email canonical-address))))

(defn resume-owner-onboarding! []
  (let [state (identity-state (store/snapshot))
        users (vals (:users state))
        owner-membership
        (some #(when (= :owner (:role %)) %) (vals (:memberships state)))
        owner (get-in state [:users (:user-id owner-membership)])]
    (when-not (and (= 1 (count users))
                   owner
                   (not (:passkey-enrolled? owner))
                   (empty? (:passkeys state)))
      (throw (ex-info "Passkey 登録の再開条件を満たしていません。"
                      {:type :passkey/onboarding-unavailable})))
    (issue-session! (:id owner))))

(defn add-user!
  [session {:keys [display-name account-id contact-email email role]}]
  (when-not (= :owner (get-in (identity-state (store/snapshot))
                              [:memberships (:membership-id session) :role]))
    (throw (ex-info "組織ユーザーの追加には owner 権限が必要です。"
                    {:type :identity/forbidden})))
  (let [state (identity-state (store/snapshot))
        organization (get-in state [:organizations (:organization-id session)])
        account-id (normalize-id
                    (or account-id (some-> email (str/split #"@" 2) first)))
        existing-user (some #(when (= account-id (:account-id %)) %)
                            (vals (:users state)))
        canonical-address (when account-id (canonical-email account-id))
        contact-email (normalize-email (or contact-email email))
        user-id (or (:id existing-user) (str "user-" (UUID/randomUUID)))
        membership-id (str "membership-" (UUID/randomUUID))
        role (if (#{"admin" "member"} role) (keyword role) :member)
        model (directory/directory (:id organization) (account-domain))
        user-model (directory/user user-id canonical-address
                                   {:display-name display-name :roles #{:member}})
        user-handle (random-token 32)
        enrollment-code (random-token 18)
        enrollment-id (str "enrollment-" (UUID/randomUUID))
        invitation-id (str "organization-invitation-" (UUID/randomUUID))
        expires-at (str (.plusSeconds (Instant/now) enrollment-seconds))
        now (store/now)]
    (when-not (:organization-id organization)
      (throw (ex-info "User を追加する前に Organization ID を設定してください。"
                      {:type :identity/organization-required})))
    (when-not (and (not (str/blank? display-name))
                   (valid-account-id? account-id))
      (throw (ex-info "有効なアカウントIDと表示名が必要です。"
                      {:type :identity/invalid-registration})))
    (when (and existing-user
               (some #(and (= user-id (:user-id %))
                           (= (:id organization) (:organization-id %)))
                     (vals (:memberships state))))
      (throw (ex-info "このUserは既にOrganizationに所属しています。"
                      {:type :identity/already-member})))
    (if existing-user
      (do
        (store/transact!
         (fn [current]
           (-> current
               (assoc-in
                [:identity :organization-invitations invitation-id]
                {:id invitation-id
                 :organization-id (:id organization)
                 :user-id user-id
                 :role role
                 :code-digest (digest enrollment-code)
                 :status :pending
                 :created-at now
                 :expires-at expires-at})
               (update :events conj
                       {:type :identity/organization-invited
                        :at now
                        :organization-id (:id organization)
                        :user-id user-id
                        :invitation-id invitation-id}))))
        {:id invitation-id :kind :organization-invitation
         :account-id account-id :email (:email existing-user)
         :invitation-code enrollment-code :expires-at expires-at})
      (do
        (directory/add-user model user-model)
        (store/transact!
         (fn [current]
           (-> current
               (assoc-in [:identity :users user-id]
                         {:id user-id :account-id account-id
                          :email canonical-address
                          :contact-email contact-email
                          :display-name (str/trim display-name)
                          :user-handle user-handle
                          :passkey-enrolled? false
                          :status :invited :created-at now})
               (assoc-in [:identity :memberships membership-id]
                         {:id membership-id :organization-id (:id organization)
                          :user-id user-id :role role :created-at now})
               (assoc-in [:identity :enrollments enrollment-id]
                         {:id enrollment-id :user-id user-id
                          :organization-id (:id organization)
                          :code-digest (digest enrollment-code)
                          :created-at now :expires-at expires-at :used? false})
               (update :events conj {:type :identity/user-added :at now
                                     :organization-id (:id organization)
                                     :user-id user-id}))))
        {:id user-id :kind :user-enrollment :account-id account-id
         :email canonical-address :enrollment-code enrollment-code
         :expires-at expires-at}))))

(defn accept-organization-invitation!
  "Accept a membership invitation that is cryptographically bound to the
  authenticated User. The accepted organization becomes active for this
  session; no other session is changed."
  [session {:keys [invitation-code]}]
  (require-passkey! session)
  (let [state (identity-state (store/snapshot))
        code (some-> invitation-code str str/trim)
        code-digest (when-not (str/blank? code) (digest code))
        now-instant (Instant/now)
        invitation
        (some
         (fn [candidate]
           (when (and (= (:user-id session) (:user-id candidate))
                      (= :pending (:status candidate))
                      code-digest
                      (MessageDigest/isEqual
                       (.getBytes ^String code-digest StandardCharsets/UTF_8)
                       (.getBytes ^String (:code-digest candidate)
                                  StandardCharsets/UTF_8))
                      (pos? (compare (Instant/parse (:expires-at candidate))
                                     now-instant)))
             candidate))
         (vals (:organization-invitations state)))]
    (when-not invitation
      (throw (ex-info "招待コードが無効、期限切れ、または別のUser宛です。"
                      {:type :identity/invalid-invitation})))
    (when (some #(and (= (:user-id session) (:user-id %))
                      (= (:organization-id invitation) (:organization-id %)))
                (vals (:memberships state)))
      (throw (ex-info "このUserは既にOrganizationに所属しています。"
                      {:type :identity/already-member})))
    (let [membership-id (str "membership-" (UUID/randomUUID))
          now (store/now)]
      (store/transact!
       (fn [current]
         (-> current
             (assoc-in [:identity :memberships membership-id]
                       {:id membership-id
                        :organization-id (:organization-id invitation)
                        :user-id (:user-id session)
                        :role (:role invitation)
                        :created-at now})
             (update-in [:identity :organization-invitations (:id invitation)]
                        merge {:status :accepted :accepted-at now})
             (update-in [:identity :sessions (:id session)]
                        merge {:organization-id (:organization-id invitation)
                               :membership-id membership-id
                               :updated-at now})
             (update :events conj
                     {:type :identity/organization-invitation-accepted
                      :at now
                      :organization-id (:organization-id invitation)
                      :user-id (:user-id session)
                      :membership-id membership-id
                      :invitation-id (:id invitation)}))))
      {:organization-id (:organization-id invitation)
       :membership-id membership-id
       :invitation-id (:id invitation)})))

(defn start-passkey-registration!
  [session rp-id origin]
  (let [user (get-in (identity-state (store/snapshot))
                     [:users (:user-id session)])]
    (passkey/start-registration! user rp-id origin)))

(defn passkey-enrolled? [session]
  (true? (get-in (identity-state (store/snapshot))
                 [:users (:user-id session) :passkey-enrolled?])))

(defn require-passkey! [session]
  (when-not (passkey-enrolled? session)
    (throw (ex-info
            "アプリを利用するには Passkey の登録が必要です。"
            {:type :passkey/required})))
  session)

(defn configure-organization!
  "Claim the public Organization ID after the owner has enrolled a Passkey."
  [session {:keys [organization-id]}]
  (require-passkey! session)
  (let [state (identity-state (store/snapshot))
        membership (get-in state [:memberships (:membership-id session)])
        organization (get-in state [:organizations (:organization-id session)])
        owner (get-in state [:users (:user-id session)])
        organization-slug (normalize-id organization-id)]
    (when-not (= :owner (:role membership))
      (throw (ex-info "Organization ID の設定には owner 権限が必要です。"
                      {:type :identity/forbidden})))
    (when-not (valid-account-id? organization-slug)
      (throw (ex-info "有効な Organization ID を入力してください。"
                      {:type :identity/invalid-registration})))
    (when (some #(and (= organization-slug (:organization-id %))
                      (not= (:id organization) (:id %)))
                (vals (:organizations state)))
      (throw (ex-info "この Organization ID は既に使用されています。"
                      {:type :identity/already-registered})))
    (when (and (:organization-id organization)
               (not= organization-slug (:organization-id organization)))
      (throw (ex-info "Organization ID は設定後に変更できません。"
                      {:type :identity/organization-id-immutable})))
    (let [domain (organization-domain organization-slug)
          organization-did (organization-did organization-slug)
          owner-account-id (or (:account-id owner) organization-slug)
          owner-email (canonical-email owner-account-id)
          now (store/now)]
      (store/transact!
       (fn [current]
         (-> current
             (update-in [:identity :organizations (:id organization)]
                        merge
                        {:organization-id organization-slug
                         :did organization-did
                         :name (if (= "Personal" (:name organization))
                                 organization-slug
                                 (:name organization))
                         :domain domain
                         :status :active
                         :subject
                         (identity/subject
                          (or organization-did (:id organization))
                          :organization
                          {:did organization-did
                           :labels #{:local :organization}})
                         :updated-at now})
             (update-in [:identity :users (:id owner)]
                        merge
                        {:account-id owner-account-id
                         :email owner-email
                         :updated-at now})
             (update :events conj
                     {:type :identity/organization-configured :at now
                      :organization-id (:id organization)
                      :organization-did organization-did
                      :user-id (:id owner)}))))
      {:organization-id organization-slug
       :domain domain
       :did organization-did
       :account-id owner-account-id
       :email owner-email})))

(defn finish-passkey-registration! [session transaction-id response]
  (passkey/finish-registration! transaction-id response (:user-id session)))

(defn- valid-enrollment [account-id code]
  (let [state (identity-state (store/snapshot))
        user (some #(when (= (normalize-id account-id) (:account-id %)) %)
                   (vals (:users state)))
        enrollment (some #(when (= (:id user) (:user-id %)) %)
                         (vals (:enrollments state)))]
    (when (and user enrollment
               (MessageDigest/isEqual
                (.getBytes (digest code) StandardCharsets/UTF_8)
                (.getBytes ^String (:code-digest enrollment)
                           StandardCharsets/UTF_8))
               (not (:used? enrollment))
               (pos? (compare (Instant/parse (:expires-at enrollment))
                              (Instant/now))))
      [user enrollment])))

(defn start-enrollment! [account-id code rp-id origin]
  (if-let [[user enrollment] (valid-enrollment account-id code)]
    (let [result (passkey/start-registration! user rp-id origin)]
      (store/transact!
       assoc-in [:identity :webauthn-transactions (:transaction-id result)
                 :enrollment-id]
       (:id enrollment))
      result)
    (throw (ex-info "アカウントIDまたは enrollment code が無効です。"
                    {:type :passkey/invalid-enrollment}))))

(defn finish-enrollment! [transaction-id response]
  (let [transaction (get-in (identity-state (store/snapshot))
                            [:webauthn-transactions transaction-id])
        enrollment-id (:enrollment-id transaction)]
    (when-not enrollment-id
      (throw (ex-info "Enrollment 要求が無効です。"
                      {:type :passkey/invalid-enrollment})))
    (let [enrollment (get-in (identity-state (store/snapshot))
                             [:enrollments enrollment-id])
          result (passkey/finish-registration!
                  transaction-id response (:user-id enrollment))
          now (store/now)]
      (store/transact!
       (fn [state]
         (-> state
             (assoc-in [:identity :enrollments enrollment-id :used?] true)
             (assoc-in [:identity :enrollments enrollment-id :used-at] now)
             (assoc-in [:identity :users (:user-id result) :status] :active))))
      (merge result (issue-session! (:user-id result))))))

(defn start-passkey-authentication! [rp-id origin]
  (passkey/start-authentication! rp-id origin))

(defn finish-passkey-authentication! [transaction-id response]
  (let [result (passkey/finish-authentication! transaction-id response)]
    (merge result (issue-session! (:user-id result)))))

(defn- callback-uri [origin provider]
  (str origin "/api/oauth/" (name provider) "/callback"))

(defn start-oauth! [session provider origin]
  (let [{:keys [configured? client-id authorization-endpoint scopes
                authorization-extra] :as config}
        (provider-config provider)]
    (when-not config
      (throw (ex-info "未対応の接続先です。" {:type :oauth/unsupported})))
    (when-not configured?
      (throw (ex-info "OAuth クライアントが未設定です。"
                      {:type :oauth/not-configured :provider provider})))
    (let [state-value (random-token 32)
          nonce (random-token 24)
          verifier (random-token 48)
          challenge (-> verifier .getBytes
                        (#(.digest (MessageDigest/getInstance "SHA-256") %))
                        (#(.encodeToString (.withoutPadding (Base64/getUrlEncoder)) %)))
          redirect-uri (callback-uri origin provider)
          request-model (oauth/auth-request
                         (str "oauth-" (UUID/randomUUID))
                         {:client-id client-id :redirect-uri redirect-uri
                          :scope scopes :state state-value :code-challenge challenge
                          :created-at (store/now)})
          expires-at (str (.plusSeconds (Instant/now) transaction-seconds))
          parameters (merge
                      {"client_id" client-id "redirect_uri" redirect-uri
                       "response_type" "code" "scope" (str/join " " scopes)
                       "state" state-value "code_challenge" challenge
                       "code_challenge_method" "S256"}
                      (when (#{:google :microsoft} provider) {"nonce" nonce})
                      authorization-extra)
          url (str authorization-endpoint "?"
                   (str/join "&" (map (fn [[key value]]
                                        (str (url-encode key) "=" (url-encode value)))
                                      parameters)))]
      (store/transact!
       (fn [current]
         (assoc-in current [:identity :oauth-transactions state-value]
                   {:id (:oauth.request/id request-model)
                    :state state-value :provider provider :nonce nonce
                    :verifier verifier :redirect-uri redirect-uri
                    :user-id (:user-id session)
                    :organization-id (:organization-id session)
                    :expires-at expires-at :used? false})))
      {:url url :provider provider :expires-at expires-at})))

(defn- form-body [values]
  (str/join "&" (map (fn [[key value]]
                       (str (url-encode (name key)) "=" (url-encode value)))
                     (remove (comp nil? val) values))))

(defn- request-json! [request]
  (let [response (.send ^HttpClient http-client request
                        (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        data (json/read-str (.body response) :key-fn keyword)]
    (when-not (<= 200 status 299)
      (throw (ex-info "接続先が要求を拒否しました。"
                      {:type :oauth/provider-error :status status})))
    data))

(defn- exchange-code! [config transaction code]
  (let [body (form-body
              {:grant_type "authorization_code"
               :code code
               :client_id (:client-id config)
               :client_secret (:client-secret config)
               :redirect_uri (:redirect-uri transaction)
               :code_verifier (:verifier transaction)})
        request (-> (HttpRequest/newBuilder (URI/create (:token-endpoint config)))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.header "Accept" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    .build)]
    (request-json! request)))

(defn- profile! [config access-token]
  (let [request (-> (HttpRequest/newBuilder (URI/create (:profile-endpoint config)))
                    (.header "Authorization" (str "Bearer " access-token))
                    (.header "Accept" "application/json")
                    (.header "User-Agent" "cloud-itonami-app")
                    .GET .build)]
    (request-json! request)))

(defn- keychain-account [transaction token-kind]
  (str (:organization-id transaction) ":" (:user-id transaction) ":"
       (name (:provider transaction)) ":" (name token-kind)))

(defn- keychain-put! [account token]
  (when-not (str/blank? token)
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "add-generic-password" "-U"
                        "-s" keychain-service "-a" account "-w" token])
                      (.redirectErrorStream true)
                      .start)
          completed? (.waitFor process 5 TimeUnit/SECONDS)]
      (when-not (and completed? (zero? (.exitValue process)))
        (.destroyForcibly process)
        (throw (ex-info "OAuth token を macOS Keychain に保存できませんでした。"
                        {:type :oauth/keychain-error})))
      (str "keychain://" keychain-service "/" account))))

(defn- keychain-get [account]
  (try
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "find-generic-password"
                        "-s" keychain-service "-a" account "-w"])
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when (and completed? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 500 "")))))
    (catch Exception _ nil)))

(defn access-token
  "Resolve the first connected provider token from Keychain. Never returns a
  token reference or token through an HTTP/public view."
  [provider]
  (let [connection (->> (:connections (identity-state (store/snapshot)))
                        vals
                        (filter #(and (= provider (:provider %))
                                      (= :connected (:status %))))
                        first)]
    (when connection
      (keychain-get
       (str (:organization-id connection) ":" (:user-id connection) ":"
            (name provider) ":access")))))

(defn complete-oauth! [provider {:keys [state code error]}]
  (when error
    (throw (ex-info "接続がキャンセルされました。" {:type :oauth/cancelled})))
  (let [current (identity-state (store/snapshot))
        transaction (get-in current [:oauth-transactions state])]
    (when-not (and transaction (= provider (:provider transaction))
                   (not (:used? transaction))
                   (pos? (compare (Instant/parse (:expires-at transaction))
                                  (Instant/now))))
      (throw (ex-info "OAuth state が無効、期限切れ、または使用済みです。"
                      {:type :oauth/invalid-state})))
    (when (str/blank? code)
      (throw (ex-info "認可コードがありません。" {:type :oauth/missing-code})))
    (store/transact!
     assoc-in [:identity :oauth-transactions state :used?] true)
    (let [config (provider-config provider)
          token (exchange-code! config transaction code)
          access-token (:access_token token)
          _ (when (str/blank? access-token)
              (throw (ex-info "接続先からアクセストークンが返りませんでした。"
                              {:type :oauth/missing-token})))
          profile (profile! config access-token)
          access-ref (keychain-put! (keychain-account transaction :access)
                                    access-token)
          refresh-ref (keychain-put! (keychain-account transaction :refresh)
                                     (:refresh_token token))
          connection-id (str (:organization-id transaction) ":" (name provider))
          provider-subject (str (or (:sub profile) (:id profile)
                                    (:userPrincipalName profile)))
          email (normalize-email
                 (or (:email profile) (:mail profile)
                     (:userPrincipalName profile)))
          display-name (or (:name profile) (:displayName profile)
                           (:login profile) email)
          scopes (set (remove str/blank?
                              (str/split (or (:scope token)
                                             (str/join " " (:scopes config))) #"\s+")))
          now (store/now)]
      (store/transact!
       (fn [state]
         (-> state
             (assoc-in [:identity :connections connection-id]
                       {:id connection-id :provider provider :status :connected
                        :organization-id (:organization-id transaction)
                        :user-id (:user-id transaction)
                        :provider-subject provider-subject :email email
                        :display-name display-name :scopes scopes
                        :access-token-ref access-ref :refresh-token-ref refresh-ref
                        :connected-at now})
             (update :events conj {:type :oauth/connected :at now
                                   :provider provider
                                   :organization-id (:organization-id transaction)}))))
      {:provider provider :connected? true})))
