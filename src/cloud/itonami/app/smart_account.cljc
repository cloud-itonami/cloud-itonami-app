(ns cloud.itonami.app.smart-account
  "Counterfactual ERC-4337 accounts controlled by a WebAuthn P-256 key.

  This namespace creates no EOA and stores no secret. It reproduces the
  Coinbase Smart Wallet 1.1 factory's public `getAddress(bytes[],uint256)`
  calculation locally. The open implementation accepts a raw 64-byte P-256
  public key as an owner. Using its immutable cross-chain factory is an
  implementation choice, not a dependency on Base chain or Base identity.

  The factory and implementation are configuration inputs. The canonical 1.1
  deployment is only the default, so Kotoba can deploy the same open contract
  family at its own deterministic addresses without changing Principal or
  Wallet data shapes."
  (:require [clojure.string :as str]
            [eth-crypto.core :as eth]
            [ethereum.abi :as abi]
            #?@(:clj [[cloud.itonami.app.did :as did]]
                :cljs [[eth-crypto.sha256 :as sha256]]))
  #?(:clj (:import [java.math BigInteger]
                   [java.security MessageDigest])))

(def schema "cloud.itonami.app.smart-account.v1")
(def owner-binding-schema "cloud.itonami.app.smart-account.owner-binding.v1")
(def owner-change-schema "cloud.itonami.app.smart-account.owner-change.v1")
(def canonical-factory "0xBA5ED110eFDBa3D005bfC882d75358ACBbB85842")
(def canonical-implementation "0x00000110dCdEdC9581cb5eCB8467282f2926534d")
(def factory-version "1.1")
(def add-owner-public-key-selector "29565e3b")
(def execute-without-chain-id-validation-selector "2c2abd1e")

(def ^:private erc1967-prefix "603d3d8160223d3973")
(def ^:private erc1967-suffix
  (str "60095155f3363d3d373d3d363d7f360894a13ba1a3210667c828492db98dca3e2076"
       "cc3735a920a3ca505d382bbc545af43d6000803e6038573d6000fd5b3d6000f3"))
(def ^:private base64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn- bytes-of [values]
  #?(:clj (byte-array (map unchecked-byte values))
     :cljs (mapv #(bit-and % 0xff) values)))

(defn- concat-bytes [& values]
  (bytes-of (mapcat seq values)))

(defn- decode-public-key [public-key-b64]
  (when-let [encoded (not-empty (str public-key-b64))]
    ;; A small synchronous decoder keeps this identity calculation portable.
    ;; WebAuthn edge values use base64url without padding; accepting ordinary
    ;; base64 as well preserves the legacy JVM adapter's representation.
    (let [encoded (-> encoded
                      (str/replace "-" "+")
                      (str/replace "_" "/")
                      (str/replace #"=+$" ""))]
      (loop [remaining (seq encoded) accumulator 0 bit-count 0 out []]
        (if-let [character (first remaining)]
          (let [value (str/index-of base64-alphabet (str character))]
            (when (neg? value)
              (throw (ex-info "Passkey public key is not base64"
                              {:type :smart-account/invalid-public-key})))
            (let [accumulator (bit-or (bit-shift-left accumulator 6) value)
                  bit-count (+ bit-count 6)]
              (if (>= bit-count 8)
                (let [remaining-bits (- bit-count 8)
                      octet (bit-and
                             (unsigned-bit-shift-right accumulator remaining-bits)
                             0xff)
                      mask (if (zero? remaining-bits)
                             0 (dec (bit-shift-left 1 remaining-bits)))]
                  (recur (next remaining) (bit-and accumulator mask)
                         remaining-bits (conj out octet)))
                (recur (next remaining) accumulator bit-count out))))
          (bytes-of out))))))

(defn owner-public-key
  "Return a Passkey credential's raw 64-byte P-256 point (X || Y).

  Modern edge registrations store the SEC1 uncompressed point in
  :public-key-b64 (65 bytes, leading 0x04). Older registrations may only have
  COSE; did/p256-coordinates is the JVM migration decoder for that old shape."
  [credential]
  (let [raw (decode-public-key (:public-key-b64 credential))
        point (cond
                (= 65 (count raw))
                (if (= 4 (bit-and (first raw) 0xff))
                  (bytes-of (rest raw))
                  raw)

                (= 64 (count raw)) raw

                (:public-key-cose credential)
                #?(:clj
                   (let [{:keys [x y]}
                         (did/p256-coordinates (:public-key-cose credential))]
                     (concat-bytes x y))
                   :cljs nil)

                :else nil)]
    (when-not (= 64 (count point))
      (throw (ex-info "Passkey Smart AccountにはP-256公開鍵が必要です。"
                      {:type :smart-account/passkey-public-key-required
                       :credential-id (:credential-id credential)})))
    point))

(defn- hex20 [value field]
  (let [hex (eth/strip0x (str value))]
    (when-not (re-matches #"(?i)[0-9a-f]{40}" hex)
      (throw (ex-info (str field " must be a 20-byte EVM address")
                      {:type :smart-account/invalid-address
                       :field field :value value})))
    hex))

(defn- unsigned-bigint [bytes]
  #?(:clj (BigInteger. 1 bytes)
     :cljs (js/BigInt (str "0x" (eth/bytes->hex bytes)))))

(defn account-nonce
  "A stable uint256 nonce for one Principal-owned wallet scope.

  The chain id is deliberately absent. With the same deterministic factory,
  one Passkey Wallet therefore has the same address on every supported EVM
  chain."
  [principal-id scope scope-id]
  (unsigned-bigint
   (eth/keccak256
    (eth/utf8 (str "cloud-itonami:passkey-smart-account:v1:"
                   principal-id ":" (name scope) ":" scope-id)))))

(defn counterfactual-address
  "Compute factory.getAddress([P256-owner], nonce), without an RPC call."
  [{:keys [factory-address implementation-address owner-public-key nonce]
    :or {factory-address canonical-factory
         implementation-address canonical-implementation}}]
  (let [factory (eth/hex->bytes (hex20 factory-address "factory-address"))
        implementation-hex (hex20 implementation-address
                                   "implementation-address")
        owner (if (string? owner-public-key)
                (eth/hex->bytes owner-public-key)
                owner-public-key)
        _ (when-not (= 64 (count owner))
            (throw (ex-info "Smart Account owner must be a 64-byte P-256 key"
                            {:type :smart-account/invalid-owner})))
        salt (eth/keccak256
              (bytes-of
               (abi/encode ["bytes[]" "uint256"]
                           [[(str "0x" (eth/bytes->hex owner))] (str nonce)])))
        init-code (eth/hex->bytes
                   (str erc1967-prefix implementation-hex erc1967-suffix))
        init-code-hash (eth/keccak256 init-code)
        digest (eth/keccak256
                (concat-bytes (bytes-of [0xff]) factory salt init-code-hash))
        address (bytes-of (drop 12 (seq digest)))]
    (eth/eip55-checksum address)))

(defn- sha256-hex [bytes]
  (eth/bytes->hex
   #?(:clj (.digest (MessageDigest/getInstance "SHA-256") bytes)
      :cljs (sha256/digest bytes))))

(defn owner-binding
  "Describe one RP-scoped Passkey controller without exposing a secret.

  A WebAuthn key is never domain-independent: `rp-id` is part of its security
  boundary. The Principal and Smart Account remain independent of that RP,
  while this binding records where the controller can actually be exercised.
  Legacy records may not carry RP provenance; the current host configuration
  is used only as an explicit migration fallback and is marked as inferred."
  [configuration credential]
  (let [stored-rp-id (some-> (:rp-id credential) str not-empty)
        fallback-rp-id (some-> (get-in configuration [:server :webauthn-rp-id])
                               str not-empty)
        rp-id (or stored-rp-id fallback-rp-id)
        stored-origins (->> (concat (or (:origins credential) [])
                                    [(:registration-origin credential)
                                     (:origin credential)])
                            (keep #(some-> % str not-empty))
                            distinct
                            vec)
        fallback-origin (some-> (get-in configuration [:server :public-origin])
                                str not-empty)
        origins (if (seq stored-origins)
                  stored-origins
                  (cond-> [] fallback-origin (conj fallback-origin)))
        public-key (owner-public-key credential)]
    {:schema owner-binding-schema
     :kind :webauthn-p256
     :credential-id (:credential-id credential)
     :public-key-sha256 (sha256-hex public-key)
     :rp-id rp-id
     :origins origins
     :rp-binding :required
     :rp-provenance (if stored-rp-id :recorded :host-inferred)
     :private-key-stored? false}))

(defn add-owner-public-key-calldata
  "ABI calldata for MultiOwnable.addOwnerPublicKey(bytes32,bytes32)."
  [credential]
  (let [owner (vec (owner-public-key credential))
        x (str "0x" (eth/bytes->hex (bytes-of (take 32 owner))))
        y (str "0x" (eth/bytes->hex (bytes-of (drop 32 owner))))]
    (abi/encode-call-hex add-owner-public-key-selector
                         ["bytes32" "bytes32"] [x y])))

(defn cross-chain-owner-update-calldata
  "Wrap one owner-management call in the Smart Wallet 1.1 replay-safe entry."
  [inner-calldata]
  (abi/encode-call-hex execute-without-chain-id-validation-selector
                       ["bytes[]"] [[inner-calldata]]))

(defn settings [configuration]
  (merge {:factory-address canonical-factory
          :implementation-address canonical-implementation
          :factory-version factory-version
          :factory-family "coinbase-smart-wallet"
          :owner-management-abi "coinbase-smart-wallet-v1"
          :entry-point-version "0.6"}
         (get-in configuration [:wallet :smart-account])))

(defn descriptor
  "Build the durable, secret-free descriptor for one Principal wallet scope."
  [configuration principal-id credential scope scope-id]
  (let [{:keys [factory-address implementation-address factory-version
                factory-family owner-management-abi entry-point-version]}
        (settings configuration)
        owner (owner-public-key credential)
        binding (owner-binding configuration credential)
        nonce (account-nonce principal-id scope scope-id)
        address (counterfactual-address
                 {:factory-address factory-address
                  :implementation-address implementation-address
                  :owner-public-key owner :nonce nonce})]
    {:schema schema
     :identity-role (if (= scope :principal)
                      :principal-smart-account :bot-smart-account)
     :account-kind :erc4337
     :custody :passkey-smart-account
     :status :counterfactual
     :deployment-state :not-yet-deployed
     :address address
     :namespace "eip155"
     :same-address-across-chains? true
     :principal-id principal-id
     :scope scope
     :scope-id (str scope-id)
     :owner-kind :webauthn-p256
     :owner-model :multi-rp-controller-set
     :identity-root :principal-smart-account
     :initial-owner-credential-id (:credential-id credential)
     :owner-public-key-sha256 (sha256-hex owner)
     :initial-owner binding
     :factory-address factory-address
     :implementation-address implementation-address
     :factory-version factory-version
     :factory-family factory-family
     :owner-management-abi owner-management-abi
     :entry-point-version entry-point-version
     :nonce (str nonce)
     :private-key-stored? false
     :user-operation-ready? false}))

(defn owner-addition-plan
  "Build the unsigned cross-chain call for adding an RP-scoped Passkey owner.

  The call is executable only after the current on-chain owner signs a complete
  ERC-4337 UserOperation. This function neither mutates the descriptor nor
  claims submission readiness."
  [configuration descriptor credential]
  (let [candidate (owner-binding configuration credential)
        current-fingerprint (:owner-public-key-sha256 descriptor)
        candidate-fingerprint (:public-key-sha256 candidate)
        management-abi (or (:owner-management-abi descriptor)
                           (:owner-management-abi (settings configuration)))]
    (when-not (= "coinbase-smart-wallet-v1" management-abi)
      (throw (ex-info "Configured Smart Account owner ABI is unsupported."
                      {:type :smart-account/unsupported-owner-management-abi
                       :owner-management-abi management-abi})))
    (when (= current-fingerprint candidate-fingerprint)
      (throw (ex-info "This Passkey is already the initial Smart Account owner."
                      {:type :smart-account/owner-already-active
                       :credential-id (:credential-id credential)})))
    (let [inner (add-owner-public-key-calldata credential)
          call-data (cross-chain-owner-update-calldata inner)]
      {:schema owner-change-schema
       :operation :add-webauthn-owner
       :status :awaiting-current-owner-authorization
       :principal-id (:principal-id descriptor)
       :account (:address descriptor)
       :candidate-owner candidate
       :contract-call {:target (:address descriptor)
                       :value "0"
                       :function "executeWithoutChainIdValidation(bytes[])"
                       :calldata call-data
                       :inner-function "addOwnerPublicKey(bytes32,bytes32)"
                       :inner-calldata inner}
       :cross-chain-replayable? true
       :current-owner-signature-required? true
       :user-operation-ready? false
       :blocked-by [:entry-point-nonce
                    :current-owner-webauthn-signature
                    :bundler-submission
                    :chain-receipt-verification]})))
