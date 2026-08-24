(ns cloud.itonami.app.commerce
  "Tenant-scoped commerce setup driven from a Bot conversation.

  This namespace joins identity, a non-custodial Bot Wallet, x402 discovery,
  and fulfillment configuration into one durable aggregate.  It deliberately
  stops at `:ready`: a ready store has the information required for a public
  storefront, but no site has been deployed and no payment has been signed.
  Those are separate observed effects, not statuses this local record may
  invent."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet]))

(def schema "cloud.itonami.app.commerce.store.v1")
(def default-facilitator "https://x402.nexus")
(def default-fulfillment-endpoint
  "https://cloud-itonami-marketplace-fulfillment.04-feasts-minded.workers.dev")
(def base-chain-id 8453)

(defn- refuse [type message]
  (throw (ex-info message {:type type})))

(defn- present [m & keys]
  (some #(when (contains? m %) (get m %)) keys))

(defn- text!
  [value field maximum]
  (let [value (some-> value str str/trim)]
    (when (str/blank? value)
      (refuse :commerce/field-required (str field "は必須です。")))
    (when (> (count value) maximum)
      (refuse :commerce/field-too-long (str field "が長すぎます。")))
    value))

(defn- business-kind! [value]
  (case (some-> value name (str/replace "_" "-") keyword)
    :corporation :corporation
    :sole-proprietor :sole-proprietor
    (refuse :commerce/business-kind
            "business_kind は corporation または sole_proprietor です。")))

(defn- address!
  [value field]
  (when-not (map? value)
    (refuse :commerce/address-required (str field "は住所オブジェクトで指定してください。")))
  (let [read-field (fn [label & keys]
                     (text! (apply present value keys) (str field "の" label) 200))]
    {:country (str/upper-case (read-field "国" :country))
     :postal-code (read-field "郵便番号" :postal-code :postal_code)
     :region (read-field "都道府県・州" :region)
     :locality (read-field "市区町村" :locality)
     :line1 (read-field "町名番地" :line1 :address-line-1 :address_line_1)
     :line2 (some-> (present value :line2 :address-line-2 :address_line_2)
                    str str/trim not-empty)}))

(defn- bot-session [bot]
  {:user-id (or (:owner-id bot) (:bot/owner bot))
   :organization-id (or (:organization-id bot) (:bot/organization bot))})

(defn- store-path [session]
  [:commerce :stores (:organization-id session)])

(defn- stored [session]
  (get-in (store/snapshot) (store-path session)))

(defn- merchant-did! [session kind]
  (let [did (case kind
              :corporation (identity/session-organization-did session)
              :sole-proprietor (identity/session-did session))]
    (when (str/blank? (str did))
      (refuse (if (= :corporation kind)
                :commerce/organization-did-required
                :commerce/user-did-required)
              (if (= :corporation kind)
                "法人ショップには、先にOrganization DIDを設定してください。"
                "個人事業主ショップには、先にUser DIDを設定してください。")))
    did))

(defn- readiness [record]
  (let [checks [{:id :identity :ready? (boolean (:merchant-did record))
                 :label "事業者DID"}
                {:id :legal-profile
                 :ready? (every? #(not (str/blank? (str %)))
                                 [(:display-name record) (:legal-name record)])
                 :label "表示名・法的名称"}
                {:id :legal-address :ready? (boolean (:legal-address record))
                 :label "事業者住所"}
                {:id :x402 :ready? (= :configured (get-in record [:payment :status]))
                 :label "x402受取設定"}
                {:id :shipping :ready? (= :configured (get-in record [:shipping :status]))
                 :label "発送元・返品先"}]
        missing (mapv :id (remove :ready? checks))]
    {:ready? (empty? missing) :checks checks :missing missing}))

(defn overview
  "The tenant's commerce aggregate. Reading an empty tenant does not create it."
  [session]
  (let [record (stored session)
        state (readiness record)]
    {:schema schema
     :status (name (or (:status record) :not-configured))
     :store (when record
              (-> record
                  (dissoc :events)
                  (update :business-kind name)))
     :readiness (update state :checks
                        #(mapv (fn [check] (update check :id name)) %))
     :publication {:status "not-published"
                   :public-url nil
                   :note "開設準備の記録です。公開storefrontのdeployは別の検証済みeffectです。"}}))

(defn configure-store!
  [session input]
  (let [kind (business-kind! (present input :business-kind :business_kind))
        existing (stored session)
        _ (when (and existing (not= kind (:business-kind existing)))
            (refuse :commerce/identity-axis-immutable
                    "開設後に法人と個人事業主を切り替えることはできません。"))
        did (merchant-did! session kind)
        now (store/now)
        record (merge existing
                      {:schema schema
                       :tenant-id (:organization-id session)
                       :owner-id (:user-id session)
                       :business-kind kind
                       :merchant-did did
                       :display-name (text! (present input :display-name :display_name)
                                            "ショップ表示名" 120)
                       :legal-name (text! (present input :legal-name :legal_name)
                                          "法的名称" 200)
                       :legal-address (address! (present input :legal-address :legal_address)
                                                "事業者住所")
                       :status (or (:status existing) :draft)
                       :updated-at now
                       :created-at (or (:created-at existing) now)})]
    (store/transact! assoc-in (store-path session) record)
    (overview session)))

(defn configure-x402!
  [session bot-id]
  (when-not (stored session)
    (refuse :commerce/store-required "先に事業者情報を設定してください。"))
  (let [signer (or (wallet/assignment bot-id)
                   (refuse :commerce/wallet-required
                           "このBotの外部Wallet署名を先に接続してください。"))]
    (when-not (and (= (:user-id session) (:user-id signer))
                   (= (:organization-id session) (:organization-id signer)))
      (refuse :commerce/wallet-forbidden
              "別のTenantのWalletをショップへ接続できません。"))
    (when-not (= base-chain-id (:chain-id signer))
      (refuse :commerce/base-wallet-required
              "x402のUSDC受取にはBase（chain 8453）のWalletを接続してください。"))
    (store/transact!
     update-in (store-path session)
     assoc :payment {:status :configured
                     :protocol "x402" :version 1
                     :facilitator default-facilitator
                     :network "base" :chain-id base-chain-id
                     :asset "USDC" :pay-to (:address signer)
                     :custody :external-wallet
                     :signer-link-id (:link-id signer)
                     :configured-at (store/now)}
           :updated-at (store/now))
    (overview session)))

(defn configure-shipping!
  [session input]
  (when-not (stored session)
    (refuse :commerce/store-required "先に事業者情報を設定してください。"))
  (let [shipping {:status :configured
                  :ship-from (address! (present input :ship-from :ship_from) "発送元")
                  :return-address (address! (present input :return-address :return_address)
                                            "返品先")
                  :carrier (some-> (present input :carrier) str str/trim not-empty)
                  :fulfillment-endpoint default-fulfillment-endpoint
                  :effect-boundary :plan-only
                  :note "送り状購入・集荷依頼は別の承認済みeffectです。"
                  :configured-at (store/now)}]
    (store/transact! update-in (store-path session)
                     assoc :shipping shipping :updated-at (store/now))
    (overview session)))

(defn finalize!
  [session]
  (let [record (or (stored session)
                   (refuse :commerce/store-required
                           "先に事業者情報を設定してください。"))
        state (readiness record)]
    (when-not (:ready? state)
      (refuse :commerce/not-ready
              (str "不足している設定があります: "
                   (str/join ", " (map name (:missing state))))))
    (store/transact! update-in (store-path session)
                     assoc :status :ready :ready-at (store/now)
                     :updated-at (store/now))
    (overview session)))

(def tool-definitions
  [{:name "commerce_store_overview"
    :description (str "Read the active tenant's DID-bound shop setup, x402, shipping, "
                      "readiness, and honest publication state.")
    :parameters {:type "object" :properties {}}}
   {:name "commerce_store_configure"
    :description "Configure a corporation or sole proprietor shop and bind it to the correct DID. (write)"
    :parameters {:type "object"
                 :properties {:business_kind {:type "string" :enum ["corporation" "sole_proprietor"]}
                              :display_name {:type "string"}
                              :legal_name {:type "string"}
                              :legal_address {:type "object"}}
                 :required ["business_kind" "display_name" "legal_name" "legal_address"]}}
   {:name "commerce_payment_configure_x402"
    :description (str "Bind this Bot's verified external Base wallet as the shop's x402 USDC payTo. "
                      "No private key is stored and no payment is signed. (write)")
    :parameters {:type "object" :properties {}}}
   {:name "commerce_shipping_configure"
    :description (str "Configure ship-from and return addresses and bind the fulfillment planning actor. "
                      "This does not buy a label or request pickup. (write)")
    :parameters {:type "object"
                 :properties {:ship_from {:type "object"}
                              :return_address {:type "object"}
                              :carrier {:type "string"}}
                 :required ["ship_from" "return_address"]}}
   {:name "commerce_store_finalize"
    :description (str "Validate the joined commerce setup and mark it ready for publication. "
                      "This does not deploy or claim a public storefront. (write)")
    :parameters {:type "object" :properties {}}}])

(defn tool? [name]
  (str/starts-with? (str name) "commerce_"))

(defn write-tool? [name]
  (and (tool? name)
       (not= "commerce_store_overview" (str name))))

(defn describe [name input]
  (case (str name)
    "commerce_store_overview" "このTenantのショップ開設状況を読みます。"
    "commerce_store_configure"
    (str (or (:display_name input) (:display-name input))
         "を事業者DIDへ結び、法的名称と住所を記録します。")
    "commerce_payment_configure_x402"
    "このBotのBase Walletをx402/USDC受取先として記録します。秘密鍵や署名は扱いません。"
    "commerce_shipping_configure"
    "発送元・返品先とfulfillment計画actorを設定します。送り状購入や集荷は実行しません。"
    "commerce_store_finalize"
    "DID・法的表示・x402・発送設定を検査し、公開準備完了として記録します。公開は実行しません。"
    "Commerce設定を更新します。"))

(defn call-tool!
  [bot name input]
  (let [session (bot-session bot)]
    (case (str name)
      "commerce_store_overview" (overview session)
      "commerce_store_configure" (configure-store! session input)
      "commerce_payment_configure_x402" (configure-x402! session (:bot/id bot))
      "commerce_shipping_configure" (configure-shipping! session input)
      "commerce_store_finalize" (finalize! session)
      (refuse :commerce/unknown-tool "未知のCommerce toolです。"))))

(defn bot-summary [bot]
  (overview (bot-session bot)))
