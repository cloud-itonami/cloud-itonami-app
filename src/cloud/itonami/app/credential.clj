(ns cloud.itonami.app.credential
  "Organization membership as a Verifiable Credential the holder can carry out
  of this app.

  ## What this adds that `capability` does not

  `cloud.itonami.app.capability` already issues signed, expiring, offline-checkable
  grants (CACAO/CAIP-74). This is not a replacement for it, and the distinction is
  not cosmetic:

  | | `capability` (CACAO) | `credential` (VC) |
  |---|---|---|
  | says | \"bearer MAY act on this resource\" | \"issuer ASSERTS this is true of this subject\" |
  | audience | one named `aud` | anyone who trusts the issuer |
  | shape | SIWE + CBOR, this ecosystem's | W3C VC + Data Integrity, interoperable |
  | revoked by | waiting for `exp` | flipping one bit in a status list |

  A membership claim needs the second row. `capability` cannot express \"alice is
  an auditor at this org\" to a verifier who has never heard of Cloud Itonami,
  and a CACAO cannot be revoked before its expiry at all. Conversely a VC is not
  a capability: it says nothing about what its holder may do here.

  ## The two keys, and why the subject does not sign

  The issuer key is `capability/issuer-seed` — the same Ed25519 seed, reused
  rather than a second one. One key is one thing to protect, and the app already
  states the boundary plainly: whoever can read `data/` can mint. A second key
  would double the surface while protecting nothing extra.

  The **subject** is the user's `did:key`, which is derived from a WebAuthn P-256
  credential (`cloud.itonami.app.did`, multicodec `p256-pub` 0x1200). A subject
  does not sign its own credential, so this is fine — the issuer signs, the
  subject is merely named.

  **A holder-signed Verifiable Presentation is a different matter and is NOT
  implemented here.** Two independent reasons, both structural:

    1. `eddsa-jcs-2022` is Ed25519. The user's DID is P-256. Verifying a proof
       from that DID needs `ecdsa-jcs-2019`, which
       `kotoba-lang/org-w3-vc-data-integrity` does not implement.
    2. Even with that suite it would not work, for the reason
       `cloud.itonami.app.capability` already records about CACAO: WebAuthn signs
       its own `authenticatorData || clientDataHash`, not bytes of our choosing.
       A Passkey cannot produce a Data Integrity proof over a canonicalized
       document, and no cryptosuite changes that.

  So this app can **issue** and **verify** credentials today. Holder-proved
  presentations need either a separate software Ed25519 holder key or WebAuthn
  treated as its own holder-binding mechanism, which is a product decision and
  not something to fake here.

  ## Which DID the issuer names

  `verificationMethod` is the issuer's `did:key` unless the deployment publishes
  `did:web`, in which case it is `did:web:<domain>#<key>`. Both resolve to the
  same Ed25519 key, because `did-web-document` embeds it. Naming an unpublished
  `did:web` would produce credentials nobody outside this process could verify,
  so the fallback is the self-describing form rather than the aspirational one."
  (:require [clojure.string :as str]
            [cloud.itonami.app.capability :as capability]
            [cloud.itonami.app.store :as store]
            [data-integrity.core :as di]
            [ed25519.core :as ed]
            [status-list.core :as sl])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(def schema "cloud.itonami.app.credential.v1")

(def credentials-context "https://www.w3.org/ns/credentials/v2")

(def ^:private membership-roles #{:owner :admin :member :auditor :guest})

(defn now-timestamp
  "An XSD dateTime for `created`/`validFrom`, truncated to the second.

  Truncated deliberately: `org-w3-vc-data-integrity` takes no clock and requires
  the caller to supply this, and a credential does not need microsecond precision
  — carrying it would only record more precisely when a person was at their desk."
  ([] (now-timestamp (Instant/now)))
  ([^Instant instant] (str (.truncatedTo instant ChronoUnit/SECONDS))))

;; ── issuer identity ──────────────────────────────────────────────────────────

(defn issuer-did-key
  "The issuer's `did:key`. Always resolvable, with no network and no deployment."
  []
  (capability/issuer-did))

(defn issuer-public-key-multibase
  "The issuer's Ed25519 public key as `z6Mk…` — the `publicKeyMultibase` form a
  DID document's verificationMethod uses."
  []
  (subs (issuer-did-key) (count "did:key:")))

(defn issuer-verification-method
  "The `verificationMethod` to sign with.

  `did:web:<domain>#<key>` when the deployment publishes a DID document,
  otherwise the `did:key` fragment form. See the namespace docstring for why the
  fallback is not simply the `did:web`."
  ([] (issuer-verification-method nil))
  ([organization-domain]
   (let [key-id (issuer-public-key-multibase)]
     (if (str/blank? (str organization-domain))
       (str (issuer-did-key) "#" key-id)
       (str "did:web:" organization-domain "#" key-id)))))

(defn did-web-document
  "The DID document to publish at `https://<organization-domain>/.well-known/did.json`.

  Contains the issuer's Ed25519 key as both `assertionMethod` (it signs
  credentials) and `authentication`. It is NOT listed under
  `keyAgreement` — this key signs, it does not do key exchange, and listing it
  for a purpose it is not used for invites a verifier to accept it for one."
  [organization-domain]
  (when (str/blank? (str organization-domain))
    (throw (ex-info "did:web document needs an organization domain."
                    {:type :credential/no-domain})))
  (let [id (str "did:web:" organization-domain)
        key-id (str id "#" (issuer-public-key-multibase))]
    {"@context" ["https://www.w3.org/ns/did/v1"
                 "https://w3id.org/security/multikey/v1"]
     "id" id
     "verificationMethod"
     [{"id" key-id
       "type" "Multikey"
       "controller" id
       "publicKeyMultibase" (issuer-public-key-multibase)}]
     "assertionMethod" [key-id]
     "authentication" [key-id]}))

(defn- local-resolver
  "Resolve the issuer's own `verificationMethod` without a network call.

  `data-integrity.core` refuses `did:web` unless given a resolver, precisely so
  that document content cannot drive outbound fetches. For credentials this app
  itself issued the answer is already in hand, so supplying it is both correct
  and strictly safer than letting the library resolve anything."
  [vm]
  (let [expected (issuer-public-key-multibase)
        fragment (when-let [i (str/index-of vm "#")] (subs vm (inc i)))]
    (when-not (= expected fragment)
      (throw (ex-info "この verificationMethod はこのアプリの発行鍵ではありません。"
                      {:type :credential/unknown-verification-method
                       :verification-method vm})))
    (ed/did-key->pubkey (str "did:key:" expected))))

;; ── status list (revocation) ─────────────────────────────────────────────────

(def status-list-id-suffix "/credentials/status/1")

(defn status-list-url
  "Where the status list credential is served. A URL, because a verifier that is
  not this process has to be able to fetch it."
  [organization-domain]
  (if (str/blank? (str organization-domain))
    "urn:cloud-itonami:status:1"
    (str "https://" organization-domain status-list-id-suffix)))

(defn- credential-state [snapshot]
  (or (:credentials snapshot) {:next-status-index 0 :revoked #{} :issued {}}))

(defn revoked-indices
  "The set of revoked status-list indices."
  [snapshot]
  (set (:revoked (credential-state snapshot))))

(defn status-list-credential
  "The `BitstringStatusListCredential` naming every revoked index, unsigned.

  Unsigned on purpose: it is a credential like any other and must carry the
  issuer's proof, which `sign` adds. Returning it already-signed would hide the
  fact that a verifier must check that proof before trusting the list — an
  unverified list of zeros un-revokes everything."
  [snapshot organization-domain]
  (sl/status-list-credential
   {:id (status-list-url organization-domain)
    :issuer (issuer-did-key)
    :purpose "revocation"
    :encoded-list (sl/generate (revoked-indices snapshot))
    :valid-from (now-timestamp)}))

(defn sign
  "Add the issuer's Data Integrity proof to any document this app issues."
  ([document] (sign document nil))
  ([document organization-domain]
   (di/issue-credential
    document
    {:seed (capability/issuer-seed)
     :verification-method (issuer-verification-method organization-domain)
     :created (now-timestamp)})))

;; ── issuing ──────────────────────────────────────────────────────────────────

(defn- allocate-status-index! []
  (let [result (volatile! nil)]
    (store/transact!
     (fn [current]
       (let [st (credential-state current)
             idx (long (:next-status-index st 0))]
         (vreset! result idx)
         (assoc current :credentials
                (-> st
                    (assoc :next-status-index (inc idx))
                    (update :revoked #(set (or % #{})))
                    (update :issued #(or % {})))))))
    @result))

(defn membership-credential
  "An unsigned membership credential. Separated from `sign` so a test can inspect
  exactly what will be signed."
  [{:keys [organization-did organization-domain subject-did role status-index
           organization-name valid-from]}]
  (when-not (contains? membership-roles (keyword role))
    (throw (ex-info "未知の membership role です。"
                    {:type :credential/unknown-role :role role})))
  (when (str/blank? (str subject-did))
    (throw (ex-info "credential subject の DID が必要です。"
                    {:type :credential/no-subject})))
  (let [issuer (or (not-empty (str organization-did)) (issuer-did-key))]
    (cond->
     {"@context" [credentials-context]
      "type" ["VerifiableCredential" "OrganizationMembershipCredential"]
      "issuer" issuer
      "validFrom" (or valid-from (now-timestamp))
      "credentialSubject"
      (cond-> {"id" subject-did
               "role" (name (keyword role))}
        (not (str/blank? (str organization-name)))
        (assoc "organizationName" organization-name)
        (not (str/blank? (str organization-did)))
        (assoc "organization" organization-did))}
      status-index
      (assoc "credentialStatus"
             (sl/entry {:index status-index
                        :purpose "revocation"
                        :status-list-credential (status-list-url organization-domain)})))))

(defn issue-membership!
  "Issue a signed, revocable membership credential and record its status index.

  The index is allocated from durable state before signing, so a credential that
  exists is always revocable. Allocating afterwards would leave a window in which
  an issued credential had no index to flip.

  `:actor` is the user id doing the issuing, recorded in the audit event. It is
  optional only because a CLI or a test has no session; a request path should
  always pass one, since \"who asserted this about whom\" is the first thing an
  auditor asks and the credential itself does not carry the answer — its issuer is
  the organization, not the person who pressed the button."
  [{:keys [organization-domain actor] :as opts}]
  (let [index (allocate-status-index!)
        credential (sign (membership-credential (assoc opts :status-index index))
                         organization-domain)]
    (store/transact!
     (fn [current]
       (-> current
           (update :events conj
                   {:type :credential/issued
                    :at (get credential "validFrom")
                    :status-index index
                    :actor actor
                    :subject (get-in credential ["credentialSubject" "id"])
                    :role (get-in credential ["credentialSubject" "role"])
                    :issuer (get credential "issuer")
                    :verification-method (get-in credential ["proof" "verificationMethod"])})
           (assoc-in [:credentials :issued index]
                 ;; The record, not the credential. This app keeps only what it
                 ;; needs to revoke it and to tell an operator what exists; the
                 ;; holder keeps the signed document, which is the point of it
                 ;; being verifiable without asking this server.
                 {:subject (get-in credential ["credentialSubject" "id"])
                  :role (get-in credential ["credentialSubject" "role"])
                  :issuer (get credential "issuer")
                  :issued-at (get credential "validFrom")
                  :issued-by actor}))))
    {:credential credential :status-index index}))

(defn revoke!
  "Flip the revocation bit for `status-index`, recording who did it.

  Idempotent in EFFECT: revoking twice leaves the same state, and a caller
  retrying a request should not get an error for a credential that is already
  revoked. Not idempotent in the LEDGER — two attempts happened, so two events
  are recorded, the second carrying `:already-revoked? true`. Collapsing them
  would lose the fact that somebody tried again, which is the sort of thing an
  auditor is looking for.

  `:actor` is the user id withdrawing the credential. Revocation stops another
  person's credential being honoured anywhere it is presented, so an unattributed
  revocation is a hole in exactly the record that matters most."
  ([status-index] (revoke! status-index nil))
  ([status-index actor]
   (let [index (long status-index)
         already? (contains? (revoked-indices (store/snapshot)) index)]
     (store/transact!
      (fn [current]
        (-> current
            (update-in [:credentials :revoked]
                       (fn [s] (conj (set (or s #{})) index)))
            (update :events conj
                    (cond-> {:type :credential/revoked
                             :at (now-timestamp)
                             :status-index index
                             :actor actor
                             :subject (get-in current [:credentials :issued index :subject])}
                      already? (assoc :already-revoked? true))))))
     (cond-> {:status-index index :revoked? true}
       already? (assoc :already-revoked? true)))))

;; ── verifying ────────────────────────────────────────────────────────────────

(defn verify-frozen
  "Verify `credential` against a status list snapshot the CALLER supplies.

  `verify` below consults this app's current state, which is right for a live
  check and wrong for evidence. A document signature made while the signer's
  role credential was valid does not stop having been validly made because the
  credential was revoked afterwards — so an evidence record freezes the signed
  status list as it stood at signing time, and this is what checks against it.
  `cloud.itonami.app.esign` is the caller.

  The list's OWN proof is verified first, and a list that does not verify is a
  refusal rather than an absence. A status list of zeros un-revokes everything,
  so accepting an unproven one would let anyone who can hand us a document
  un-revoke their own credential — the hazard
  `status-list-credential` already names, arriving from the other direction."
  [credential status-list]
  (let [list-result (di/verify-credential status-list {:resolve-key local-resolver})]
    (if-not (:verified list-result)
      {:verified false :valid? false :reason :status-list-unverifiable}
      (let [result (di/verify-credential credential {:resolve-key local-resolver})]
        (if-not (:verified result)
          {:verified false :valid? false :reason (:reason result)}
          (let [entry (get credential "credentialStatus")
                status (when entry
                         (sl/check-status entry status-list
                                          {:expected-purpose "revocation"}))
                revoked? (boolean (and status (not (:valid? status))))]
            (cond-> {:verified true
                     :revoked? revoked?
                     :valid? (not revoked?)
                     :subject (get-in credential ["credentialSubject" "id"])
                     :role (get-in credential ["credentialSubject" "role"])
                     :verification-method (:verification-method result)}
              status (assoc :status-index (:index status)))))))))

(defn verify
  "Verify a credential this app issued, and check its revocation status.

  Returns `{:verified bool :revoked? bool :valid? bool …}`. `:valid?` is the only
  field a caller should gate on — a credential can be perfectly signed and still
  revoked, and reporting only `:verified` for that case is how a revoked
  credential gets honoured."
  ([credential] (verify credential (store/snapshot)))
  ([credential snapshot]
   (let [result (di/verify-credential credential {:resolve-key local-resolver})]
     (if-not (:verified result)
       {:verified false :valid? false :reason (:reason result)}
       (let [entry (get credential "credentialStatus")
             status (when entry
                      (sl/check-status
                       entry
                       (status-list-credential snapshot
                                               ;; the list we check against is
                                               ;; our own state, not a fetched
                                               ;; document
                                               nil)
                       {:expected-purpose "revocation"}))
             revoked? (boolean (and status (not (:valid? status))))]
         (cond-> {:verified true
                  :revoked? revoked?
                  :valid? (not revoked?)
                  :subject (get-in credential ["credentialSubject" "id"])
                  :role (get-in credential ["credentialSubject" "role"])
                  :verification-method (:verification-method result)}
           status (assoc :status-index (:index status))))))))

;; ── the issued register ──────────────────────────────────────────────────────

(def revoking-roles
  "Roles that may revoke a membership credential. A member revoking their
  colleague's credential is not a share, it is an eviction, so the two roles that
  already administer the organization are the two that can do it."
  #{:owner :admin})

(defn may-revoke?
  "Whether `role` may revoke. Not a matter of taste: `revoke!` flips a bit that
  stops another person's credential from being honoured anywhere it is presented,
  which is a strictly larger power than anything a `:member` holds here."
  [role]
  (contains? revoking-roles (keyword role)))

(defn issued-credentials
  "The register of credentials this app has issued, newest index first.

  Returns the RECORD, never the credential itself: this app does not keep the
  signed document, only what it needs to revoke it and to tell an operator what
  exists. The holder keeps the credential — that is the point of it being
  verifiable without asking this server."
  [snapshot]
  (let [st (credential-state snapshot)
        revoked (set (:revoked st))]
    (->> (:issued st)
         (map (fn [[index record]]
                (assoc record
                       :status-index index
                       :revoked? (contains? revoked index))))
         (sort-by :status-index #(compare %2 %1))
         vec)))

(defn issued-count [snapshot]
  (count (:issued (credential-state snapshot))))

(defn verify-presented
  "`verify` for a document that arrived from OUTSIDE this process.

  Every input-level failure becomes `{:verified false :valid? false :reason kw}`
  instead of an exception. The distinction matters at an HTTP boundary: `verify`
  throwing on a malformed document would surface as a 500, telling the caller the
  server broke when in fact the credential they sent was junk. \"Is this
  credential good?\" is answered successfully; the answer is no.

  Genuine programming errors are not swallowed — only `ex-info`, which is what
  this stack raises for bad input, is caught. A NullPointerException still
  propagates."
  ([presented] (verify-presented presented (store/snapshot)))
  ([presented snapshot]
   (if-not (map? presented)
     {:verified false :valid? false :reason :credential/not-a-document}
     (try
       (verify presented snapshot)
       (catch clojure.lang.ExceptionInfo error
         (let [data (ex-data error)]
           {:verified false
            :valid? false
            :reason (or (:data-integrity/error data)
                        (:status-list/error data)
                        (:type data)
                        :credential/unverifiable)}))))))
