(ns cloud.itonami.app.wallet-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet]
            [eth-crypto.core :as eth]
            [wallet.siwe :as siwe]))

(def alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def bob {:user-id "bob" :organization-id "org-2" :kind :passkey})
(def mallory {:user-id "mallory" :organization-id "org-1" :kind :passkey})
(def private-key
  (eth/hex->bytes "0000000000000000000000000000000000000000000000000000000000000001"))
(def address (eth/address-of-privkey private-key))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- with-wallet-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-wallet-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state
              (assoc-in (store/initial-state) [:identity :users "alice" :did]
                        "did:key:alice"))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))] (f))
      (finally (reset! store/state previous)))))

(defn- connect! []
  (let [challenge (wallet/start-connection!
                   alice {:address address :chain-id 1}
                   "localhost" "http://localhost:1338")
        signature (siwe/sign-message (:message challenge) private-key)]
    (wallet/finish-connection!
     alice {:transaction-id (:id challenge) :signature signature} "localhost")))

(deftest siwe-connects-a-public-account-without-custody
  (with-wallet-store
    (fn []
      (let [link (connect!)
            persisted (get-in (store/snapshot) [:wallet :links "alice" (:id link)])]
        (is (= address (:address link)))
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
        (is (= :wallet/assignment-not-found
               (refuses #(wallet/call-tool! "bot-a"
                                            "wallet_receive_address" {}))))
        (wallet/assign! alice bot-a (:id link))
        (is (= :wallet/already-assigned
               (refuses #(wallet/assign! alice bot-b (:id link)))))
        (is (= address (:address (wallet/call-tool! "bot-a"
                                                   "wallet_receive_address" {}))))
        (let [proposal (wallet/call-tool!
                        "bot-a" "wallet_propose_send"
                        {:to "0x0000000000000000000000000000000000000002"
                         :value_wei "10000000000000000"})]
          (is (= :awaiting-wallet (:status proposal)))
          (is (= :bot (:proposed-by proposal)))
          (is (nil? (:tx-hash proposal)))
          (is (= :wallet/invalid-amount
                 (refuses #(wallet/create-transfer!
                            "bot-a"
                            {:to "0x0000000000000000000000000000000000000002"
                             :value-wei "0"}
                            :bot)))))))))

(deftest submission-records-only-a-valid-external-wallet-receipt
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
        (is (= :wallet/invalid-tx-hash
               (refuses #(wallet/submit-transfer! alice (:id proposal) "not-a-hash"))))
        (is (= :wallet/transfer-not-found
               (refuses #(wallet/submit-transfer! bob (:id proposal) tx-hash))))
        (is (= :wallet/transfer-not-found
               (refuses #(wallet/submit-transfer! mallory (:id proposal) tx-hash))))
        (let [submitted (wallet/submit-transfer! alice (:id proposal) tx-hash)]
          (is (= :submitted (:status submitted)))
          (is (= "alice" (:submitted-by submitted))))))))
