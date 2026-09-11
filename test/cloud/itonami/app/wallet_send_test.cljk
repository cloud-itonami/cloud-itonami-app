(ns cloud.itonami.app.wallet-send-test
  "sign-and-submit! for :custody :kagi transfers. The oracle is
  RECOMPUTATION: the raw tx the stub node receives must be byte-identical
  to signing the same fields with the same Signer in this test, and the
  recorded :tx-hash must be the locally computed keccak of that raw tx.
  Refusals pin their :type; a refused submission must leave the transfer
  UNTOUCHED (a half-submitted state is worse than either outcome)."
  (:require [clojure.test :refer [deftest is]]
            [btc-crypto.bip32 :as bip32]
            [btc-crypto.bip39 :as bip39]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet]
            [cloud.itonami.app.wallet-send :as wallet-send]
            [eth-crypto.core :as eth]
            [wallet.chain :as wchain]
            [wallet.signer :as wsigner]
            [wallet.siwe :as siwe]))

(def alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def bob {:user-id "bob" :organization-id "org-2" :kind :passkey})

(def sgnr
  (wsigner/seed-signer
   (bip32/seed->master
    (bip39/mnemonic->seed
     "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- with-wallet-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-wallet-send-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state
              (assoc-in (store/initial-state) [:identity :users "alice" :did]
                        "did:key:alice"))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))] (f))
      (finally (reset! store/state previous)))))

(def bot {:id "bot-k" :did "did:key:bot-k" :name "Treasurer"
          :owner-id "alice" :organization-id "org-1"})

(defn- kagi-transfer!
  "connect the kagi signer, assign it to the bot, propose a transfer."
  []
  (let [link (wallet/connect-kagi-signer! alice sgnr {} "localhost"
                                          "http://localhost:1338")]
    (wallet/assign! alice bot (:id link))
    {:link link
     :transfer (wallet/create-transfer!
                "bot-k" {:to "0x0000000000000000000000000000000000000002"
                         :value-wei "10000000000000000"}
                "alice")}))

(defn- stub-transport
  "A fake node: fixed nonce/gas-price, records every request, answers
  eth_sendRawTransaction with the TRUE hash of what it received (or a lie,
  when `lie?`)."
  [calls & {:keys [lie?]}]
  (fn [request]
    (swap! calls conj request)
    (case (:method request)
      "eth_getTransactionCount" {:result "0x5"}
      "eth_gasPrice" {:result "0x3b9aca00"}
      "eth_sendRawTransaction"
      {:result (if lie?
                 (str "0x" (apply str (repeat 64 "f")))
                 (eth/raw-tx-hash (first (:params request))))})))

(deftest kagi-transfer-is-signed-broadcast-and-recorded
  (with-wallet-store
    (fn []
      (let [{:keys [link transfer]} (kagi-transfer!)
            calls (atom [])
            submitted (wallet-send/sign-and-submit!
                       alice (:id transfer) sgnr (stub-transport calls))
            sent-raw (->> @calls
                          (filter #(= "eth_sendRawTransaction" (:method %)))
                          first :params first)
            ;; the oracle: re-sign the same fields with the same Signer
            expected-raw (wchain/sign-tx-with
                          sgnr {:chain :eth :path (:derivation-path link)}
                          {:nonce 5 :gas-price 1000000000 :gas 21000
                           :to "0x0000000000000000000000000000000000000002"
                           :value 10000000000000000
                           :data "0x" :chain-id 1})]
        (is (= expected-raw sent-raw) "the node got exactly the tx we authorized")
        (is (= :submitted (:status submitted)))
        (is (= (eth/raw-tx-hash expected-raw) (:tx-hash submitted)))
        (is (= :kagi (:custody submitted)))
        (is (= (:derivation-path link) (:signed-with submitted)))
        (is (= "5" (:nonce submitted)))
        ;; only whitelisted methods ever reached the transport
        (is (= #{"eth_getTransactionCount" "eth_gasPrice" "eth_sendRawTransaction"}
               (set (map :method @calls))))
        ;; a submitted transfer cannot be submitted again
        (is (= :wallet/transfer-state
               (refuses #(wallet-send/sign-and-submit!
                          alice (:id transfer) sgnr (stub-transport calls)))))))))

(deftest a-lying-node-is-refused-and-nothing-is-recorded
  (with-wallet-store
    (fn []
      (let [{:keys [transfer]} (kagi-transfer!)
            calls (atom [])]
        (is (= :wallet/tx-hash-mismatch
               (refuses #(wallet-send/sign-and-submit!
                          alice (:id transfer) sgnr
                          (stub-transport calls :lie? true)))))
        (is (= :awaiting-wallet
               (:status (get-in (store/snapshot) [:wallet :transfers (:id transfer)])))
            "a refused submission leaves the transfer untouched")))))

(deftest external-custody-transfers-are-refused-here
  (with-wallet-store
    (fn []
      ;; an EXTERNAL link (plain SIWE by a browser wallet key) assigned to the
      ;; bot: proposing works, but this actor must not sign for it.
      (let [private-key (eth/hex->bytes
                         "0000000000000000000000000000000000000000000000000000000000000001")
            address (eth/address-of-privkey private-key)
            challenge (wallet/start-connection!
                       alice {:address address :chain-id 1}
                       "localhost" "http://localhost:1338")
            signature (siwe/sign-message (:message challenge) private-key)
            link (wallet/finish-connection!
                  alice {:transaction-id (:id challenge) :signature signature}
                  "localhost")
            _ (wallet/assign! alice bot (:id link))
            transfer (wallet/create-transfer!
                      "bot-k" {:to "0x0000000000000000000000000000000000000002"
                               :value-wei "1"}
                      "alice")]
        (is (= :wallet/custody-mismatch
               (refuses #(wallet-send/sign-and-submit!
                          alice (:id transfer) sgnr (stub-transport (atom []))))))))))

(deftest rpc-errors-and-strangers-are-refused
  (with-wallet-store
    (fn []
      (let [{:keys [transfer]} (kagi-transfer!)]
        (is (= :wallet/transfer-not-found
               (refuses #(wallet-send/sign-and-submit!
                          bob (:id transfer) sgnr (stub-transport (atom []))))))
        (is (= :wallet/rpc-error
               (refuses #(wallet-send/sign-and-submit!
                          alice (:id transfer) sgnr
                          (fn [_] {:error {:code -32000 :message "boom"}})))))
        (is (= :awaiting-wallet
               (:status (get-in (store/snapshot)
                                [:wallet :transfers (:id transfer)]))))))))
