(ns cloud.itonami.app.esign
  "Signing a Drive document with a Passkey, and the evidence record that makes
  the signature checkable by somebody who is not this process.

  ## The layers, and which one this is

  `cloud.itonami.app.credential` established the separation this follows:

      identity       DID              who                did:key from a Passkey
      authorization  CACAO            what may be done   capability
      assertion      VC               what is claimed    issuer signs
      presentation   VP               who is here now    holder signs
      **signature**  **this**         **what was agreed**  **the signer signs a document**

  A signature is not any of the four above it. A VC is the issuer's claim about
  a subject; a CACAO is permission to act. Neither says \"this person read this
  document and agreed to it\", and that is the only thing a signature says.

  `credential` records that a holder-signed VP is not implementable here,
  because WebAuthn signs `authenticatorData || SHA-256(clientDataJSON)` rather
  than bytes of our choosing. **That limitation does not apply to this layer,
  and the reason is worth stating rather than leaving to be rediscovered:** a
  Data Integrity proof must be over the canonicalized document, which WebAuthn
  cannot do — but a *signature* only has to be over something that commits to
  the document, and the challenge is 32 bytes we choose. So the thing that made
  VP structurally impossible makes this straightforward.
  `cloud.itonami.app.esign.commitment` is where that commitment is built.

  ## What is frozen, and why freezing is the feature

  A Drive document is mutable: `documents/update!` adds a version. An envelope
  captures ONE version — its `:object-ref`, the digest of its stored bytes, and
  an exhaustive outline of its contents — and nothing afterwards changes what
  was signed. Editing the document later produces a new version that the
  envelope does not reference; it does not alter, invalidate or silently
  re-point the signature.

  Three things are frozen per signature besides the document:

  - the **role credential**, issued at signing time, so the evidence says what
    the signer's standing in the organization was *then*;
  - the **signed status list** as it stood at that moment, so that revoking the
    role credential later does not retroactively unmake a signature that was
    validly made. This is the one that is easy to get backwards, and getting it
    backwards means a revocation quietly invalidates history;
  - the **authenticator's public key and AAGUID**, because verifying the
    signature in ten years must not require this app's identity store to still
    exist.

  ## Time is one of three things, and the record says which

  `timeAttestation` is `:app-attested`, `:tsa-attested` or `:accredited`, and
  the three are different legal objects:

  | value | what it is |
  |---|---|
  | `:app-attested` | this server's word. No TSA configured. |
  | `:tsa-attested` | a verified RFC 3161 token from a TSA nobody vouched for |
  | `:accredited` | a token whose signer is in this deployment's accredited set |

  Only the third is what 電子帳簿保存法 names as a tamper-evidence measure for
  retained transaction data (総務大臣認定タイムスタンプ). Collapsing them into
  \"timestamped\" is how a self-issued token reads as an accredited one, which
  `org-ietf-rfc3161` refuses to allow by keeping `:verified` and `:trusted`
  apart — and this preserves that distinction rather than flattening it at the
  app layer.

  A blockchain anchor is none of the three: it proves \"no later than\", which
  is not what the regulation names.

  Signing with no TSA configured is **not refused**. It records `:app-attested`
  and the UI says so, because an operator who has not chosen a TSA should still
  be able to sign internally. What is refused is silence: `timestamp!` never
  returns a token it could not verify.

  ## Content and evidence are separated on purpose

  The envelope holds the outline — that is document content, and a deletion
  request must be able to destroy it. The **evidence record holds digests
  only**: no document bytes, no outline text, no names, no email addresses. So
  `forget-content!` can satisfy an erasure request while the evidence still
  proves that a document with digest X was signed by `did:key:…`.

  The outline is stored **encrypted** under a per-envelope key, and
  `forget-content!` destroys the key rather than the map entry — see
  `cloud.itonami.app.esign.vault` for why removing an entry from a persisted
  EDN file is redaction from the live view and not a deletion. The three fields
  電子帳簿保存法 requires be retained and searchable live in
  `cloud.itonami.app.esign.retention`, in the clear, and deliberately SURVIVE
  the shredding: the document a signer read and the transaction record the law
  keeps are different objects.

  This is a structural decision and it had to be made before any byte reached a
  content-addressed store. Writing signed content to Filecoin or IPFS first and
  deciding erasure afterwards is not recoverable — which is why
  `cloud.itonami.app.filecoin` staging is not in this path."
  (:require [clojure.string :as str]
            [cloud.itonami.app.credential :as credential]
            [webauthn.assurance :as assurance]
            [cloud.itonami.app.did :as did]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.esign.assertion :as assertion]
            [cloud.itonami.app.esign.commitment :as commitment]
            [cloud.itonami.app.esign.retention :as retention]
            [cloud.itonami.app.esign.timestamp :as timestamp]
            [cloud.itonami.app.esign.vault :as vault]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.config :as config]
            [asn1.core :as asn1])
  (:import [java.security SecureRandom]
           [java.util Base64]))

(def schema "cloud.itonami.app.esign.v1")
(def evidence-schema "cloud.itonami.app.esign.evidence.v1")

(def evidence-assurance-floor
  "The assurance level below which `verify-evidence` reports `:indeterminate`
  rather than `:total-passed`.

  NOT a floor on signing, and the difference is deliberate.
  `credential-assurance/default-policy` explains why a signing floor above what
  the hardware is known to clear would register a Passkey successfully and then
  refuse every use of it. So signing accepts what the shipped policy accepts,
  and the *evidence* records what was actually achieved — a verifier reading it
  can then say \"this signature does not establish that the key was in
  hardware\" without this app having had to refuse the signature at a time when
  nobody could tell whether the refusal was correct.

  Recording the weaker fact and reporting it honestly is strictly better than
  either overstating it or blocking on it."
  :platform-attested)

;; ── state ────────────────────────────────────────────────────────────────────

(defn- envelopes-path [] [:esign :envelopes])
(defn- envelope-path [id] (conj (envelopes-path) id))

(defn- envelope-of [state id]
  (get-in state (envelope-path id)))

(defn- nonce []
  (let [bytes (byte-array 16)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- refuse! [message type detail]
  (throw (ex-info message (assoc detail :type type))))

;; ── signers ──────────────────────────────────────────────────────────────────

(defn- passkey-of
  "A live Passkey belonging to the User whose DID is `did`, or nil.

  A commitment names the person. The credential's COSE `did:key` is a
  different identity — a user with two Passkeys has two credential DIDs and
  one person DID. Resolving by credential DID would miss the signer the
  envelope named. Verification still uses the `credential-id` from the
  ceremony, so picking any live Passkey here is only \"can this person sign\"."
  [state did]
  (let [user-id (some (fn [user]
                        (when (= did (:did user)) (:id user)))
                      (vals (get-in state [:identity :users] {})))]
    (when user-id
      (some #(when (= user-id (:user-id %)) %)
            (vals (get-in state [:identity :passkeys] {}))))))

(defn- membership-role
  "The signer's role in the organization, or `:member`.

  `:member` rather than a refusal because a Drive document can be sent for
  signature to somebody whose standing is exactly \"a person in this
  organization\", and that is a role — the credential says what it says. What is
  NOT done here is inventing a higher one: an absent membership does not become
  `:admin`."
  [state user-id]
  (or (some #(when (= user-id (:user-id %)) (:role %))
            (vals (get-in state [:identity :memberships] {})))
      :member))

(defn- signer-entry
  "One requested signer.

  A signer is named by DID, not by email or user id, and a principal without an
  enrolled Passkey is refused rather than recorded as pending. A pending signer
  who has no key cannot ever sign, so accepting one would produce an envelope
  that is permanently incomplete and looks merely unfinished."
  [state did]
  (when (str/blank? (str did))
    (refuse! "署名者の DID が必要です。" :esign/no-signer {}))
  (let [credential (passkey-of state did)]
    (when-not credential
      (refuse! (str "この DID に対応する Passkey が登録されていません: " did)
               :esign/signer-has-no-passkey {:did did}))
    {:signer/did did
     :signer/user-id (:user-id credential)
     :signer/nonce (nonce)
     :signer/status :pending}))

;; ── creating ─────────────────────────────────────────────────────────────────

(defn create!
  "Freeze `document-id`'s current version into an envelope awaiting signatures.

  `actor` must be able to read the document — the read goes through
  `documents/source-bytes`, so the ACL answers that and this does not have a
  second opinion about it."
  [{:keys [document-id purpose intent signer-dids actor organization-did
           object-store]}]
  (let [state (store/snapshot)
        purpose (keyword purpose)
        _ (when-not (contains? commitment/purposes purpose)
            (refuse! "未知の署名目的です。" :esign/unknown-purpose
                     {:purpose purpose
                      :known (vec (sort (map str (keys commitment/purposes))))}))
        _ (when (empty? signer-dids)
            (refuse! "署名者を 1 人以上指定してください。" :esign/no-signers {}))
        source (documents/source-bytes document-id actor
                                       (or object-store (documents/store-instance)))
        outline (commitment/outline (:resource source))
        signers (mapv #(signer-entry state %) (distinct signer-dids))
        ;; Before the envelope map, because the id is the AAD the outline is
        ;; sealed under — a ciphertext moved to another envelope's record must
        ;; fail to authenticate rather than decrypt into it.
        id (store/new-id "env")
        now (store/now)
        envelope {:esign/id id
                  :esign/document-id document-id
                  :esign/document-title (get-in source [:item :name])
                  :esign/object-ref (:object-ref source)
                  :esign/document-digest (commitment/digest-of (:bytes source))
                  :esign/media-type (:media-type source)
                  :esign/resource-kind (:resource-kind source)
                  ;; Document content, encrypted under a key `forget-content!`
                  ;; destroys. The digest is over the PLAINTEXT, because that is
                  ;; what the signer saw and what the commitment binds — a
                  ;; digest of the ciphertext would change with the nonce and
                  ;; mean nothing to a verifier.
                  :esign/presentation-sealed (vault/seal! id outline)
                  :esign/presentation-digest (commitment/digest-of-string outline)
                  :esign/purpose purpose
                  :esign/intent (or (not-empty (str/trim (str intent)))
                                    (get commitment/purposes purpose))
                  :esign/organization-did organization-did
                  :esign/created-by actor
                  :esign/created-at now
                  :esign/status :awaiting-signatures
                  :esign/signers signers
                  :esign/signatures []}]
    (store/transact! assoc-in (envelope-path id) envelope)
    envelope))

;; ── the commitment a given signer will sign ──────────────────────────────────

(defn commitment-for
  "The commitment `did` is being asked to sign in `envelope`.

  Rebuilt from the envelope rather than stored, so that there is exactly one
  definition of what gets hashed. A stored copy would be a second one, and the
  two would drift the first time a field was added."
  [envelope did role-credential-id]
  (let [signer (some #(when (= did (:signer/did %)) %) (:esign/signers envelope))]
    (when-not signer
      (refuse! "この envelope の署名者ではありません。"
               :esign/not-a-signer {:did did :envelope-id (:esign/id envelope)}))
    (commitment/commitment
     {:envelope-id (:esign/id envelope)
      :document-id (:esign/document-id envelope)
      :document-digest (:esign/document-digest envelope)
      :presentation-digest (:esign/presentation-digest envelope)
      :media-type (:esign/media-type envelope)
      :signer-did did
      :purpose (:esign/purpose envelope)
      :intent (:esign/intent envelope)
      :nonce (:signer/nonce signer)
      :role-credential-id role-credential-id
      :organization-did (:esign/organization-did envelope)})))

;; ── the signing ceremony ─────────────────────────────────────────────────────

(defn- signer! [envelope did]
  (or (some #(when (= did (:signer/did %)) %) (:esign/signers envelope))
      (refuse! "この envelope の署名者ではありません。"
               :esign/not-a-signer {:did did})))

(defn- pending! [envelope did]
  (let [signer (signer! envelope did)]
    (when-not (= :awaiting-signatures (:esign/status envelope))
      (refuse! "この envelope は署名を受け付けていません。"
               :esign/envelope-closed {:status (:esign/status envelope)}))
    (when-not (= :pending (:signer/status signer))
      (refuse! (case (:signer/status signer)
                 :signed "すでに署名済みです。"
                 :declined "この envelope は辞退済みです。"
                 "署名できない状態です。")
               :esign/signer-not-pending
               {:signer-status (:signer/status signer)}))
    signer))

(defn- ensure-role-credential!
  "The role credential bound into this signer's commitment, issued once.

  Issued at `start-signature!` rather than at finish, because its id is inside
  the commitment and therefore inside the hashed challenge — issued afterwards
  it would be a document sitting beside a signature rather than something the
  signer attested to.

  Reused across retries, and that is not an optimisation. `issue-membership!`
  allocates a revocation index from durable state on every call, so issuing per
  attempt would spend an index and mint a credential for each abandoned
  ceremony — a signer who cancels the Touch ID prompt four times would leave
  four live credentials nobody can account for. One per (envelope, signer) is
  the number that matches what is being attested."
  [envelope-id did]
  (let [state (store/snapshot)
        envelope (envelope-of state envelope-id)
        signer (signer! envelope did)]
    (if-let [existing (:signer/role-credential signer)]
      {:credential existing :status-index (:signer/status-index signer)}
      (let [issued (credential/issue-membership!
                    {:organization-did (:esign/organization-did envelope)
                     :organization-domain nil
                     :subject-did did
                     :role (membership-role state (:signer/user-id signer))})]
        (store/transact!
         (fn [current]
           (update-in current (conj (envelope-path envelope-id) :esign/signers)
                      (fn [signers]
                        (mapv (fn [s]
                                (if (= did (:signer/did s))
                                  (assoc s :signer/role-credential (:credential issued)
                                         :signer/status-index (:status-index issued))
                                  s))
                              signers)))))
        issued))))

(defn start-signature!
  "Begin the Passkey ceremony for `did` on `envelope-id`.

  The challenge is `SHA-256(JCS(commitment))`, so the authenticator's signature
  covers the document digest, the outline digest, the purpose and this signer's
  nonce. Nothing that arrives with the response can change any of them.

  The ceremony context stored with the WebAuthn transaction is only the envelope
  and the signer — deliberately not the commitment. The commitment is recomputed
  from the envelope at finish, so there is nowhere for a stored copy to disagree
  with the envelope it came from."
  [{:keys [envelope-id did user-id rp-id origin]}]
  (let [envelope (envelope-of (store/snapshot) envelope-id)
        _ (when-not envelope
            (refuse! "envelope が見つかりません。" :esign/not-found
                     {:envelope-id envelope-id}))
        signer (pending! envelope did)
        _ (when-not (= user-id (:signer/user-id signer))
            (refuse! "この署名者としてログインしていません。"
                     :esign/wrong-signer {:did did}))
        issued (ensure-role-credential! envelope-id did)
        commitment-map (commitment-for (envelope-of (store/snapshot) envelope-id)
                                       did (get (:credential issued) "id"))
        challenge (commitment/challenge-bytes commitment-map)
        started (passkey/start-signing!
                 user-id challenge
                 {:envelope-id envelope-id :signer-did did}
                 rp-id origin)]
    (assoc started
           :schema schema
           :envelope-id envelope-id
           :commitment commitment-map
           :commitment-digest (commitment/commitment-digest commitment-map)
           ;; Shown so the signer's client can display exactly what the
           ;; challenge was computed over, and so a reviewer can recompute it.
           :presentation (vault/open envelope-id (:esign/presentation-sealed envelope)))))

(defn signature-entry
  "One signature, in the shape it is exchanged in.

  **String keys, like `credential.clj`'s credentials and for the same reason.**
  This is an interchange object, not app state: it leaves as JSON and comes back
  as JSON to be verified, and it has to survive that round trip byte-for-byte.
  Namespaced Clojure keywords would not — they would arrive as strings and need
  translating back, and `\"commitment\"` in particular holds RFC 8785 JSON whose
  keys must stay strings or the canonicalization that the whole signature rests
  on produces different bytes. One shape everywhere means there is no
  translation step to get wrong.

  Everything needed to verify offline and nothing that identifies a person
  beyond the DID: no name, no email, no user id. That omission is what makes an
  evidence record survivable under an erasure request."
  [{:keys [commitment-map response passkey-credential verification signed-at
           role-credential status-list stamp]}]
  (let [graded (assurance/assurance passkey-credential)]
    {"signerDid" (get commitment-map "signerDid")
     "credentialId" (:credential-id passkey-credential)
     "publicKeyCose" (:public-key-cose passkey-credential)
     "algorithm" "ES256"
     "clientDataJSON" (get-in response [:response :clientDataJSON])
     "authenticatorData" (get-in response [:response :authenticatorData])
     "signature" (get-in response [:response :signature])
     "rpIdHash" (:rp-id-hash verification)
     "signCount" (:sign-count verification)
     ;; From the SIGNED authenticator data, not from the ceremony's report of it.
     "userVerified" (:user-verified? verification)
     "aaguid" (:aaguid passkey-credential)
     "assurance" (name (:passkey/assurance graded))
     "assuranceBasis" (:passkey/basis graded)
     "commitment" commitment-map
     "commitmentDigest" (commitment/commitment-digest commitment-map)
     "signedAt" signed-at
     ;; One of app-attested / tsa-attested / accredited. See the namespace
     ;; docstring: they are different legal objects and the field keeps them
     ;; apart so no reader has to infer which one this is.
     "timeAttestation" (name (or (:timestamp/attestation stamp) :app-attested))
     "timestamp" (when stamp
                   {"tokenDer" (asn1/hex (:timestamp/token-der stamp))
                    "genTime" (:timestamp/gen-time stamp)
                    "tsa" (:timestamp/tsa stamp)
                    "serialNumber" (:timestamp/serial-number stamp)
                    "policy" (:timestamp/policy stamp)})
     "roleCredential" role-credential
     ;; The list as it stood NOW. A later revocation must not unmake this.
     "statusListCredential" status-list}))

(defn finish-signature!
  "Verify the Passkey response and record the signature.

  Verified twice, on purpose. `passkey/finish-signing!` runs the library's
  ceremony check — origin, credential ownership, counter, and the challenge
  against the request it issued. Then `assertion/verify` runs the archival check
  against a challenge recomputed here from the envelope. The second is not
  redundant: it is the code path a third party will use, exercised on every real
  signature rather than only in tests, and it shares no implementation with the
  first."
  [{:keys [transaction-id response user-id rp-id]}]
  (let [finished (passkey/finish-signing! transaction-id response)
        _ (when-not (= user-id (:user-id finished))
            (refuse! "署名者が一致しません。" :esign/wrong-signer {}))
        {:keys [envelope-id signer-did]} (:authorization-context finished)
        _ (when (or (str/blank? (str envelope-id)) (str/blank? (str signer-did)))
            (refuse! "署名 context が envelope を指していません。"
                     :esign/context-mismatch {}))
        state (store/snapshot)
        envelope (envelope-of state envelope-id)
        _ (when-not envelope
            (refuse! "envelope が見つかりません。" :esign/not-found
                     {:envelope-id envelope-id}))
        signer (pending! envelope signer-did)
        role-credential (:signer/role-credential signer)
        status-index (:signer/status-index signer)
        _ (when-not role-credential
            (refuse! "役割 credential が発行されていません。"
                     :esign/no-role-credential {:signer-did signer-did}))
        passkey-credential (get-in state [:identity :passkeys (:credential-id finished)])
        owner-did (get-in state [:identity :users
                                 (:user-id passkey-credential) :did])
        ;; Recomputed from the envelope rather than carried through the
        ;; ceremony, so there is exactly one definition of what was signed and
        ;; nowhere for a second copy to disagree with it.
        recomputed (commitment-for envelope signer-did
                                   (get role-credential "id"))
        _ (when-not (= signer-did owner-did)
            (refuse! "署名に使われた Passkey は署名者の鍵ではありません。"
                     :esign/key-not-the-signers
                     {:signer-did signer-did :owner-did owner-did
                      :key-did (:did passkey-credential)}))
        verification (assertion/verify
                      {:client-data-json (get-in response [:response :clientDataJSON])
                       :authenticator-data (get-in response [:response :authenticatorData])
                       :signature (get-in response [:response :signature])
                       :public-key-cose (:public-key-cose passkey-credential)
                       :expected-challenge (commitment/challenge-bytes recomputed)
                       :expected-rp-id-hash (when rp-id (assertion/rp-id-hash rp-id))})
        _ (when-not (:verified verification)
            (refuse! "署名を検証できませんでした。" :esign/signature-invalid
                     {:reason (:reason verification)
                      :detail (:detail verification)}))
        now (store/now)
        ;; Over the commitment digest — the same 32 bytes the authenticator
        ;; signed. Timestamping the signature bytes instead would attest to the
        ;; signature's existence without binding it to what was signed, and the
        ;; TSA would learn nothing either way: a digest is all that leaves.
        stamp (timestamp/timestamp!
               (commitment/sha256 (commitment/canonical-bytes recomputed)))
        status-list (credential/sign
                     (credential/status-list-credential (store/snapshot) nil))
        entry (signature-entry {:commitment-map recomputed
                                :response response
                                :passkey-credential passkey-credential
                                :verification verification
                                :signed-at now
                                :role-credential role-credential
                                :status-list status-list
                                :stamp stamp})
        result (volatile! nil)]
    (store/transact!
     (fn [current]
       (let [envelope (envelope-of current envelope-id)
             signers (mapv (fn [signer]
                             (if (= signer-did (:signer/did signer))
                               (assoc signer :signer/status :signed
                                      :signer/at now
                                      :signer/status-index status-index)
                               signer))
                           (:esign/signers envelope))
             complete? (every? #(= :signed (:signer/status %)) signers)
             updated (assoc envelope
                            :esign/signers signers
                            :esign/signatures (conj (vec (:esign/signatures envelope))
                                                    entry)
                            :esign/status (if complete? :completed :awaiting-signatures)
                            :esign/completed-at (when complete? now))]
         (vreset! result updated)
         (-> current
             (assoc-in (envelope-path envelope-id) updated)
             (update :events conj {:type :esign/signed :at now
                                   :envelope-id envelope-id
                                   :signer-did signer-did})))))
    {:schema schema
     :envelope @result
     :signature entry
     :verification verification}))

(defn decline!
  "Record that `did` will not sign. Terminal for the envelope: a declined
  signature is not a pending one, and an envelope missing a required signature
  is not going to become complete."
  [{:keys [envelope-id did user-id reason]}]
  (let [envelope (envelope-of (store/snapshot) envelope-id)
        _ (when-not envelope
            (refuse! "envelope が見つかりません。" :esign/not-found
                     {:envelope-id envelope-id}))
        signer (pending! envelope did)
        _ (when-not (= user-id (:signer/user-id signer))
            (refuse! "この署名者としてログインしていません。"
                     :esign/wrong-signer {:did did}))
        now (store/now)
        result (volatile! nil)]
    (store/transact!
     (fn [current]
       (let [envelope (envelope-of current envelope-id)
             signers (mapv (fn [s]
                             (if (= did (:signer/did s))
                               (assoc s :signer/status :declined
                                      :signer/at now
                                      :signer/reason (not-empty (str/trim (str reason))))
                               s))
                           (:esign/signers envelope))
             updated (assoc envelope :esign/signers signers
                            :esign/status :declined
                            :esign/declined-at now)]
         (vreset! result updated)
         (-> current
             (assoc-in (envelope-path envelope-id) updated)
             (update :events conj {:type :esign/declined :at now
                                   :envelope-id envelope-id
                                   :signer-did did})))))
    @result))

;; ── reading ──────────────────────────────────────────────────────────────────

(def ^:private attestation-strength
  {"app-attested" 0 "tsa-attested" 1 "accredited" 2})

(defn weakest-attestation
  "The weakest `timeAttestation` among the envelope's signatures.

  The WEAKEST, not the strongest and not the most recent. An envelope with three
  signatures of which one is `app-attested` is an envelope whose tamper-evidence
  measure does not cover one signature — and a regulator reads the envelope, not
  the best signature in it. Reporting the strongest would let one accredited
  token launder two that are not.

  An envelope with no signatures yet is `\"app-attested\"`, which is the honest
  floor rather than an absence a caller has to interpret."
  [envelope]
  (or (->> (:esign/signatures envelope)
           (map #(get % "timeAttestation" "app-attested"))
           (sort-by #(get attestation-strength % 0))
           first)
      "app-attested"))

(defn- participant? [envelope principal did]
  (or (= principal (:esign/created-by envelope))
      (boolean (some #(= did (:signer/did %)) (:esign/signers envelope)))))

(defn envelope-view
  "One envelope, for the party asking.

  The outline is included only for a participant, and only while it exists:
  after `forget-content!` there is nothing to show and `:content-forgotten?`
  says so rather than an empty string implying an empty document."
  [envelope {:keys [principal did]}]
  (let [participant? (participant? envelope principal did)]
    (cond-> {:schema schema
             :id (:esign/id envelope)
             :document-id (:esign/document-id envelope)
             :document-title (:esign/document-title envelope)
             :document-digest (:esign/document-digest envelope)
             :presentation-digest (:esign/presentation-digest envelope)
             :object-ref (:esign/object-ref envelope)
             :media-type (:esign/media-type envelope)
             :purpose (subs (str (:esign/purpose envelope)) 1)
             :intent (:esign/intent envelope)
             :status (name (:esign/status envelope))
             :created-by (:esign/created-by envelope)
             :created-at (:esign/created-at envelope)
             :completed-at (:esign/completed-at envelope)
             :content-forgotten? (boolean (:esign/content-forgotten? envelope))
             :signers (mapv (fn [signer]
                              {:did (:signer/did signer)
                               :status (name (:signer/status signer))
                               :at (:signer/at signer)
                               :reason (:signer/reason signer)
                               :assurance
                               (some #(when (= (:signer/did signer)
                                               (get % "signerDid"))
                                        (get % "assurance"))
                                     (:esign/signatures envelope))})
                            (:esign/signers envelope))
             :signature-count (count (:esign/signatures envelope))
             ;; Stated on every read, not only in the evidence record: a UI that
             ;; showed "signed" without this would be implying more than the
             ;; signature establishes. `:accredited` is the ONLY value that means
             ;; 電子帳簿保存法's tamper-evidence measure is met by a timestamp.
             :qualified-timestamp? (= "accredited" (weakest-attestation envelope))
             :time-attestation (weakest-attestation envelope)
             :participant? participant?}
      participant?
      (assoc :presentation (vault/open (:esign/id envelope)
                                       (:esign/presentation-sealed envelope))))))

(defn envelopes
  "Every envelope this principal created or is asked to sign, newest first."
  [state {:keys [principal did] :as who}]
  (->> (vals (get-in state (envelopes-path) {}))
       (filter #(participant? % principal did))
       (sort-by :esign/created-at)
       reverse
       (mapv #(envelope-view % who))))

(defn envelope!
  "One envelope this principal may see, or a refusal."
  [state id who]
  (let [envelope (envelope-of state id)]
    (when-not envelope
      (refuse! "envelope が見つかりません。" :esign/not-found {:envelope-id id}))
    (when-not (participant? envelope (:principal who) (:did who))
      (refuse! "この envelope を参照する権限がありません。"
               :esign/not-permitted {:envelope-id id}))
    (envelope-view envelope who)))

;; ── the evidence record ──────────────────────────────────────────────────────

(defn evidence
  "Everything a verifier needs and nothing that identifies a person.

  Digests, DIDs, public keys, signed credentials and the frozen status lists.
  No document bytes, no outline, no titles, no email addresses — see the
  namespace docstring on why that separation is what makes an erasure request
  answerable at all.

  String keys throughout — see `signature-entry` on why an interchange object
  does not use Clojure keywords.

  `\"timestamps\"` collects every RFC 3161 token the signatures carry. It is an
  empty vector rather than an absent key when there are none — absent would read
  as \"not applicable\"; empty says there are none, which is the fact.

  `\"qualifiedTimestamp\"` is true only when EVERY signature carries an
  `:accredited` token. One signature without one is one signature the tamper-
  evidence measure does not cover, and an envelope is the unit a regulator
  reads."
  [envelope]
  {"schema" evidence-schema
   "envelopeId" (:esign/id envelope)
   "status" (name (:esign/status envelope))
   "purpose" (subs (str (:esign/purpose envelope)) 1)
   "intent" (:esign/intent envelope)
   "document"
   {"id" (:esign/document-id envelope)
    "digest" (:esign/document-digest envelope)
    "presentationDigest" (:esign/presentation-digest envelope)
    "objectRef" (:esign/object-ref envelope)
    "mediaType" (:esign/media-type envelope)}
   "requiredSigners" (mapv :signer/did (:esign/signers envelope))
   "signatures" (vec (:esign/signatures envelope))
   "timestamps" (into [] (keep #(get % "timestamp")) (:esign/signatures envelope))
   "qualifiedTimestamp" (= "accredited" (weakest-attestation envelope))})

(defn record-retention!
  "Record the three fields 電子帳簿保存法 requires be searchable.

  Separate from `create!` and not part of it, because most envelopes are not
  transactions: an internal consent or a set of minutes has no 取引金額. Making
  it a required step of every signature would push operators into inventing
  values for the fields the law is about."
  [envelope-id entry-input]
  (let [envelope (envelope-of (store/snapshot) envelope-id)]
    (when-not envelope
      (refuse! "envelope が見つかりません。" :esign/not-found {:envelope-id envelope-id}))
    (let [entry (assoc (retention/entry
                        (assoc entry-input
                               :envelope-id envelope-id
                               :document-digest (:esign/document-digest envelope)))
                       :retention/recorded-at (store/now))]
      (store/transact! assoc-in [:esign :retention envelope-id] entry)
      entry)))

(defn retention-entry [state envelope-id]
  (get-in state [:esign :retention envelope-id]))

(defn compliance
  "What is still missing for 電子帳簿保存法 on this envelope.

  Returns the gaps, never a pass. The 真実性 limb can be satisfied by an
  operator's 事務処理規程, which this process cannot observe — so it is an input
  and the answer says which limb rests on it."
  [state envelope-id {:keys [procedure-documented?]}]
  (let [envelope (envelope-of state envelope-id)]
    {:schema "cloud.itonami.app.esign.compliance.v1"
     :envelope-id envelope-id
     :time-attestation (weakest-attestation envelope)
     :retention (retention-entry state envelope-id)
     :gaps (retention/compliance-gaps
            {:retention-entry (retention-entry state envelope-id)
             :timestamp-attestation (keyword (weakest-attestation envelope))
             :procedure-documented? procedure-documented?})}))

(defn forget-content!
  "Destroy the outline, keep the evidence.

  This is the erasure path, and what it can and cannot do is the honest shape of
  the problem: the content a signer read is destroyed, and the proof that a
  document with digest X was signed by `did:key:…` remains. A retention
  obligation over the second is therefore compatible with a deletion request
  over the first, which is only true because they were never stored together.

  **It destroys a KEY, not a map entry.** `cloud.itonami.app.esign.vault`
  explains why: `store/transact!` persists by writing a new `state.edn` over the
  old, so removing an entry leaves every prior copy — old files, backups,
  unoverwritten disk blocks — intact. Destroying the per-envelope key makes all
  of them undecryptable at once. The ciphertext still exists everywhere it did;
  it is unreadable. That is a stronger claim than removal and a weaker one than
  physical destruction, and it is the one available when copies cannot be
  recalled.

  What it does NOT do is remove the document from the Drive — that is
  `documents/purge!` and a different decision. Nor does it touch the retention
  index: 電子帳簿保存法 requires 取引年月日・取引金額・取引先 be kept and
  searchable, and those are a different object from the document a signer read.
  `cloud.itonami.app.esign.retention` says so and makes the operator state the
  basis. Nor does it make the digest reversible; it never was."
  [envelope-id]
  (let [result (volatile! nil)]
    (vault/forget! envelope-id)
    (store/transact!
     (fn [current]
       (if-let [envelope (envelope-of current envelope-id)]
         (let [updated (-> envelope
                           (dissoc :esign/presentation-sealed)
                           (assoc :esign/content-forgotten? true
                                  :esign/content-forgotten-at (store/now)))]
           (vreset! result updated)
           (assoc-in current (envelope-path envelope-id) updated))
         current)))
    (or @result
        (refuse! "envelope が見つかりません。" :esign/not-found
                 {:envelope-id envelope-id}))))

;; ── verifying an evidence record ─────────────────────────────────────────────

(defn- signature-status
  "The status of one signature, and every reason it is not better than that.

  Three outcomes, following ETSI EN 319 102-1 rather than a boolean:
  `:total-failed` when something is provably wrong, `:indeterminate` when the
  record does not settle the question, `:total-passed` when it does. A boolean
  would collapse the middle one into a pass or a fail, and both readings are
  wrong — `:indeterminate` is the answer for a signature that is
  cryptographically sound but whose signer's key was never shown to be in
  hardware."
  [signature {:keys [document envelope-id rp-id-hash]}]
  (let [commitment-map (get signature "commitment")
        signer-did (get signature "signerDid")
        reasons (volatile! [])
        add! (fn [status reason detail]
               (vswap! reasons conj {:status status :reason reason :detail detail}))]
    (cond
      (nil? commitment-map)
      {:status :total-failed
       :reasons [{:status :total-failed :reason :no-commitment
                  :detail "署名が commitment を含んでいません"}]}

      :else
      (do
        (when-not (= (get signature "commitmentDigest")
                     (commitment/commitment-digest commitment-map))
          (add! :total-failed :commitment-digest-mismatch
                "記録された digest が commitment を再計算した値と一致しません"))

        (when-not (= (get commitment-map "documentDigest") (get document "digest"))
          (add! :total-failed :document-not-bound
                "commitment が指す文書 digest が evidence の文書と違います"))

        (when-not (= (get commitment-map "presentationDigest")
                     (get document "presentationDigest"))
          (add! :total-failed :presentation-not-bound
                "commitment が指す表示 digest が evidence の表示と違います"))

        (when-not (= (get commitment-map "envelopeId") envelope-id)
          (add! :total-failed :envelope-mismatch
                "commitment が別の envelope を指しています"))

        (when-not (= (get commitment-map "signerDid") signer-did)
          (add! :total-failed :signer-mismatch
                "commitment の signerDid が署名記録と一致しません"))

        ;; The recorded public key must be the signer's own. Without this a
        ;; signature made by any key at all would verify, as long as whoever
        ;; wrote the record put that key beside it.
        (let [key-did (try (did/did-key-from-cose (get signature "publicKeyCose"))
                           (catch Exception _ nil))]
          (when-not (= key-did signer-did)
            (add! :total-failed :key-not-the-signers
                  (str "公開鍵から導出した DID が署名者の DID と違います: "
                       (pr-str key-did)))))

        (when (empty? @reasons)
          (let [result (assertion/verify
                        {:client-data-json (get signature "clientDataJSON")
                         :authenticator-data (get signature "authenticatorData")
                         :signature (get signature "signature")
                         :public-key-cose (get signature "publicKeyCose")
                         :expected-challenge (commitment/challenge-bytes commitment-map)
                         :expected-rp-id-hash rp-id-hash})]
            (when-not (:verified result)
              (add! :total-failed (:reason result) (:detail result)))))

        ;; The stored `timeAttestation` is this app's word, and an evidence
        ;; record exists so a reader does not have to take this app's word. So
        ;; the token is re-verified against the commitment digest it should
        ;; cover, and a stored claim that does not survive that is downgraded
        ;; rather than reported.
        (let [stamp (get signature "timestamp")
              claimed (get signature "timeAttestation" "app-attested")]
          (cond
            (and (not= "app-attested" claimed) (nil? stamp))
            (add! :total-failed :timestamp-claimed-but-absent
                  (str "timeAttestation は " claimed " ですが token がありません"))

            stamp
            (let [checked (try (timestamp/verify-stored
                                (config/load-config)
                                {:timestamp/token-der (asn1/unhex (get stamp "tokenDer"))}
                                (commitment/challenge-bytes commitment-map))
                               (catch Exception e
                                 {:verified false :reason (or (:type (ex-data e)) :exception)}))]
              (cond
                (not (:verified checked))
                (add! :total-failed :timestamp-invalid
                      (str "記録された timestamp が commitment を検証しません: "
                           (:reason checked)))

                ;; Verified but the TSA is not one this deployment accredits.
                ;; Real evidence of time, and not what 電子帳簿保存法 names.
                (and (= "accredited" claimed) (not (true? (:trusted checked))))
                (add! :indeterminate :timestamp-not-accredited-here
                      "この deployment の設定では、この TSA は認定事業者として登録されていません。")

                (not= (:gen-time checked) (get stamp "genTime"))
                (add! :total-failed :timestamp-time-altered
                      "記録された genTime が token の中身と一致しません")))

            ;; NO reason is added when there is simply no timestamp. Time is a
            ;; separate question from signature validity — the same
            ;; :verified/:trusted separation `org-ietf-rfc3161` keeps and this
            ;; nearly folded away. An internally-signed agreement with no TSA
            ;; configured has a perfectly valid signature, and marking every one
            ;; of them :indeterminate would make the field say nothing while
            ;; hiding the signatures that really are indeterminate. The time
            ;; qualification is reported at the top level instead, in
            ;; :esign/time-attestation and :esign/time-note.
            :else nil))

        (when-not (assurance/at-least? (keyword (get signature "assurance"))
                                       evidence-assurance-floor)
          (add! :indeterminate :assurance-below-floor
                (str "鍵がハードウェアにあることは示されていません（"
                     (get signature "assurance") "）: "
                     (get signature "assuranceBasis"))))

        (let [role (get signature "roleCredential")
              frozen (get signature "statusListCredential")]
          (cond
            (or (nil? role) (nil? frozen))
            (add! :indeterminate :role-not-attested
                  "署名時点の役割 credential が記録されていません")

            :else
            (let [checked (try (credential/verify-frozen role frozen)
                               (catch Exception e {:verified false
                                                   :reason (or (:type (ex-data e))
                                                               :exception)}))]
              (cond
                (not (:verified checked))
                (add! :indeterminate :role-credential-unverifiable
                      (str "役割 credential を検証できません: " (:reason checked)))

                (:revoked? checked)
                ;; Revoked in the list AS FROZEN, which means it was already
                ;; revoked when the signature was made. A revocation that came
                ;; afterwards is not in this list and correctly does not appear
                ;; here.
                (add! :indeterminate :role-revoked-at-signing
                      "署名時点で役割 credential が失効していました")))))

        (let [collected @reasons]
          {:status (cond
                     (some #(= :total-failed (:status %)) collected) :total-failed
                     (seq collected) :indeterminate
                     :else :total-passed)
           :reasons collected})))))

(defn verify-evidence
  "Verify an evidence record with no session, no network and no clock.

  `rp-id` is optional and checking it is stronger: an assertion made for a
  different relying party is signed data about something else. Omitting it
  verifies the signature and the binding but not where the ceremony happened.

  `:esign/status` is never `:total-passed` for an envelope whose required
  signers have not all signed — a record with one of three signatures is not a
  failed agreement, it is an unfinished one, and `:indeterminate` is what that
  is."
  ([evidence-record] (verify-evidence evidence-record {}))
  ([evidence-record {:keys [rp-id]}]
   (let [document (get evidence-record "document")
         envelope-id (get evidence-record "envelopeId")
         rp-id-hash (when rp-id (assertion/rp-id-hash rp-id))
         signatures (get evidence-record "signatures")
         checked (mapv (fn [signature]
                         (assoc (signature-status signature
                                                  {:document document
                                                   :envelope-id envelope-id
                                                   :rp-id-hash rp-id-hash})
                                :signer-did (get signature "signerDid")
                                :signed-at (get signature "signedAt")
                                :assurance (get signature "assurance")))
                       signatures)
         signed (set (map #(get % "signerDid") signatures))
         missing (remove signed (get evidence-record "requiredSigners"))
         statuses (set (map :status checked))
         attestation (or (->> signatures
                              (map #(get % "timeAttestation" "app-attested"))
                              (sort-by #(get attestation-strength % 0))
                              first)
                         "app-attested")]
     {:schema "cloud.itonami.app.esign.verification.v1"
      :esign/envelope-id envelope-id
      :esign/status (cond
                      (empty? signatures) :indeterminate
                      (contains? statuses :total-failed) :total-failed
                      (contains? statuses :indeterminate) :indeterminate
                      (seq missing) :indeterminate
                      :else :total-passed)
      :esign/signatures checked
      :esign/missing-signers (vec missing)
      ;; Reported on every verification, at the top level, because it is the
      ;; single most likely thing for a reader to assume the wrong way round.
      ;; The WEAKEST attestation across the signatures — one signature without a
      ;; qualified timestamp is one the measure does not cover, and an envelope
      ;; is the unit a regulator reads.
      :esign/qualified-timestamp? (= "accredited" attestation)
      :esign/time-attestation (keyword attestation)
      :esign/time-note
      (case attestation
        "accredited"
        (str "署名時刻は認定 TSA の RFC 3161 タイムスタンプによります。"
             "電子帳簿保存法の真実性確保措置のうちタイムスタンプの要件を満たし得ます。")
        "tsa-attested"
        (str "RFC 3161 タイムスタンプは検証できましたが、この TSA はこの deployment が"
             "認定事業者として設定したものではありません。電子帳簿保存法が求める"
             "認定タイムスタンプに該当するとは限りません。")
        (str "署名時刻はこの app が記録したものであり、RFC 3161 の時刻認証局による"
             "タイムスタンプではありません。電子帳簿保存法が認める認定タイムスタンプ"
             "には該当しません。"))
      :esign/rp-id-checked? (boolean rp-id-hash)})))
