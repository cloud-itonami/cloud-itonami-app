(ns cloud.itonami.app.maturity-test
  (:require [btc-crypto.bip32 :as bip32]
            [btc-crypto.bip39 :as bip39]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.bitcoin :as bitcoin]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.mcp-http :as mcp-http]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.receipt-export :as receipt-export]
            [cloud.itonami.app.scheduler :as scheduler]
            [cloud.itonami.app.store :as store]
            [wallet.chain :as wallet])
  (:import [java.security KeyPairGenerator Signature]
           [java.util Base64]))

(def configuration
  {:agent-control {:fleet {:enabled? false}}
   :memory {:max-session-messages 2 :max-memory-messages 20}})

(deftest state-store-recovers-only-validated-bounded-backups
  (let [temporary
        (java.nio.file.Files/createTempDirectory
         "cloud-itonami-state-test-"
         (make-array java.nio.file.attribute.FileAttribute 0))
        directory (.toFile temporary)
        previous @store/state]
    (try
      (with-redefs [config/data-dir (constantly directory)]
        (reset! store/state (store/initial-state))
        (store/transact! assoc :recovery-marker :first)
        (store/transact! assoc :recovery-marker :second)
        (spit (store/state-file) "{invalid")
        (let [load-state (ns-resolve 'cloud.itonami.app.store 'load-state)
              recovered (load-state)]
          (is (= :first (:recovery-marker recovered)))
          (reset! store/state recovered)
          (store/transact! assoc :recovery-marker :recovered)
          (is (= :recovered
                 (:recovery-marker
                  (edn/read-string (slurp (store/state-file))))))
          (is (= 1 (dec (count (file-seq
                                (io/file directory "state-quarantine"))))))
          (is (<= (dec (count (file-seq
                               (io/file directory "state-backups"))))
                  store/max-state-backups))))
      (finally
        (reset! store/state previous)
        (doseq [file (reverse (file-seq directory))]
          (io/delete-file file true))))))

(deftest taproot-bip322-is-bip340-verified-and-tamper-evident
  (let [seed (bip39/mnemonic->seed
              (str "abandon abandon abandon abandon abandon abandon "
                   "abandon abandon abandon abandon abandon about"))
        private-key (:private-key
                     (wallet/account (bip32/seed->master seed) :btc))
        message "cloud-itonami taproot ownership"
        {:keys [address signature]}
        (bitcoin/sign-bip322-taproot-simple private-key message :mainnet)]
    (is (= :p2tr (:script-type (bitcoin/address-info address))))
    (is (bitcoin/verify-bip322-simple address message signature))
    (is (not (bitcoin/verify-bip322-simple
              address (str message " tampered") signature)))))

(deftest mcp-http-supports-stateful-and-stateless-contracts
  (mcp-http/clear-sessions!)
  (let [actor "mcp-user"
        accept {"accept" "application/json, text/event-stream"}
        initialized
        (mcp-http/handle-post
         configuration actor
         {"jsonrpc" "2.0" "id" 1 "method" "initialize"
          "params" {"protocolVersion" "2025-06-18"
                    "clientInfo" {"name" "test" "version" "1"}
                    "capabilities" {}}}
         accept)
        session-id (get-in initialized [:headers "Mcp-Session-Id"])]
    (is (= 200 (:status initialized)))
    (is (uuid? (parse-uuid session-id)))
    (is (= 200
           (:status
            (mcp-http/handle-post
             configuration actor
             {"jsonrpc" "2.0" "id" 2 "method" "tools/list" "params" {}}
             (assoc accept
                    "mcp-session-id" session-id
                    "mcp-protocol-version" mcp-http/protocol-version)))))
    (is (= 204
           (:status
            (mcp-http/handle-delete
             actor {"mcp-session-id" session-id
                    "mcp-protocol-version" mcp-http/protocol-version})))))
  (let [metadata
        {"io.modelcontextprotocol/protocolVersion"
         mcp-http/stateless-protocol-version
         "io.modelcontextprotocol/clientCapabilities" {}}
        headers
        {"accept" "application/json, text/event-stream"
         "mcp-protocol-version" mcp-http/stateless-protocol-version
         "mcp-method" "server/discover"}
        result
        (mcp-http/handle-post
         configuration "modern-user"
         {"jsonrpc" "2.0" "id" "discover" "method" "server/discover"
          "params" {"_meta" metadata}}
         headers)]
    (is (= 200 (:status result)))
    (is (= mcp-http/stateless-protocol-version
           (first (get-in result [:body "result" "supportedVersions"]))))
    (is (= "complete" (get-in result [:body "result" "resultType"])))
    (is (= "cloud-itonami"
           (get-in result
                   [:body "result" "_meta"
                    "io.modelcontextprotocol/serverInfo" "name"])))))

(deftest scheduler-is-actor-scoped-and-idempotent
  (let [previous @store/state
        dispatched (atom [])
        actor "scheduled-user"]
    (try
      (reset! store/state (store/initial-state))
      (let [schedule
            (scheduler/create!
             configuration
             {:goal "Inspect the inbox" :interval-seconds 300}
             actor)
            due-at (java.time.Instant/parse (:next-run-at schedule))]
        (with-redefs
         [agent-control/create-run!
          (fn [_ request run-actor]
            (swap! dispatched conj [request run-actor])
            {:agent.run/id "run-1" :agent.run/status :held})]
          (is (= :dispatched
                 (:status (first (scheduler/tick! configuration due-at)))))
          (is (empty? (scheduler/tick! configuration due-at)))
          (is (= actor (second (first @dispatched)))))
        (is (empty? (scheduler/schedules "another-user")))
        (is (false? (:enabled?
                     (scheduler/disable! actor (:id schedule))))))
      (finally
        (reset! store/state previous)))))

(deftest receipt-export-is-independently-verifiable
  (let [body {:schema passkey/authorization-receipt-schema
              :verification :webauthn-server :verified? true
              :verified-at "2026-07-31T00:00:00Z"
              :transaction-id "tx" :rp-id "gftd.ai"
              :origin "https://gftd.ai" :challenge "challenge"
              :user-id "user" :credential-id "credential"
              :signature-count 1
              :authorization-context {:digest "approved"}
              :evidence {:client-data-json "client"
                         :authenticator-data "authenticator"
                         :signature "webauthn-signature"}}
        receipt (assoc body :verification-digest
                       (passkey/authorization-receipt-digest body))
        payload {:schema receipt-export/payload-schema
                 :source-type "bitcoin-psbt" :source-id "proposal"
                 :actor "user" :authorization-receipt receipt}
        pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        signer (doto (Signature/getInstance "Ed25519")
                 (.initSign (.getPrivate pair)))
        digest (receipt-export/payload-digest payload)
        _ (.update signer
                   (receipt-export/signing-bytes "receipt-key" digest))
        signature (.encodeToString (Base64/getEncoder) (.sign signer))
        public-key (.encodeToString
                    (Base64/getEncoder) (.getEncoded (.getPublic pair)))
        envelope
        (receipt-export/envelope
         payload "receipt-key" signature public-key)]
    (is (receipt-export/verify-envelope? envelope public-key))
    (is (not
         (receipt-export/verify-envelope?
          (assoc-in envelope
                    [:payload :authorization-receipt :evidence :signature]
                    "tampered")
          public-key)))))
