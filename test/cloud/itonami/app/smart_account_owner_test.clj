(ns cloud.itonami.app.smart-account-owner-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.smart-account :as smart-account]
            [eth-crypto.core :as eth])
  (:import [java.util Base64]))

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
