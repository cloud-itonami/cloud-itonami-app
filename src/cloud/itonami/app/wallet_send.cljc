(ns cloud.itonami.app.wallet-send
  "Sign-and-broadcast for :custody :kagi transfers (ADR-2608241100).

  A proposed transfer on an assignment whose custody is :kagi is signed by
  the org's kagi-backed Signer (wallet.signer/Signer — the seed never leaves
  the kagi vault, every signature governed + ledgered there) and broadcast
  as a raw transaction. External-wallet custody keeps its original flow
  (the human's browser wallet signs; wallet/submit-transfer! records the
  receipt) — this module REFUSES those, it does not compete with them.

  ## Why org-ethereum-jsonrpc is NOT used for the send

  That repo is a PERMANENT read-only observer: it will never carry
  eth_sendRawTransaction, by a scope boundary enforced in its code. The
  send capability therefore lives HERE, in the app that owns the custody
  model, behind an explicit whitelist of exactly the three JSON-RPC methods
  this actor needs. Weakening the observer's boundary would have been the
  wrong fix.

  ## Nonce: single writer per address, honestly scoped

  The nonce is fetched (eth_getTransactionCount \"pending\") and consumed
  under a per-address monitor, so two threads in THIS process cannot race
  the same account. Multi-process deployments must still serialize
  externally — that limit is stated here rather than silently assumed away.

  ## The recorded tx-hash is computed, not trusted

  :tx-hash is keccak256 of the raw tx computed locally (eth/raw-tx-hash);
  the node's answer must MATCH it or the submission is refused as
  inconsistent. Scope: plain value transfers only (data 0x, gas 21000) —
  contract calls are out of scope for a Bot allowance wallet."
  (:require [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet]
            [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])
            [eth-crypto.core :as eth]
            [wallet.chain :as wchain]
            [wallet.chains :as wchains])
  #?(:clj (:import [java.math BigInteger]
                   [java.net URI]
                   [java.net.http HttpClient HttpRequest
                    HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
                   [java.time Duration])))

(def rpc-methods
  "The ONLY JSON-RPC methods this actor may call."
  #{"eth_getTransactionCount" "eth_gasPrice" "eth_sendRawTransaction"})

(defn- refuse [type message]
  (throw (ex-info message {:type type})))

#?(:clj
   (do

(defn- rpc-call! [transport method params]
  (when-not (contains? rpc-methods method)
    (refuse :wallet/rpc-method-not-allowed
            (str "wallet-send: " method " はこのactorのwhitelist外です。")))
  (let [{:keys [result error]} (transport {:jsonrpc "2.0" :id 1
                                           :method method :params params})]
    (when error
      (refuse :wallet/rpc-error
              (str "RPC " method " が失敗しました: "
                   (or (:message error) (pr-str error)))))
    (when (nil? result)
      (refuse :wallet/rpc-error (str "RPC " method " がresultを返しませんでした。")))
    result))

(defn- hex->bigint ^BigInteger [value]
  (let [s (str value)]
    (when-not (re-matches #"0x[0-9a-fA-F]+" s)
      (refuse :wallet/rpc-error (str "RPCが不正なhex quantityを返しました: " s)))
    (BigInteger. (subs s 2) 16)))

(defonce ^:private address-locks (atom {}))

(defn- lock-for
  "One monitor per (lowercased) address — the in-process single writer."
  [address]
  (let [address (str/lower-case address)]
    (or (get @address-locks address)
        (get (swap! address-locks
                    (fn [m] (if (contains? m address) m (assoc m address (Object.)))))
             address))))

(defn- evm-chain-key
  "The wallet.chains key for an EIP-155 chain id — refusing an id the
  registry does not carry rather than signing for an unknown network."
  [chain-id]
  (or (some (fn [[k entry]]
              (when (and (= :evm (:family entry))
                         (nil? (:status entry))
                         (= (long chain-id) (long (:chain-id entry))))
                k))
            wchains/chains)
      (refuse :wallet/unsupported-chain
              (str "chain-id " chain-id " はwallet.chains registryに未登録です。"))))

(defn http-transport
  "JSON-RPC transport over HTTP for `endpoint`: request-map -> parsed
  response-map. Injected into sign-and-submit! so tests run against a stub
  and production supplies the configured endpoint."
  [endpoint]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 8))
                   .build)]
    (fn [request]
      (let [http-request (-> (HttpRequest/newBuilder (URI/create endpoint))
                             (.timeout (Duration/ofSeconds 20))
                             (.header "Content-Type" "application/json")
                             (.POST (HttpRequest$BodyPublishers/ofString
                                     (json/write-str request)))
                             .build)
            response (.send client http-request (HttpResponse$BodyHandlers/ofString))]
        (when-not (= 200 (.statusCode response))
          (refuse :wallet/rpc-error
                  (str "RPC endpointがHTTP " (.statusCode response) "を返しました。")))
        (json/read-str (.body response) :key-fn keyword)))))

(defn sign-and-submit!
  "Sign the :awaiting-wallet transfer `transfer-id` with `sgnr` (the org's
  kagi-backed wallet.signer/Signer) and broadcast it via `transport`.
  Refuses unless the transfer's assignment custody is :kagi — external
  custody keeps the browser-wallet flow. Returns the :submitted transfer.
  Nothing is persisted unless the broadcast succeeded AND the node's hash
  matched the locally computed one."
  [session transfer-id sgnr transport]
  (let [transfer (get-in (store/snapshot) [:wallet :transfers transfer-id])]
    (when-not (and transfer
                   (= (:user-id session) (:user-id transfer))
                   (= (:organization-id session) (:organization-id transfer)))
      (refuse :wallet/transfer-not-found "送金提案が見つかりません。"))
    (when-not (= :awaiting-wallet (:status transfer))
      (refuse :wallet/transfer-state "この送金提案は既に処理されています。"))
    (let [assignment (wallet/assignment (:bot-id transfer))]
      (when-not (and assignment (= (:link-id assignment) (:link-id transfer)))
        (refuse :wallet/assignment-not-found
                "この送金提案のWallet割り当てが見つかりません。"))
      (when-not (= :kagi (:custody assignment))
        (refuse :wallet/custody-mismatch
                "外部Wallet custodyの送金はブラウザWalletが署名・送信します。"))
      (let [link (get-in (store/snapshot)
                         [:wallet :links (:user-id assignment) (:link-id assignment)])]
        (when-not (and link (= :active (:status link)) (:derivation-path link))
          (refuse :wallet/link-inactive
                  "kagi署名リンクが無効か、derivation pathを持ちません。"))
        (let [chain-id (long (:chain-id transfer))
              chain (evm-chain-key chain-id)
              from (:from transfer)]
          (locking (lock-for from)
            (let [nonce (hex->bigint (rpc-call! transport "eth_getTransactionCount"
                                                [from "pending"]))
                  gas-price (hex->bigint (rpc-call! transport "eth_gasPrice" []))
                  tx {:nonce nonce :gas-price gas-price :gas 21000
                      :to (:to transfer)
                      :value (BigInteger. ^String (:value-wei transfer))
                      :data "0x" :chain-id chain-id}
                  raw (wchain/sign-tx-with sgnr {:chain chain
                                                 :path (:derivation-path link)} tx)
                  local-hash (eth/raw-tx-hash raw)
                  node-hash (rpc-call! transport "eth_sendRawTransaction" [raw])]
              (when-not (= (str/lower-case local-hash)
                           (str/lower-case (str node-hash)))
                (refuse :wallet/tx-hash-mismatch
                        (str "nodeの返したtx hashがローカル計算と一致しません: "
                             node-hash " ≠ " local-hash)))
              (let [submitted (assoc transfer
                                     :status :submitted
                                     :tx-hash local-hash
                                     :submitted-by (:user-id session)
                                     :submitted-at (store/now)
                                     :custody :kagi
                                     :signed-with (:derivation-path link)
                                     :nonce (str nonce)
                                     :gas-price (str gas-price))]
                (store/transact! assoc-in [:wallet :transfers transfer-id] submitted)
                submitted))))))))

) ;; end :clj
   :cljs
   (do
     (defn http-transport [& _]
       (throw (js/Error. "cloud.itonami.app.wallet-send: JVM-only (the app server is)")))
     (defn sign-and-submit! [& _]
       (throw (js/Error. "cloud.itonami.app.wallet-send: JVM-only (the app server is)")))))
