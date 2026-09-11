(ns cloud.itonami.app.did
  "DID helpers for WebAuthn credential public keys.

  A Passkey's COSE P-256 key has a `did:key`. That string names the
  credential, not the person. The User DID is minted at account creation
  (`cloud.itonami.app.identity`) and a Passkey binds to it."
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.fasterxml.jackson.dataformat.cbor CBORFactory]
           [java.math BigInteger]
           [java.util Base64 Map]))

(def ^:private base58-alphabet
  "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(def ^:private cbor-mapper
  (ObjectMapper. (CBORFactory.)))

(defn- unsigned-byte [value]
  (bit-and (int value) 0xff))

(defn- cose-value
  "One COSE_Key label's value, whatever type the CBOR mapper made of the key.

  The string lookup is the one that actually works, and it is not a fallback.
  Jackson's `ObjectMapper.readValue(bytes, Map.class)` applies JSON semantics,
  where object keys are always strings, so it deserialises CBOR INTEGER map keys
  to `\"1\"` / `\"-1\"` / `\"-2\"`. COSE_Key labels are integers (1 = kty,
  3 = alg, -1 = crv, -2 = x, -3 = y), so every numeric lookup misses.

  Measured 2026-07-30: without the string case, `cose-value` returned nil for
  every label, `(long kty)` threw a NullPointerException, and the catch-all in
  `did-key-from-cose` reported `:did/invalid-public-key` -- for every credential,
  always. Passkey registration in this app had never once completed, and there
  was no test over this function to say so.

  The numeric lookups are kept rather than replaced: they cost nothing and they
  are what a CBOR mapper configured to preserve integer keys would need."
  [^Map cose key]
  (or (.get cose (int key))
      (.get cose (long key))
      (.get cose (str key))))

(defn- base58btc [bytes]
  (let [leading-zeroes (count (take-while zero? (map unsigned-byte bytes)))
        value (BigInteger. 1 bytes)]
    (loop [remaining value
           encoded ()]
      (if (zero? (.signum remaining))
        (str (apply str (repeat leading-zeroes \1))
             (if (seq encoded) (apply str encoded) ""))
        (let [parts (.divideAndRemainder remaining (BigInteger/valueOf 58))]
          (recur (aget parts 0)
                 (conj encoded (.charAt base58-alphabet
                                        (.intValue (aget parts 1))))))))))

(defn did-key-from-p256
  "Build a did:key identifier from 32-byte P-256 affine coordinates."
  [x y]
  (when-not (and (= 32 (alength ^bytes x))
                 (= 32 (alength ^bytes y)))
    (throw (ex-info "P-256 公開鍵の座標長が不正です。"
                    {:type :did/invalid-public-key})))
  (let [compressed-prefix (if (even? (unsigned-byte (aget ^bytes y 31)))
                            0x02
                            0x03)
        ;; unsigned-varint(0x1200) is the p256-pub multicodec prefix 0x80 0x24.
        payload (byte-array
                 (concat [(unchecked-byte 0x80)
                          (unchecked-byte 0x24)
                          (unchecked-byte compressed-prefix)]
                         x))]
    (str "did:key:z" (base58btc payload))))

(defn p256-coordinates
  "The affine `{:x bytes :y bytes}` of a base64url encoded COSE EC2/P-256 key.

  Separated from `did-key-from-cose` because verifying a WebAuthn signature
  needs the coordinates themselves, not the DID derived from them
  (`cloud.itonami.app.esign.assertion`). Two decoders of the same COSE bytes
  would be two places for the label-type problem this namespace documents to
  come back."
  [public-key-cose]
  (try
    (let [encoded (.decode (Base64/getUrlDecoder) ^String public-key-cose)
          cose (.readValue cbor-mapper encoded Map)
          kty (cose-value cose 1)
          crv (cose-value cose -1)
          x (cose-value cose -2)
          y (cose-value cose -3)]
      (when-not (and (= 2 (long kty))
                     (= 1 (long crv))
                     (instance? (Class/forName "[B") x)
                     (instance? (Class/forName "[B") y))
        (throw (ex-info "Passkey は EC2/P-256 公開鍵ではありません。"
                        {:type :did/unsupported-public-key})))
      {:x x :y y})
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Exception error
      (throw (ex-info "Passkey 公開鍵を読み取れません。"
                      {:type :did/invalid-public-key}
                      error)))))

(defn did-key-from-cose
  "Derive a did:key from a base64url encoded COSE EC2/P-256 public key."
  [public-key-cose]
  (let [{:keys [x y]} (p256-coordinates public-key-cose)]
    (did-key-from-p256 x y)))
