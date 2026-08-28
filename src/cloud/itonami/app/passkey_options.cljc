(ns cloud.itonami.app.passkey-options
  "WebAuthn ceremony *options* as plain EDN. No JVM WebAuthn library.

  Builds the PublicKeyCredentialCreationOptions / RequestOptions-shaped maps
  the browser's `navigator.credentials` expects. Cryptographic verification
  is `cloud.itonami.app.passkey-verify` (cljs / WebCrypto) — ADR-0065.")

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

(defn base64url-encode
  "Encode a seq of byte ints (0-255) to unpadded base64url."
  [bytes]
  (let [bytes (vec bytes)
        n (count bytes)]
    (loop [i 0 chunks []]
      (if (>= i n)
        (apply str chunks)
        (let [b0 (nth bytes i)
              b1 (when (< (inc i) n) (nth bytes (inc i)))
              b2 (when (< (+ i 2) n) (nth bytes (+ i 2)))
              triple (bit-or (bit-shift-left b0 16)
                             (bit-shift-left (or b1 0) 8)
                             (or b2 0))
              c0 (nth b64-alphabet (bit-and (bit-shift-right triple 18) 0x3F))
              c1 (nth b64-alphabet (bit-and (bit-shift-right triple 12) 0x3F))
              c2 (nth b64-alphabet (bit-and (bit-shift-right triple 6) 0x3F))
              c3 (nth b64-alphabet (bit-and triple 0x3F))
              chunk (cond
                      (nil? b1) (str c0 c1)
                      (nil? b2) (str c0 c1 c2)
                      :else (str c0 c1 c2 c3))]
          (recur (+ i 3) (conj chunks chunk)))))))

(defn random-challenge-b64url
  "32 cryptographically random bytes, base64url-encoded."
  []
  #?(:clj (let [b (byte-array 32)]
            (.nextBytes (java.security.SecureRandom.) b)
            (base64url-encode (map #(bit-and % 0xff) b)))
     :cljs (let [a (js/Uint8Array. 32)]
             (.getRandomValues js/crypto a)
             (base64url-encode (array-seq a)))))

(defn creation-options
  [{:keys [rp-id rp-name user-id user-name user-display-name challenge
           timeout]
    :or {rp-name "Cloud Itonami" timeout 120000}}]
  {:publicKey
   {:rp {:id rp-id :name rp-name}
    :user {:id user-id
           :name user-name
           :displayName user-display-name}
    :challenge challenge
    :pubKeyCredParams [{:type "public-key" :alg -7}]
    :timeout timeout
    :attestation "direct"
    :authenticatorSelection
    {:authenticatorAttachment "platform"
     :residentKey "required"
     :requireResidentKey true
     :userVerification "required"}}})

(defn request-options
  [{:keys [rp-id challenge timeout user-verification allow-credentials]
    :or {timeout 120000 user-verification "required"}}]
  {:publicKey
   (cond-> {:challenge challenge
            :rpId rp-id
            :timeout timeout
            :userVerification user-verification}
     (seq allow-credentials)
     (assoc :allowCredentials
            (mapv (fn [credential-id]
                    {:type "public-key" :id credential-id})
                  allow-credentials)))})
