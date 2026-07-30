(ns cloud.itonami.app.passkey
  "Server-side WebAuthn ceremonies backed by the local identity store.

  The browser only performs authenticator operations. Challenge, RP/origin,
  credential ownership, signature, user verification, and counter validation
  are all enforced here by Yubico's WebAuthn server implementation."
  (:require [cloud.itonami.app.did :as did]
            [cloud.itonami.app.store :as store]
            [clojure.data.json :as json]
            [identity.model :as identity])
  (:import [com.yubico.webauthn AssertionRequest CredentialRepository
            FinishAssertionOptions FinishRegistrationOptions RegisteredCredential
            RelyingParty StartAssertionOptions StartRegistrationOptions]
           [com.yubico.webauthn.data AuthenticatorSelectionCriteria ByteArray
            PublicKeyCredential PublicKeyCredentialCreationOptions
            PublicKeyCredentialDescriptor PublicKeyCredentialParameters
            RelyingPartyIdentity
            ResidentKeyRequirement UserIdentity UserVerificationRequirement]
           [java.time Instant]
           [java.util Optional UUID]))

(def transaction-seconds 300)

(defn- identity-state []
  (merge {:users {} :memberships {} :passkeys {} :webauthn-transactions {}}
         (:identity (store/snapshot))))

(defn- webauthn-bytes [value]
  (ByteArray/fromBase64Url value))

(defn- credential-record [credential]
  (-> (RegisteredCredential/builder)
      (.credentialId (webauthn-bytes (:credential-id credential)))
      (.userHandle (webauthn-bytes (:user-handle credential)))
      (.publicKeyCose (webauthn-bytes (:public-key-cose credential)))
      (.signatureCount (long (:signature-count credential 0)))
      .build))

(defn- descriptor [credential]
  (-> (PublicKeyCredentialDescriptor/builder)
      (.id (webauthn-bytes (:credential-id credential)))
      .build))

(defn- repository []
  (reify CredentialRepository
    (getCredentialIdsForUsername [_ username]
      (let [state (identity-state)
            user (some #(when (= username (:email %)) %) (vals (:users state)))]
        (set (map descriptor
                  (filter #(= (:id user) (:user-id %))
                          (vals (:passkeys state)))))))
    (getUserHandleForUsername [_ username]
      (let [user (some #(when (= username (:email %)) %)
                       (vals (:users (identity-state))))]
        (if-let [handle (:user-handle user)]
          (Optional/of (byte-array handle))
          (Optional/empty))))
    (getUsernameForUserHandle [_ user-handle]
      (let [encoded (.getBase64Url ^ByteArray user-handle)
            user (some #(when (= encoded (:user-handle %)) %)
                       (vals (:users (identity-state))))]
        (if-let [username (:email user)]
          (Optional/of username)
          (Optional/empty))))
    (lookup [_ credential-id user-handle]
      (let [credential-id (.getBase64Url ^ByteArray credential-id)
            user-handle (.getBase64Url ^ByteArray user-handle)
            credential (some #(when (and (= credential-id (:credential-id %))
                                         (= user-handle (:user-handle %)))
                                %)
                             (vals (:passkeys (identity-state))))]
        (if credential
          (Optional/of (credential-record credential))
          (Optional/empty))))
    (lookupAll [_ credential-id]
      (let [encoded (.getBase64Url ^ByteArray credential-id)]
        (set (map credential-record
                  (filter #(= encoded (:credential-id %))
                          (vals (:passkeys (identity-state))))))))))

(defn- relying-party [rp-id origin]
  (let [rp-identity (-> (RelyingPartyIdentity/builder)
                        (.id rp-id)
                        (.name "Cloud Itonami")
                        .build)]
    (-> (RelyingParty/builder)
        (.identity rp-identity)
        (.credentialRepository (repository))
        ;; User DIDs are did:key identifiers rooted in a compressed P-256 key.
        (.preferredPubkeyParams [PublicKeyCredentialParameters/ES256])
        (.origins #{origin})
        (.allowOriginPort false)
        (.allowOriginSubdomain false)
        (.allowUntrustedAttestation true)
        (.validateSignatureCounter true)
        .build)))

(defn- active-transaction! [transaction-id expected-kind]
  (let [transaction (get-in (identity-state)
                            [:webauthn-transactions transaction-id])]
    (when-not (and transaction
                   (= expected-kind (:kind transaction))
                   (not (:used? transaction))
                   (pos? (compare (Instant/parse (:expires-at transaction))
                                  (Instant/now))))
      (throw (ex-info "Passkey 要求が無効、期限切れ、または使用済みです。"
                      {:type :passkey/invalid-transaction})))
    ;; Consume before verification so a captured response cannot be replayed.
    (store/transact! assoc-in
                     [:identity :webauthn-transactions transaction-id :used?]
                     true)
    transaction))

(defn start-registration!
  [{:keys [id email display-name user-handle]} rp-id origin]
  (let [rp (relying-party rp-id origin)
        user (-> (UserIdentity/builder)
                 (.name email)
                 (.displayName display-name)
                 (.id (webauthn-bytes user-handle))
                 .build)
        selection (-> (AuthenticatorSelectionCriteria/builder)
                      (.residentKey ResidentKeyRequirement/REQUIRED)
                      (.userVerification UserVerificationRequirement/REQUIRED)
                      .build)
        options (.startRegistration
                 rp
                 (-> (StartRegistrationOptions/builder)
                     (.user user)
                     (.authenticatorSelection selection)
                     (.timeout 120000)
                     .build))
        transaction-id (str "webauthn-" (UUID/randomUUID))
        expires-at (str (.plusSeconds (Instant/now) transaction-seconds))]
    (store/transact!
     assoc-in [:identity :webauthn-transactions transaction-id]
     {:id transaction-id :kind :registration :user-id id
      :request-json (.toJson options) :origin origin :rp-id rp-id
      :created-at (store/now) :expires-at expires-at :used? false})
    {:transaction-id transaction-id
     :options (json/read-str (.toCredentialsCreateJson options)
                             :key-fn keyword)
     :expires-at expires-at}))

(defn finish-registration!
  [transaction-id credential-response expected-user-id]
  (let [{:keys [request-json origin rp-id user-id]}
        (let [transaction (get-in (identity-state)
                                  [:webauthn-transactions transaction-id])]
          (when-not (= expected-user-id (:user-id transaction))
            (throw (ex-info "Passkey 登録対象が一致しません。"
                            {:type :passkey/invalid-transaction})))
          (active-transaction! transaction-id :registration))
        request (PublicKeyCredentialCreationOptions/fromJson request-json)
        response (PublicKeyCredential/parseRegistrationResponseJson
                  (json/write-str credential-response))
        result (.finishRegistration
                (relying-party rp-id origin)
                (-> (FinishRegistrationOptions/builder)
                    (.request request)
                    (.response response)
                    .build))
        user (get-in (identity-state) [:users user-id])
        credential-id (.getBase64Url (.getId (.getKeyId result)))
        public-key-cose (.getBase64Url (.getPublicKeyCose result))
        user-did (did/did-key-from-cose public-key-cose)
        now (store/now)]
    (when-not (.isUserVerified result)
      (throw (ex-info "Authenticator がユーザー確認を完了していません。"
                      {:type :passkey/user-verification-required})))
    (store/transact!
     (fn [state]
       (-> state
           (assoc-in [:identity :passkeys credential-id]
                     {:id credential-id :credential-id credential-id
                      :user-id user-id :user-handle (:user-handle user)
                      :public-key-cose public-key-cose
                      :did user-did
                      :signature-count (.getSignatureCount result)
                      :backup-eligible? (.isBackupEligible result)
                      :backed-up? (.isBackedUp result)
                      :created-at now :last-used-at nil})
           (assoc-in [:identity :users user-id :did] user-did)
           (assoc-in [:identity :users user-id :subject]
                     (identity/subject user-did :person
                                       {:did user-did
                                        :labels #{:local :passkey}}))
           (assoc-in [:identity :users user-id :passkey-enrolled?] true)
           (assoc-in [:identity :users user-id :status] :active)
           (update :events conj {:type :passkey/registered :at now
                                 :user-id user-id
                                 :credential-id credential-id}))))
    {:user-id user-id :credential-id credential-id
     :did user-did :verified? true}))

(defn- start-assertion!
  "Begin a user-verifying assertion. `kind` distinguishes a plain sign-in
  (`:assertion`) from an operation-bound authorization (`:authorization`), and
  `transaction-data` is merged into the stored transaction so an authorization can
  bind server-side facts a later response cannot alter."
  [kind transaction-data rp-id origin]
  (let [rp (relying-party rp-id origin)
        request (.startAssertion
                 rp
                 (-> (StartAssertionOptions/builder)
                     (.userVerification UserVerificationRequirement/REQUIRED)
                     (.timeout 120000)
                     .build))
        transaction-id (str "webauthn-" (UUID/randomUUID))
        expires-at (str (.plusSeconds (Instant/now) transaction-seconds))]
    (store/transact!
     assoc-in [:identity :webauthn-transactions transaction-id]
     (merge
      {:id transaction-id :kind kind
       :request-json (.toJson request) :origin origin :rp-id rp-id
       :created-at (store/now) :expires-at expires-at :used? false}
      transaction-data))
    {:transaction-id transaction-id
     :options (json/read-str (.toCredentialsGetJson request) :key-fn keyword)
     :expires-at expires-at}))

(defn start-authentication! [rp-id origin]
  (start-assertion! :assertion {} rp-id origin))

(defn start-authorization!
  "Start a user-verifying Passkey assertion bound server-side to an immutable
  operation context, such as a PSBT digest or a proposal digest.

  The context is stored with the transaction and returned by
  `finish-authorization!`, so the caller can verify that what the human approved
  is what it is about to act on. Binding it here rather than trusting the client
  to echo it back is the whole point: a client-supplied context could be swapped
  after the user consented.

  A separate `:kind` from `:assertion` matters -- without it a plain sign-in
  assertion could be presented to `finish-authorization!` and would carry no
  context at all, so an operation would appear approved by a user who only
  logged in. `active-transaction!` enforces the kind."
  [user-id context rp-id origin]
  (start-assertion! :authorization
                    {:expected-user-id user-id
                     :authorization-context context}
                    rp-id origin))

(defn- finish-assertion!
  [transaction-id credential-response expected-kind]
  (let [{:keys [request-json origin rp-id expected-user-id
                authorization-context]}
        (active-transaction! transaction-id expected-kind)
        request (AssertionRequest/fromJson request-json)
        response (PublicKeyCredential/parseAssertionResponseJson
                  (json/write-str credential-response))
        result (.finishAssertion
                (relying-party rp-id origin)
                (-> (FinishAssertionOptions/builder)
                    (.request request)
                    (.response response)
                    .build))
        credential-id (.getBase64Url (.getCredentialId result))
        user-handle (.getBase64Url (.getUserHandle result))
        user (some #(when (= user-handle (:user-handle %)) %)
                   (vals (:users (identity-state))))
        now (store/now)]
    ;; expected-user-id is nil for a plain sign-in and set for an authorization:
    ;; an authorization must be completed by the SAME user it was started for,
    ;; or one signed-in user could complete another's pending approval.
    (when-not (and (.isSuccess result) (.isUserVerified result) user
                   (or (nil? expected-user-id)
                       (= expected-user-id (:id user))))
      (throw (ex-info "Passkey 認証を確認できませんでした。"
                      {:type :passkey/verification-failed})))
    (store/transact!
     (fn [state]
       (-> state
           (assoc-in [:identity :passkeys credential-id :signature-count]
                     (.getSignatureCount result))
           (assoc-in [:identity :passkeys credential-id :backed-up?]
                     (.isBackedUp result))
           (assoc-in [:identity :passkeys credential-id :last-used-at] now)
           (update :events conj {:type :passkey/authenticated :at now
                                 :user-id (:id user)
                                 :credential-id credential-id}))))
    (cond-> {:user-id (:id user) :credential-id credential-id :verified? true}
      (some? authorization-context)
      (assoc :authorization-context authorization-context))))

(defn finish-authentication! [transaction-id credential-response]
  (finish-assertion! transaction-id credential-response :assertion))

(defn finish-authorization!
  "Complete an operation-bound assertion. Returns the sign-in result plus
  `:authorization-context` exactly as it was stored at `start-authorization!`,
  so the caller can compare it against what it is about to act on."
  [transaction-id credential-response]
  (finish-assertion! transaction-id credential-response :authorization))
