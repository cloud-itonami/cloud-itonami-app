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
(def storefront-schema "cloud.itonami.app.commerce.storefront.v1")
(def order-schema "cloud.itonami.app.commerce.order.v1")

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
     :publication (or (:publication record)
                      {:status "not-published"
                       :public-url nil
                       :note "開設準備の記録です。公開storefrontはまだ有効ではありません。"})}))

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

(defn- sku! [value]
  (let [value (some-> value str str/trim str/upper-case)]
    (when-not (and value (re-matches #"[A-Z0-9][A-Z0-9._-]{1,63}" value))
      (refuse :commerce/invalid-sku
              "SKUは2〜64文字の英数字、点、ハイフン、アンダースコアで指定してください。"))
    value))

(defn- price! [value]
  (try
    (let [price (bigdec (str value))]
      (when (or (not (pos? (.signum price))) (> (.scale price) 6))
        (refuse :commerce/invalid-price
                "USDC価格は0より大きく、小数6桁以内で指定してください。"))
      (.toPlainString (.stripTrailingZeros price)))
    (catch NumberFormatException _
      (refuse :commerce/invalid-price "USDC価格を数値で指定してください。"))))

(defn- inventory! [value]
  (let [quantity (if (integer? value) value
                     (try (Long/parseLong (str value))
                          (catch NumberFormatException _ -1)))]
    (when (or (neg? quantity) (> quantity 1000000000))
      (refuse :commerce/invalid-inventory "在庫数は0以上の整数で指定してください。"))
    quantity))

(defn upsert-product!
  [session input]
  (let [record (or (stored session)
                   (refuse :commerce/store-required
                           "先に事業者情報を設定してください。"))
        sku (sku! (present input :sku))
        now (store/now)
        product {:sku sku
                 :name (text! (present input :name) "商品名" 160)
                 :description (text! (present input :description) "商品説明" 1000)
                 :price-usdc (price! (present input :price-usdc :price_usdc))
                 :inventory (inventory! (present input :inventory))
                 :status :active
                 :updated-at now
                 :created-at (or (get-in record [:products sku :created-at]) now)}]
    (store/transact! update-in (store-path session)
                     (fn [current]
                       (-> current
                           (assoc-in [:products sku] product)
                           (assoc :updated-at now))))
    (overview session)))

(defn- slug! [value]
  (let [value (some-> value str str/trim str/lower-case)]
    (when-not (and value (re-matches #"[a-z0-9][a-z0-9-]{2,62}" value))
      (refuse :commerce/invalid-store-slug
              "store slugは3〜63文字の小文字英数字とハイフンで指定してください。"))
    value))

(defn- active-products [record]
  (->> (:products record)
       vals
       (filter #(= :active (:status %)))
       (sort-by :sku)
       vec))

(defn publish-storefront!
  [session input]
  (let [record (or (stored session)
                   (refuse :commerce/store-required
                           "先に事業者情報を設定してください。"))
        state (readiness record)
        slug (slug! (present input :slug))
        duplicate (some (fn [[tenant candidate]]
                          (when (and (not= tenant (:organization-id session))
                                     (= slug (get-in candidate [:publication :slug])))
                            tenant))
                        (get-in (store/snapshot) [:commerce :stores]))]
    (when-not (:ready? state)
      (refuse :commerce/not-ready "開設設定を完了してからstorefrontを公開してください。"))
    (when (empty? (active-products record))
      (refuse :commerce/product-required "公開する商品を1件以上登録してください。"))
    (when duplicate
      (refuse :commerce/store-slug-taken "このstore slugは既に使われています。"))
    (let [publication {:status "published"
                       :slug slug
                       :public-url (str "/?store=" slug "#/storefront")
                       :published-at (store/now)
                       :note "このCloud Itonami deploymentの公開storefrontです。"}]
      (store/transact! update-in (store-path session)
                       assoc :publication publication :updated-at (store/now))
      (overview session))))

(defn- safe-product [product]
  (select-keys product [:sku :name :description :price-usdc :inventory]))

(defn- public-record [record]
  (when (= "published" (get-in record [:publication :status]))
    {:schema storefront-schema
     :slug (get-in record [:publication :slug])
     :store {:display-name (:display-name record)
             :merchant-did (:merchant-did record)}
     :products (mapv safe-product (active-products record))
     :payment (select-keys (:payment record)
                           [:protocol :version :facilitator :network :chain-id :asset :pay-to])
     :shipping {:carrier (get-in record [:shipping :carrier])
                :effect-boundary "checkout-plan-only"}
     :checkout {:status "available"
                :requires "Passkey session and external Wallet signature"}}))

(defn storefront
  "Public, deliberately redacted storefront by slug. Legal and delivery addresses stay private."
  [slug]
  (some (fn [[_ record]]
          (when (= (str/lower-case (str slug))
                   (get-in record [:publication :slug]))
            (public-record record)))
        (get-in (store/snapshot) [:commerce :stores])))

(defn current-storefront [session]
  (some-> (stored session) public-record))

(defn- quantity! [value]
  (let [quantity (if (integer? value) value
                     (try (Long/parseLong (str value))
                          (catch NumberFormatException _ -1)))]
    (when (or (not (pos? quantity)) (> quantity 1000))
      (refuse :commerce/invalid-quantity "数量は1〜1000の整数で指定してください。"))
    quantity))

(defn- money-add [left right]
  (.toPlainString (.stripTrailingZeros (.add (bigdec (str left)) (bigdec (str right))))))

(defn- line-total [price quantity]
  (.toPlainString
   (.stripTrailingZeros (.multiply (bigdec (str price)) (bigdec quantity)))))

(defn- atomic-usdc [amount]
  (str (.toBigIntegerExact (.movePointRight (bigdec amount) 6))))

(defn create-order!
  [session slug input]
  (let [storefront-record (some (fn [[tenant record]]
                                  (when (= (str/lower-case (str slug))
                                           (get-in record [:publication :slug]))
                                    [tenant record]))
                                (get-in (store/snapshot) [:commerce :stores]))
        [tenant record] (or storefront-record
                            (refuse :commerce/storefront-not-found
                                    "公開storefrontが見つかりません。"))
        buyer-did (or (identity/session-did session)
                      (refuse :commerce/buyer-did-required
                              "注文にはUser DIDが必要です。"))
        requested (present input :lines)
        _ (when-not (and (vector? requested) (seq requested) (<= (count requested) 100))
            (refuse :commerce/order-lines-required "商品を1〜100件指定してください。"))
        lines (mapv
               (fn [line]
                 (let [sku (sku! (:sku line))
                       quantity (quantity! (:quantity line))
                       product (or (get-in record [:products sku])
                                   (refuse :commerce/product-not-found
                                           (str sku "は販売されていません。")))]
                   (when (> quantity (:inventory product))
                     (refuse :commerce/insufficient-inventory
                             (str sku "の在庫が不足しています。")))
                   {:sku sku :name (:name product) :quantity quantity
                    :unit-price-usdc (:price-usdc product)
                    :line-total-usdc (line-total (:price-usdc product) quantity)}))
               requested)
        amount (reduce money-add "0" (map :line-total-usdc lines))
        order-id (str (java.util.UUID/randomUUID))
        now (store/now)
        order {:schema order-schema :id order-id :store-slug slug
               :merchant-did (:merchant-did record) :buyer-did buyer-did
               :buyer-user-id (:user-id session) :lines lines
               :delivery-address (address! (present input :delivery-address :delivery_address)
                                           "配送先")
               :amount-usdc amount :status :awaiting-wallet-signature
               :payment-request {:protocol "x402" :version 1 :scheme "exact"
                                 :network "base" :chain-id base-chain-id :asset "USDC"
                                 :amount amount :amount-atomic (atomic-usdc amount)
                                 :pay-to (get-in record [:payment :pay-to])
                                 :facilitator (get-in record [:payment :facilitator])
                                 :signature nil
                                 :note "外部Walletでの署名とsettlementはまだ行われていません。"}
               :fulfillment {:status :not-requested
                             :effect-boundary :plan-only}
               :created-at now :updated-at now}]
    (store/transact! assoc-in [:commerce :orders tenant order-id] order)
    (-> order
        (dissoc :buyer-user-id)
        (update :status name)
        (update-in [:fulfillment :status] name)
        (update-in [:fulfillment :effect-boundary] name))))

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
    :parameters {:type "object" :properties {}}}
   {:name "commerce_product_upsert"
    :description "Create or update one deterministic storefront product. (write)"
    :parameters {:type "object"
                 :properties {:sku {:type "string"} :name {:type "string"}
                              :description {:type "string"}
                              :price_usdc {:type "string"}
                              :inventory {:type "integer"}}
                 :required ["sku" "name" "description" "price_usdc" "inventory"]}}
   {:name "commerce_store_publish"
    :description (str "Publish the ready catalog on this Cloud Itonami deployment. "
                      "This does not claim an external DNS or separate deployment. (write)")
    :parameters {:type "object"
                 :properties {:slug {:type "string"}}
                 :required ["slug"]}}])

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
    "commerce_product_upsert"
    (str (or (:sku input) "商品") "の商品名・USDC価格・在庫を公開カタログ候補へ記録します。")
    "commerce_store_publish"
    (str (or (:slug input) "store") "として、このCloud Itonami上のstorefrontを公開します。")
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
      "commerce_product_upsert" (upsert-product! session input)
      "commerce_store_publish" (publish-storefront! session input)
      (refuse :commerce/unknown-tool "未知のCommerce toolです。"))))

(defn bot-summary [bot]
  (overview (bot-session bot)))
