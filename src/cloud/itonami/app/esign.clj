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

  ## Time is app-attested and this record says so

  `:signature/time-attestation` is `:app-attested`. There is no RFC 3161
  timestamp token, because there is no ASN.1/CMS implementation in this
  workspace to build one with and an accredited TSA speaks nothing else.

  This matters legally rather than cosmetically, so the record states it in a
  field rather than in a comment: in Japan an accredited timestamp
  (総務大臣認定) is one of the three ways to satisfy the tamper-evidence
  requirement for retained electronic transaction data (電子帳簿保存法), and
  this is not one. `verify-evidence` reports `:qualified-timestamp? false` and
  never returns a status that could be read as \"legally timestamped\". A
  blockchain anchor would not change that answer either — it proves \"no later
  than\", which is not what the regulation names.

  ## Content and evidence are separated on purpose

  The envelope holds the outline — that is document content, and a deletion
  request must be able to destroy it. The **evidence record holds digests
  only**: no document bytes, no outline text, no names, no email addresses. So
  `forget-content!` can satisfy an erasure request while the evidence still
  proves that a document with digest X was signed by `did:key:…`.

  This is a structural decision and it had to be made before any byte reached a
  content-addressed store. Writing signed content to Filecoin or IPFS first and
  deciding erasure afterwards is not recoverable — which is why
  `cloud.itonami.app.filecoin` staging is not in this path."
  (:require [clojure.string :as str]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.credential-assurance :as assurance]
            [cloud.itonami.app.did :as did]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.esign.assertion :as assertion]
            [cloud.itonami.app.esign.commitment :as commitment]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store])
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
  "The enrolled credential whose `did:key` is `did`, or nil.

  Keyed on the DID rather than the user id because that is what a commitment
  names, and a user with two Passkeys has two DIDs — resolving by user would
  pick one of them arbitrarily and then verify a signature against a key that
  did not make it."
  [state did]
  (some #(when (= did (:did %)) %)
        (vals (get-in state [:identity :passkeys] {}))))

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
        id (store/new-id "env")
        now (store/now)
        envelope {:esign/id id
                  :esign/document-id document-id
                  :esign/document-title (get-in source [:item :name])
                  :esign/object-ref (:object-ref source)
                  :esign/document-digest (commitment/digest-of (:bytes source))
                  :esign/media-type (:media-type source)
                  :esign/resource-kind (:resource-kind source)
                  ;; Document content. Erasable — see `forget-content!`.
                  :esign/presentation outline
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
           :presentation (:esign/presentation envelope))))

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
           role-credential status-list]}]
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
     ;; Not an RFC 3161 token and not an accredited timestamp. Named as what it
     ;; is so that no reader has to infer it. See the namespace docstring.
     "timeAttestation" "app-attested"
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
        ;; Recomputed from the envelope rather than carried through the
        ;; ceremony, so there is exactly one definition of what was signed and
        ;; nowhere for a second copy to disagree with it.
        recomputed (commitment-for envelope signer-did
                                   (get role-credential "id"))
        _ (when-not (= signer-did (:did passkey-credential))
            (refuse! "署名に使われた Passkey は署名者の鍵ではありません。"
                     :esign/key-not-the-signers
                     {:signer-did signer-did :key-did (:did passkey-credential)}))
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
        status-list (credential/sign
                     (credential/status-list-credential (store/snapshot) nil))
        entry (signature-entry {:commitment-map recomputed
                                :response response
                                :passkey-credential passkey-credential
                                :verification verification
                                :signed-at now
                                :role-credential role-credential
                                :status-list status-list})
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
             ;; signature establishes.
             :qualified-timestamp? false
             :time-attestation "app-attested"
             :participant? participant?}
      (and participant? (:esign/presentation envelope))
      (assoc :presentation (:esign/presentation envelope)))))

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

  `\"timestamps\"` is an empty vector rather than an absent key. Absent would
  read as \"not applicable\"; empty says there are none, which is the fact."
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
   "timestamps" []
   "qualifiedTimestamp" false})

(defn forget-content!
  "Destroy the outline, keep the evidence.

  This is the erasure path, and what it can and cannot do is the honest shape of
  the problem: the content a signer read is destroyed, and the proof that a
  document with digest X was signed by `did:key:…` remains. A retention
  obligation over the second is therefore compatible with a deletion request
  over the first, which is only true because they were never stored together.

  What it does NOT do is remove the document from the Drive — that is
  `documents/purge!` and a different decision. Nor does it make the digest
  reversible; it never was."
  [envelope-id]
  (let [result (volatile! nil)]
    (store/transact!
     (fn [current]
       (if-let [envelope (envelope-of current envelope-id)]
         (let [updated (-> envelope
                           (dissoc :esign/presentation)
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
         statuses (set (map :status checked))]
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
      :esign/qualified-timestamp? false
      :esign/time-attestation :app-attested
      :esign/time-note
      (str "署名時刻はこの app が記録したものであり、RFC 3161 の時刻認証局による"
           "タイムスタンプではありません。電子帳簿保存法が認める認定タイムスタンプ"
           "には該当しません。")
      :esign/rp-id-checked? (boolean rp-id-hash)})))
