(ns cloud.itonami.app.esign.assertion
  "Verifying a WebAuthn assertion without the WebAuthn library.

  ## Why this exists when `passkey` already verifies assertions

  `cloud.itonami.app.passkey` hands the ceremony to Yubico's implementation,
  which is the right thing for a ceremony: it owns the challenge, the origin,
  credential ownership, the signature counter and replay. None of that is
  duplicated here and this namespace is not an alternative to it.

  What it is for is the *other* verification — the one performed years later, by
  somebody who is not this process, holding an evidence record and no session.
  That verifier needs to answer one question from stored bytes alone:

      did the holder of this public key sign these exact 32 bytes of challenge?

  Yubico's API cannot answer it, because `finishAssertion` needs an
  `AssertionRequest` that was issued by a live `RelyingParty` — a ceremony
  object, not an archival one. Requiring the archive to reconstruct one would
  make the evidence depend on this app still existing in the same shape. So the
  arithmetic is written out: `authenticatorData || SHA-256(clientDataJSON)`,
  ECDSA-P256, and the flags read out of the signed bytes rather than off the
  response.

  It also means the binding is testable without a browser, a device or a
  network, and that the signing path checks itself with an implementation that
  does not share code with the one that produced the signature.

  ## What is deliberately NOT checked here

  Origin, and the signature counter. Both are ceremony properties: the origin
  matters at the moment of signing and is checked then, and a counter is only
  meaningful against the previous value from the same credential, which an
  evidence record does not carry and must not, because it would leak how often
  the signer authenticates elsewhere. `rp-id` IS checked when the caller
  supplies it — an assertion for a different relying party is signed data about
  something else entirely.

  No store, no clock, no network."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.did :as did]
            [cloud.itonami.app.esign.commitment :as commitment])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security AlgorithmParameters KeyFactory Signature]
           [java.security.spec ECGenParameterSpec ECParameterSpec ECPoint
            ECPublicKeySpec]
           [java.util Arrays Base64]))

(def schema "cloud.itonami.app.esign.assertion.v1")

(def ^:private user-present-flag 0x01)
(def ^:private user-verified-flag 0x04)

(defn decode
  "base64url, padded or not. A response that arrives padded is not malformed,
  and refusing it would fail a verification for a reason unrelated to the
  signature."
  ^bytes [value]
  (.decode (Base64/getUrlDecoder) ^String (str/replace (str value) "=" "")))

(defn encode ^String [^bytes value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- p256-parameters ^ECParameterSpec []
  (let [parameters (AlgorithmParameters/getInstance "EC")]
    (.init parameters (ECGenParameterSpec. "secp256r1"))
    (.getParameterSpec parameters ECParameterSpec)))

(defn public-key
  "A `java.security.PublicKey` from the COSE key stored at enrolment.

  `BigInteger. 1 …` — the coordinates are unsigned big-endian and the one-arg
  constructor would read a leading byte above 0x7f as a sign bit, producing a
  negative coordinate and a key that verifies nothing."
  [public-key-cose]
  (let [{:keys [x y]} (did/p256-coordinates public-key-cose)]
    (.generatePublic (KeyFactory/getInstance "EC")
                     (ECPublicKeySpec. (ECPoint. (BigInteger. 1 x)
                                                 (BigInteger. 1 y))
                                       (p256-parameters)))))

(defn parse-authenticator-data
  "The fields of authenticator data that are worth reading, from the SIGNED
  bytes.

  `:user-verified?` here is not the same fact as the `isUserVerified` a
  ceremony reports — it is that fact read out of the bytes the authenticator
  signed. For evidence that distinction is the entire point: the ceremony's
  answer is a claim by this server about a past moment, and this one is
  checkable by anyone holding the record."
  [^bytes data]
  (when (< (alength data) 37)
    (throw (ex-info "authenticator data が短すぎます。"
                    {:type :esign/malformed-assertion
                     :byte-count (alength data)})))
  (let [flags (bit-and (aget data 32) 0xff)]
    {:rp-id-hash (commitment/hex (Arrays/copyOfRange data 0 32))
     :flags flags
     :user-present? (pos? (bit-and flags user-present-flag))
     :user-verified? (pos? (bit-and flags user-verified-flag))
     :sign-count (reduce (fn [acc i]
                          (bit-or (bit-shift-left acc 8)
                                  (bit-and (aget data i) 0xff)))
                        0 (range 33 37))}))

(defn- refused [reason detail]
  {:schema schema :verified false :reason reason :detail detail})

(defn verify
  "Whether this assertion is a signature by `public-key-cose` over
  `expected-challenge`.

  Returns `{:verified bool …}` rather than throwing, for the reason
  `credential/verify` gives: an assertion that does not verify is an answer,
  and a caller must not be able to read \"it did not throw\" as \"it is good\".
  Malformed input still throws, so a truncated record is not silently reported
  as a failed signature.

  `expected-challenge` is REQUIRED and must be non-empty. An assertion checked
  against no challenge is a signature over bytes the verifier never examined,
  which is indistinguishable from not verifying it at all — so a nil challenge
  is a programming error and is refused rather than skipped."
  [{:keys [client-data-json authenticator-data signature public-key-cose
           expected-challenge expected-rp-id-hash require-user-verification?]
    :or {require-user-verification? true}}]
  (when (or (nil? expected-challenge) (zero? (alength ^bytes expected-challenge)))
    (throw (ex-info "expected-challenge の無い assertion 検証は何も検証しません。"
                    {:type :esign/no-expected-challenge})))
  (let [client-bytes (decode client-data-json)
        auth-bytes (decode authenticator-data)
        signature-bytes (decode signature)
        client-data (json/read-str (String. client-bytes StandardCharsets/UTF_8)
                                   :key-fn keyword)
        parsed (parse-authenticator-data auth-bytes)]
    (cond
      (not= "webauthn.get" (:type client-data))
      (refused :wrong-ceremony-type
               (str "clientDataJSON.type=" (pr-str (:type client-data))))

      (not (Arrays/equals ^bytes expected-challenge
                          ^bytes (decode (:challenge client-data))))
      (refused :challenge-mismatch
               "clientDataJSON の challenge が commitment の digest と一致しません")

      (and expected-rp-id-hash (not= expected-rp-id-hash (:rp-id-hash parsed)))
      (refused :rp-id-mismatch
               (str "rpIdHash=" (:rp-id-hash parsed)))

      (not (:user-present? parsed))
      (refused :user-not-present "authenticator data の UP flag が立っていません")

      (and require-user-verification? (not (:user-verified? parsed)))
      (refused :user-not-verified
               "authenticator data の UV flag が立っていません（生体/PIN 未確認）")

      :else
      (let [signed (byte-array (+ (alength auth-bytes) 32))
            _ (System/arraycopy auth-bytes 0 signed 0 (alength auth-bytes))
            _ (System/arraycopy (commitment/sha256 client-bytes) 0
                                signed (alength auth-bytes) 32)
            ;; A bad signature and an unreadable key both mean "not verified";
            ;; ECDSA verification of malformed DER throws rather than returning
            ;; false, and letting that escape would report a forged signature
            ;; as a server error.
            ok? (try
                  (let [verifier (Signature/getInstance "SHA256withECDSA")]
                    (.initVerify verifier (public-key public-key-cose))
                    (.update verifier signed)
                    (.verify verifier signature-bytes))
                  (catch Exception _ false))]
        (if ok?
          {:schema schema
           :verified true
           :user-verified? (:user-verified? parsed)
           :sign-count (:sign-count parsed)
           :rp-id-hash (:rp-id-hash parsed)
           :origin (:origin client-data)}
          (refused :signature-invalid
                   "ES256 署名が authenticatorData || SHA-256(clientDataJSON) を検証しません"))))))

(defn rp-id-hash
  "`SHA-256(rpId)` in hex — what `authenticator-data` reports, so a caller can
  compare without knowing how it is encoded."
  [rp-id]
  (commitment/hex
   (commitment/sha256 (.getBytes ^String (str rp-id) StandardCharsets/UTF_8))))
