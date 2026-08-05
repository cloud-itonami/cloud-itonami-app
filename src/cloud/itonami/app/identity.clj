(ns cloud.itonami.app.identity
  "Local account, organization membership, and delegated OAuth connections.

  Public state contains metadata and Keychain references only. OAuth access
  and refresh tokens are written to macOS Keychain and never enter state.edn."
  (:require [cloud.itonami.app.did :as did]
            [cloud.itonami.app.email-login :as email-login]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.passkey :as passkey]
            [authentication.core :as authn]
            [authentication.model :as authn-model]
            [authorization.core :as authz]
            [authorization.model :as authz-model]
            [authorization.ports :as authz-ports]
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
(def email-login-seconds 600)
(def email-login-cooldown-seconds 60)
(def account-id-pattern #"^[a-z0-9](?:[a-z0-9._-]{1,30}[a-z0-9])?$")
(def keychain-service "cloud-itonami-app.oauth")
(def default-identity-profile
  {:account-domain "cloud-itonami.app"
   :organization-domain-suffix "cloud-itonami.app"
   :organization-domain-overrides {}
   :publish-did-web? false})
(defonce runtime-identity-profile (atom default-identity-profile))
;; Provider -> {:service :account}: an OAuth client this deployment holds under
;; a name the application did not choose. See `referenced-client`.
(defonce runtime-oauth-clients (atom {}))
(defonce http-client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 8))
                         .build))

(defn configure!
  "Install the distribution/tenant profile for this process."
  [configuration]
  (reset! runtime-identity-profile
          (merge default-identity-profile (:identity configuration)))
  (reset! runtime-oauth-clients (or (:oauth-clients configuration) {})))

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
    ;; `gmail.modify` rather than `gmail.readonly`, and `gmail.send` beside
    ;; it. Read-only is the right default for a workspace that only shows you
    ;; mail, and it was the wrong one the moment the inbox grew a reply box
    ;; and a way to file a message: filing under a label and sending a reply
    ;; are both writes, and with a read-only grant they fail at the provider
    ;; with a 403 that no amount of local correctness prevents.
    ;;
    ;; `modify` covers reading, so this is one scope replacing one, not an
    ;; addition on top. It deliberately stops short of `mail.google.com`
    ;; (full account access, including permanent delete) — this app's trash
    ;; is reversible on purpose and never needs it.
    ;;
    ;; Existing connections keep working on their old grant until the person
    ;; reconnects; `prompt=consent` above means that reconnect actually
    ;; re-asks rather than silently reissuing the narrower scope set.
    :scopes ["openid" "email" "profile"
             "https://www.googleapis.com/auth/gmail.modify"
             "https://www.googleapis.com/auth/gmail.send"
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
    ;; `Mail.ReadWrite`/`Mail.Send` for the same reason Google gets
    ;; `gmail.modify`/`gmail.send`: `Mail.ReadBasic` cannot even read a
    ;; message body — it is headers and preview only — so a mailbox synced
    ;; under it arrives with nothing to show when somebody opens a message.
    :scopes ["openid" "email" "profile" "offline_access"
             "User.Read" "Mail.ReadWrite" "Mail.Send"
             "Files.Read" "Calendars.ReadBasic"]}})

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

(defn keychain-find
  "The password stored under `service` for `account`, or nil.

  Takes the service *and* the account by name because that is the only way
  this codebase reads a keychain: one item, both halves of its identity known
  in advance. There is deliberately no listing, no wildcard, and no
  enumeration — a caller that does not already know which item it wants has
  no business in the keychain at all."
  [service account]
  (try
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "find-generic-password"
                        "-s" service "-a" account "-w"])
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when (and completed? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 500 "")))))
    (catch Exception _ nil)))

(defn- configured-value [provider suffix]
  (let [config (get provider-catalog provider)
        env-name (get config (keyword (str suffix "-env")))]
    (or (some-> env-name System/getenv not-empty)
        (keychain-find (:credential-service config)
                       (str/upper-case (str/replace suffix "-" "_"))))))

;; A deployment may already hold an OAuth client for a provider under a name
;; this app did not choose — issued to some other tool on the same machine,
;; for the same person. Pointing at it beats copying it: a secret duplicated
;; into a second keychain item is a secret with two expiry dates and one
;; rotation.
;;
;; Named in full, service and account both, and empty unless configured.

(defn- referenced-client [provider]
  (when-let [{:keys [service account]} (get @runtime-oauth-clients provider)]
    (try
      (some-> (keychain-find service account) (json/read-str :key-fn keyword))
      (catch Exception _ nil))))

(defn provider-config [provider]
  (when-let [config (get provider-catalog provider)]
    (let [referenced (referenced-client provider)
          client-id (or (configured-value provider "client-id")
                        (:client_id referenced))
          client-secret (or (configured-value provider "client-secret")
                            (:client_secret referenced))]
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

(defn issue-session!
  "Mint a session for `user-id` and return its token.

  `opts` marks non-browser sessions. A browser session takes none and is
  `:kind :passkey` — the shape every caller had before agent sessions existed,
  so an unmarked record read back from an older store is a browser session and
  is treated as one.

    :kind        :passkey | :agent   what may present this token
    :label       free-form           what to call it when revoking
    :issued-via  :local-ownership    what was proved to get it
    :ttl-seconds                     defaults to `session-seconds`

  See `cloud.itonami.app.agent-session` for why `:agent` exists and for what it
  still cannot do."
  ([user-id] (issue-session! user-id nil))
  ([user-id {:keys [kind label issued-via ttl-seconds authn-level
                    authn-decision authn-factors]}]
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
           expires-at (str (.plusSeconds (Instant/now)
                                         (or ttl-seconds session-seconds)))]
       (store/transact!
        assoc-in [:identity :sessions session-id]
        (cond-> {:id session-id :user-id user-id
                 :organization-id (:organization-id membership)
                 :membership-id (:id membership) :token-digest (digest token)
                 :csrf csrf :created-at now :expires-at expires-at
                 :revoked? false
                 :kind (or kind :passkey)}
          label (assoc :label label)
          issued-via (assoc :issued-via issued-via)
          authn-level (assoc :authn-level authn-level)
          authn-decision (assoc :authn-decision authn-decision)
          authn-factors (assoc :authn-factors authn-factors)))
       {:token token :expires-at expires-at :session-id session-id
        :csrf csrf}))))

(defn- public-connection [connection]
  ;; :user-did is shown. Whose account a connection belongs to is exactly the
  ;; question the old org-wide slot could not answer, and a settings screen
  ;; listing "Microsoft 365 — connected" without saying connected-as is how a
  ;; shared machine ends up syncing one person's mailbox under another's name.
  (select-keys connection [:id :provider :status :display-name :email
                           :provider-subject :user-did :scopes :connected-at
                           :last-error]))

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

(defn user-did
  "The `did:key` of a local User — the identity this application actually has.

  A User's DID comes from the first P-256 public key its Passkey established
  (`docs/tenant-model.md`), so holding one is the same statement as 'this
  person has proved who they are on this machine'. Display name, mail address
  and Organization can all change without changing it, which is what makes it
  the right thing to bind an external account to."
  [state user-id]
  (get-in state [:users user-id :did]))

(defn- derive-user-did [state user]
  (some (fn [credential]
          (when (= (:id user) (:user-id credential))
            (try
              (did/did-key-from-cose (:public-key-cose credential))
              (catch Exception _ nil))))
        (vals (:passkeys state))))

(defn ensure-did-links!
  "Fill in DIDs that a store written by an older version does not carry:
  Users, Organizations, and — since connections became person-bound — the
  connections themselves.

  Public because `public-state` is not the only entry that depends on it: a
  caller resolving a token by DID needs the legacy connections stamped first,
  or they read as \"not connected\"."
  []
  (let [state (identity-state (store/snapshot))
        missing-user? (some #(and (:passkey-enrolled? %) (nil? (:did %)))
                            (vals (:users state)))
        missing-organization?
        (and (:publish-did-web? @runtime-identity-profile)
             (some #(and (:organization-id %) (nil? (:did %)))
                   (vals (:organizations state))))
        ;; Connections written before connections were bound to a person.
        ;; They carry :user-id, so the DID is derivable — and it has to be
        ;; derived, because a connection with no :user-did is invisible to
        ;; every DID-scoped lookup and would read as "not connected".
        missing-connection? (some #(and (:user-id %) (nil? (:user-did %)))
                                  (vals (:connections state)))]
    (when (or missing-user? missing-organization? missing-connection?)
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
            (fn [result [connection-id connection]]
              (if (and (:user-id connection) (nil? (:user-did connection)))
                ;; Read the DID out of the state being written, not the
                ;; snapshot taken before it: a user whose DID is filled in by
                ;; the pass above must be visible to this one, or a
                ;; first-run migration leaves the connection unbound.
                (if-let [did (get-in result [:identity :users
                                             (:user-id connection) :did])]
                  (assoc-in result [:identity :connections connection-id :user-did] did)
                  result)
                result))
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
            (:organizations identity-state))
            (:connections identity-state))))))))

(defn session-organization-did
  "The `did:web` of the organization this session belongs to, or nil.

  nil when the organization has not claimed an Organization ID yet, and a
  caller must treat that as \"no organization DID\" rather than substituting the
  issuer's own — `cloud.itonami.app.esign` puts this inside a signing
  commitment, and naming a DID that does not resolve would produce a signature
  that appears to be on behalf of an entity nobody can look up."
  [session]
  (get-in (identity-state (store/snapshot))
          [:organizations (:organization-id session) :did]))

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

(defn membership-credential-context
  "Everything `cloud.itonami.app.credential` needs to issue a membership
  credential for this session's ACTIVE membership.

  Lives here rather than in `credential` so that the credential namespace never
  reads identity's private state shape: it takes explicit inputs and stays
  testable without a session. The `:role` returned is the role of the active
  membership, so a user who belongs to two organizations gets a credential for
  the one they are currently acting in and not the union of both.

  Throws rather than returning a partial context. A membership credential naming
  a subject with no DID would be an assertion about nobody, and issuing one for
  an organization that has not claimed an Organization ID would name an issuer
  that does not exist yet."
  [session]
  (let [state (identity-state (store/snapshot))
        user (get-in state [:users (:user-id session)])
        membership (get-in state [:memberships (:membership-id session)])
        organization (get-in state [:organizations (:organization-id session)])]
    (when-not (:did user)
      (throw (ex-info "Credential を発行するには Passkey の登録が必要です。"
                      {:type :credential/no-subject-did})))
    (when-not (:role membership)
      (throw (ex-info "この session に有効な membership がありません。"
                      {:type :credential/no-membership})))
    (when-not (:organization-id organization)
      (throw (ex-info "Organization ID を設定してから Credential を発行してください。"
                      {:type :credential/organization-incomplete})))
    {:subject-did (:did user)
     :role (:role membership)
     ;; nil until the deployment publishes did:web — `credential` falls back to
     ;; the issuer did:key rather than naming an address that answers nothing.
     :organization-did (:did organization)
     :organization-domain (organization-domain-for-did-web)
     :organization-name (:name organization)}))

(defn membership-role
  "The role of this session's active membership, for authorization checks."
  [session]
  (get-in (identity-state (store/snapshot))
          [:memberships (:membership-id session) :role]))

(defn public-state [token]
  (ensure-did-links!)
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
     ;; Scoped to the signed-in person, not to the Organization. Two people in
     ;; one org each hold their own external grants, and listing a colleague's
     ;; Microsoft connection under "your connections" is how somebody clicks
     ;; Disconnect on an account that was never theirs.
     :connections (when session
                    (let [did (user-did state (:user-id session))]
                      (->> (:connections state)
                           vals
                           (filter #(and (= (:organization-id session)
                                            (:organization-id %))
                                         (= did (:user-did %))))
                           (mapv public-connection))))
     :providers
     (let [did (when session (user-did state (:user-id session)))]
       (mapv (fn [[provider config]]
               ;; `:connected?` used to be true if ANYBODY anywhere in the
               ;; state had connected this provider — across organizations,
               ;; not just across users. A second person then saw "connected"
               ;; and never linked their own account, while every sync ran on
               ;; the first person's grant.
               (let [connection (some #(when (and (= provider (:provider %))
                                                  (= did (:user-did %)))
                                         %)
                                      (vals (:connections state)))
                     provider (provider-config provider)]
                 {:id (name (:provider provider))
                  :name (:name config)
                  :configured? (:configured? provider)
                  :connected? (= :connected (:status connection))
                  :scopes (:scopes config)}))
             provider-catalog))}))

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
  "Begin a WebAuthn registration ceremony, repairing a missing user handle.

  `passkey/start-registration!` refuses a user with no `:user-handle` —
  WebAuthn requires a stable, opaque user id and there is nothing sensible to
  invent at that layer. Stores written before profile-free onboarding can lack
  it, and such a user could not enrol at all: the ceremony threw
  `:passkey/invalid-user` every time.

  That was survivable while merely *being enrolled* let a session act. It is
  not now — `may-act?` requires a session minted BY a ceremony, so a user who
  cannot start one cannot act at all. The repair is what keeps this from being
  a permanent lockout rather than a stale record.

  An existing credential's handle is reused before a new one is minted:
  registering a second authenticator for the same person must land on the same
  WebAuthn user, or the two credentials describe two users to the platform."
  [session rp-id origin]
  (let [state (identity-state (store/snapshot))
        user-id (:user-id session)
        user (get-in state [:users user-id])
        user-handle (or (:user-handle user)
                        (some #(when (= user-id (:user-id %)) (:user-handle %))
                              (vals (:passkeys state)))
                        (random-token 32))]
    ;; Persist before the ceremony, and only when it was actually absent: the
    ;; handle the authenticator is about to bind a credential to has to be the
    ;; one this store will still hold when the assertion comes back.
    (when-not (:user-handle user)
      (store/transact! assoc-in [:identity :users user-id :user-handle]
                       user-handle))
    (passkey/start-registration! (assoc user :user-handle user-handle)
                                 rp-id origin)))

(defn passkey-enrolled? [session]
  (true? (get-in (identity-state (store/snapshot))
                 [:users (:user-id session) :passkey-enrolled?])))

(defn human-session?
  "Whether this session belongs to a person at a browser rather than to a CLI or
  an MCP client.

  The money surface's rule, in one place. `payment-tools` and the server's
  `require-human-session!` both ask it — and before 2026-07-31 both asked
  something else instead: payment-tools checked `passkey-enrolled?`, which is
  about the USER, not the session. On an install where the owner HAS enrolled a
  Passkey — the normal case — an agent token passed that check, so the boundary
  held only where it happened not to be tested."
  [session]
  (not= :agent (:kind session)))

(defn may-act?
  "Whether this session has established who it is well enough to act.

  The one expression of that rule. `require-passkey!` is this plus a throw, and
  anything else asking the same question should ask it here rather than reach
  for `passkey-enrolled?` — a second spelling of one rule is how the two drift.

  Measured 2026-07-31: they had already drifted. `require-passkey!` learned about
  agent sessions and `payment-tools/session` did not, because it called
  `passkey-enrolled?` directly. That is now a DELIBERATE difference, documented
  where it is made, rather than an oversight — see that namespace."
  [session]
  (let [request (authz-model/request
                 (str "authz-" (UUID/randomUUID))
                 (:user-id session) :workspace/use :cloud-itonami-app
                 {:context {:session session}})
        port (reify authz-ports/IAuthorization
               (decide! [_ request]
                 (let [candidate (get-in request [:authz.request/context
                                                  :session])
                       allow? (case (:kind candidate)
                                :agent true
                                :email (and (= :email-magic-link
                                               (:issued-via candidate))
                                            (= :single-factor
                                               (:authn-level candidate))
                                            (= :authenticated
                                               (:authn-decision candidate)))
                                ;; A Passkey session must have been minted BY
                                ;; a Passkey ceremony, not merely belong to
                                ;; somebody who has one.
                                ;;
                                ;; This asked only `passkey-enrolled?`, which is
                                ;; a fact about the USER. Every browser session
                                ;; defaults to `:kind :passkey` (see
                                ;; `issue-session!`), so a token minted at
                                ;; registration — proving only that a browser
                                ;; holds it — started passing the moment its
                                ;; owner enrolled. Enrolment is not
                                ;; authentication, and it is certainly not
                                ;; retroactive authentication of a token that
                                ;; already existed.
                                ;;
                                ;; The markers are minted only by
                                ;; `passkey-session-options`, which derives them
                                ;; from the WebAuthn result through
                                ;; `authn/decide` — so a caller that never did
                                ;; the ceremony cannot set them.
                                ;; `passkey-enrolled?` stays too: a ceremony
                                ;; against a credential since removed is not a
                                ;; live proof.
                                :passkey (and (= :passkey (:issued-via candidate))
                                              (= :phishing-resistant
                                                 (:authn-level candidate))
                                              (passkey-enrolled? candidate))
                                false)]
                   (authz-model/decision
                    request (if allow? :allow :deny)
                    {:by :cloud-itonami/session-policy
                     :reason (when-not allow? :insufficient-session-proof)
                     :policy-ref :cloud-itonami/session-may-act
                     :policy-version 1}))))]
    (= :allow (:authz.decision/decision (authz/authorize port request)))))

(defn- normalized-email [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn- email-login-user [state email]
  (let [matches (->> (:users state)
                     vals
                     (filter (fn [user]
                               (contains? #{(normalized-email (:email user))
                                            (normalized-email (:contact-email user))}
                                          email)))
                     (filter #(and (= :active (:status %))
                                   (true? (:passkey-enrolled? %))))
                     vec)]
    (when (= 1 (count matches)) (first matches))))

(defn start-email-authentication!
  "Create and deliver a private one-time proof, while always returning the same
  public result. Unknown, ambiguous, inactive and unrooted addresses do not
  create a transaction or call the delivery adapter."
  [configuration email]
  (let [email (normalized-email email)
        state (identity-state (store/snapshot))
        user (when email (email-login-user state email))
        now (System/currentTimeMillis)
        cooldown-ms (* 1000 email-login-cooldown-seconds)
        recent? (some (fn [transaction]
                        (and (= (:id user)
                                (:identity.email-challenge/user-id transaction))
                             (not (:identity.email-challenge/consumed? transaction))
                             (< (- now (:identity.email-challenge/created-at
                                        transaction 0))
                                cooldown-ms)))
                      (vals (:email-login-transactions state)))]
    (when (and (email-login/configured? configuration) user (not recent?))
      (let [token (random-token 32)
            transaction-id (str "email-login-" (UUID/randomUUID))
            expires-at (+ now (* 1000 email-login-seconds))
            destination (or (normalized-email (:contact-email user))
                            (normalized-email (:email user)))
            origin (str/replace (get-in configuration [:server :public-origin])
                                #"/+$" "")]
        (store/transact!
         assoc-in [:identity :email-login-transactions transaction-id]
         {:identity.email-challenge/id transaction-id
          :identity.email-challenge/user-id (:id user)
          :identity.email-challenge/token-digest (digest token)
          :identity.email-challenge/created-at now
          :identity.email-challenge/expires-at expires-at
          :identity.email-challenge/consumed? false})
        (try
          (email-login/deliver!
           configuration
           {:to destination
            :magic-link (str origin "/#email-login=" token)
            :expires-at (str (Instant/ofEpochMilli expires-at))})
          (catch Exception error
            (store/transact!
             update :events conj
             {:type :identity/email-login-delivery-failed
              :at (store/now) :user-id (:id user)
              :transaction-id transaction-id
              :reason (.getMessage error)})))))
    {:accepted true}))

(defn finish-email-authentication! [token]
  (let [token (some-> token str str/trim not-empty)
        token-digest (when token (digest token))
        now (System/currentTimeMillis)
        transaction
        (some (fn [candidate]
                (let [expected (:identity.email-challenge/token-digest candidate)]
                  (when (and expected token-digest
                             (MessageDigest/isEqual
                              (.getBytes ^String expected StandardCharsets/UTF_8)
                              (.getBytes ^String token-digest StandardCharsets/UTF_8))
                             (not (:identity.email-challenge/consumed? candidate))
                             (> (:identity.email-challenge/expires-at candidate 0)
                                now))
                    candidate)))
              (vals (:email-login-transactions
                     (identity-state (store/snapshot)))))]
    (when-not transaction
      (throw (ex-info "Email ログインリンクが無効または期限切れです。"
                      {:type :email-login/invalid-token})))
    (let [transaction-id (:identity.email-challenge/id transaction)
          user-id (:identity.email-challenge/user-id transaction)]
      (store/transact!
       (fn [state]
         (let [current (get-in state [:identity :email-login-transactions
                                      transaction-id])]
           (when (:identity.email-challenge/consumed? current)
             (throw (ex-info "Email ログインリンクは使用済みです。"
                             {:type :email-login/invalid-token})))
           (-> state
               (assoc-in [:identity :email-login-transactions transaction-id
                          :identity.email-challenge/consumed?] true)
               (assoc-in [:identity :email-login-transactions transaction-id
                          :identity.email-challenge/consumed-at] now)))))
      (let [request (authn-model/request
                     (str "authn-" (UUID/randomUUID)) user-id
                     {:required-level :single-factor
                      :purpose :email-login :created-at (store/now)})
            factor (authn-model/factor
                    (str "factor-" (UUID/randomUUID)) :email true
                    {:subject user-id :evidence-ref transaction-id
                     :at (store/now)})
            decision (authn/decide request [factor])]
        (when-not (= :authenticated (:authn.decision/decision decision))
          (throw (ex-info "Email 認証を完了できませんでした。"
                          {:type :email-login/invalid-token})))
        (issue-session!
         user-id {:kind :email :issued-via :email-magic-link
                  :authn-level (:authn.decision/level decision)
                  :authn-decision (:authn.decision/decision decision)
                  :authn-factors [:email]})))))

(defn require-passkey!
  "Refuse a session that has not established who it is.

  For a browser session that means an enrolled Passkey: the loopback server is
  reachable by every process and page on this machine, and a half-enrolled user
  must not act.

  An `:agent` session satisfies it by a different root — it was minted against a
  0600 file inside the data directory, and anything that can read that file can
  already rewrite `state.edn` and mint itself whatever it likes. Requiring a
  Passkey on top of that refuses the operator and stops nobody else. See
  `cloud.itonami.app.agent-session`; the check is inline here rather than
  delegated because that namespace requires this one.

  This is not the approval gate. `approve/finish` needs a WebAuthn
  user-verifying assertion and no agent can produce one (ADR-0006); that stays
  exactly where it was."
  [session]
  (when-not (may-act? session)
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

(defn- passkey-session-options
  "The session markers a Passkey ceremony earns, or a refusal.

  `may-act?` requires `:issued-via :passkey` and
  `:authn-level :phishing-resistant` on a `:kind :passkey` session, and this is
  the only thing that mints them — so a caller that did not complete a ceremony
  cannot set them.

  The level is not asserted, it is DECIDED: the WebAuthn result becomes an
  `authentication` factor carrying its own `:verified?` and assurance, and
  `authn/decide` is asked whether that reaches `:phishing-resistant`. A ceremony
  that came back unverified therefore throws here rather than producing a
  session that merely claims the level. Same shape
  `finish-email-authentication!` already uses for the magic-link factor."
  [result purpose]
  (let [factor (authn-model/factor
                (:credential-id result) :passkey (boolean (:verified? result))
                {:subject (:user-id result)
                 :evidence-ref (:credential-id result)
                 :assurance :user-verifying})
        request (authn-model/request
                 (str "authn-" (UUID/randomUUID)) (:user-id result)
                 {:required-level :phishing-resistant
                  :purpose purpose
                  :created-at (store/now)})
        decision (authn/decide request [factor])]
    (when-not (= :authenticated (:authn.decision/decision decision))
      (throw (ex-info "Passkey 認証保証が不足しています。"
                      {:type :passkey/verification-failed})))
    {:kind :passkey
     :issued-via :passkey
     :authn-ref (:authn.decision/request-id decision)
     :authn-level (:authn.decision/level decision)
     :authn-decision (:authn.decision/decision decision)
     :authn-factors [:passkey]}))

(defn finish-passkey-registration! [session transaction-id response]
  (let [result (passkey/finish-registration!
                transaction-id response (:user-id session))
        assurance (passkey-session-options result :registration)]
    ;; Registration itself IS a user-verifying WebAuthn ceremony, so the
    ;; session that initiated it is upgraded in place rather than making
    ;; somebody perform an identical assertion one second later. Without this
    ;; the whole onboarding path — register, create a credential, configure the
    ;; organization — would be locked out by the rule above, since the browser
    ;; still holds the token `register!` minted.
    (store/transact!
     update-in [:identity :sessions (:id session)] merge assurance)
    result))

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
      (merge result (issue-session! (:user-id result)
                                    (passkey-session-options result :enrollment))))))

(defn start-passkey-authentication! [rp-id origin]
  (passkey/start-authentication! rp-id origin))

(defn finish-passkey-authentication! [transaction-id response]
  (let [result (passkey/finish-authentication! transaction-id response)]
    (merge result (issue-session! (:user-id result)
                                  (passkey-session-options result :login)))))

(defn- callback-uri [origin provider]
  (str origin "/api/oauth/" (name provider) "/callback"))

(defn start-oauth!
  "Begin binding an external account (Microsoft 365 / Google / GitHub) to the
  signed-in person.

  The connection is bound to that person's `did:key`, so it cannot begin before
  there is one: a User without an enrolled Passkey has not proved who they are,
  and an external grant attached to them would belong to whoever reached the
  loopback server first. Refusing here rather than at the callback means the
  consent screen never appears, so nobody hands Microsoft a password for a link
  this app was never going to make."
  [session provider origin]
  (let [{:keys [configured? client-id authorization-endpoint scopes
                authorization-extra] :as config}
        (provider-config provider)
        did (user-did (identity-state (store/snapshot)) (:user-id session))]
    (when-not config
      (throw (ex-info "未対応の接続先です。" {:type :oauth/unsupported})))
    (when-not configured?
      (throw (ex-info "OAuth クライアントが未設定です。"
                      {:type :oauth/not-configured :provider provider})))
    (when (str/blank? (str did))
      (throw (ex-info "外部アカウントを接続する前に Passkey の登録が必要です。接続は did:key に結ばれます。"
                      {:type :passkey/required :provider provider})))
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
                    ;; Carried through the round trip so the callback binds to
                    ;; the person who *started* it. Re-reading the session's
                    ;; DID at callback time would bind whoever is signed in
                    ;; when the browser comes back.
                    :user-did did
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

(defn- keychain-account
  "Where one grant's token lives.

  Qualified by the provider subject — the external account's own id — because
  a person may hold more than one mailbox at the same provider and the two
  grants are different secrets. Without the subject, connecting a second
  Google account wrote its token over the first one's slot, and the first
  connection went on reporting itself connected while every sync for it
  returned the second account's mail.

  `subject` is nil for a caller that has not read the profile yet; that
  produces the historical unqualified name, which is what `keychain-prefix`
  falls back to for grants stored before this was qualified."
  ([transaction token-kind] (keychain-account transaction token-kind nil))
  ([transaction token-kind subject]
   (str (:organization-id transaction) ":" (:user-id transaction) ":"
        (name (:provider transaction)) ":"
        (when-not (str/blank? (str subject)) (str subject ":"))
        (name token-kind))))

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
  (keychain-find keychain-service account))

(defn connections-for
  "Every live connection for `provider`, optionally narrowed to one person's
  `did:key`."
  ([provider] (connections-for provider nil))
  ([provider did]
   (->> (:connections (identity-state (store/snapshot)))
        vals
        (filter #(and (= provider (:provider %))
                      (= :connected (:status %))
                      (or (nil? did) (= did (:user-did %)))))
        (sort-by :id)
        vec)))

(defn connection-for
  "The connection for `provider` belonging to `did`.

  Called without a DID it resolves only when the answer is unambiguous — one
  live connection — and returns nil when there are several. It used to take
  `first`, which is the shape mail-sync's own comment warns about: *'an
  application that reaches for whichever Google token is on the machine is an
  application that reads mail it was never pointed at.'* That reasoning did not
  stop at the machine boundary; picking the first of several connections inside
  this app is the same act, and the caller cannot even tell it happened.

  Refusing costs a single-user deployment nothing (there is one connection, and
  it is returned) and costs a multi-user one an error where it used to get
  somebody else's mailbox."
  ([provider] (connection-for provider nil))
  ([provider did]
   (let [cs (connections-for provider did)]
     (cond
       (empty? cs) nil
       (= 1 (count cs)) (first cs)
       did (first cs)                    ; a DID already names exactly one person
       :else (throw (ex-info (str (name provider)
                                  " の接続が複数あります。どの利用者の接続を使うか指定してください。")
                             {:type :oauth/ambiguous-connection
                              :provider provider
                              :dids (mapv :user-did cs)}))))))

(defn- keychain-prefix
  "Where this connection's tokens live.

  Still NOT keyed by DID — the DID governs *which connection* is chosen, and
  renaming by it would strand every token already in the Keychain behind a
  name nothing looks up. It IS keyed by the provider subject, because that
  names the external account rather than the person, and one person may hold
  two mailboxes at one provider. Those are two secrets and they need two
  slots; sharing one was how the second Google account overwrote the first.

  Returns the qualified prefix. `keychain-token` is what callers should use,
  since it also knows the unqualified name grants stored before this change
  are still sitting under."
  [connection]
  (str (:organization-id connection) ":" (:user-id connection) ":"
       (name (:provider connection)) ":"
       (when-not (str/blank? (str (:provider-subject connection)))
         (str (:provider-subject connection) ":"))))

(defn- legacy-keychain-prefix
  "The unqualified name a grant was stored under before subjects qualified it."
  [connection]
  (str (:organization-id connection) ":" (:user-id connection) ":"
       (name (:provider connection)) ":"))

(defn- keychain-token
  "One of this connection's tokens, by the qualified name or the name it was
  written under before subjects were part of it.

  The fallback is deliberately one-way and read-only: nothing re-writes an old
  grant under the new name, because doing so on a read would move a secret as a
  side effect of looking at it. A reconnect writes the qualified name; until
  then the old grant keeps working where it lies."
  [connection token-kind]
  (or (keychain-get (str (keychain-prefix connection) (name token-kind)))
      (keychain-get (str (legacy-keychain-prefix connection)
                         (name token-kind)))))

(defn access-token
  "Resolve one person's provider token from Keychain. Never returns a token
  reference or token through an HTTP/public view."
  ([provider] (access-token provider nil))
  ([provider did]
   (when-let [connection (connection-for provider did)]
     (keychain-token connection :access))))

(defn connected-providers
  "The providers a connection exists for, optionally for one person only.

  Without a `did` this stays deployment-wide because its callers are asking
  what this process could reach at all, not what any one person may see. The
  narrowing that matters happens at token resolution, where `connection-for`
  refuses to pick between two people.

  Note that a *provider* is no longer the unit a mail sync walks — see
  `mail-account/accounts`, which is per mailbox, because one person can hold
  two Google accounts and 'is Google connected' stops being a useful question
  at that point. This remains for callers asking the coarser question."
  ([] (connected-providers nil))
  ([did]
   (->> (:connections (identity-state (store/snapshot)))
        vals
        (keep #(when (and (= :connected (:status %))
                          (or (nil? did) (= did (:user-did %))))
                 (:provider %)))
        set)))

;; `access-token` hands back whatever the authorization-code exchange wrote,
;; which for Google stops working an hour after somebody clicked Connect.
;; That was survivable while every caller was a person watching the screen.
;; A background sync is not: it runs on a timer for as long as the process
;; lives, so it needs the refresh grant the connect flow never had.
;;
;; The cache exists because refreshing is a network round trip against the
;; provider and the sync loop asks once a minute. It is in-memory on purpose:
;; a restart should re-derive the token rather than trust one it cannot check.

(defonce ^:private access-token-cache (atom {}))

(def ^:private token-skew-seconds 60)

(defn- cached-access-token [cache-key]
  (let [{:keys [token expires-at]} (get @access-token-cache cache-key)]
    (when (and token expires-at
               (pos? (compare expires-at
                              (.plusSeconds (Instant/now) token-skew-seconds))))
      token)))

(defn- cache-access-token! [cache-key token expires-in]
  (swap! access-token-cache assoc cache-key
         {:token token
          :expires-at (.plusSeconds (Instant/now) (long (or expires-in 3600)))})
  token)

(defn refresh-access-token!
  "Trade `refresh-token` for a fresh access token and cache it.

  Returns the access token, or nil when the provider refuses — a revoked or
  expired grant is an ordinary outcome here, not an error worth unwinding a
  background sync over, and the caller finds out by getting nil.

  `cache-key` defaults to `provider`, but a caller resolving one person's grant
  passes `[provider did]`. Caching a per-person token under the bare provider
  keyword would hand the next caller a token minted for somebody else."
  ([provider refresh-token] (refresh-access-token! provider refresh-token provider))
  ([provider refresh-token cache-key]
  (let [config (provider-config provider)]
    (when (and (:configured? config) (not (str/blank? refresh-token)))
      (try
        (let [body (form-body {:grant_type "refresh_token"
                               :refresh_token refresh-token
                               :client_id (:client-id config)
                               :client_secret (:client-secret config)})
              request (-> (HttpRequest/newBuilder
                           (URI/create (:token-endpoint config)))
                          (.header "Content-Type"
                                   "application/x-www-form-urlencoded")
                          (.header "Accept" "application/json")
                          (.POST (HttpRequest$BodyPublishers/ofString body))
                          .build)
              token (request-json! request)
              access (:access_token token)]
          (when-not (str/blank? access)
            (cache-access-token! cache-key access (:expires_in token))))
        (catch Exception _ nil))))))

(defn provider-access-token!
  "A currently-valid access token for `provider`, refreshing if it can.

  Prefers the refresh token this app stored at connect time. Falls back to
  the access token itself for providers whose grants do not expire (GitHub),
  where there is nothing to refresh and the stored value is the answer.

  `did` names whose grant to use. Omitting it resolves only when there is
  exactly one connection (see `connection-for`); the cache is keyed by the pair
  so two people's tokens can never be served from one another's slot."
  ([provider] (provider-access-token! provider nil))
  ([provider did]
   (let [cache-key [provider did]]
     (or (cached-access-token cache-key)
         (when-let [connection (connection-for provider did)]
           (let [prefix (keychain-prefix connection)]
             (or (some->> (keychain-get (str prefix "refresh"))
                          (#(refresh-access-token! provider % cache-key)))
                 (keychain-get (str prefix "access")))))))))

(defn connection-access-token!
  "A currently-valid access token for one named connection.

  The per-connection form of `provider-access-token!`, and the one a mail sync
  wants. `provider-access-token!` answers *'the token for this provider'*,
  which stops being a question with an answer as soon as a person connects two
  Google accounts — `connection-for` is right to refuse to guess between them.
  A sync that walks accounts already knows which one it is holding, so it
  should not have to re-derive it from a provider name that no longer
  identifies anything.

  Keyed in the cache by connection id, so two mailboxes at one provider can
  never be served from one another's slot."
  [connection]
  (when connection
    (let [provider (:provider connection)
          cache-key [:connection (:id connection)]]
      (or (cached-access-token cache-key)
          (some->> (keychain-token connection :refresh)
                   (#(refresh-access-token! provider % cache-key)))
          (keychain-token connection :access)))))

;; A delegated credential is one this application did not obtain and does not
;; own: an access grant some other tool on this machine already holds for the
;; same person and the same scope. Reusing one is how a local-first workspace
;; avoids marching its owner through a second consent screen to read the same
;; mailbox twice.
;;
;; It is opt-in by naming, and only by naming. The caller passes the exact
;; keychain service and account; nothing here searches, guesses a slug, or
;; falls back to a default identity. A tenant-neutral application that read
;; whatever Google credential happened to be lying around would be reading
;; somebody's mail on the strength of a coincidence.

(defn delegated-access-token!
  "Refresh `provider`'s access token from a credential another tool owns.

  `client-service`/`client-account` name a keychain item holding the OAuth
  client as JSON (`client_id`, `client_secret`); `refresh-service`/
  `refresh-account` name one holding that account's refresh token. Returns
  nil if either is absent or the provider refuses the grant."
  [provider {:keys [client-service client-account
                    refresh-service refresh-account]}]
  (when (and client-service client-account refresh-service refresh-account)
    (or
     (cached-access-token [:delegated provider refresh-service refresh-account])
     (try
      (let [client (some-> (keychain-find client-service client-account)
                           (json/read-str :key-fn keyword))
            refresh-token (keychain-find refresh-service refresh-account)
            endpoint (get-in provider-catalog [provider :token-endpoint])]
        (when (and (:client_id client) (:client_secret client)
                   refresh-token endpoint)
          (let [body (form-body {:grant_type "refresh_token"
                                 :refresh_token refresh-token
                                 :client_id (:client_id client)
                                 :client_secret (:client_secret client)})
                request (-> (HttpRequest/newBuilder (URI/create endpoint))
                            (.header "Content-Type"
                                     "application/x-www-form-urlencoded")
                            (.header "Accept" "application/json")
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            .build)
                token (request-json! request)
                access (not-empty (str (:access_token token)))]
            (when access
              (cache-access-token!
               [:delegated provider refresh-service refresh-account]
               access (:expires_in token))))))
      (catch Exception _ nil)))))

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
          provider-subject (str (or (:sub profile) (:id profile)
                                    (:userPrincipalName profile)))
          access-ref (keychain-put! (keychain-account transaction :access
                                                      provider-subject)
                                    access-token)
          refresh-ref (keychain-put! (keychain-account transaction :refresh
                                                      provider-subject)
                                     (:refresh_token token))
          ;; Keyed by DID *and* by the external account. Keying by DID alone
          ;; fixed one overwrite — the second person to connect Microsoft used
          ;; to erase the first — and left the mirror image of it in place:
          ;; one person with two Gmail accounts still had one slot, so the
          ;; second mailbox they connected replaced the first, which went on
          ;; showing as connected while syncing the other account's mail.
          ;;
          ;; The subject rather than the address, because an address can be
          ;; reassigned and an alias can deliver to a mailbox whose primary
          ;; address is something else; the subject is what the provider
          ;; itself considers the account.
          connection-id (str (:organization-id transaction) ":"
                             (:user-did transaction) ":" (name provider)
                             ":" provider-subject)
          ;; One external account is one person. Binding the same Microsoft
          ;; subject to a second DID would let two local users act as each
          ;; other upstream, and nothing downstream could tell them apart —
          ;; the provider sees one account either way.
          _ (when-let [held (some (fn [c]
                                    (when (and (= provider (:provider c))
                                               (= provider-subject (:provider-subject c))
                                               (:user-did c)
                                               (not= (:user-did transaction) (:user-did c)))
                                      c))
                                  (vals (:connections current)))]
              (throw (ex-info "この外部アカウントは既に別の利用者に接続されています。"
                              {:type :oauth/subject-already-bound
                               :provider provider
                               :bound-to (:user-did held)})))
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
             ;; The same account under its pre-subject id, if it is there.
             ;; Reconnecting used to land on the identical key and replace the
             ;; record; now that the key carries the subject it would leave the
             ;; old one behind, and one mailbox would be listed — and synced —
             ;; twice. Removed rather than migrated in place, because the
             ;; record being written on the next line IS the migration.
             (update-in [:identity :connections]
                        (fn [connections]
                          (into {}
                                (remove (fn [[id c]]
                                          (and (not= id connection-id)
                                               (= provider (:provider c))
                                               (= provider-subject
                                                  (:provider-subject c))
                                               (= (:user-did transaction)
                                                  (:user-did c)))))
                                connections)))
             (assoc-in [:identity :connections connection-id]
                       {:id connection-id :provider provider :status :connected
                        :organization-id (:organization-id transaction)
                        :user-id (:user-id transaction)
                        :user-did (:user-did transaction)
                        :provider-subject provider-subject :email email
                        :display-name display-name :scopes scopes
                        :access-token-ref access-ref :refresh-token-ref refresh-ref
                        :connected-at now})
             (update :events conj {:type :oauth/connected :at now
                                   :provider provider
                                   :organization-id (:organization-id transaction)}))))
      {:provider provider :connected? true
       :connection-id connection-id :email email})))
