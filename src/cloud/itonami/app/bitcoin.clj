(ns cloud.itonami.app.bitcoin
  "Non-custodial Bitcoin boundary for Cloud Itonami.

  Account ownership uses BIP-322 `simple` signatures for native P2WPKH.
  Spending requests accept PSBT (BIP-174) only and parse the unsigned
  transaction plus witness UTXOs before a Passkey-bound approval is issued.
  This namespace never accepts or persists private keys or seed phrases."
  (:require [btc-crypto.bech32 :as bech32]
            [btc-crypto.core :as btc]
            [btc-crypto.schnorr :as schnorr]
            [btc-crypto.tx :as btc-tx]
            [bitcoin.consensus.sighash :as consensus-sighash]
            [clojure.string :as str]
            [eth-crypto.core :as eth])
  (:import [java.io ByteArrayOutputStream]
           [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Arrays Base64]))

(def chain-references
  {:mainnet "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
   :testnet "000000000933ea01ad0ee984209779baeba33b57f066a63f7924a8e9eada3e4"})

(def ^:private ^BigInteger secp-p
  (BigInteger. "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F" 16))
(def ^:private ^BigInteger secp-n
  (BigInteger. "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141" 16))
(def ^:private ^BigInteger secp-gx
  (BigInteger. "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798" 16))
(def ^:private ^BigInteger secp-gy
  (BigInteger. "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8" 16))
(def ^:private generator [secp-gx secp-gy])

(defn- unsigned-byte [value] (bit-and (long value) 0xff))

(defn- concat-bytes ^bytes [parts]
  (let [output (ByteArrayOutputStream.)]
    (doseq [^bytes part parts] (.write output part 0 (alength part)))
    (.toByteArray output)))

(defn- u32-le ^bytes [value]
  (byte-array (map #(unchecked-byte (bit-shift-right (long value) (* 8 %)))
                   (range 4))))

(defn- u64-le ^bytes [value]
  (byte-array (map #(unchecked-byte (bit-shift-right (long value) (* 8 %)))
                   (range 8))))

(defn- point-add [left right]
  (cond
    (nil? left) right
    (nil? right) left
    :else
    (let [[^BigInteger x1 ^BigInteger y1] left
          [^BigInteger x2 ^BigInteger y2] right]
      (if (and (= x1 x2)
               (= (.mod (.add y1 y2) secp-p) BigInteger/ZERO))
        nil
        (let [slope
              (if (= left right)
                (.mod
                 (.multiply
                  (.multiply (BigInteger/valueOf 3) (.multiply x1 x1))
                  (.modInverse (.multiply (BigInteger/valueOf 2) y1) secp-p))
                 secp-p)
                (.mod
                 (.multiply (.subtract y2 y1)
                            (.modInverse (.subtract x2 x1) secp-p))
                 secp-p))
              x3 (.mod (.subtract (.subtract (.multiply slope slope) x1) x2)
                       secp-p)
              y3 (.mod (.subtract (.multiply slope (.subtract x1 x3)) y1)
                       secp-p)]
          [x3 y3])))))

(defn- point-multiply [^BigInteger scalar point]
  (loop [remaining scalar result nil addend point]
    (if (= remaining BigInteger/ZERO)
      result
      (recur (.shiftRight remaining 1)
             (if (.testBit remaining 0)
               (point-add result addend)
               result)
             (point-add addend addend)))))

(defn- decompress-pubkey [^bytes public-key]
  (when-not (and (= 33 (alength public-key))
                 (contains? #{2 3} (unsigned-byte (aget public-key 0))))
    (throw (ex-info "BIP-322 public key must be compressed."
                    {:type :bitcoin/invalid-proof})))
  (let [x (BigInteger. 1 (Arrays/copyOfRange public-key 1 33))
        y2 (.mod (.add (.modPow x (BigInteger/valueOf 3) secp-p)
                       (BigInteger/valueOf 7))
                 secp-p)
        y (.modPow y2
                   (.divide (.add secp-p BigInteger/ONE)
                            (BigInteger/valueOf 4))
                   secp-p)
        odd? (= 3 (unsigned-byte (aget public-key 0)))
        y (if (= odd? (.testBit y 0)) y (.subtract secp-p y))]
    (when-not (= y2 (.mod (.multiply y y) secp-p))
      (throw (ex-info "BIP-322 public key is not on secp256k1."
                      {:type :bitcoin/invalid-proof})))
    [x y]))

(defn- parse-der [^bytes signature]
  (when-not (and (<= 8 (alength signature) 72)
                 (= 0x30 (unsigned-byte (aget signature 0)))
                 (= (- (alength signature) 2)
                    (unsigned-byte (aget signature 1))))
    (throw (ex-info "Bitcoin DER signature is malformed."
                    {:type :bitcoin/invalid-proof})))
  (let [r-length (unsigned-byte (aget signature 3))
        r-start 4
        s-marker (+ r-start r-length)
        s-length-index (inc s-marker)
        s-start (inc s-length-index)]
    (when-not (and (= 0x02 (unsigned-byte (aget signature 2)))
                   (< s-length-index (alength signature))
                   (= 0x02 (unsigned-byte (aget signature s-marker)))
                   (= (alength signature)
                      (+ s-start (unsigned-byte
                                  (aget signature s-length-index)))))
      (throw (ex-info "Bitcoin DER signature fields are malformed."
                      {:type :bitcoin/invalid-proof})))
    (let [r (BigInteger. 1 (Arrays/copyOfRange signature r-start s-marker))
          s (BigInteger. 1
                         (Arrays/copyOfRange
                          signature s-start (alength signature)))]
      (when-not (and (pos? (.signum r)) (neg? (.compareTo r secp-n))
                     (pos? (.signum s)) (neg? (.compareTo s secp-n))
                     (not (pos? (.compareTo s (.shiftRight secp-n 1)))))
        (throw (ex-info "Bitcoin signature is not canonical low-S."
                        {:type :bitcoin/invalid-proof})))
      {:r r :s s})))

(defn- ecdsa-valid? [^bytes digest ^bytes der-signature ^bytes public-key]
  (try
    (let [{:keys [^BigInteger r ^BigInteger s]} (parse-der der-signature)
          q (decompress-pubkey public-key)
          z (BigInteger. 1 digest)
          inverse (.modInverse s secp-n)
          point (point-add
                 (point-multiply (.mod (.multiply z inverse) secp-n)
                                 generator)
                 (point-multiply (.mod (.multiply r inverse) secp-n) q))]
      (and point (= r (.mod ^BigInteger (first point) secp-n))))
    (catch Exception _ false)))

(defn address-info
  "Validate a native SegWit address and return its network/script metadata.
  BIP-322 verification accepts P2WPKH and Taproot key-path proofs."
  [address]
  (let [{:keys [hrp witver program]} (bech32/decode-segwit-address address)
        network (case hrp "bc" :mainnet "tb" :testnet nil)]
    (when-not network
      (throw (ex-info "Bitcoin network prefix is unsupported."
                      {:type :bitcoin/invalid-address})))
    {:address (str/lower-case address)
     :network network
     :chain-reference (get chain-references network)
     :witness-version witver
     :program (byte-array (map unchecked-byte program))
     :script-type (cond
                    (and (zero? witver) (= 20 (count program))) :p2wpkh
                    (and (= 1 witver) (= 32 (count program))) :p2tr
                    :else :unknown)}))

(defn account-id [address]
  (let [{:keys [chain-reference address]} (address-info address)]
    (str "bip122:" chain-reference ":" address)))

(defn did-pkh [address]
  (str "did:pkh:" (account-id address)))

(defn ownership-message
  [{:keys [domain subject-did account nonce issued-at expires-at resource]}]
  (str "Cloud Itonami Bitcoin Account Link\n"
       "Domain: " domain "\n"
       "Subject: " subject-did "\n"
       "Account: " account "\n"
       "Nonce: " nonce "\n"
       "Issued At: " issued-at "\n"
       "Expiration Time: " expires-at "\n"
       "Resource: " resource))

(defn- tagged-hash ^bytes [tag ^bytes message]
  (let [tag-hash (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes tag StandardCharsets/UTF_8))]
    (.digest (MessageDigest/getInstance "SHA-256")
             (concat-bytes [tag-hash tag-hash message]))))

(defn- serialize-output ^bytes [value ^bytes script]
  (concat-bytes [(u64-le value) (btc-tx/varint (alength script)) script]))

(defn- to-spend-txid ^bytes [^String message ^bytes challenge-script]
  (let [message-hash (tagged-hash
                      "BIP0322-signed-message"
                      (.getBytes message StandardCharsets/UTF_8))
        script-sig (concat-bytes
                    [(byte-array [(unchecked-byte 0x00)
                                  (unchecked-byte 0x20)])
                     message-hash])
        serialized
        (concat-bytes
         [(u32-le 0)
          (btc-tx/varint 1)
          (byte-array 32)
          (u32-le 0xffffffff)
          (btc-tx/varint (alength script-sig))
          script-sig
          (u32-le 0)
          (btc-tx/varint 1)
          (serialize-output 0 challenge-script)
          (u32-le 0)])]
    ;; btc-crypto transaction maps use displayed txid byte order and reverse it
    ;; for wire serialization. SHA256d is wire/internal order, so reverse here.
    (byte-array (reverse (seq (btc/sha256d serialized))))))

(defn- read-compact-size [^bytes data offset]
  (let [first-byte (unsigned-byte (aget data offset))]
    (case first-byte
      0xfd [(+ (unsigned-byte (aget data (inc offset)))
               (bit-shift-left (unsigned-byte (aget data (+ offset 2))) 8))
            (+ offset 3)]
      0xfe [(reduce bit-or
                    (map-indexed
                     (fn [index value]
                       (bit-shift-left (unsigned-byte value) (* 8 index)))
                     (Arrays/copyOfRange data (inc offset) (+ offset 5))))
            (+ offset 5)]
      0xff (throw (ex-info "64-bit CompactSize is not accepted here."
                           {:type :bitcoin/invalid-psbt}))
      [first-byte (inc offset)])))

(defn- read-vector [^bytes data offset]
  (let [[length start] (read-compact-size data offset)
        end (+ start length)]
    (when (> end (alength data))
      (throw (ex-info "Bitcoin vector is truncated."
                      {:type :bitcoin/invalid-proof})))
    [(Arrays/copyOfRange data start end) end]))

(defn decode-simple-witness [signature]
  (let [encoded (if (str/starts-with? signature "smp")
                  (subs signature 3)
                  signature)
        data (try
               (.decode (Base64/getDecoder) encoded)
               (catch Exception _
                 (throw (ex-info "BIP-322 signature is not base64."
                                 {:type :bitcoin/invalid-proof}))))
        [items offset] (read-compact-size data 0)]
    (when-not (<= 1 items 2)
      (throw (ex-info "BIP-322 witness item count is unsupported."
                      {:type :bitcoin/invalid-proof})))
    (loop [index 0 position offset result []]
      (if (< index items)
        (let [[item next-position] (read-vector data position)]
          (recur (inc index) next-position (conj result item)))
        (do
          (when-not (= position (alength data))
            (throw (ex-info "BIP-322 witness has trailing data."
                            {:type :bitcoin/invalid-proof})))
          result)))))

(defn decode-simple-signature [signature]
  (let [items (decode-simple-witness signature)]
    (when-not (= 2 (count items))
      (throw (ex-info "P2WPKH BIP-322 witness must contain two items."
                      {:type :bitcoin/invalid-proof})))
    {:signature (first items) :public-key (second items)}))

(defn- unsigned-vector [values]
  (mapv unsigned-byte values))

(defn- verify-p2tr-bip322
  [^bytes program message encoded-witness]
  (let [items (decode-simple-witness encoded-witness)
        annex (when (and (= 2 (count items))
                         (pos? (alength ^bytes (second items)))
                         (= 0x50
                            (unsigned-byte (aget ^bytes (second items) 0))))
                (second items))
        stack (if annex [(first items)] items)]
    (when-not (= 1 (count stack))
      (throw (ex-info "Taproot BIP-322 requires one key-path signature."
                      {:type :bitcoin/invalid-proof})))
    (let [signature-value ^bytes (first stack)
          size (alength signature-value)
          _ (when-not (contains? #{64 65} size)
              (throw (ex-info "Taproot signature must be 64 or 65 bytes."
                              {:type :bitcoin/invalid-proof})))
          hash-type (if (= size 65)
                      (unsigned-byte (aget signature-value 64))
                      0)
          _ (when (and (= size 65) (zero? hash-type))
              (throw (ex-info "Explicit SIGHASH_DEFAULT is invalid."
                              {:type :bitcoin/invalid-proof})))
          challenge-script
          (concat-bytes
           [(byte-array [(unchecked-byte 0x51) (unchecked-byte 0x20)])
            program])
          displayed-txid (to-spend-txid message challenge-script)
          transaction
          {:version 0
           :inputs
           [{:txid-natural
             (unsigned-vector (reverse (seq displayed-txid)))
             :vout 0 :sequence 0}]
           :outputs [{:value 0 :script-pubkey [0x6a]}]
           :locktime 0}
          prevout
          {:value 0 :script-pubkey (unsigned-vector challenge-script)}
          digest
          (consensus-sighash/taproot-keypath
           transaction 0 [prevout] hash-type
           (some-> annex unsigned-vector))
          schnorr-signature
          (unsigned-vector (Arrays/copyOfRange signature-value 0 64))]
      (schnorr/verify digest (unsigned-vector program) schnorr-signature))))

(defn verify-bip322-simple
  "Verify BIP-322 simple for native P2WPKH or Taproot key-path addresses."
  [address message signature]
  (try
    (let [{:keys [script-type program]} (address-info address)
          _ (when (= :unknown script-type)
              (throw (ex-info "BIP-322 script type is unsupported."
                              {:type :bitcoin/unsupported-script})))]
      (if (= :p2tr script-type)
        (verify-p2tr-bip322 program message signature)
        (let [{:keys [signature public-key]}
              (decode-simple-signature signature)
          signature-length (alength ^bytes signature)
          _ (when-not (and (> signature-length 1)
                           (= btc-tx/sighash-all
                              (unsigned-byte
                               (aget ^bytes signature
                                     (dec signature-length)))))
              (throw (ex-info "BIP-322 proof must use SIGHASH_ALL."
                              {:type :bitcoin/invalid-proof})))
          _ (when-not (Arrays/equals ^bytes program
                                    ^bytes (btc/hash160 public-key))
              (throw (ex-info "BIP-322 public key does not match address."
                              {:type :bitcoin/invalid-proof})))
          challenge-script (concat-bytes
                            [(byte-array [(unchecked-byte 0x00)
                                          (unchecked-byte 0x14)])
                             program])
          tx {:version 0
              :inputs [{:txid (to-spend-txid message challenge-script)
                        :vout 0 :sequence 0}]
              :outputs [{:value 0
                         :script-pubkey
                         (byte-array [(unchecked-byte 0x6a)])}]
              :locktime 0}
          digest (btc-tx/bip143-sighash
                  tx 0 (btc-tx/p2pkh-script program) 0
                  btc-tx/sighash-all)
          der (Arrays/copyOfRange signature 0 (dec signature-length))]
          (ecdsa-valid? digest der public-key))))
    (catch Exception _ false)))

(defn sign-bip322-simple
  "Test/reference signer for P2WPKH. Production Cloud Itonami never calls
  this because private keys remain in the external wallet."
  [^bytes private-key message network]
  (let [public-key (btc/compressed-pubkey private-key)
        address (btc/p2wpkh-address public-key network)
        program (:program (address-info address))
        challenge-script (concat-bytes
                          [(byte-array [(unchecked-byte 0x00)
                                        (unchecked-byte 0x14)])
                           program])
        tx {:version 0
            :inputs [{:txid (to-spend-txid message challenge-script)
                      :vout 0 :sequence 0}]
            :outputs [{:value 0
                       :script-pubkey (byte-array [(unchecked-byte 0x6a)])}]
            :locktime 0}
        digest (btc-tx/bip143-sighash
                tx 0 (btc-tx/p2pkh-script program) 0 btc-tx/sighash-all)
        signature (btc-tx/der-encode-sig
                   (eth/secp256k1-sign private-key digest))
        with-type (concat-bytes
                   [signature (byte-array [(unchecked-byte 0x01)])])
        witness (concat-bytes
                 [(btc-tx/varint 2)
                  (btc-tx/varint (alength with-type)) with-type
                  (btc-tx/varint (alength public-key)) public-key])]
    (str "smp" (.encodeToString (Base64/getEncoder) witness))))

(defn- integer-bytes32 ^bytes [^BigInteger value]
  (let [encoded (.toByteArray value)
        encoded (if (> (alength encoded) 32)
                  (Arrays/copyOfRange encoded (- (alength encoded) 32)
                                      (alength encoded))
                  encoded)
        result (byte-array 32)]
    (System/arraycopy encoded 0 result
                      (- 32 (alength encoded)) (alength encoded))
    result))

(defn- schnorr-sign-reference ^bytes
  [^bytes private-key ^bytes digest]
  (let [secret (BigInteger. 1 private-key)
        _ (when-not (and (pos? (.signum secret))
                         (neg? (.compareTo secret secp-n)))
            (throw (ex-info "Taproot private key is outside secp256k1."
                            {:type :bitcoin/invalid-private-key})))
        [public-x public-y] (point-multiply secret generator)
        adjusted-secret (if (.testBit ^BigInteger public-y 0)
                          (.subtract secp-n secret)
                          secret)
        public-key (integer-bytes32 public-x)
        aux-hash (tagged-hash "BIP0340/aux" (byte-array 32))
        masked-secret
        (byte-array
         (map bit-xor (seq (integer-bytes32 adjusted-secret)) (seq aux-hash)))
        nonce-value
        (.mod
         (BigInteger.
          1
          (tagged-hash
           "BIP0340/nonce"
           (concat-bytes [masked-secret public-key digest])))
         secp-n)
        _ (when (zero? (.signum nonce-value))
            (throw (ex-info "BIP-340 nonce is zero."
                            {:type :bitcoin/signing-failed})))
        [nonce-x nonce-y] (point-multiply nonce-value generator)
        nonce (if (.testBit ^BigInteger nonce-y 0)
                (.subtract secp-n nonce-value)
                nonce-value)
        nonce-x-bytes (integer-bytes32 nonce-x)
        challenge
        (.mod
         (BigInteger.
          1
          (tagged-hash
           "BIP0340/challenge"
           (concat-bytes [nonce-x-bytes public-key digest])))
         secp-n)
        response
        (.mod (.add nonce (.multiply challenge adjusted-secret)) secp-n)]
    (concat-bytes [nonce-x-bytes (integer-bytes32 response)])))

(defn taproot-address
  "Reference x-only key-path address derivation for tests and offline tools."
  [^bytes private-key network]
  (let [[public-x _]
        (point-multiply (BigInteger. 1 private-key) generator)
        hrp (case network :mainnet "bc" :testnet "tb"
                  (throw (ex-info "Unsupported Bitcoin network."
                                  {:type :bitcoin/invalid-address})))]
    (bech32/encode-segwit-address hrp 1
                                  (unsigned-vector
                                   (integer-bytes32 public-x)))))

(defn sign-bip322-taproot-simple
  "Test/reference BIP-322 Taproot key-path signer. Production never calls this;
  private keys remain in the external wallet."
  [^bytes private-key message network]
  (let [address (taproot-address private-key network)
        program (:program (address-info address))
        challenge-script
        (concat-bytes
         [(byte-array [(unchecked-byte 0x51) (unchecked-byte 0x20)])
          program])
        displayed-txid (to-spend-txid message challenge-script)
        transaction
        {:version 0
         :inputs
         [{:txid-natural (unsigned-vector (reverse (seq displayed-txid)))
           :vout 0 :sequence 0}]
         :outputs [{:value 0 :script-pubkey [0x6a]}]
         :locktime 0}
        digest
        (consensus-sighash/taproot-keypath
         transaction 0
         [{:value 0 :script-pubkey (unsigned-vector challenge-script)}]
         0 nil)
        signature
        (schnorr-sign-reference private-key
                                (byte-array (map unchecked-byte digest)))
        witness (concat-bytes
                 [(btc-tx/varint 1)
                  (btc-tx/varint (alength signature))
                  signature])]
    {:address address
     :signature
     (str "smp" (.encodeToString (Base64/getEncoder) witness))}))

(defn- read-u32-le [^bytes data offset]
  (reduce bit-or
          (map-indexed
           (fn [index value]
             (bit-shift-left (unsigned-byte value) (* 8 index)))
           (Arrays/copyOfRange data offset (+ offset 4)))))

(defn- read-u64-le [^bytes data offset]
  (reduce
   (fn [value index]
     (+ value
        (* (bigint (unsigned-byte (aget data (+ offset index))))
           (.pow (biginteger 256) index))))
   0N (range 8)))

(defn- parse-unsigned-transaction [^bytes data]
  (let [length (alength data)
        version (read-u32-le data 0)
        [input-count input-start] (read-compact-size data 4)]
    (when (or (zero? input-count) (> input-count 1000))
      (throw (ex-info "PSBT input count is invalid."
                      {:type :bitcoin/invalid-psbt})))
    (loop [index 0 offset input-start inputs []]
      (if (< index input-count)
        (let [end-outpoint (+ offset 36)
              _ (when (> (+ end-outpoint 1) length)
                  (throw (ex-info "Unsigned transaction is truncated."
                                  {:type :bitcoin/invalid-psbt})))
              txid (Arrays/copyOfRange data offset (+ offset 32))
              vout (read-u32-le data (+ offset 32))
              [script next-offset] (read-vector data end-outpoint)
              _ (when-not (zero? (alength ^bytes script))
                  (throw (ex-info "PSBT unsigned transaction has scriptSig."
                                  {:type :bitcoin/invalid-psbt})))
              sequence (read-u32-le data next-offset)]
          (recur (inc index) (+ next-offset 4)
                 (conj inputs {:txid txid :vout vout :sequence sequence})))
        (let [[output-count output-start] (read-compact-size data offset)]
          (when (or (zero? output-count) (> output-count 1000))
            (throw (ex-info "PSBT output count is invalid."
                            {:type :bitcoin/invalid-psbt})))
          (loop [output-index 0 output-offset output-start outputs []]
            (if (< output-index output-count)
              (let [value (read-u64-le data output-offset)
                    [script next-offset] (read-vector data (+ output-offset 8))]
                (recur (inc output-index) next-offset
                       (conj outputs {:value value :script-pubkey script})))
              (let [final-offset (+ output-offset 4)]
                (when-not (= final-offset length)
                  (throw (ex-info "Unsigned transaction has trailing data."
                                  {:type :bitcoin/invalid-psbt})))
                {:version version :inputs inputs :outputs outputs
                 :locktime (read-u32-le data output-offset)}))))))))

(defn- read-map [^bytes data start]
  (loop [offset start entries []]
    (let [[key-length key-start] (read-compact-size data offset)]
      (if (zero? key-length)
        [entries key-start]
        (let [key-end (+ key-start key-length)
              [value next-offset] (read-vector data key-end)]
          (when (> key-end (alength data))
            (throw (ex-info "PSBT map is truncated."
                            {:type :bitcoin/invalid-psbt})))
          (recur next-offset
                 (conj entries
                       {:type (unsigned-byte (aget data key-start))
                        :key-data (Arrays/copyOfRange data (inc key-start)
                                                     key-end)
                        :value value})))))))

(defn- script-address [^bytes script network]
  (cond
    (and (= 22 (alength script))
         (= 0 (unsigned-byte (aget script 0)))
         (= 20 (unsigned-byte (aget script 1))))
    (bech32/encode-segwit-address
     (if (= network :mainnet) "bc" "tb") 0
     (seq (Arrays/copyOfRange script 2 22)))

    (and (= 34 (alength script))
         (= 0x51 (unsigned-byte (aget script 0)))
         (= 32 (unsigned-byte (aget script 1))))
    (bech32/encode-segwit-address
     (if (= network :mainnet) "bc" "tb") 1
     (seq (Arrays/copyOfRange script 2 34)))

    :else nil))

(defn parse-psbt
  "Parse and validate a PSBT v0 envelope. Returns only review-safe metadata.
  Every input must carry witness_utxo so the fee can be computed without
  trusting browser-supplied summaries."
  [encoded network]
  (let [data (try
               (.decode (Base64/getDecoder) encoded)
               (catch Exception _
                 (throw (ex-info "PSBT must be base64."
                                 {:type :bitcoin/invalid-psbt}))))
        magic (byte-array [(unchecked-byte 0x70) (unchecked-byte 0x73)
                           (unchecked-byte 0x62) (unchecked-byte 0x74)
                           (unchecked-byte 0xff)])]
    (when-not (and (>= (alength data) 6)
                   (Arrays/equals magic (Arrays/copyOfRange data 0 5)))
      (throw (ex-info "PSBT magic bytes are missing."
                      {:type :bitcoin/invalid-psbt})))
    (let [[global-map after-global] (read-map data 5)
          unsigned-entry (some #(when (= 0 (:type %)) %) global-map)
          _ (when-not (and unsigned-entry
                           (zero? (alength ^bytes (:key-data unsigned-entry))))
              (throw (ex-info "PSBT v0 unsigned transaction is missing."
                              {:type :bitcoin/invalid-psbt})))
          transaction (parse-unsigned-transaction (:value unsigned-entry))
          [input-maps offset]
          (loop [remaining (count (:inputs transaction))
                 offset after-global maps []]
            (if (zero? remaining)
              [maps offset]
              (let [[entries next-offset] (read-map data offset)]
                (recur (dec remaining) next-offset (conj maps entries)))))
          [_ final-offset]
          (loop [remaining (count (:outputs transaction))
                 offset offset]
            (if (zero? remaining)
              [nil offset]
              (let [[_ next-offset] (read-map data offset)]
                (recur (dec remaining) next-offset))))
          _ (when-not (= final-offset (alength data))
              (throw (ex-info "PSBT has trailing bytes."
                              {:type :bitcoin/invalid-psbt})))
          input-values
          (mapv
           (fn [entries]
             (let [witness-utxo (some #(when (= 1 (:type %)) %) entries)
                   value (:value witness-utxo)]
               (when-not (and value (>= (alength ^bytes value) 9))
                 (throw (ex-info
                         "Every PSBT input must include witness_utxo."
                         {:type :bitcoin/invalid-psbt})))
               (read-u64-le value 0)))
           input-maps)
          outputs
          (mapv (fn [{:keys [value script-pubkey]}]
                  {:value-sats value
                   :address (script-address script-pubkey network)
                   :script-hex
                   (apply str
                          (map #(format "%02x" (unsigned-byte %))
                               script-pubkey))})
                (:outputs transaction))
          total-input (reduce + 0N input-values)
          total-output (reduce + 0N (map :value-sats outputs))
          fee (- total-input total-output)]
      (when (neg? fee)
        (throw (ex-info "PSBT outputs exceed its inputs."
                        {:type :bitcoin/invalid-psbt})))
      {:schema "cloud.itonami.bitcoin.psbt-review.v1"
       :network network
       :input-count (count input-values)
       :output-count (count outputs)
       :input-sats total-input
       :output-sats total-output
       :fee-sats fee
       :outputs outputs
       :unsigned-tx-sha256
       (apply str
              (map #(format "%02x" (unsigned-byte %))
                   (.digest (MessageDigest/getInstance "SHA-256")
                            ^bytes (:value unsigned-entry))))
       :psbt-sha256
       (apply str
              (map #(format "%02x" (unsigned-byte %))
                   (.digest (MessageDigest/getInstance "SHA-256") data)))})))
