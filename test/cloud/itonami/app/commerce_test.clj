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
