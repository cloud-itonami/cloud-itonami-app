(ns cloud.itonami.app.smart-account-owner-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.smart-account :as smart-account]
            [cloud.itonami.app.smart-account-userop :as userop]
            [eth-crypto.core :as eth]
            [ethereum.abi :as abi])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security KeyPairGenerator MessageDigest Signature]
           [java.security.interfaces ECPublicKey]
           [java.security.spec ECGenParameterSpec]
           [java.util Base64]))

(defn- b64-point [hex]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (eth/hex->bytes (str "04" hex))))

(def initial-key
  (str "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
       "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"))

(def second-key
  (str "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978"
       "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"))

(def configuration
  {:server {:webauthn-rp-id "localhost"
            :public-origin "http://localhost:1338"}})

(def initial-credential
  {:credential-id "cred-local" :public-key-b64 (b64-point initial-key)
   :rp-id "localhost" :registration-origin "http://localhost:1338"})

(def candidate-credential
  {:credential-id "cred-kotobase" :public-key-b64 (b64-point second-key)
   :rp-id "kotobase.net" :registration-origin "https://auth.kotobase.net"})

(deftest an-owner-record-keeps-the-rp-boundary-visible
  (let [binding (smart-account/owner-binding configuration candidate-credential)]
    (is (= :webauthn-p256 (:kind binding)))
    (is (= "kotobase.net" (:rp-id binding)))
    (is (= ["https://auth.kotobase.net"] (:origins binding)))
    (is (= :recorded (:rp-provenance binding)))
    (is (false? (:private-key-stored? binding)))))

(deftest legacy-owner-provenance-is-inferred-not-invented
  (let [binding (smart-account/owner-binding
                 configuration (dissoc initial-credential
                                       :rp-id :registration-origin))]
    (is (= "localhost" (:rp-id binding)))
    (is (= ["http://localhost:1338"] (:origins binding)))
    (is (= :host-inferred (:rp-provenance binding)))))

(deftest a-second-rp-produces-an-unsigned-cross-chain-owner-call
  (let [descriptor (smart-account/descriptor
                    configuration "urn:kotoba:principal:alice"
                    initial-credential :principal "urn:kotoba:principal:alice")
        plan (smart-account/owner-addition-plan
              configuration descriptor candidate-credential)]
    (is (= :add-webauthn-owner (:operation plan)))
    (is (= :awaiting-current-owner-authorization (:status plan)))
    (is (= "kotobase.net" (get-in plan [:candidate-owner :rp-id])))
    (is (.startsWith ^String (get-in plan [:contract-call :inner-calldata])
                     "0x29565e3b"))
    (is (.startsWith ^String (get-in plan [:contract-call :calldata])
                     "0x2c2abd1e"))
    (is (true? (:cross-chain-replayable? plan)))
    (is (true? (:current-owner-signature-required? plan)))
    (is (false? (:user-operation-ready? plan)))
    (is (= [:entry-point-nonce
            :current-owner-webauthn-signature
            :bundler-submission
            :chain-receipt-verification]
           (:blocked-by plan)))))

(deftest the-initial-key-cannot-be-planned-as-a-new-owner
  (let [descriptor (smart-account/descriptor
                    configuration "urn:kotoba:principal:alice"
                    initial-credential :principal "urn:kotoba:principal:alice")]
    (testing "login credential registration never silently duplicates authority"
      (is (= :smart-account/owner-already-active
             (try
               (smart-account/owner-addition-plan
                configuration descriptor initial-credential)
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))))))

(deftest user-operation-hashes-match-independent-solidity-abi-vectors
  ;; Expected values were independently encoded with Foundry cast against the
  ;; EntryPoint v0.6 UserOperationLib.pack field order.
  (let [operation {:sender "0x1111111111111111111111111111111111111111"
                   :nonce "0x21050000000000000000"
                   :initCode "0x1234" :callData "0xabcd"
                   :callGasLimit "0x123" :verificationGasLimit "0x456"
                   :preVerificationGas "0x789" :maxFeePerGas "0xabc"
                   :maxPriorityFeePerGas "0xdef" :paymasterAndData "0x"
                   :signature "0x"}]
    (is (= "5b8f6115fe2be7ecabb05ae314f7511b60a6a55b38cfa2b0adca9fcc6a2ffb8f"
           (eth/bytes->hex
            (smart-account/user-operation-pack-hash operation))))
    (is (= "765e29d1610d0a63cc3e39ee99ddb8d58be21df4ade4cacc452ea797b9d7e11a"
           (eth/bytes->hex
            (smart-account/cross-chain-user-operation-hash operation))))
    (is (= "16056c6eb75dcd489c684a42ccf36f25f0ef517b37fba476484778d1d667a875"
           (eth/bytes->hex
            (smart-account/entry-point-user-operation-hash
             operation smart-account/canonical-entry-point 1))))))

(defn- p256-credential [credential-id rp-id origin]
  (let [generator (KeyPairGenerator/getInstance "EC")
        _ (.initialize generator (ECGenParameterSpec. "secp256r1"))
        pair (.generateKeyPair generator)
        public ^ECPublicKey (.getPublic pair)
        coordinate (fn [^BigInteger value]
                     (let [raw (.toByteArray value)
                           raw (if (and (> (alength raw) 32)
                                        (zero? (aget raw 0)))
                                 (java.util.Arrays/copyOfRange raw 1 (alength raw))
                                 raw)
                           out (byte-array 32)]
                       (System/arraycopy raw 0 out (- 32 (alength raw))
                                         (alength raw))
                       out))
        point (byte-array 65)
        x (coordinate (.getAffineX (.getW public)))
        y (coordinate (.getAffineY (.getW public)))]
    (aset-byte point 0 (byte 4))
    (System/arraycopy x 0 point 1 32)
    (System/arraycopy y 0 point 33 32)
    {:credential {:credential-id credential-id
                  :public-key-b64
                  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) point)
                  :rp-id rp-id :registration-origin origin
                  :user-verified? true}
     :private-key (.getPrivate pair)}))

(defn- b64u [^bytes bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- authenticator-data [rp-id sign-count]
  (let [out (byte-array 37)
        rp-hash (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes rp-id StandardCharsets/UTF_8))]
    (System/arraycopy rp-hash 0 out 0 32)
    (aset-byte out 32 (byte 5))
    (doseq [offset (range 4)]
      (aset-byte out (+ 33 offset)
                 (unchecked-byte
                  (bit-and 0xff (unsigned-bit-shift-right
                                 sign-count (* 8 (- 3 offset)))))))
    out))

(defn- browser-assertion [operation initial-private credential-id rp-id origin]
  (let [client-json (json/write-str
                     {:type "webauthn.get"
                      :challenge (b64u (userop/signing-challenge operation))
                      :origin origin :crossOrigin false})
        client-bytes (.getBytes client-json StandardCharsets/UTF_8)
        auth-data (authenticator-data rp-id 1)
        client-hash (.digest (MessageDigest/getInstance "SHA-256") client-bytes)
        signed (byte-array (+ (alength auth-data) 32))
        _ (System/arraycopy auth-data 0 signed 0 (alength auth-data))
        _ (System/arraycopy client-hash 0 signed (alength auth-data) 32)
        signer (doto (Signature/getInstance "SHA256withECDSA")
                 (.initSign initial-private)
                 (.update signed))]
    {:id credential-id :rawId credential-id :type "public-key"
     :response {:clientDataJSON (b64u client-bytes)
                :authenticatorData (b64u auth-data)
                :signature (b64u (.sign signer))}}))

(deftest a-current-owner-assertion-is-submitted-and-confirmed-on-chain
  (let [{initial :credential initial-private :private-key}
        (p256-credential "initial" "localhost" "http://localhost:1338")
        {candidate :credential}
        (p256-credential "candidate" "kotobase.net"
                         "https://auth.kotobase.net")
        config {:server {:webauthn-rp-id "localhost"
                         :public-origin "http://localhost:1338"}
                :wallet {:chains [{:chain-id 1 :name "Ethereum"
                                   :rpc-url "http://127.0.0.1:8545"
                                   :bundler-url "http://127.0.0.1:4337"}]}}
        descriptor (smart-account/descriptor
                    config "urn:kotoba:principal:alice" initial
                    :principal "urn:kotoba:principal:alice")
        sent-hash (atom nil)
        owner-raw (str "0x" (eth/bytes->hex
                              (smart-account/owner-public-key initial)))
        transport
        (fn [_endpoint request]
          (let [method (:method request)
                params (:params request)
                data (get-in params [0 :data])
                selector (some-> data eth/strip0x (subs 0 (min 8
                                                              (count (eth/strip0x data)))))]
            {:jsonrpc "2.0" :id 1
             :result
             (case method
               "eth_chainId" "0x1"
               "eth_supportedEntryPoints" [smart-account/canonical-entry-point]
               "eth_getCode" "0x60016000"
               "eth_gasPrice" "0x3b9aca00"
               "eth_estimateUserOperationGas"
               {:callGasLimit "0x186a0" :verificationGasLimit "0x30d40"
                :preVerificationGas "0xc350"}
               "eth_sendUserOperation" @sent-hash
               "eth_getUserOperationReceipt"
               {:userOpHash @sent-hash
                :entryPoint smart-account/canonical-entry-point
                :sender (:address descriptor)
                :nonce "0x21050000000000000000"
                :success true :actualGasCost "0x10" :actualGasUsed "0x20"
                :receipt {:transactionHash (str "0x" (apply str (repeat 64 "a")))
                          :blockNumber "0x2" :status "0x1"}}
               "eth_call"
               (case selector
                 "5c60da1b" (abi/encode-hex ["address"]
                                             [smart-account/canonical-implementation])
                 "250b1b41" (abi/encode-hex ["address"] [(:address descriptor)])
                 "8ea69029" (abi/encode-hex ["bytes"] [owner-raw])
                 "35567e1a" (abi/encode-hex ["uint256"]
                                             [(str (.shiftLeft
                                                    (BigInteger/valueOf 8453) 64))])
                 "066a1eb7" (abi/encode-hex ["bool"] [true])
                 (throw (ex-info "unexpected eth_call" {:selector selector}))))}))]
    (binding [userop/*transport* transport]
      (let [prepared (userop/prepare-owner-addition!
                      config descriptor initial candidate 1)
            assertion (browser-assertion prepared initial-private "initial"
                                         "localhost" "http://localhost:1338")
            encoded (userop/encode-passkey-signature
                     prepared initial assertion "localhost"
                     "http://localhost:1338")
            [owner-index signature-data]
            (abi/decode ["uint256" "bytes"] (:signature encoded))
            [_auth _client challenge-index type-index _r s]
            (abi/decode ["bytes" "string" "uint256" "uint256"
                         "uint256" "uint256"] signature-data)]
        (is (= "0" owner-index))
        (is (= "23" challenge-index))
        (is (= "1" type-index))
        (is (not (pos? (.compareTo (BigInteger. s)
                                  (.shiftRight
                                   (BigInteger.
                                    "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"
                                    16) 1))))
            "the on-chain verifier rejects high-S P-256 signatures")
        (reset! sent-hash (:expected-user-operation-hash prepared))
        (let [submitted (userop/submit! config prepared (:signature encoded))
              confirmed (userop/verify-receipt!
                         config (merge prepared submitted))]
          (is (= :submitted (:status submitted)))
          (is (= :confirmed (:status confirmed)))
          (is (= (str "0x" (apply str (repeat 64 "a")))
                 (:transaction-hash confirmed))))))))
