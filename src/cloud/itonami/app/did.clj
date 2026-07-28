(ns cloud.itonami.app.did
  "DID helpers for identities rooted in WebAuthn credentials."
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

(defn- cose-value [^Map cose key]
  (or (.get cose (int key))
      (.get cose (long key))))

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

(defn did-key-from-cose
  "Derive a did:key from a base64url encoded COSE EC2/P-256 public key."
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
      (did-key-from-p256 x y))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Exception error
      (throw (ex-info "Passkey 公開鍵から DID を生成できません。"
                      {:type :did/invalid-public-key}
                      error)))))
