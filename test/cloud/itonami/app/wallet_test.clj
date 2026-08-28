(ns cloud.itonami.app.wallet-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [btc-crypto.bip32 :as bip32]
            [btc-crypto.bip39 :as bip39]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.smart-account :as smart-account]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet]
            [eth-crypto.core :as eth]
            [wallet.signer :as wsigner]
            [wallet.siwe :as siwe])
  (:import [java.util Base64]))

(def alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def bob {:user-id "bob" :organization-id "org-2" :kind :passkey})
(def mallory {:user-id "mallory" :organization-id "org-1" :kind :passkey})
(def private-key
  (eth/hex->bytes "0000000000000000000000000000000000000000000000000000000000000001"))
(def address (eth/address-of-privkey private-key))
(def p256-owner-hex
  (str "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
       "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"))
(def p256-public-key-b64
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (eth/hex->bytes (str "04" p256-owner-hex))))
(def second-p256-owner-hex
  (str "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978"
       "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"))
(def second-p256-public-key-b64
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (eth/hex->bytes (str "04" second-p256-owner-hex))))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- with-wallet-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-wallet-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state
              (-> (store/initial-state)
                  (assoc-in [:identity :users "alice" :did] "did:key:alice")
                  (assoc-in [:identity :users "alice" :principal-id]
                            "urn:kotoba:principal:alice")
                  (assoc-in [:identity :passkeys "cred-alice"]
                            {:id "cred-alice" :credential-id "cred-alice"
                             :user-id "alice" :public-key-b64 p256-public-key-b64
                             :user-verified? true
                             :created-at "2026-08-28T00:00:00Z"})))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))] (f))
      (finally (reset! store/state previous)))))

(defn- connect! []
  (let [challenge (wallet/start-connection!
                   alice {:address address :chain-id 1}
                   "localhost" "http://localhost:1338")
        signature (siwe/sign-message (:message challenge) private-key)]
    (wallet/finish-connection!
     alice {:transaction-id (:id challenge) :signature signature} "localhost")))

(deftest counterfactual-address-matches-the-canonical-factory-vector
  ;; Read-only eth_call against the canonical v1.1 factory's
  ;; getAddress([P-256 generator], 0) returned this address on 2026-08-28.
  (is (= "0x4bF597E75af919CDbB04505C39F4957454262011"
         (smart-account/counterfactual-address
          {:owner-public-key (eth/hex->bytes p256-owner-hex) :nonce 0}))))

(deftest a-passkey-account-keeps-one-address-across-evm-chains
  (with-wallet-store
    (fn []
      (let [ethereum (wallet/ensure-principal-account!
                      {:wallet {:chains [{:chain-id 1 :name "Ethereum"}]}} alice)
            base (wallet/ensure-principal-account!
                  {:wallet {:chains [{:chain-id 8453 :name "Base"}]}} alice)]
        (is (= (:address ethereum) (:address base)))
        (is (= 1 (:chain-id ethereum)))
        (is (= 8453 (:chain-id base)))
        (is (= :passkey-smart-account (:custody ethereum)))
        (is (= :counterfactual (:status ethereum)))
        (is (false? (:private-key-stored? ethereum)))
        (is (false? (:user-operation-ready? ethereum)))))))

(deftest rp-scoped-passkeys-share-a-principal-without-silently-sharing-authority
  (with-wallet-store
    (fn []
      (let [configuration {:server {:webauthn-rp-id "localhost"
                                    :public-origin "http://localhost:1338"}}
            initial (wallet/ensure-principal-account! configuration alice)
            address-before (:address initial)]
        (store/transact!
         assoc-in [:identity :passkeys "cred-kotobase"]
         {:id "cred-kotobase" :credential-id "cred-kotobase"
          :user-id "alice" :public-key-b64 second-p256-public-key-b64
          :user-verified? true :rp-id "kotobase.net"
          :registration-origin "https://auth.kotobase.net"
          :created-at "2026-08-28T00:01:00Z"})
        (let [snapshot (wallet/snapshot configuration alice [])
              account (:principal-account snapshot)
              candidates (:owner-candidates account)
              plan (wallet/plan-owner-addition
                    configuration alice "cred-kotobase")]
          (is (= address-before (:address account))
              "registering a second RP controller never moves the account")
          (is (= [:initial-owner :requires-add-owner-user-operation]
                 (mapv :owner-state candidates)))
          (is (= ["localhost" "kotobase.net"] (mapv :rp-id candidates)))
          (is (= :awaiting-current-owner-authorization (:status plan)))
          (is (= "kotobase.net" (get-in plan [:candidate-owner :rp-id])))
          (is (false? (:user-operation-ready? plan))))))))

(deftest siwe-connects-a-public-account-without-custody
  (with-wallet-store
    (fn []
      (let [link (connect!)
            persisted (get-in (store/snapshot) [:wallet :links "alice" (:id link)])]
        (is (= address (:address link)))
        (is (= "cloud.itonami.app.wallet.link.v2" (:schema link)))
        (is (= :linked-chain-account (:identity-role link)))
        (is (= "urn:kotoba:principal:alice" (:principal-id link)))
        (is (= "did:key:alice" (:account-did link)))
        (is (nil? (:subject-did link)) "a linked account is not the Principal")
        (is (= "eip4361" (:proof-type link)))
        (is (= ["receive" "propose-send"] (:capabilities link)))
        (is (nil? (:private-key persisted)))
        (is (nil? (:signature persisted)))
        (let [again (wallet/start-connection!
                     alice {:address address :chain-id 1}
                     "localhost" "http://localhost:1338")
              signature (siwe/sign-message (:message again) private-key)]
          (is (= :wallet/already-bound
                 (refuses #(wallet/finish-connection!
                            alice {:transaction-id (:id again)
                                   :signature signature}
                            "localhost")))))))))

(deftest a-wallet-proof-is-bound-to-the-stable-principal
  (with-wallet-store
    (fn []
      (let [challenge (wallet/start-connection!
                       alice {:address address :chain-id 8453}
                       "localhost" "http://localhost:1338")
            transaction (get-in (store/snapshot)
                                [:wallet :connection-transactions (:id challenge)])]
        (is (str/includes? (:message challenge)
                           "Cloud Itonami Principal urn:kotoba:principal:alice"))
        (is (= "urn:kotoba:principal:alice" (:principal-id transaction)))
        (is (= "did:key:alice" (:account-did transaction)))
        (is (nil? (:subject-did transaction)))))))

(deftest the-same-evm-address-is-a-distinct-account-on-each-chain
  (with-wallet-store
    (fn []
      (let [ethereum (connect!)
            base-challenge (wallet/start-connection!
                            alice {:address address :chain-id 8453}
                            "localhost" "http://localhost:1338")
            base (wallet/finish-connection!
                  alice
                  {:transaction-id (:id base-challenge)
                   :signature (siwe/sign-message (:message base-challenge) private-key)}
                  "localhost")]
        (is (= (str "eip155:1:" (str/lower-case address)) (:account ethereum)))
        (is (= (str "eip155:8453:" (str/lower-case address)) (:account base)))
        (let [duplicate (wallet/start-connection!
                         alice {:address address :chain-id 8453}
                         "localhost" "http://localhost:1338")]
          (is (= :wallet/already-bound
                 (refuses #(wallet/finish-connection!
                            alice
                            {:transaction-id (:id duplicate)
                             :signature (siwe/sign-message (:message duplicate) private-key)}
                            "localhost")))))))))

(deftest kagi-custody-walks-the-same-siwe-path
  ;; ADR-2608241100 decision 6: the self-custodied (kagi-backed) signer attaches
  ;; where MetaMask would have signed — same challenge, same verify, and the
  ;; link records :custody :kagi + the derivation path. seed-signer stands in
  ;; for kagi.chain-signer here (byte-parity between the two is pinned in
  ;; kagi's own chain-signer tests); this module only sees the Signer protocol.
  (with-wallet-store
    (fn []
      (let [sgnr (wsigner/seed-signer
                  (bip32/seed->master
                   (bip39/mnemonic->seed
                    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")))
            link (wallet/connect-kagi-signer!
                  alice sgnr {} "localhost" "http://localhost:1338")
            persisted (get-in (store/snapshot) [:wallet :links "alice" (:id link)])
            bot {:id "bot-k" :did "did:key:bot-k" :name "Treasurer"
                 :owner-id "alice" :organization-id "org-1"}]
        (is (= :active (:status link)))
        (is (= :kagi (:custody link)))
        (is (= "m/44'/60'/0'/0/0" (:derivation-path link)))
        (is (= "eip4361" (:proof-type link)) "same proof an injected wallet leaves")
        (is (nil? (:private-key persisted)))
        (is (nil? (:signature persisted)))
        ;; A kagi link remains available for legacy assets, but never replaces
        ;; the Bot's Passkey Smart Account.
        (let [assignment (wallet/assign! alice bot (:id link))]
          (is (= :kagi (:custody assignment)))
          (is (= :passkey-smart-account (:custody (wallet/bot-wallet "bot-k")))))
        (wallet/unassign! alice "bot-k")
        (is (= :passkey-smart-account (:custody (wallet/bot-wallet "bot-k"))))
        ;; the external path keeps its custody default
        (let [external (connect!)]
          (is (= :external-wallet (:custody external)))
          (is (nil? (:derivation-path external))))))))

(deftest a-siwe-challenge-is-one-use-and-domain-bound
  (with-wallet-store
    (fn []
      (let [challenge (wallet/start-connection!
                       alice {:address address :chain-id 1}
                       "localhost" "http://localhost:1338")
            signature (siwe/sign-message (:message challenge) private-key)]
        (is (= :wallet/invalid-transaction
               (refuses #(wallet/finish-connection!
                          alice {:transaction-id (:id challenge)
                                 :signature signature}
                          "evil.example"))))
        (wallet/finish-connection!
         alice {:transaction-id (:id challenge) :signature signature} "localhost")
        (is (= :wallet/invalid-transaction
               (refuses #(wallet/finish-connection!
                          alice {:transaction-id (:id challenge)
                                 :signature signature}
                          "localhost"))))))))

(deftest each-bot-gets-one-exclusive-wallet-and-can-only-propose
  (with-wallet-store
    (fn []
      (let [link (connect!)
            bot-a {:id "bot-a" :did "did:key:bot-a" :name "Treasurer"
                   :owner-id "alice" :organization-id "org-1"}
            bot-b (assoc bot-a :id "bot-b" :did "did:key:bot-b" :name "Buyer")]
        (wallet/provision-bot! alice bot-a)
        (wallet/provision-bot! alice bot-b)
        (is (re-matches #"0x[0-9A-Fa-f]{40}"
                        (:address (wallet/call-tool!
                                   "bot-a" "wallet_receive_address" {}))))
        (wallet/assign! alice bot-a (:id link))
        (is (= :wallet/already-assigned
               (refuses #(wallet/assign! alice bot-b (:id link)))))
        (is (not= address (:address (wallet/call-tool! "bot-a"
                                                      "wallet_receive_address" {})))
            "the optional EOA does not replace the Bot account")
        (let [proposal (wallet/call-tool!
                        "bot-a" "wallet_propose_send"
                        {:to "0x0000000000000000000000000000000000000002"
                         :value_wei "10000000000000000"})]
          (is (= :awaiting-passkey-user-operation (:status proposal)))
          (is (= :bot (:proposed-by proposal)))
          (is (nil? (:tx-hash proposal)))
          (is (= :wallet/invalid-amount
                 (refuses #(wallet/create-transfer!
                            "bot-a"
                            {:to "0x0000000000000000000000000000000000000002"
                             :value-wei "0"}
                            :bot)))))))))

(deftest a-passkey-proposal-cannot-be-marked-as-a-legacy-external-transaction
  (with-wallet-store
    (fn []
      (let [link (connect!)
            bot {:id "bot-a" :did "did:key:bot-a" :name "Treasurer"
                 :owner-id "alice" :organization-id "org-1"}
            _ (wallet/assign! alice bot (:id link))
            proposal (wallet/create-transfer!
                      "bot-a"
                      {:to "0x0000000000000000000000000000000000000002"
                       :value-wei "1"}
                      "alice")
            tx-hash (str "0x" (apply str (repeat 64 "a")))]
        (is (= :wallet/transfer-not-found
               (refuses #(wallet/submit-transfer! bob (:id proposal) tx-hash))))
        (is (= :wallet/transfer-not-found
               (refuses #(wallet/submit-transfer! mallory (:id proposal) tx-hash))))
        (is (= :wallet/transfer-state
               (refuses #(wallet/submit-transfer! alice (:id proposal) tx-hash))))))))

(deftest bot-wallet-container-exists-before-a-signer-is-connected
  (with-wallet-store
    (fn []
      (let [bot {:id "bot-auto" :did "did:key:bot-auto" :name "Treasurer"
                 :owner-id "alice" :organization-id "org-1"}
            first-wallet (wallet/provision-bot! alice bot)
            retry-wallet (wallet/provision-bot! alice bot)
            snapshot (wallet/snapshot {} alice [bot])
            public-wallet (-> snapshot :bots first :wallet)]
        (is (= (:id first-wallet) (:id retry-wallet)) "provisioning is retry-safe")
        (is (= :counterfactual (:status first-wallet)))
        (is (= :passkey-smart-account (:custody first-wallet)))
        (is (re-matches #"0x[0-9A-Fa-f]{40}" (:address first-wallet)))
        (is (not= (:address (:principal-account snapshot)) (:address first-wallet))
            "Principal and Bot scopes derive distinct accounts")
        (is (true? (:signer-connected? public-wallet)))
        (is (false? (:user-operation-ready? public-wallet)))
        (is (nil? (:private-key first-wallet)))
        (is (= :wallet/bot-forbidden
               (refuses #(wallet/provision-bot! mallory bot))))))))
