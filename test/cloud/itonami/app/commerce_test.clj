(ns cloud.itonami.app.commerce-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.commerce :as commerce]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]))

(def alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def bob {:user-id "bob" :organization-id "org-2" :kind :passkey})
(def bot {:bot/id "bot-commerce" :bot/owner "alice" :bot/organization "org-1"})
(def address {:country "JP" :postal_code "100-0001" :region "東京都"
              :locality "千代田区" :line1 "千代田1-1"})

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- with-commerce-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-commerce-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state
              (-> (store/initial-state)
                  (assoc-in [:identity :users "alice" :did] "did:key:alice")
                  (assoc-in [:identity :users "bob" :did] "did:key:bob")
                  (assoc-in [:identity :organizations "org-1" :did]
                            "did:webvh:shop-org-1")
                  (assoc-in [:identity :organizations "org-2" :did]
                            "did:webvh:shop-org-2")))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))] (f))
      (finally (reset! store/state previous)))))

(defn- ready-store! []
  (commerce/configure-store!
   alice {:business_kind "corporation" :display_name "Alice Store"
          :legal_name "Alice株式会社" :legal_address address})
  (store/transact! assoc-in [:wallet :assignments "bot-commerce"]
                   {:bot-id "bot-commerce" :bot-did "did:key:bot"
                    :user-id "alice" :organization-id "org-1"
                    :link-id "link-base" :chain-id 8453
                    :address "0x0000000000000000000000000000000000000001"})
  (commerce/configure-x402! alice "bot-commerce")
  (commerce/configure-shipping!
   alice {:ship_from address :return_address address :carrier "日本郵便"})
  (commerce/finalize! alice))

(deftest merchant-kind-selects-one-immutable-did-axis
  (with-commerce-store
    (fn []
      (let [corporation (commerce/configure-store!
                         alice {:business_kind "corporation"
                                :display_name "Alice Store"
                                :legal_name "Alice株式会社"
                                :legal_address address})]
        (is (= "did:webvh:shop-org-1" (get-in corporation [:store :merchant-did])))
        (is (= "corporation" (get-in corporation [:store :business-kind])))
        (is (= :commerce/identity-axis-immutable
               (refuses #(commerce/configure-store!
                           alice {:business_kind "sole_proprietor"
                                  :display_name "Alice Store"
                                  :legal_name "山田アリス"
                                  :legal_address address})))))
      (let [personal (commerce/configure-store!
                      bob {:business_kind "sole_proprietor"
                           :display_name "Bob Works"
                           :legal_name "Bob"
                           :legal_address address})]
        (is (= "did:key:bob" (get-in personal [:store :merchant-did])))))))

(deftest x402-and-shipping-join-without-claiming-external-effects
  (with-commerce-store
    (fn []
      (commerce/configure-store!
       alice {:business_kind "corporation" :display_name "Alice Store"
              :legal_name "Alice株式会社" :legal_address address})
      (testing "a signer must be an assigned Base wallet"
        (is (= :commerce/wallet-required
               (refuses #(commerce/configure-x402! alice "bot-commerce"))))
        (store/transact! assoc-in [:wallet :assignments "bot-commerce"]
                         {:bot-id "bot-commerce" :bot-did "did:key:bot"
                          :user-id "alice" :organization-id "org-1"
                          :link-id "link-base" :chain-id 8453
                          :address "0x0000000000000000000000000000000000000001"})
        (let [configured (commerce/configure-x402! alice "bot-commerce")]
          (is (= "x402" (get-in configured [:store :payment :protocol])))
          (is (= "USDC" (get-in configured [:store :payment :asset])))
          (is (= :external-wallet (get-in configured [:store :payment :custody])))
          (is (nil? (get-in configured [:store :payment :private-key])))))
      (commerce/configure-shipping!
       alice {:ship_from address :return_address address :carrier "日本郵便"})
      (let [ready (commerce/finalize! alice)]
        (is (= "ready" (:status ready)))
        (is (true? (get-in ready [:readiness :ready?])))
        (is (= "not-published" (get-in ready [:publication :status])))
        (is (nil? (get-in ready [:publication :public-url])))
        (is (= :plan-only (get-in ready [:store :shipping :effect-boundary])))))))

(deftest bot-tools-stay-tenant-scoped
  (with-commerce-store
    (fn []
      (commerce/call-tool!
       bot "commerce_store_configure"
       {:business_kind "corporation" :display_name "Alice Store"
        :legal_name "Alice株式会社" :legal_address address})
      (is (= "Alice Store"
             (get-in (commerce/overview alice) [:store :display-name])))
      (is (= "not-configured" (:status (commerce/overview bob))))
      (is (= "Alice Store"
             (get-in (commerce/call-tool! bot "commerce_store_overview" {})
                     [:store :display-name]))))))

(deftest published-storefront-is-public-but-private-addresses-are-not
  (with-commerce-store
    (fn []
      (ready-store!)
      (commerce/upsert-product!
       alice {:sku "TEE-01" :name "営みTシャツ" :description "綿100%"
              :price_usdc "12.340000" :inventory 4})
      (commerce/publish-storefront! alice {:slug "alice-store"})
      (let [public (commerce/storefront "alice-store")]
        (is (= "cloud.itonami.app.commerce.storefront.v1" (:schema public)))
        (is (= "12.34" (get-in public [:products 0 :price-usdc])))
        (is (= "did:webvh:shop-org-1" (get-in public [:store :merchant-did])))
        (is (= "0x0000000000000000000000000000000000000001"
               (get-in public [:payment :pay-to])))
        (is (nil? (get-in public [:store :legal-address])))
        (is (nil? (get-in public [:shipping :ship-from])))
        (is (= :commerce/store-slug-taken
               (do
                 (commerce/configure-store!
                  bob {:business_kind "sole_proprietor" :display_name "Bob Works"
                       :legal_name "Bob" :legal_address address})
                 (store/transact! assoc-in [:wallet :assignments "bot-bob"]
                                  {:bot-id "bot-bob" :bot-did "did:key:bot-bob"
                                   :user-id "bob" :organization-id "org-2"
                                   :link-id "link-bob" :chain-id 8453
                                   :address "0x0000000000000000000000000000000000000002"})
                 (commerce/configure-x402! bob "bot-bob")
                 (commerce/configure-shipping!
                  bob {:ship_from address :return_address address})
                 (commerce/finalize! bob)
                 (commerce/upsert-product!
                  bob {:sku "BOB-1" :name "Bob item" :description "item"
                       :price_usdc "1" :inventory 1})
                 (refuses #(commerce/publish-storefront! bob {:slug "alice-store"})))))))))

(deftest order-recomputes-price-and-stops-before-wallet-signature
  (with-commerce-store
    (fn []
      (ready-store!)
      (commerce/upsert-product!
       alice {:sku "TEE-01" :name "営みTシャツ" :description "綿100%"
              :price_usdc "12.34" :inventory 4})
      (commerce/publish-storefront! alice {:slug "alice-store"})
      (let [order (commerce/create-order!
                   bob "alice-store"
                   {:lines [{:sku "TEE-01" :quantity 2 :price_usdc "0.01"}]
                    :delivery_address address})]
        (is (= "24.68" (:amount-usdc order))
            "client-supplied prices are ignored")
        (is (= "24680000" (get-in order [:payment-request :amount-atomic])))
        (is (= "did:key:bob" (:buyer-did order)))
        (is (= "awaiting-wallet-signature" (:status order)))
        (is (nil? (get-in order [:payment-request :signature])))
        (is (= "not-requested" (get-in order [:fulfillment :status])))
        (is (= 4 (get-in (commerce/storefront "alice-store")
                         [:products 0 :inventory]))
            "inventory is not decremented before settlement")))))
