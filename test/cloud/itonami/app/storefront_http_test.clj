(ns cloud.itonami.app.storefront-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.commerce :as commerce]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "storefront-csrf")
(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434"
                :reviewed? true :enabled? true}]})
(defonce ^:private client (HttpClient/newHttpClient))
(defonce ^:private current-session (atom nil))
(def ^:private address
  {:country "JP" :postal_code "100-0001" :region "東京都"
   :locality "千代田区" :line1 "千代田1-1"})

(defn- call [method path body with-csrf?]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:"
                                      (.getPort (.getAddress @server/server)) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin))
        builder (if with-csrf?
                  (.header builder "X-CLOUD-ITONAMI-CSRF" csrf)
                  builder)
        request (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- seed-store! []
  (reset! store/state
          (-> (store/initial-state)
              (assoc-in [:identity :users "alice" :did] "did:key:alice")
              (assoc-in [:identity :users "bob" :did] "did:key:bob")
              (assoc-in [:identity :organizations "org-1" :did] "did:webvh:merchant")))
  (commerce/configure-store!
   {:user-id "alice" :organization-id "org-1"}
   {:business_kind "corporation" :display_name "Alice Store"
    :legal_name "Alice株式会社" :legal_address address})
  (store/transact! assoc-in [:wallet :assignments "merchant-bot"]
                   {:bot-id "merchant-bot" :user-id "alice" :organization-id "org-1"
                    :link-id "base-wallet" :chain-id 8453
                    :address "0x0000000000000000000000000000000000000001"})
  (commerce/configure-x402! {:user-id "alice" :organization-id "org-1"}
                            "merchant-bot")
  (commerce/configure-shipping!
   {:user-id "alice" :organization-id "org-1"}
   {:ship_from address :return_address address :carrier "日本郵便"})
  (commerce/finalize! {:user-id "alice" :organization-id "org-1"})
  (commerce/upsert-product!
   {:user-id "alice" :organization-id "org-1"}
   {:sku "TEE-01" :name "営みTシャツ" :description "綿100%"
    :price_usdc "12.34" :inventory 4})
  (commerce/publish-storefront!
   {:user-id "alice" :organization-id "org-1"} {:slug "alice-store"}))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-storefront-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] @current-session)
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)]
        (seed-store!)
        (server/stop!)
        (server/start! config)
        (try (f) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(deftest published-catalog-is-readable-without-a-session
  (with-server
    (fn []
      (reset! current-session nil)
      (let [response (call :get "/api/storefront/alice-store" nil false)]
        (is (= 200 (:status response)))
        (is (= "営みTシャツ" (get-in response [:body :products 0 :name])))
        (is (nil? (get-in response [:body :store :legal-address])))
        (is (nil? (get-in response [:body :shipping :ship-from])))))))

(deftest checkout-requires-passkey-and-csrf-and-returns-reserved-x402-order
  (with-server
    (fn []
      (let [body {:lines [{:sku "TEE-01" :quantity 2 :price_usdc "0.01"}]
                  :delivery_address address}]
        (reset! current-session nil)
        (is (= 401 (:status (call :post "/api/storefront/alice-store/orders"
                                  body true))))
        (reset! current-session {:csrf csrf :user-id "bob" :organization-id "buyer-org"
                                 :kind :passkey :authn-level :phishing-resistant})
        (testing "a browser session without the CSRF header cannot create an order"
          (is (= 403 (:status (call :post "/api/storefront/alice-store/orders"
                                    body false)))))
        (let [response (call :post "/api/storefront/alice-store/orders" body true)]
          (is (= 201 (:status response)))
          (is (= "24.68" (get-in response [:body :amount-usdc])))
          (is (= "awaiting-wallet-signature" (get-in response [:body :status])))
          (is (nil? (get-in response [:body :payment-request :signature])))
          (is (= "transaction"
                 (get-in response [:body :payment-request :requirements :scheme])))
          (is (= "active" (get-in response [:body :reservation :status])))
          (is (= "not-requested" (get-in response [:body :fulfillment :status]))))))))

(deftest payment-route-verifies-onchain-before-capturing-inventory
  (with-server
    (fn []
      (reset! current-session {:csrf csrf :user-id "bob" :organization-id "buyer-org"
                               :kind :passkey :authn-level :phishing-resistant})
      (let [created (call :post "/api/storefront/alice-store/orders"
                          {:lines [{:sku "TEE-01" :quantity 2}]
                           :delivery_address address} true)
            order-id (get-in created [:body :id])
            path (str "/api/storefront/alice-store/orders/" order-id "/payment")
            proof {:transaction "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                   :payer "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]
        (testing "unverified proof is a 402 and leaves on-hand stock untouched"
          (with-redefs [commerce/*verify-payment!*
                        (fn [_ _ _] {:isValid false :invalidReason "tx-not-found"})]
            (is (= 402 (:status (call :post path proof true)))))
          (is (= 4 (get-in (store/snapshot)
                           [:commerce :stores "org-1" :products "TEE-01" :inventory]))))
        (testing "verified proof captures stock exactly once"
          (with-redefs [commerce/*verify-payment!*
                        (fn [_ _ _] {:isValid true :payer (:payer proof)})]
            (let [paid (call :post path proof true)]
              (is (= 200 (:status paid)))
              (is (= "paid" (get-in paid [:body :status])))
              (is (= "ready-to-pack" (get-in paid [:body :fulfillment :status])))
              (is (= 2 (get-in (store/snapshot)
                               [:commerce :stores "org-1" :products "TEE-01" :inventory])))
              (is (= 200 (:status (call :post path proof true))))
              (is (= 2 (get-in (store/snapshot)
                               [:commerce :stores "org-1" :products "TEE-01" :inventory]))))))
        (testing "only the buyer can read the private order"
          (is (= 200 (:status (call :get
                                    (str "/api/storefront/alice-store/orders/" order-id)
                                    nil false))))
          (reset! current-session {:csrf csrf :user-id "alice" :organization-id "org-1"
                                   :kind :passkey :authn-level :phishing-resistant})
          (is (= 403 (:status (call :get
                                    (str "/api/storefront/alice-store/orders/" order-id)
                                    nil false)))))))))
