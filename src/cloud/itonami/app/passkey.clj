(ns cloud.itonami.app.passkey
  "Server-side WebAuthn ceremony orchestration.

  Challenge options are plain EDN (`passkey-options`). Cryptographic
  verification is ClojureScript + WebCrypto (`passkey-verify` /
  `webauthn.adapters.edge`). This namespace stores transactions and binds
  Passkeys to the User DID (ADR-0064 / ADR-0065). It does not import a JVM
  WebAuthn library."
  (:require [cloud.itonami.app.did :as did]
            [cloud.itonami.app.identity-axis :as axis]
            [cloud.itonami.app.passkey-options :as options]
            [cloud.itonami.app.store :as store]
            [clojure.string :as str]
            [identity.authenticators :as authenticators])
  (:import [java.time Instant]
           [java.util UUID]))

(def transaction-seconds 300)

(defn- identity-state []
  (merge {:users {} :memberships {} :passkeys {} :webauthn-transactions {}}
         (:identity (store/snapshot))))

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

(defn- cljs-verify-required!
  "JVM hosts do not verify WebAuthn signatures (ADR-0065). Callers that need
  a finished ceremony must run on ClojureScript / Workers."
  []
  (throw (ex-info "Passkey verification requires the ClojureScript WebCrypto host."
                  {:type :passkey/cljs-verify-required})))

(defn start-registration!
  [{:keys [id email display-name user-handle]} rp-id origin]
  (let [user-name (or (some-> email str/trim not-empty)
                      (some-> id str str/trim not-empty))
        display-name (or (some-> display-name str/trim not-empty)
                         user-name)]
    (when-not (and user-name display-name user-handle)
      (throw (ex-info "Passkey 登録に必要なUser情報がありません。"
                      {:type :passkey/invalid-user})))
    (let [challenge (options/random-challenge-b64url)
          opts (options/creation-options
                {:rp-id rp-id
                 :user-id user-handle
                 :user-name user-name
                 :user-display-name display-name
                 :challenge challenge})
          transaction-id (str "webauthn-" (UUID/randomUUID))
          expires-at (str (.plusSeconds (Instant/now) transaction-seconds))]
      (store/transact!
       assoc-in [:identity :webauthn-transactions transaction-id]
       {:id transaction-id :kind :registration :user-id id
        :challenge challenge :origin origin :rp-id rp-id
        :created-at (store/now) :expires-at expires-at :used? false})
      {:transaction-id transaction-id
       :options opts
       :expires-at expires-at})))

(defn finish-registration!
  "Bind a verified Passkey to the User. Verification itself is cljs-only;
  on the JVM this fails closed after consuming the transaction."
  [transaction-id credential-response expected-user-id]
  (let [transaction (get-in (identity-state)
                            [:webauthn-transactions transaction-id])]
    (when-not (= expected-user-id (:user-id transaction))
      (throw (ex-info "Passkey 登録対象が一致しません。"
                      {:type :passkey/invalid-transaction})))
    (active-transaction! transaction-id :registration)
    ;; Intentionally after consume: a captured response cannot be replayed
    ;; against a second verifier once this host refuses.
    (cljs-verify-required!)))

(defn- start-assertion!
  ([kind transaction-data rp-id origin]
   (start-assertion! kind transaction-data rp-id origin nil))
  ([kind transaction-data rp-id origin challenge]
   (let [challenge (or challenge (options/random-challenge-b64url))
         challenge (if (bytes? challenge)
                     (options/base64url-encode
                      (map #(bit-and % 0xff) challenge))
                     challenge)
         opts (options/request-options
               {:rp-id rp-id :challenge challenge
                :allow-credentials (:allow-credentials transaction-data)})
         transaction-id (str "webauthn-" (UUID/randomUUID))
         expires-at (str (.plusSeconds (Instant/now) transaction-seconds))]
     (store/transact!
      assoc-in [:identity :webauthn-transactions transaction-id]
      (merge
       {:id transaction-id :kind kind
        :challenge challenge :origin origin :rp-id rp-id
        :created-at (store/now) :expires-at expires-at :used? false}
       transaction-data))
     {:transaction-id transaction-id
      :options opts
      :expires-at expires-at})))

(defn start-authentication! [rp-id origin]
  (start-assertion! :assertion {} rp-id origin))

(defn start-authorization!
  [user-id context rp-id origin]
  (start-assertion! :authorization
                    {:expected-user-id user-id
                     :authorization-context context}
                    rp-id origin))

(defn- finish-assertion!
  [transaction-id _credential-response expected-kind]
  (active-transaction! transaction-id expected-kind)
  (cljs-verify-required!))

(defn start-signing!
  ([user-id challenge context rp-id origin]
   (start-signing! user-id challenge context rp-id origin nil))
  ([user-id challenge context rp-id origin allow-credentials]
   (when-not (and (bytes? challenge) (= 32 (alength ^bytes challenge)))
     (throw (ex-info "署名 challenge は commitment の SHA-256 (32 byte) です。"
                     {:type :esign/invalid-challenge
                      :byte-count (when (bytes? challenge) (alength ^bytes challenge))})))
   (start-assertion! :esign
                     {:expected-user-id user-id
                      :authorization-context context
                      :allow-credentials (vec (or allow-credentials []))}
                     rp-id origin challenge)))

(defn consume-signing-transaction!
  "Consume and return a server-owned signing request without verifying it.

  This is only for a caller that performs the full WebAuthn verification
  itself. Smart Account signing needs the raw assertion fields after that
  verification because the contract verifies the same assertion on-chain."
  [transaction-id]
  (active-transaction! transaction-id :esign))

(defn finish-signing! [transaction-id credential-response]
  (finish-assertion! transaction-id credential-response :esign))

(defn finish-authentication! [transaction-id credential-response]
  (finish-assertion! transaction-id credential-response :assertion))

(defn finish-authorization! [transaction-id credential-response]
  (finish-assertion! transaction-id credential-response :authorization))

(defn bind-verified-registration!
  "Persist a registration that `passkey-verify` already accepted on cljs.

  `result` is the edge adapter map: :credential-id, :public-key-b64,
  :sign-count, :aaguid, :user-verified?. `ceremony` is the server-owned RP ID
  and exact origin used by that verifier. Keeping it with the public key makes
  the controller portable without pretending the Passkey is domain-free."
  ([user-id result]
   (bind-verified-registration! user-id result {}))
  ([user-id result {:keys [rp-id origin]}]
   (let [user (get-in (identity-state) [:users user-id])
         credential-id (:credential-id result)
         ;; Edge returns uncompressed P-256 point, not COSE. Credential DID
         ;; naming from COSE stays on the legacy JVM helper when COSE is present.
         public-key-cose (:public-key-cose result)
         credential-did (when public-key-cose
                          (try (did/did-key-from-cose public-key-cose)
                               (catch Exception _ nil)))
         now (store/now)]
     ;; Fail closed: missing :user-verified? must not count as verified.
     (when-not (= true (:user-verified? result))
       (throw (ex-info "Authenticator がユーザー確認を完了していません。"
                       {:type :passkey/user-verification-required})))
     (when-not (or public-key-cose (:public-key-b64 result))
       (throw (ex-info "Verified registration is missing a public key."
                       {:type :passkey/missing-public-key})))
     (store/transact!
      (fn [state]
        (let [held (get-in state [:identity :users user-id :did])
              person-did (or held credential-did)
              fill? (axis/may-fill-user-did-on-passkey? held)]
          (cond-> (-> state
                      (assoc-in [:identity :passkeys credential-id]
                                {:id credential-id :credential-id credential-id
                                 :user-id user-id :user-handle (:user-handle user)
                                 :public-key-cose public-key-cose
                                 :public-key-b64 (:public-key-b64 result)
                                 :did credential-did
                                 :rp-id (some-> rp-id str str/trim not-empty)
                                 :registration-origin
                                 (some-> origin str str/trim not-empty)
                                 :signature-count (long (:sign-count result 0))
                                 :user-verified? true
                                 :aaguid (:aaguid result)
                                 :created-at now :last-used-at nil})
                      (assoc-in [:identity :authenticators credential-id]
                                (assoc
                                 (authenticators/binding
                                  (or held person-did) :passkey credential-id
                                  {:bound-at now})
                                 :identity.authenticator/rp-id rp-id
                                 :identity.authenticator/origin origin))
                      (assoc-in [:identity :users user-id :passkey-enrolled?] true)
                      (assoc-in [:identity :users user-id :status] :active)
                      (update :events conj {:type :passkey/registered :at now
                                            :user-id user-id
                                            :credential-id credential-id
                                            :rp-id rp-id :origin origin}))
            (and fill? person-did)
            (assoc-in [:identity :users user-id :did] person-did)))))
     {:user-id user-id :credential-id credential-id
      :did (get-in (identity-state) [:users user-id :did])
      :credential-did credential-did :rp-id rp-id
      :registration-origin origin :verified? true})))
