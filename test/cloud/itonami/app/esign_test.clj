(ns cloud.itonami.app.esign-test
  "What a document signature has to be true of.

  The WebAuthn signatures here are made by a P-256 key generated in the test
  rather than by an authenticator, and that is the point rather than a
  compromise: `cloud.itonami.app.esign.assertion` is written to verify from
  stored bytes with no ceremony object, so a synthetic signature exercises
  exactly the code path a third party would run years later. A test that needed
  a real Touch ID prompt would verify the ceremony and leave the archival path
  untested, which is the one that has to keep working."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.did :as app-did]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.esign :as esign]
            [cloud.itonami.app.esign.assertion :as assertion]
            [cloud.itonami.app.esign.commitment :as commitment]
            [cloud.itonami.app.store :as store]
            [drive.store.memory :as memory])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.fasterxml.jackson.dataformat.cbor CBORFactory]
           [java.nio.charset StandardCharsets]
           [java.security KeyPairGenerator Signature]
           [java.security.spec ECGenParameterSpec]
           [java.util HashMap]))

(def ^:private rp-id "localhost")
(def ^:private origin "http://localhost:8765")

(defn- reset-esign-state! []
  (store/transact!
   (fn [current]
     (assoc current
            :esign {:envelopes {}}
            :credentials {:next-status-index 0 :revoked #{} :issued {}}
            :identity (merge (:identity current)
                             {:users {} :memberships {} :passkeys {}
                              :webauthn-transactions {}})
            :drive {:workspaces {}}))))

(use-fixtures :each (fn [f] (reset-esign-state!) (f)))

;; ── a synthetic authenticator ────────────────────────────────────────────────

(def ^:private cbor-mapper (ObjectMapper. (CBORFactory.)))

(defn- unsigned-32
  "A coordinate as 32 unsigned big-endian bytes. `BigInteger.toByteArray` adds a
  leading zero when the high bit is set and omits leading zeroes otherwise, so
  neither end can be taken on trust."
  [^java.math.BigInteger value]
  (let [raw (.toByteArray value)
        out (byte-array 32)]
    (if (<= (alength raw) 32)
      (System/arraycopy raw 0 out (- 32 (alength raw)) (alength raw))
      (System/arraycopy raw (- (alength raw) 32) out 0 32))
    out))

(defn- authenticator
  "A key pair plus the COSE encoding of its public half, in the shape
  `passkey/finish-registration!` would have stored."
  []
  (let [generator (doto (KeyPairGenerator/getInstance "EC")
                    (.initialize (ECGenParameterSpec. "secp256r1")))
        pair (.generateKeyPair generator)
        point (.getW (.getPublic pair))
        cose (doto (HashMap.)
               (.put (int 1) (int 2))     ; kty: EC2
               (.put (int 3) (int -7))    ; alg: ES256
               (.put (int -1) (int 1))    ; crv: P-256
               (.put (int -2) (unsigned-32 (.getAffineX point)))
               (.put (int -3) (unsigned-32 (.getAffineY point))))
        encoded (assertion/encode (.writeValueAsBytes cbor-mapper cose))]
    {:private (.getPrivate pair)
     :public-key-cose encoded
     :did (app-did/did-key-from-cose encoded)}))

(defn- authenticator-data-bytes
  "32 bytes of rpIdHash, one flags byte, four of counter — the layout
  `assertion/parse-authenticator-data` reads."
  [rp-id {:keys [user-present? user-verified? sign-count]
          :or {user-present? true user-verified? true sign-count 7}}]
  (let [hash (commitment/sha256 (.getBytes ^String rp-id StandardCharsets/UTF_8))
        out (byte-array 37)
        flags (cond-> 0
                user-present? (bit-or 0x01)
                user-verified? (bit-or 0x04))]
    (System/arraycopy hash 0 out 0 32)
    (aset-byte out 32 (unchecked-byte flags))
    (dotimes [i 4]
      (aset-byte out (+ 33 i)
                 (unchecked-byte (bit-and (bit-shift-right sign-count
                                                           (* 8 (- 3 i)))
                                          0xff))))
    out))

(defn- assert-over
  "A WebAuthn assertion response over `challenge`, signed by `auth`."
  [auth ^bytes challenge & [{:keys [origin-override type-override] :as flags}]]
  (let [client-data (json/write-str
                     {"type" (or type-override "webauthn.get")
                      "challenge" (assertion/encode challenge)
                      "origin" (or origin-override origin)
                      "crossOrigin" false})
        client-bytes (.getBytes ^String client-data StandardCharsets/UTF_8)
        auth-data (authenticator-data-bytes rp-id (or flags {}))
        signed (byte-array (+ (alength auth-data) 32))
        _ (System/arraycopy auth-data 0 signed 0 (alength auth-data))
        _ (System/arraycopy (commitment/sha256 client-bytes) 0
                            signed (alength auth-data) 32)
        signer (doto (Signature/getInstance "SHA256withECDSA")
                 (.initSign (:private auth))
                 (.update signed))]
    {:clientDataJSON (assertion/encode client-bytes)
     :authenticatorData (assertion/encode auth-data)
     :signature (assertion/encode (.sign signer))}))

;; ── the commitment ───────────────────────────────────────────────────────────

(def ^:private base-commitment
  {:envelope-id "env-1"
   :document-id "doc-1"
   :document-digest "sha256:aa"
   :presentation-digest "sha256:bb"
   :media-type "application/edn"
   :signer-did "did:key:zTest"
   :purpose :contract/execute
   :intent "同意します"
   :nonce "n1"})

(deftest commitment-is-canonical-and-binds-the-document
  (testing "JCS output is byte-identical regardless of insertion order"
    (let [a (commitment/commitment base-commitment)
          b (commitment/commitment (into (sorted-map) (reverse (seq base-commitment))))]
      (is (= (seq (commitment/canonical-bytes a))
             (seq (commitment/canonical-bytes b))))))

  (testing "the challenge is 32 bytes and equals the recorded digest"
    (let [c (commitment/commitment base-commitment)]
      (is (= 32 (alength (commitment/challenge-bytes c))))
      (is (= (commitment/commitment-digest c)
             (str "sha256:" (commitment/hex (commitment/challenge-bytes c)))))))

  (testing "changing the document digest changes the challenge"
    (is (not= (seq (commitment/challenge-bytes (commitment/commitment base-commitment)))
              (seq (commitment/challenge-bytes
                    (commitment/commitment
                     (assoc base-commitment :document-digest "sha256:cc")))))))

  (testing "changing only the presentation digest changes the challenge — the
            view the signer saw is bound, not just the bytes"
    (is (not= (seq (commitment/challenge-bytes (commitment/commitment base-commitment)))
              (seq (commitment/challenge-bytes
                    (commitment/commitment
                     (assoc base-commitment :presentation-digest "sha256:dd")))))))

  (testing "a missing digest is refused rather than defaulted"
    (doseq [field [:document-digest :presentation-digest :nonce :signer-did]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (commitment/commitment (dissoc base-commitment field)))
          (str field " must be required"))))

  (testing "purpose is a closed set"
    (is (thrown? clojure.lang.ExceptionInfo
                 (commitment/commitment (assoc base-commitment :purpose :whatever/else))))))

;; ── the outline ──────────────────────────────────────────────────────────────

(deftest outline-is-exhaustive-and-order-stable
  (testing "a map of more than eight keys renders in the same order every time"
    ;; The reason this test exists: above eight entries a Clojure map becomes a
    ;; hash map and `pr-str` order follows hashing, so a digest over a
    ;; re-serialization would depend on nothing the signer could see.
    (let [big (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 20)))]
      (is (= (commitment/outline big)
             (commitment/outline (into {} (reverse (seq big))))))))

  (testing "every scalar appears, including ones a rendering would hide"
    (let [resource {:sheets/type :workbook
                    :sheets/tabs {"t1" {:cells {"[1 1]" "見積 100万円"
                                                "[900 900]" "隠れた但し書き"}}}}
          text (commitment/outline resource)]
      (is (str/includes? text "見積 100万円"))
      (is (str/includes? text "隠れた但し書き"))))

  (testing "an empty collection is written rather than dropped"
    (is (str/includes? (commitment/outline {:a {}}) "{}"))
    (is (str/includes? (commitment/outline {:a []}) "[]")))

  (testing "sets get an imposed order rather than inheriting the reader's"
    (is (= (commitment/outline {:a #{3 1 2}})
           (commitment/outline {:a #{2 3 1}})))))

;; ── the assertion verifier ───────────────────────────────────────────────────

(deftest assertion-verifies-only-the-challenge-it-was-given
  (let [auth (authenticator)
        challenge (commitment/challenge-bytes (commitment/commitment base-commitment))
        response (assert-over auth challenge)
        verify (fn [overrides]
                 (assertion/verify
                  (merge {:client-data-json (:clientDataJSON response)
                          :authenticator-data (:authenticatorData response)
                          :signature (:signature response)
                          :public-key-cose (:public-key-cose auth)
                          :expected-challenge challenge}
                         overrides)))]
    (testing "a genuine assertion verifies, and UV is read from signed bytes"
      (let [result (verify {})]
        (is (:verified result))
        (is (:user-verified? result))
        (is (= 7 (:sign-count result)))))

    (testing "the rpIdHash can be checked, and a different rp is refused"
      (is (:verified (verify {:expected-rp-id-hash (assertion/rp-id-hash rp-id)})))
      (is (= :rp-id-mismatch
             (:reason (verify {:expected-rp-id-hash
                               (assertion/rp-id-hash "evil.example")})))))

    (testing "a different challenge is refused — this IS the document binding"
      (is (= :challenge-mismatch
             (:reason (verify {:expected-challenge
                               (commitment/challenge-bytes
                                (commitment/commitment
                                 (assoc base-commitment
                                        :document-digest "sha256:cc")))})))))

    (testing "another key's signature does not verify"
      (is (= :signature-invalid
             (:reason (verify {:public-key-cose (:public-key-cose (authenticator))})))))

    (testing "a tampered signature is refused rather than throwing"
      (is (false? (:verified (verify {:signature (assertion/encode (byte-array 70))})))))

    (testing "verifying against no challenge is a programming error, not a pass"
      (is (thrown? clojure.lang.ExceptionInfo (verify {:expected-challenge nil})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (verify {:expected-challenge (byte-array 0)}))))))

(deftest assertion-requires-user-verification-and-presence
  (let [auth (authenticator)
        challenge (commitment/challenge-bytes (commitment/commitment base-commitment))
        check (fn [flags]
                (let [response (assert-over auth challenge flags)]
                  (assertion/verify
                   {:client-data-json (:clientDataJSON response)
                    :authenticator-data (:authenticatorData response)
                    :signature (:signature response)
                    :public-key-cose (:public-key-cose auth)
                    :expected-challenge challenge})))]
    (testing "an assertion with UV clear is refused even though the signature is good"
      (is (= :user-not-verified (:reason (check {:user-verified? false})))))
    (testing "an assertion with UP clear is refused"
      (is (= :user-not-present (:reason (check {:user-present? false
                                                :user-verified? false})))))
    (testing "a registration ceremony response cannot be used as a signature"
      (let [response (assert-over auth challenge {:type-override "webauthn.create"})]
        (is (= :wrong-ceremony-type
               (:reason (assertion/verify
                         {:client-data-json (:clientDataJSON response)
                          :authenticator-data (:authenticatorData response)
                          :signature (:signature response)
                          :public-key-cose (:public-key-cose auth)
                          :expected-challenge challenge}))))))))

;; ── the envelope ─────────────────────────────────────────────────────────────

(def ^:private alice "user-alice")

(defn- enrol!
  "Put a synthetic authenticator into the identity store, as
  `finish-registration!` would, with `aaguid` deciding the assurance grade."
  [auth user-id aaguid]
  (store/transact!
   (fn [current]
     (-> current
         (assoc-in [:identity :users user-id]
                   {:id user-id :did (:did auth) :email (str user-id "@example.test")})
         (assoc-in [:identity :memberships (str "m-" user-id)]
                   {:id (str "m-" user-id) :user-id user-id
                    :organization-id "org-1" :role :admin})
         (assoc-in [:identity :passkeys (str "cred-" user-id)]
                   {:id (str "cred-" user-id)
                    :credential-id (str "cred-" user-id)
                    :user-id user-id
                    :did (:did auth)
                    :public-key-cose (:public-key-cose auth)
                    :user-verified? true
                    :aaguid aaguid
                    :attachment "platform"
                    :attestation-trusted? false}))))
  auth)

(defn- fixture-document! [object-store]
  (:item (documents/create! :sheets "業務委託契約" alice object-store)))

(deftest envelope-freezes-one-version-of-the-document
  (let [object-store (memory/store)
        auth (enrol! (authenticator) alice
                     "adce0002-35bc-c60a-648b-0b25f1f05503")
        document (fixture-document! object-store)
        envelope (esign/create! {:document-id (:id document)
                                 :purpose :contract/execute
                                 :signer-dids [(:did auth)]
                                 :actor alice
                                 :object-store object-store})]
    (testing "the frozen digest is over the stored bytes of that version"
      (is (str/starts-with? (:esign/document-digest envelope) "sha256:"))
      (is (= (:esign/object-ref envelope)
             (:etag (:item (documents/content (:id document) alice object-store))))))

    (testing "an exhaustive outline was captured and digested"
      (is (seq (:esign/presentation envelope)))
      (is (= (commitment/digest-of-string (:esign/presentation envelope))
             (:esign/presentation-digest envelope))))

    (testing "editing the document afterwards does not change what was frozen"
      (let [before (:esign/document-digest envelope)]
        (documents/update! (:id document)
                           (:payload (documents/content (:id document) alice object-store))
                           alice
                           (:etag (:item (documents/content (:id document) alice
                                                            object-store)))
                           object-store)
        (is (= before
               (:esign/document-digest
                (get-in (store/snapshot) [:esign :envelopes (:esign/id envelope)]))))))

    (testing "a signer with no enrolled Passkey is refused, not left pending"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Passkey が登録されていません"
           (esign/create! {:document-id (:id document)
                           :purpose :contract/execute
                           :signer-dids ["did:key:zNobody"]
                           :actor alice
                           :object-store object-store}))))

    (testing "an envelope with no signers is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (esign/create! {:document-id (:id document)
                                   :purpose :contract/execute
                                   :signer-dids []
                                   :actor alice
                                   :object-store object-store}))))))

;; ── the evidence record ──────────────────────────────────────────────────────

(defn- signed-envelope!
  "An envelope with one recorded signature, assembled without the WebAuthn
  ceremony.

  The ceremony needs a live `RelyingParty` and a browser; what is under test
  here is the evidence record and its verifier, so the signature is made over
  the same commitment `start-signature!` would have produced and recorded
  through the same `signature-entry` shape."
  [{:keys [aaguid] :or {aaguid "adce0002-35bc-c60a-648b-0b25f1f05503"}}]
  (let [object-store (memory/store)
        auth (enrol! (authenticator) alice aaguid)
        document (fixture-document! object-store)
        envelope (esign/create! {:document-id (:id document)
                                 :purpose :contract/execute
                                 :signer-dids [(:did auth)]
                                 :actor alice
                                 :object-store object-store})
        issued (credential/issue-membership!
                {:subject-did (:did auth) :role :admin})
        commitment-map (esign/commitment-for envelope (:did auth)
                                             (get (:credential issued) "id"))
        challenge (commitment/challenge-bytes commitment-map)
        response (assert-over auth challenge)
        verification (assertion/verify
                      {:client-data-json (:clientDataJSON response)
                       :authenticator-data (:authenticatorData response)
                       :signature (:signature response)
                       :public-key-cose (:public-key-cose auth)
                       :expected-challenge challenge})
        _ (assert (:verified verification))
        signature (esign/signature-entry
                   {:commitment-map commitment-map
                    :response {:response response}
                    :passkey-credential
                    (assoc (get-in (store/snapshot)
                                   [:identity :passkeys (str "cred-" alice)])
                           :aaguid aaguid)
                    :verification verification
                    :signed-at (store/now)
                    :role-credential (:credential issued)
                    :status-list (credential/sign
                                  (credential/status-list-credential
                                   (store/snapshot) nil))})
        completed (assoc envelope
                         :esign/status :completed
                         :esign/signatures [signature])]
    {:envelope completed
     :auth auth
     :status-index (:status-index issued)
     :evidence (esign/evidence completed)}))

(deftest evidence-record-carries-no-document-content
  (let [{:keys [evidence envelope]} (signed-envelope! {})
        text (pr-str evidence)]
    (testing "the outline is in the envelope but not in the evidence"
      (is (seq (:esign/presentation envelope)))
      (is (not (str/includes? text "業務委託契約"))
          "a document title is content and does not belong in evidence")
      (is (not (str/includes? text (:esign/presentation envelope)))))
    (testing "what is there is digests, DIDs and signed credentials"
      (is (= (:esign/document-digest envelope)
             (get-in evidence ["document" "digest"])))
      (is (= [(:signer/did (first (:esign/signers envelope)))]
             (get evidence "requiredSigners"))))
    (testing "the absence of a qualified timestamp is stated, not implied"
      (is (false? (get evidence "qualifiedTimestamp")))
      (is (= [] (get evidence "timestamps"))))))

(deftest evidence-verifies-offline-and-fails-closed
  (let [{:keys [evidence]} (signed-envelope! {})
        result (esign/verify-evidence evidence {:rp-id rp-id})]
    (testing "a complete, hardware-attested envelope passes"
      (is (= :total-passed (:esign/status result)) (pr-str (:esign/signatures result)))
      (is (empty? (:esign/missing-signers result)))
      (is (true? (:esign/rp-id-checked? result))))

    (testing "verification never claims a qualified timestamp"
      (is (false? (:esign/qualified-timestamp? result)))
      (is (= :app-attested (:esign/time-attestation result)))
      (is (str/includes? (:esign/time-note result) "電子帳簿保存法")))

    (testing "swapping the document under a valid signature is TOTAL-FAILED"
      (let [tampered (assoc-in evidence ["document" "digest"] "sha256:0000")
            checked (esign/verify-evidence tampered)]
        (is (= :total-failed (:esign/status checked)))
        (is (= :document-not-bound
               (:reason (first (:reasons (first (:esign/signatures checked)))))))))

    (testing "swapping only the presentation digest is also TOTAL-FAILED"
      (is (= :total-failed
             (:esign/status
              (esign/verify-evidence
               (assoc-in evidence ["document" "presentationDigest"]
                         "sha256:0000"))))))

    (testing "recording somebody else's key beside the signature is TOTAL-FAILED"
      (let [other (authenticator)
            tampered (assoc-in evidence ["signatures" 0 "publicKeyCose"]
                               (:public-key-cose other))]
        (is (= :total-failed (:esign/status (esign/verify-evidence tampered))))))

    (testing "a commitment edited after the fact is TOTAL-FAILED"
      (let [tampered (assoc-in evidence
                               ["signatures" 0 "commitment" "intent"]
                               "何にでも同意します")]
        (is (= :total-failed (:esign/status (esign/verify-evidence tampered))))))

    (testing "an envelope missing a required signer is INDETERMINATE, not failed"
      (let [tampered (update evidence "requiredSigners"
                             conj "did:key:zSomebodyElse")
            checked (esign/verify-evidence tampered)]
        (is (= :indeterminate (:esign/status checked)))
        (is (= ["did:key:zSomebodyElse"] (:esign/missing-signers checked)))))

    (testing "an evidence record with no signatures is INDETERMINATE"
      (is (= :indeterminate
             (:esign/status (esign/verify-evidence
                             (assoc evidence "signatures" []))))))))

(deftest assurance-below-the-floor-is-indeterminate-not-passed
  (let [{:keys [evidence]} (signed-envelope!
                            {:aaguid "00000000-0000-0000-0000-000000000000"})
        result (esign/verify-evidence evidence)
        reasons (:reasons (first (:esign/signatures result)))]
    (testing "a browser-withheld AAGUID grades as claimed, and claimed is not evidence"
      (is (= :indeterminate (:esign/status result)))
      (is (= "platform-claimed" (:assurance (first (:esign/signatures result)))))
      (is (some #(= :assurance-below-floor (:reason %)) reasons)))
    (testing "the signature itself is still cryptographically sound"
      (is (not-any? #(= :signature-invalid (:reason %)) reasons)))))

(deftest revocation-after-signing-does-not-unmake-the-signature
  (let [{:keys [evidence status-index]} (signed-envelope! {})]
    (testing "before revocation it passes"
      (is (= :total-passed (:esign/status (esign/verify-evidence evidence)))))
    (credential/revoke! status-index)
    (testing "revoking the role credential afterwards leaves the signature valid"
      ;; The frozen status list is the one consulted, and it does not contain a
      ;; revocation that happened later. A verifier that fetched the CURRENT
      ;; list would report this signature as bad, which would mean any signer
      ;; could invalidate their own past signatures by leaving the company.
      (is (= :total-passed (:esign/status (esign/verify-evidence evidence)))))
    (testing "a credential already revoked at signing time IS indeterminate"
      (let [revoked-list (credential/sign
                          (credential/status-list-credential (store/snapshot) nil))
            frozen-late (assoc-in evidence ["signatures" 0 "statusListCredential"]
                                  revoked-list)
            result (esign/verify-evidence frozen-late)]
        (is (= :indeterminate (:esign/status result)))
        (is (some #(= :role-revoked-at-signing (:reason %))
                  (:reasons (first (:esign/signatures result)))))))
    (testing "an unsigned status list is refused rather than trusted"
      (let [zeros (credential/status-list-credential (store/snapshot) nil)
            tampered (assoc-in evidence ["signatures" 0 "statusListCredential"]
                               zeros)
            result (esign/verify-evidence tampered)]
        (is (= :indeterminate (:esign/status result)))
        (is (some #(= :role-credential-unverifiable (:reason %))
                  (:reasons (first (:esign/signatures result)))))))))

(deftest forgetting-content-keeps-the-evidence
  (let [{:keys [envelope]} (signed-envelope! {})]
    (store/transact! assoc-in [:esign :envelopes (:esign/id envelope)] envelope)
    (let [forgotten (esign/forget-content! (:esign/id envelope))
          evidence (esign/evidence forgotten)]
      (testing "the outline is gone and its absence is stated"
        (is (nil? (:esign/presentation forgotten)))
        (is (true? (:esign/content-forgotten? forgotten))))
      (testing "the signature still verifies against the digests"
        (is (= :total-passed (:esign/status (esign/verify-evidence evidence)))))
      (testing "a view says content-forgotten rather than showing an empty document"
        (let [view (esign/envelope-view forgotten {:principal alice})]
          (is (true? (:content-forgotten? view)))
          (is (not (contains? view :presentation))))))))

(deftest envelope-view-does-not-leak-to-non-participants
  (let [{:keys [envelope auth]} (signed-envelope! {})]
    (store/transact! assoc-in [:esign :envelopes (:esign/id envelope)] envelope)
    (testing "a participant sees the outline"
      (let [view (esign/envelope!(store/snapshot) (:esign/id envelope)
                                 {:principal alice :did (:did auth)})]
        (is (:participant? view))
        (is (seq (:presentation view)))))
    (testing "somebody else is refused rather than shown a redacted envelope"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"権限がありません"
           (esign/envelope! (store/snapshot) (:esign/id envelope)
                            {:principal "user-mallory" :did "did:key:zMallory"}))))
    (testing "every view states that the timestamp is not a qualified one"
      (is (false? (:qualified-timestamp?
                   (esign/envelope-view envelope {:principal alice})))))))

(deftest evidence-survives-a-json-round-trip
  ;; The record is handed to a counterparty as JSON and comes back as JSON. The
  ;; commitment inside it is RFC 8785 JSON whose keys must stay strings — this is
  ;; why the whole record is string-keyed rather than using Clojure keywords, and
  ;; this test is what would catch a reversion to them: keywordized keys print
  ;; back with a colon, the canonical bytes change, and every signature fails to
  ;; verify for a reason that has nothing to do with the signature.
  (let [{:keys [evidence]} (signed-envelope! {})
        round-tripped (json/read-str (json/write-str evidence))]
    (testing "the record is JSON-native: writing and reading changes nothing"
      (is (= evidence round-tripped)))
    (testing "and it still verifies afterwards"
      (is (= :total-passed (:esign/status (esign/verify-evidence round-tripped)))))))
