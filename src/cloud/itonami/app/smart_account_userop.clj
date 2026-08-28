(ns cloud.itonami.app.smart-account-userop
  "ERC-4337 v0.6 owner-addition operations for Passkey Smart Accounts.

  Preparation reads the configured chain and bundler, fixes every field of the
  UserOperation, and only then returns the 32-byte WebAuthn challenge. Finish
  verifies the assertion against the current owner credential, encodes the
  exact Coinbase Smart Wallet v1.1 SignatureWrapper, and submits it. A receipt
  is final only after the candidate public key is independently observed in the
  account's on-chain owner set.

  No private key enters this namespace and no signature is persisted by it."
  (:require [asn1.core :as asn1]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.esign.assertion :as assertion]
            [cloud.itonami.app.smart-account :as smart-account]
            [eth-crypto.core :as eth]
            [ethereum.abi :as abi])
  (:import [java.math BigInteger]
           [java.net InetAddress URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security AlgorithmParameters KeyFactory MessageDigest Signature]
           [java.security.spec ECGenParameterSpec ECParameterSpec ECPoint
            ECPublicKeySpec]
           [java.time Duration]))

(def schema "cloud.itonami.app.smart-account.owner-user-operation.v1")

(def ^:private p256-order
  (BigInteger. "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551" 16))
(def ^:private p256-half-order (.shiftRight p256-order 1))
(def ^:private hash-pattern #"(?i)^0x[0-9a-f]{64}$")
(def ^:private bytes-pattern #"(?i)^0x(?:[0-9a-f]{2})*$")

(def rpc-methods
  {:node #{"eth_chainId" "eth_getCode" "eth_call" "eth_gasPrice"}
   :bundler #{"eth_chainId" "eth_supportedEntryPoints"
              "eth_estimateUserOperationGas" "eth_sendUserOperation"
              "eth_getUserOperationReceipt"
              "pimlico_getUserOperationGasPrice"}
   :paymaster #{"pm_sponsorUserOperation"}})

(def ^:dynamic *environment* #(System/getenv %))

(defn- refuse
  ([type message] (refuse type message {}))
  ([type message data]
   (throw (ex-info message (assoc data :type type)))))

(defn- loopback-host? [host]
  (try (.isLoopbackAddress (InetAddress/getByName host))
       (catch Exception _ false)))

(defn- endpoint! [chain kind]
  (let [field (keyword (str (name kind) "-url"))
        env-field (keyword (str (name kind) "-url-env"))
        value (or (some-> (get chain field) str str/trim not-empty)
                  (some-> (get chain env-field) *environment* str str/trim not-empty))]
    (when-not value
      (refuse :wallet/user-operation-not-configured
              (str "Chain " (:chain-id chain) " の " (name kind)
                   " endpoint が設定されていません。")
              {:chain-id (:chain-id chain) :endpoint kind}))
    (let [uri (try (URI/create value)
                   (catch Exception _
                     (refuse :wallet/invalid-user-operation-endpoint
                             (str (name kind) " endpoint URLが不正です。"))))]
      (when-not (or (= "https" (.getScheme uri))
                    (and (= "http" (.getScheme uri))
                         (loopback-host? (.getHost uri))))
        (refuse :wallet/invalid-user-operation-endpoint
                (str (name kind) " endpoint はHTTPS（またはloopback）で指定してください。")))
      value)))

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 8))
             (.followRedirects HttpClient$Redirect/NEVER)
             .build)))

(defn http-transport
  "POST one JSON-RPC request. Endpoint selection and method whitelisting happen
  before this seam; tests replace it with an in-memory chain/bundler."
  [endpoint request]
  (let [http-request (-> (HttpRequest/newBuilder (URI/create endpoint))
                         (.timeout (Duration/ofSeconds 25))
                         (.header "Content-Type" "application/json")
                         (.header "Accept" "application/json")
                         (.POST (HttpRequest$BodyPublishers/ofString
                                 (json/write-str request)))
                         .build)
        response (.send @http-client http-request
                        (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (refuse :wallet/user-operation-rpc-error
              (str "JSON-RPC endpoint がHTTP " (.statusCode response)
                   "を返しました。")))
    (try (json/read-str (.body response) :key-fn keyword)
         (catch Exception _
           (refuse :wallet/user-operation-rpc-error
                   "JSON-RPC endpoint がJSONを返しませんでした。")))))

(def ^:dynamic *transport* http-transport)

(defn- rpc!
  ([endpoint kind method params]
   (rpc! endpoint kind method params false))
  ([endpoint kind method params allow-null?]
   (when-not (contains? (get rpc-methods kind #{}) method)
     (refuse :wallet/user-operation-rpc-method-not-allowed
             (str method " は " (name kind) " actor のwhitelist外です。")))
   (let [response (*transport* endpoint
                               {:jsonrpc "2.0" :id 1
                                :method method :params params})
         error (or (:error response) (get response "error"))
         result (if (contains? response :result)
                  (:result response) (get response "result"))]
     (when error
       (refuse :wallet/user-operation-rpc-error
               (str method " が失敗しました: "
                    (or (:message error) (get error "message") (pr-str error)))
               {:method method :rpc-error error}))
     (when (and (nil? result) (not allow-null?))
       (refuse :wallet/user-operation-rpc-error
               (str method " がresultを返しませんでした。") {:method method}))
     result)))

(defn- quantity! ^BigInteger [value]
  (let [value (str value)]
    (when-not (re-matches #"0x[0-9a-fA-F]+" value)
      (refuse :wallet/user-operation-rpc-error
              (str "RPCが不正なhex quantityを返しました: " value)))
    (BigInteger. (subs value 2) 16)))

(defn- quantity [value]
  (str "0x" (.toString (if (instance? BigInteger value)
                          value (BigInteger. (str value))) 16)))

(defn- valid-bytes! [value field]
  (when-not (and (string? value) (re-matches bytes-pattern value))
    (refuse :wallet/user-operation-rpc-error
            (str field " が不正なhex bytesです。") {:field field}))
  value)

(defn- same-hex? [left right]
  (= (str/lower-case (str left)) (str/lower-case (str right))))

(defn- call-data [signature types values]
  (abi/encode-call-hex (smart-account/function-selector signature) types values))

(defn- eth-call! [node to data]
  (rpc! node :node "eth_call" [{:to to :data data} "latest"]))

(defn- decode-one [type value]
  (first (abi/decode [type] (valid-bytes! value "eth_call result"))))

(defn- code? [value]
  (and (string? value) (re-matches bytes-pattern value)
       (not= "0x" (str/lower-case value))))

(defn- chain-config! [configuration chain-id]
  (let [chain-id (try (long chain-id) (catch Exception _ 0))
        matches (filter #(= chain-id (long (or (:chain-id %) 0)))
                        (get-in configuration [:wallet :chains]))]
    (when-not (= 1 (count matches))
      (refuse :wallet/unsupported-chain
              (str "Chain " chain-id " はWallet allowlistに一意に登録されていません。")
              {:chain-id chain-id}))
    (let [chain (first matches)]
      (assoc chain
             :chain-id chain-id
             :node-endpoint (endpoint! chain :rpc)
             :bundler-endpoint (endpoint! chain :bundler)
             :paymaster-endpoint
             (when (or (some-> (:paymaster-url chain) str str/trim not-empty)
                       (some-> (:paymaster-url-env chain) *environment*
                               str str/trim not-empty))
               (endpoint! chain :paymaster))))))

(defn configured-chain?
  "Whether one public chain entry has both required endpoints available."
  [chain]
  (boolean
   (and (or (some-> (:rpc-url chain) str not-empty)
            (some-> (:rpc-url-env chain) *environment* str not-empty))
        (or (some-> (:bundler-url chain) str not-empty)
            (some-> (:bundler-url-env chain) *environment* str not-empty)))))

(defn- assert-chain! [chain entry-point]
  (let [expected (BigInteger/valueOf (:chain-id chain))
        node-chain (quantity! (rpc! (:node-endpoint chain) :node
                                    "eth_chainId" []))
        bundler-chain (quantity! (rpc! (:bundler-endpoint chain) :bundler
                                       "eth_chainId" []))
        supported (rpc! (:bundler-endpoint chain) :bundler
                        "eth_supportedEntryPoints" [])]
    (when-not (and (= expected node-chain) (= expected bundler-chain))
      (refuse :wallet/user-operation-chain-mismatch
              "RPC、bundler、選択したchain IDが一致しません。"
              {:expected (str expected) :node (str node-chain)
               :bundler (str bundler-chain)}))
    (when-not (some #(same-hex? entry-point %) supported)
      (refuse :wallet/user-operation-entry-point-unsupported
              "BundlerがSmart AccountのEntryPoint v0.6をサポートしていません。"
              {:entry-point entry-point}))))

(defn- assert-contracts!
  [chain descriptor initial-credential entry-point]
  (let [node (:node-endpoint chain)
        factory (:factory-address descriptor)
        account (:address descriptor)
        implementation (:implementation-address descriptor)
        factory-code (rpc! node :node "eth_getCode" [factory "latest"])
        entry-code (rpc! node :node "eth_getCode" [entry-point "latest"])]
    (when-not (and (code? factory-code) (code? entry-code))
      (refuse :wallet/user-operation-contract-unavailable
              "FactoryまたはEntryPointが選択chainにdeployされていません。"
              {:factory-present? (code? factory-code)
               :entry-point-present? (code? entry-code)}))
    (let [actual-implementation
          (decode-one "address"
                      (eth-call! node factory
                                 (call-data "implementation()" [] [])))
          owners [(str "0x" (eth/bytes->hex
                              (smart-account/owner-public-key
                               initial-credential)))]
          predicted
          (decode-one "address"
                      (eth-call! node factory
                                 (call-data "getAddress(bytes[],uint256)"
                                            ["bytes[]" "uint256"]
                                            [owners (:nonce descriptor)])))]
      (when-not (same-hex? implementation actual-implementation)
        (refuse :wallet/user-operation-factory-mismatch
                "FactoryのimplementationがSmart Account descriptorと一致しません。"))
      (when-not (same-hex? account predicted)
        (refuse :wallet/user-operation-address-mismatch
                "Factoryのcounterfactual addressが保存済みWalletと一致しません。")))
    (let [account-code (rpc! node :node "eth_getCode" [account "latest"])
          deployed? (code? account-code)]
      (when deployed?
        (let [account-implementation
              (decode-one "address"
                          (eth-call! node account
                                     (call-data "implementation()" [] [])))
              owner (decode-one
                     "bytes"
                     (eth-call! node account
                                (call-data "ownerAtIndex(uint256)"
                                           ["uint256"] ["0"])))
              expected (eth/bytes->hex
                        (smart-account/owner-public-key initial-credential))]
          (when-not (same-hex? implementation account-implementation)
            (refuse :wallet/user-operation-account-implementation-mismatch
                    "展開済みSmart Accountのimplementationがdescriptorと一致しません。"))
          (when-not (= expected (eth/bytes->hex owner))
            (refuse :wallet/user-operation-current-owner-mismatch
                    "on-chain owner index 0 が現在のPasskeyと一致しません。"))))
      deployed?)))

(defn- dummy-signature []
  (let [auth-data (str "0x" (apply str (repeat 37 "00")))
        client-data "{\"type\":\"webauthn.get\",\"challenge\":\"AA\",\"origin\":\"https://invalid.example\"}"
        signature-data
        (abi/encode-hex ["(bytes,string,uint256,uint256,uint256,uint256)"]
                        [[auth-data client-data "23" "1" "1" "1"]])]
    (abi/encode-hex ["(uint256,bytes)"] [["0" signature-data]])))

(defn- merge-gas [operation estimate]
  (reduce (fn [op field]
            (if-let [value (get estimate field)]
              (assoc op field (quantity (quantity! value)))
              op))
          operation [:callGasLimit :verificationGasLimit :preVerificationGas]))

(defn- gas-price-pair!
  [value source]
  (let [max-fee (quantity! (or (:maxFeePerGas value)
                               (get value "maxFeePerGas")))
        max-priority (quantity! (or (:maxPriorityFeePerGas value)
                                    (get value "maxPriorityFeePerGas")))]
    (when (pos? (.compareTo max-priority max-fee))
      (refuse :wallet/user-operation-rpc-error
              (str source " のmaxPriorityFeePerGasがmaxFeePerGasを超えています。")))
    {:maxFeePerGas (quantity max-fee)
     :maxPriorityFeePerGas (quantity max-priority)}))

(defn- method-not-found? [error]
  (= "-32601" (str (or (get-in (ex-data error) [:rpc-error :code])
                         (get-in (ex-data error) [:rpc-error "code"])))))

(defn- user-operation-gas-prices!
  "Prefer the bundler's current UserOperation prices. Pimlico requires these
  rather than a node's eth_gasPrice and exposes slow/standard/fast tiers. The
  fast tier leaves room for the human WebAuthn ceremony between preparation
  and submission. A provider that does not implement the optional method may
  fall back to eth_gasPrice; every other RPC failure remains fail-closed."
  [chain]
  (try
    (let [prices (rpc! (:bundler-endpoint chain) :bundler
                       "pimlico_getUserOperationGasPrice" [])
          fast (or (:fast prices) (get prices "fast"))]
      (when-not (map? fast)
        (refuse :wallet/user-operation-rpc-error
                "pimlico_getUserOperationGasPrice がfast tierを返しませんでした。"))
      (gas-price-pair! fast "Pimlico fast gas price"))
    (catch clojure.lang.ExceptionInfo error
      (if (method-not-found? error)
        (let [gas-price (quantity
                         (quantity! (rpc! (:node-endpoint chain) :node
                                         "eth_gasPrice" [])))]
          {:maxFeePerGas gas-price :maxPriorityFeePerGas gas-price})
        (throw error)))))

(defn- sponsor! [chain operation entry-point]
  (if-let [endpoint (:paymaster-endpoint chain)]
    (let [result (rpc! endpoint :paymaster "pm_sponsorUserOperation"
                       [operation entry-point])
          paymaster (valid-bytes! (:paymasterAndData result)
                                  "paymasterAndData")]
      (-> operation
          (assoc :paymasterAndData paymaster)
          (merge-gas result)))
    operation))

(defn prepare-owner-addition!
  "Prepare a complete, unsigned owner-addition UserOperation for one chain."
  [configuration descriptor initial-credential candidate-credential chain-id]
  (let [chain (chain-config! configuration chain-id)
        settings (smart-account/settings configuration)
        entry-point (:entry-point-address settings)
        _ (when-not (= "0.6" (:entry-point-version settings))
            (refuse :wallet/user-operation-entry-point-unsupported
                    "Owner追加はEntryPoint v0.6だけをサポートします。"))
        _ (assert-chain! chain entry-point)
        deployed? (assert-contracts! chain descriptor initial-credential
                                     entry-point)
        node (:node-endpoint chain)
        account (:address descriptor)
        nonce-result
        (eth-call! node entry-point
                   (call-data "getNonce(address,uint192)"
                              ["address" "uint192"]
                              [account (str smart-account/replayable-nonce-key)]))
        nonce (quantity (BigInteger. (decode-one "uint256" nonce-result)))
        gas-prices (user-operation-gas-prices! chain)
        plan (smart-account/owner-addition-plan
              configuration descriptor candidate-credential)
        base {:sender account
              :nonce nonce
              :initCode (if deployed? "0x"
                            (smart-account/account-init-code
                             descriptor initial-credential))
              :callData (get-in plan [:contract-call :calldata])
              :callGasLimit "0x0"
              :verificationGasLimit "0x0"
              :preVerificationGas "0x0"
              :maxFeePerGas (:maxFeePerGas gas-prices)
              :maxPriorityFeePerGas (:maxPriorityFeePerGas gas-prices)
              :paymasterAndData "0x"
              :signature (dummy-signature)}
        ;; Pimlico stopped injecting a balance override into gas estimation.
        ;; An unfunded counterfactual account therefore fails with AA21 when
        ;; an unsponsored estimate is attempted first.  The v0.6
        ;; pm_sponsorUserOperation contract accepts zero gas fields and returns
        ;; both paymasterAndData and estimates, so establish sponsorship before
        ;; the bundler simulation whenever a paymaster is configured.
        sponsored (sponsor! chain base entry-point)
        final-estimate
        (merge-gas
         sponsored
         (rpc! (:bundler-endpoint chain) :bundler
               "eth_estimateUserOperationGas" [sponsored entry-point]))
        operation (assoc final-estimate :signature "0x")
        signing-hash (smart-account/cross-chain-user-operation-hash
                      operation entry-point)
        user-op-hash (smart-account/entry-point-user-operation-hash
                      operation entry-point (:chain-id chain))]
    {:schema schema
     :status :awaiting-current-owner-authorization
     :chain-id (:chain-id chain)
     :chain-name (:name chain)
     :account account
     :entry-point entry-point
     :entry-point-version "0.6"
     :current-owner-index 0
     :current-owner-credential-id (:credential-id initial-credential)
     :candidate-credential-id (:credential-id candidate-credential)
     ;; Internal receipt postcondition input. public-view deliberately omits it.
     :candidate-public-key-b64 (:public-key-b64 candidate-credential)
     :candidate-public-key-sha256
     (:public-key-sha256
      (smart-account/owner-binding configuration candidate-credential))
     :candidate-rp-id (:rp-id candidate-credential)
     :account-was-deployed? deployed?
     :user-operation operation
     :signing-hash (str "0x" (eth/bytes->hex signing-hash))
     :expected-user-operation-hash (str "0x" (eth/bytes->hex user-op-hash))}))

(defn signing-challenge [operation]
  (eth/hex->bytes (:signing-hash operation)))

(defn- p256-parameters ^ECParameterSpec []
  (let [parameters (AlgorithmParameters/getInstance "EC")]
    (.init parameters (ECGenParameterSpec. "secp256r1"))
    (.getParameterSpec parameters ECParameterSpec)))

(defn- public-key [credential]
  (let [raw (smart-account/owner-public-key credential)
        x (BigInteger. 1 (byte-array (take 32 raw)))
        y (BigInteger. 1 (byte-array (drop 32 raw)))]
    (.generatePublic (KeyFactory/getInstance "EC")
                     (ECPublicKeySpec. (ECPoint. x y) (p256-parameters)))))

(defn- sha256 ^bytes [^bytes value]
  (.digest (MessageDigest/getInstance "SHA-256") value))

(defn- verify-assertion!
  [operation credential credential-response expected-rp-id expected-origin]
  (let [response (:response credential-response)
        credential-id (or (:id credential-response) (:rawId credential-response))
        _ (when-not (= (:credential-id credential) credential-id)
            (refuse :wallet/user-operation-wrong-passkey
                    "現在のSmart Account owner Passkeyを選択してください。"))
        client-bytes (assertion/decode (:clientDataJSON response))
        auth-bytes (assertion/decode (:authenticatorData response))
        der (assertion/decode (:signature response))
        client-json (String. client-bytes StandardCharsets/UTF_8)
        client-data (try (json/read-str client-json :key-fn keyword)
                         (catch Exception _
                           (refuse :wallet/user-operation-invalid-assertion
                                   "clientDataJSONが不正です。")))
        parsed (assertion/parse-authenticator-data auth-bytes)
        expected-challenge (signing-challenge operation)
        actual-challenge (assertion/decode (:challenge client-data))]
    (when-not (= "webauthn.get" (:type client-data))
      (refuse :wallet/user-operation-invalid-assertion
              "WebAuthn authentication assertionではありません。"))
    (when-not (= expected-origin (:origin client-data))
      (refuse :wallet/user-operation-origin-mismatch
              "Passkey署名のoriginが開始時のoriginと一致しません。"))
    (when (or (= true (:crossOrigin client-data)) (:topOrigin client-data))
      (refuse :wallet/user-operation-origin-mismatch
              "cross-origin WebAuthn assertionはowner変更に使えません。"))
    (when-not (MessageDigest/isEqual expected-challenge actual-challenge)
      (refuse :wallet/user-operation-challenge-mismatch
              "Passkey署名が準備済みUserOperationを対象にしていません。"))
    (when-not (= (assertion/rp-id-hash expected-rp-id) (:rp-id-hash parsed))
      (refuse :wallet/user-operation-rp-mismatch
              "authenticatorDataのRP IDが開始時のRPと一致しません。"))
    (when-not (and (:user-present? parsed) (:user-verified? parsed))
      (refuse :wallet/user-operation-user-verification-required
              "owner変更には生体認証または端末PINが必要です。"))
    (let [signed (byte-array (+ (alength auth-bytes) 32))
          _ (System/arraycopy auth-bytes 0 signed 0 (alength auth-bytes))
          _ (System/arraycopy (sha256 client-bytes) 0 signed
                              (alength auth-bytes) 32)
          verifier (doto (Signature/getInstance "SHA256withECDSA")
                     (.initVerify (public-key credential))
                     (.update signed))]
      (when-not (try (.verify verifier der) (catch Exception _ false))
        (refuse :wallet/user-operation-invalid-signature
                "現在ownerのPasskey署名を検証できませんでした。")))
    {:authenticator-data auth-bytes
     :client-data-json client-json
     :signature-der der
     :sign-count (:sign-count parsed)}))

(defn- der-components [^bytes der]
  (let [decoded (try (asn1/decode der)
                     (catch Exception _
                       (refuse :wallet/user-operation-invalid-signature
                               "Passkey署名のDERが不正です。")))
        component (fn [index]
                    (try (BigInteger.
                          ^String (asn1/integer-hex
                                   (asn1/nth-element decoded index)) 16)
                         (catch Exception _
                           (refuse :wallet/user-operation-invalid-signature
                                   "Passkey署名のr/sが不正です。"))))
        r (component 0)
        raw-s (component 1)
        s (if (pos? (.compareTo raw-s p256-half-order))
            (.subtract p256-order raw-s) raw-s)]
    (when-not (and (pos? (.signum r)) (neg? (.compareTo r p256-order))
                   (pos? (.signum s)) (neg? (.compareTo s p256-order)))
      (refuse :wallet/user-operation-invalid-signature
              "Passkey署名のr/sがP-256の範囲外です。"))
    [r s]))

(defn encode-passkey-signature
  "Verify a browser assertion and return the exact Smart Wallet signature."
  [operation credential credential-response expected-rp-id expected-origin]
  (let [{:keys [authenticator-data client-data-json signature-der sign-count]}
        (verify-assertion! operation credential credential-response
                           expected-rp-id expected-origin)
        [r s] (der-components signature-der)
        challenge-index (str/index-of client-data-json "\"challenge\":\"")
        type-index (str/index-of client-data-json "\"type\":\"webauthn.get\"")]
    (when-not (and (nat-int? challenge-index) (nat-int? type-index))
      (refuse :wallet/user-operation-invalid-assertion
              "clientDataJSONのtype/challenge位置を確認できません。"))
    (let [signature-data
          (abi/encode-hex
           ["(bytes,string,uint256,uint256,uint256,uint256)"]
           [[(str "0x" (eth/bytes->hex authenticator-data))
             client-data-json (str challenge-index) (str type-index)
             (str r) (str s)]])]
      {:signature
       (abi/encode-hex ["(uint256,bytes)"]
                       [[(str (:current-owner-index operation)) signature-data]])
       :sign-count sign-count})))

(defn submit!
  "Submit a verified signed operation and compare the bundler's hash locally."
  [configuration operation signature]
  (let [chain (chain-config! configuration (:chain-id operation))
        _ (assert-chain! chain (:entry-point operation))
        user-operation (assoc (:user-operation operation) :signature signature)
        returned (rpc! (:bundler-endpoint chain) :bundler
                       "eth_sendUserOperation"
                       [user-operation (:entry-point operation)])
        expected (:expected-user-operation-hash operation)]
    (when-not (and (re-matches hash-pattern (str returned))
                   (same-hex? expected returned))
      (refuse :wallet/user-operation-hash-mismatch
              "BundlerのUserOperation hashがローカル計算と一致しません。"
              {:expected expected :returned returned}))
    {:status :submitted :user-operation-hash expected}))

(defn- candidate-owner? [node operation]
  (let [[x y] (smart-account/owner-coordinates
               {:credential-id (:candidate-credential-id operation)
                :public-key-b64 (:candidate-public-key-b64 operation)})
        result (eth-call! node (:account operation)
                          (call-data "isOwnerPublicKey(bytes32,bytes32)"
                                     ["bytes32" "bytes32"] [x y]))]
    (true? (decode-one "bool" result))))

(defn verify-receipt!
  "Poll and verify the ERC-7769 receipt plus the account owner postcondition."
  [configuration operation]
  (let [chain (chain-config! configuration (:chain-id operation))
        _ (assert-chain! chain (:entry-point operation))
        hash (:user-operation-hash operation)
        receipt (rpc! (:bundler-endpoint chain) :bundler
                      "eth_getUserOperationReceipt" [hash] true)]
    (if (nil? receipt)
      {:status :pending :user-operation-hash hash}
      (let [transaction (:receipt receipt)
            tx-hash (:transactionHash transaction)]
        (when-not (and (same-hex? hash (:userOpHash receipt))
                       (same-hex? (:account operation) (:sender receipt))
                       (same-hex? (:entry-point operation) (:entryPoint receipt))
                       (same-hex? (get-in operation [:user-operation :nonce])
                                  (:nonce receipt)))
          (refuse :wallet/user-operation-receipt-mismatch
                  "Receiptが送信したUserOperationの座標と一致しません。"))
        (when-not (and (= true (:success receipt))
                       (contains? #{"0x1" "0x01"} (:status transaction))
                       (re-matches hash-pattern (str tx-hash)))
          (refuse :wallet/user-operation-execution-failed
                  "Owner追加UserOperationはchain上で成功しませんでした。"
                  {:success (:success receipt) :reason (:reason receipt)
                   :transaction-status (:status transaction)}))
        (when-not (code? (rpc! (:node-endpoint chain) :node
                               "eth_getCode" [(:account operation) "latest"]))
          (refuse :wallet/user-operation-postcondition-failed
                  "Receipt後もSmart Account codeを確認できません。"))
        (when-not (candidate-owner? (:node-endpoint chain) operation)
          (refuse :wallet/user-operation-postcondition-failed
                  "Receipt後のon-chain owner setに新しいPasskeyがありません。"))
        {:status :confirmed
         :user-operation-hash hash
         :transaction-hash tx-hash
         :block-number (:blockNumber transaction)
         :actual-gas-cost (:actualGasCost receipt)
         :actual-gas-used (:actualGasUsed receipt)}))))

(defn public-view
  "Client-safe operation view. RPC endpoints, WebAuthn bytes and signatures
  never cross this projection."
  [operation]
  (select-keys operation
               [:schema :id :status :chain-id :chain-name :account
                :entry-point :entry-point-version :candidate-credential-id
                :candidate-public-key-sha256 :candidate-rp-id
                :account-was-deployed? :signing-hash
                :expected-user-operation-hash :user-operation-hash
                :transaction-hash :block-number :created-at :submitted-at
                :confirmed-at]))
