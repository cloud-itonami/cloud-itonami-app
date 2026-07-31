(ns cloud.itonami.app.bitcoin-consensus-sync-test
  (:require [bitcoin.node.disk-consensus :as disk-consensus]
            [bitcoin.node.peer :as peer]
            [bitcoin.node.peer-pool :as peer-pool]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bitcoin-consensus-sync :as sync]
            [cloud.itonami.app.bitcoin-node :as bitcoin-node]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(defn- temporary-path []
  (str (.resolve
        (java.nio.file.Files/createTempDirectory
         "cloud-itonami-consensus-sync"
         (make-array java.nio.file.attribute.FileAttribute 0))
        "consensus.sqlite")))

(deftest enabled-sync-requires-durable-history-and-a-peer-source
  (testing "disabled defaults do not allocate or discover anything"
    (let [options (sync/normalize-options
                   {:network :mainnet :path nil :peer-sync {}})]
      (is (false? (:enabled? options)))
      (is (false? (sync/configured? options)))))
  (testing "enabled sync cannot restart with memory-only peer selection"
    (let [error
          (try
            (sync/normalize-options
             {:network :mainnet
              :peer-sync {:enabled? true :dns-discovery? true}})
            nil
            (catch clojure.lang.ExceptionInfo value value))]
      (is (= :bitcoin.node/sync-configuration
             (:type (ex-data error))))
      (is (= :pool-path (:field (ex-data error))))))
  (testing "enabled sync needs either discovery or an explicit anchor"
    (let [error
          (try
            (sync/normalize-options
             {:network :mainnet :path (temporary-path)
              :peer-sync {:enabled? true}})
            nil
            (catch clojure.lang.ExceptionInfo value value))]
      (is (= :peers (:field (ex-data error))))))
  (testing "operator peers are network-bound and require full blocks"
    (let [options
          (sync/normalize-options
           {:network :mainnet :path (temporary-path)
            :peer-sync
            {:enabled? true
             :peers [{:host "203.0.113.20" :port 8333}]}})
          peer (first (:operator-peers options))]
      (is (= :mainnet (:network peer)))
      (is (= :operator (:source peer)))
      (is (true? (:anchor? peer)))
      (is (= peer/node-network-service (:required-services peer)))
      (is (sync/configured? options))))
  (testing "transport bounds fail during preflight, not the first network call"
    (doseq [[request field]
            [[{:enabled? true
               :peers [{:host "node.example" :port 70000}]}
              :port]
             [{:enabled? true :required-successes 2
               :peers [{:host "node.example"}]}
              :required-successes]
             [{:enabled? true
               :peers [{:host "node.example" :required-services -1}]}
              :required-services]]]
      (let [error
            (try
              (sync/normalize-options
               {:network :mainnet :path (temporary-path)
                :peer-sync request})
              nil
              (catch clojure.lang.ExceptionInfo value value))]
        (is (= :bitcoin.node/sync-configuration
               (:type (ex-data error))))
        (is (= field (:field (ex-data error))))))))

(deftest one-cycle-validates-headers-before-requesting-blocks
  (let [path (str (temporary-path) ".peers.edn")
        options
        (sync/normalize-options
         {:network :mainnet :path (temporary-path)
          :peer-sync
          {:enabled? true :pool-path path
           :peers [{:host "203.0.113.20" :port 8333}]
           :maximum-peers 1 :max-header-batches 4
           :max-blocks-per-cycle 2}})
        pool (atom (peer-pool/create (:operator-peers options)))
        order (atom [])
        saved (atom 0)]
    (with-redefs
     [disk-consensus/sync-headers-managed!
      (fn [_ _ _ passed]
        (swap! order conj :headers)
        (is (= 4 (:max-batches passed)))
        {:status :synced :accepted 2})
      disk-consensus/pending-best-chain-blocks
      (fn [_ _] (swap! order conj :pending) [])
      disk-consensus/consensus-status
      (fn [_] {:height 2 :best-header-height 2 :fully-validated? true})
      peer-pool/save!
      (fn [actual-path _]
        (is (= path actual-path))
        (swap! saved inc))]
      (let [result (sync/sync-cycle! ::node pool options)]
        (is (= :synced (:status result)))
        (is (= 0 (get-in result [:blocks :downloaded])))
        (is (= [:headers :pending] @order))
        (is (= 1 @saved))))))

(deftest block-download-delegates-to-managed-multi-peer-validation
  (let [path (str (temporary-path) ".peers.edn")
        options
        (sync/normalize-options
        {:network :mainnet :path (temporary-path)
          :peer-sync
          {:enabled? true :pool-path path :maximum-peers 2
           :peers [{:host "203.0.113.20" :port 8333}
                   {:host "198.51.100.42" :port 8333}]}})
        pool (atom (peer-pool/create (:operator-peers options)))
        validation-calls (atom 0)]
    (with-redefs
     [disk-consensus/sync-headers-managed!
      (fn [_ _ _ _] {:status :synced :accepted 1})
      disk-consensus/pending-best-chain-blocks
      (fn [_ _] [[1 2 3]])
      disk-consensus/sync-blocks-managed!
      (fn [node actual-pool _ passed]
        (is (= ::node node))
        (is (= pool actual-pool))
        (is (= 32 (:max-blocks passed)))
        (is (= 2 (:maximum-peers passed)))
        (is (= 2 (:parallel-peers passed)))
        (is (= 16 (:per-peer-limit passed)))
        (is (= 30000 (:batch-timeout-ms passed)))
        (is (= path (:pool-path passed)))
        (is (integer? (:now-ms passed)))
        (swap! validation-calls inc)
        {:status :synced :downloaded 1 :more? false
         :observations
         [{:peer {:host "198.51.100.42"} :downloaded 1}]
         :failures
         [{:peer {:host "203.0.113.20"}
           :type :bitcoin.node/peer-timeout}]})
      disk-consensus/consensus-status
      (fn [_] {:height 1 :best-header-height 1 :fully-validated? true})
      peer-pool/save! (fn [_ _] nil)]
      (let [result (sync/sync-cycle! ::node pool options)]
        (is (= :synced (:status result)))
        (is (= 1 @validation-calls)
            "Only disk-consensus may publish a downloaded block")
        (is (= 1 (count (get-in result [:blocks :observations]))))
        (is (= :bitcoin.node/peer-timeout
               (get-in result [:blocks :failures 0 :type])))))))

(deftest sync-status-never-pretends-disabled-is-running
  (sync/clear-caches!)
  (let [options
        (sync/normalize-options
         {:network :mainnet :path (temporary-path)
          :peer-sync {:enabled? false}})]
    (is (= {:configured? false
            :enabled? false
            :network :mainnet
            :interval-seconds 300
            :status :stopped
            :running? false
            :cycles 0}
           (sync/status options)))))

(deftest failed-sync-retains-rejected-block-provider-evidence
  (sync/clear-caches!)
  (let [options
        (sync/normalize-options
         {:network :regtest :path (temporary-path)
          :peer-sync
          {:enabled? true :pool-path (str (temporary-path) ".peers")
           :peers [{:host "invalid-body"}]}})
        failure-data
        {:type :bitcoin.consensus/bad-coinbase-amount
         :block-validation-result :invalid
         :invalid-block-hash "0000bad"
         :consensus-invalid? true
         :source-peer {:host "invalid-body" :port 18444 :network :regtest}
         :peer-feedback :bitcoin.node/peer-invalid-block}]
    (with-redefs
     [sync/sync-cycle!
      (fn [& _]
        (throw (ex-info "rejected provider body" failure-data)))]
      (let [error
            (try
              (sync/run-once! ::node options)
              (catch clojure.lang.ExceptionInfo value value))
            retained (:last-error (sync/status options))]
        (is (= :bitcoin.consensus/bad-coinbase-amount
               (:type (ex-data error))))
        (is (= (select-keys
                failure-data
                [:type :block-validation-result :invalid-block-hash
                 :consensus-invalid? :source-peer :peer-feedback])
               (dissoc retained :message)))
        (is (= "rejected provider body" (:message retained)))))))

(def ^:private http-origin "http://localhost:1338")
(def ^:private csrf "bitcoin-sync-csrf")
(defonce ^:private http-client (HttpClient/newHttpClient))

(defn- bound-port []
  (.getPort (.getAddress @server/server)))

(defn- post-sync []
  (let [request
        (-> (HttpRequest/newBuilder
             (URI/create
              (str "http://127.0.0.1:" (bound-port)
                   "/api/bitcoin/consensus/sync")))
            (.header "Content-Type" "application/json")
            (.header "Origin" http-origin)
            (.header "X-CLOUD-ITONAMI-CSRF" csrf)
            (.POST (HttpRequest$BodyPublishers/ofString "{}"))
            .build)
        response
        (.send http-client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(deftest manual-consensus-sync-is-owner-admin-control-plane
  (let [configuration
        {:brand {:name "Test"}
         :server {:host "127.0.0.1" :port 0
                  :public-origin http-origin
                  :webauthn-rp-id "localhost"}
         :privacy {:bind-loopback-only? true}
         :bitcoin {:embedded-consensus
                   {:path nil :network :mainnet
                    :peer-sync {:enabled? false}}}}
        role (atom :member)
        calls (atom 0)]
    (with-redefs
     [identity/session (fn [_] {:csrf csrf :user-id "user-1"
                                :membership-id "membership-1"})
      identity/require-passkey! identity
      identity/configure! (fn [_] nil)
      identity/membership-role (fn [_] @role)
      bitcoin-node/sync-consensus!
      (fn [_]
        (swap! calls inc)
        {:status :synced :blocks {:downloaded 1}})]
      (server/stop!)
      (server/start! configuration)
      (try
        (let [denied (post-sync)]
          (is (= 403 (:status denied)))
          (is (= "sync-operator-required"
                 (get-in denied [:body :error :type])))
          (is (zero? @calls)))
        (reset! role :admin)
        (let [accepted (post-sync)]
          (is (= 200 (:status accepted)))
          (is (= "synced" (get-in accepted [:body :status])))
          (is (= 1 @calls)))
        (finally
          (server/stop!))))))
