(ns cloud.itonami.app.commerce
  "Tenant-scoped commerce setup driven from a Bot conversation.

  This namespace joins identity, a non-custodial Bot Wallet, x402 discovery,
  fulfillment configuration, public ordering, payment proof, inventory, and a
  one-way shipping state machine into durable aggregates. Wallet signing stays
  an explicit buyer action, and inventory is captured only after an on-chain
  payment verifier accepts the proof.

  ## Two fulfillment kinds

  A store declares `:fulfillment-kind` once, and it decides which of two
  mutually exclusive halves of this namespace applies:

    :physical  ship-from and return addresses are required, payment leaves the
               order at :ready-to-pack for a human, and the shipment state
               machine (packed -> label-ready -> shipped -> delivered) runs.
    :digital   there is no shipment. A delivery method is required instead of
               addresses, each product carries the reference the buyer receives,
               and a verified payment delivers the order in the same
               transaction -- :delivered, effect boundary :none.

  Digital exists because the readiness gate used to demand a shipping origin
  from every store, including one selling per-query API access that will never
  post a parcel. The answer to `what is this address for` was, for those
  stores, nothing: `:legal-address` is stored and deliberately never published
  (see `storefront`), and `:ship-from` is read in exactly one place, when a
  physical shipment is prepared. Requiring it anyway taught operators to type
  something -- and a shipping origin nobody ships from is worse than no field,
  because it reads afterwards like a fact somebody checked.

  `:legal-address` stays required for BOTH kinds. It is the merchant's own
  registered address, not a shipping fact, and a store with no address behind
  it is not a merchant. It is the shipping half, and only that half, that
  digital removes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def schema "cloud.itonami.app.commerce.store.v1")
(def default-facilitator "https://x402.nexus")
(def default-fulfillment-endpoint
  "https://cloud-itonami-marketplace-fulfillment.04-feasts-minded.workers.dev")
(def base-chain-id 8453)
(def storefront-schema "cloud.itonami.app.commerce.storefront.v1")
(def order-schema "cloud.itonami.app.commerce.order.v1")
(def usdc-base "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913")
(def payment-network "base")
(def payment-scheme "transaction")
(def reservation-seconds (* 30 60))

(declare refuse)

(defonce ^:private facilitator-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 8))
      .build))

(defn- verify-payment-http!
  [facilitator payment requirements]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str (str/replace facilitator #"/$" "") "/verify")))
                    (.timeout (Duration/ofSeconds 15))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str {:payment payment
                                             :requirements requirements})))
                    .build)
        response (.send facilitator-client request
                        (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (refuse :commerce/payment-verifier-unavailable
              "x402決済確認サービスが応答しませんでした。送金hashを保持して再試行してください。"))
    (json/read-str (.body response) :key-fn keyword)))

(def ^:dynamic *verify-payment!* verify-payment-http!)
(def ^:dynamic *instant* #(Instant/now))

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

(defn- fulfillment-kind!
  "How buyers receive what they bought. Absent means :physical -- every store
   written before this key existed shipped things, and a default that silently
   dropped their shipping gate would be a downgrade nobody asked for."
  [value]
  (if (nil? value)
    :physical
    (case (some-> value name (str/replace "_" "-") str/lower-case keyword)
      :digital :digital
      :physical :physical
      (refuse :commerce/fulfillment-kind
              "fulfillment_kind は digital または physical です。"))))

(defn- digital? [record]
  (= :digital (:fulfillment-kind record :physical)))

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
                ;; The fifth check is whichever one this store can actually
                ;; answer. A digital store is not "missing shipping" -- shipping
                ;; is not a question it has. It is asked the question it does
                ;; have instead, so the count of gates stays five and a kind is
                ;; never a way to be ready with less.
                (if (digital? record)
                  {:id :delivery
                   :ready? (= :configured (get-in record [:delivery :status]))
                   :label "デジタル納品設定"}
                  {:id :shipping
                   :ready? (= :configured (get-in record [:shipping :status]))
                   :label "発送元・返品先"})]
        missing (mapv :id (remove :ready? checks))]
    {:ready? (empty? missing) :checks checks :missing missing}))

(defn overview
  "The tenant's commerce aggregate. Reading an empty tenant does not create it."
  [session]
  (let [record (stored session)
        state (readiness record)]
    {:schema schema
     :status (name (or (:status record) :not-configured))
     :fulfillment-kind (when record (name (:fulfillment-kind record :physical)))
     :store (when record
              (-> record
                  (dissoc :events)
                  (update :business-kind name)
                  (update :fulfillment-kind #(name (or % :physical)))))
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
        ;; Given wins; otherwise keep what the store already is; a brand new
        ;; store with nothing said is physical, as every store was before.
        fulfillment (let [given (present input :fulfillment-kind :fulfillment_kind)]
                      (cond
                        (some? given) (fulfillment-kind! given)
                        existing (:fulfillment-kind existing :physical)
                        :else :physical))
        ;; Changing the kind changes which fields a product must carry, so it
        ;; may only move while no product has been written under the old one.
        _ (when (and existing
                     (not= fulfillment (:fulfillment-kind existing :physical))
                     (seq (:products existing)))
            (refuse :commerce/fulfillment-axis-immutable
                    "商品を登録した後に digital と physical を切り替えることはできません。"))
        did (merchant-did! session kind)
        now (store/now)
        record (merge existing
                      {:schema schema
                       :tenant-id (:organization-id session)
                       :owner-id (:user-id session)
                       :business-kind kind
                       :fulfillment-kind fulfillment
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
  (let [record (or (stored session)
                   (refuse :commerce/store-required "先に事業者情報を設定してください。"))]
    (when (digital? record)
      (refuse :commerce/digital-store
              "デジタル納品のショップに発送元・返品先は設定できません。commerce_delivery_configure を使ってください。")))
  (let [shipping {:status :configured
                  :ship-from (address! (present input :ship-from :ship_from) "発送元")
                  :return-address (address! (present input :return-address :return_address)
                                            "返品先")
                  :carrier (some-> (present input :carrier) str str/trim not-empty)
                  :fulfillment-endpoint default-fulfillment-endpoint
                  :fulfillment-integration
                  {:status :not-connected
                   :reason :marketplace-order-reference-required}
                  :effect-boundary :plan-only
                  :note (str "荷姿と配送参照はChatで管理できます。送り状購入・集荷依頼は"
                             "運送会社との接続後にだけ実行できます。")
                  :configured-at (store/now)}]
    (store/transact! update-in (store-path session)
                     assoc :shipping shipping :updated-at (store/now))
    (overview session)))

(defn- delivery-method! [value]
  (case (some-> value name (str/replace "_" "-") str/lower-case keyword)
    :download-url :download-url
    :license-key :license-key
    :api-credential :api-credential
    :content-address :content-address
    (refuse :commerce/delivery-method
            (str "delivery_method は download_url, license_key, api_credential, "
                 "content_address のいずれかです。"))))

(defn configure-delivery!
  "The digital twin of `configure-shipping!`. Records HOW a buyer receives what
   they bought; WHAT they receive is per product (`:delivery-ref`).

   No addresses. That is the whole point of the split -- see the namespace
   docstring."
  [session input]
  (let [record (or (stored session)
                   (refuse :commerce/store-required "先に事業者情報を設定してください。"))]
    (when-not (digital? record)
      (refuse :commerce/physical-store
              "発送を伴うショップにデジタル納品は設定できません。commerce_shipping_configure を使ってください。"))
    (let [delivery {:status :configured
                    :method (delivery-method! (present input :delivery-method :delivery_method))
                    :instructions (text! (present input :instructions) "納品案内" 1000)
                    :support-contact (some-> (present input :support-contact :support_contact)
                                             str str/trim not-empty)
                    :effect-boundary :released-on-payment
                    :note (str "支払いが検証された時点で、注文に商品の納品参照が付与されます。"
                               "配送は発生しません。")
                    :configured-at (store/now)}]
      (store/transact! update-in (store-path session)
                       assoc :delivery delivery :updated-at (store/now))
      (overview session))))

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
        given-ref (present input :delivery-ref :delivery_ref)
        _ (when (and (not (digital? record)) (some? given-ref))
            (refuse :commerce/physical-store
                    "発送を伴う商品に delivery_ref は設定できません。"))
        now (store/now)
        product (cond-> {:sku sku
                         :name (text! (present input :name) "商品名" 160)
                         :description (text! (present input :description) "商品説明" 1000)
                         :price-usdc (price! (present input :price-usdc :price_usdc))
                         :inventory (inventory! (present input :inventory))
                         :status :active
                         :updated-at now
                         :created-at (or (get-in record [:products sku :created-at]) now)}
                  ;; Required, not optional. A digital product with nothing to
                  ;; hand over is a paid order that delivers silence, and the
                  ;; order would still say :delivered.
                  (digital? record)
                  (assoc :delivery-ref (text! given-ref "納品参照" 2000)))]
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

(defn- reservation-active?
  [order now]
  (and (contains? #{:awaiting-wallet-signature :payment-verification-pending}
                  (:status order))
       (= :active (get-in order [:reservation :status]))
       (try (.isAfter (Instant/parse (get-in order [:reservation :expires-at])) now)
            (catch Exception _ false))))

(defn- reserved-quantity
  [state tenant sku now]
  (reduce + 0
          (for [order (vals (get-in state [:commerce :orders tenant]))
                :when (reservation-active? order now)
                line (:lines order)
                :when (= sku (:sku line))]
            (:quantity line))))

(defn- safe-product [state tenant product now]
  (assoc (select-keys product [:sku :name :description :price-usdc])
         :inventory (max 0 (- (:inventory product)
                              (reserved-quantity state tenant (:sku product) now)))))

(defn- public-record [state tenant record]
  (when (= "published" (get-in record [:publication :status]))
    {:schema storefront-schema
     :slug (get-in record [:publication :slug])
     :store {:display-name (:display-name record)
             :merchant-did (:merchant-did record)}
     :products (mapv #(safe-product state tenant % (*instant*))
                     (active-products record))
     :payment (select-keys (:payment record)
                           [:protocol :version :facilitator :network :chain-id :asset :pay-to])
     ;; `safe-product` is an allowlist, so `:delivery-ref` -- the thing the
     ;; buyer is paying for -- cannot reach here. Only the method does.
     :fulfillment-kind (name (:fulfillment-kind record :physical))
     :delivery (when (digital? record)
                 {:method (some-> (get-in record [:delivery :method]) name)
                  :effect-boundary "released-on-payment"})
     :shipping (when-not (digital? record)
                 {:carrier (get-in record [:shipping :carrier])
                  :effect-boundary "checkout-plan-only"})
     :checkout {:status "available"
                :requires "Passkey session and external Wallet signature"}}))

(defn storefront
  "Public, deliberately redacted storefront by slug. Legal and delivery addresses stay private."
  [slug]
  (let [state (store/snapshot)]
    (some (fn [[tenant record]]
            (when (= (str/lower-case (str slug))
                     (get-in record [:publication :slug]))
              (public-record state tenant record)))
          (get-in state [:commerce :stores]))))

(defn current-storefront [session]
  (let [state (store/snapshot)
        tenant (:organization-id session)]
    (some->> (get-in state [:commerce :stores tenant])
             (public-record state tenant))))

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

(defn- payment-requirements
  [record slug order-id amount]
  {:scheme payment-scheme
   :network payment-network
   :maxAmountRequired (atomic-usdc amount)
   :resource (str "/api/storefront/" slug "/orders/" order-id "/payment")
   :description (str "Cloud Itonami order " order-id)
   :mimeType "application/json"
   :payTo (get-in record [:payment :pay-to])
   :maxTimeoutSeconds reservation-seconds
   :asset usdc-base
   :extra {:name "USD Coin" :version "2"}})

(defn- public-order
  [order]
  (let [expired? (and (= :awaiting-wallet-signature (:status order))
                      (not (reservation-active? order (*instant*))))]
    (-> order
        (dissoc :buyer-user-id)
        (assoc :status (name (if expired? :payment-window-expired (:status order))))
        (cond-> expired? (assoc-in [:reservation :status] :expired))
        (update-in [:reservation :status] name)
        (update-in [:fulfillment :status] name)
        (update-in [:fulfillment :effect-boundary] name)
        (cond-> (get-in order [:fulfillment :shipment :status])
          (update-in [:fulfillment :shipment :status] name)))))

(defn- storefront-record
  [state slug]
  (some (fn [[tenant record]]
          (when (= (str/lower-case (str slug))
                   (get-in record [:publication :slug]))
            [tenant record]))
        (get-in state [:commerce :stores])))

(defn create-order!
  [session slug input]
  (let [buyer-did (or (identity/session-did session)
                      (refuse :commerce/buyer-did-required
                              "注文にはUser DIDが必要です。"))
        requested (present input :lines)
        _ (when-not (and (vector? requested) (seq requested) (<= (count requested) 100))
            (refuse :commerce/order-lines-required "商品を1〜100件指定してください。"))
        requested-skus (mapv #(sku! (:sku %)) requested)
        _ (when-not (= (count requested-skus) (count (distinct requested-skus)))
            (refuse :commerce/duplicate-order-sku
                    "同じSKUは注文内で1行にまとめてください。"))
        order-id (str (java.util.UUID/randomUUID))
        now (store/now)
        expires-at (str (.plusSeconds (*instant*) reservation-seconds))
        created (atom nil)]
    (store/transact!
     (fn [state]
       (let [[tenant record] (or (storefront-record state slug)
                                 (refuse :commerce/storefront-not-found
                                         "公開storefrontが見つかりません。"))
             ;; Resolved here rather than with the other inputs above, because
             ;; whether a shipping address is a required field or a refused one
             ;; is a property of the store, and the store is not known until the
             ;; storefront resolves. Asking a buyer of a per-query credential
             ;; where to post it was the shape this whole split exists to remove.
             given-address (present input :delivery-address :delivery_address)
             delivery (if (digital? record)
                        (when (some? given-address)
                          (refuse :commerce/digital-store
                                  "デジタル納品の注文に配送先は指定できません。"))
                        (address! given-address "配送先"))
             lines (mapv
                    (fn [line]
                      (let [sku (sku! (:sku line))
                            quantity (quantity! (:quantity line))
                            product (or (get-in record [:products sku])
                                        (refuse :commerce/product-not-found
                                                (str sku "は販売されていません。")))
                            available (- (:inventory product)
                                         (reserved-quantity state tenant sku (*instant*)))]
                        (when (> quantity available)
                          (refuse :commerce/insufficient-inventory
                                  (str sku "の予約可能在庫が不足しています。")))
                        {:sku sku :name (:name product) :quantity quantity
                         :unit-price-usdc (:price-usdc product)
                         :line-total-usdc (line-total (:price-usdc product) quantity)}))
                    requested)
             amount (reduce money-add "0" (map :line-total-usdc lines))
             requirements (payment-requirements record slug order-id amount)
             order {:schema order-schema :id order-id :store-slug slug
                    :merchant-did (:merchant-did record) :buyer-did buyer-did
                    :buyer-user-id (:user-id session) :lines lines
                    :delivery-address delivery :amount-usdc amount
                    :status :awaiting-wallet-signature
                    :reservation {:status :active :expires-at expires-at}
                    :payment-request {:protocol "x402" :version 1
                                      :facilitator (get-in record [:payment :facilitator])
                                      :requirements requirements
                                      :signature nil
                                      :note "外部Wallet送信とオンチェーン検証はまだ行われていません。"}
                    :fulfillment {:status :not-requested
                                  :effect-boundary :payment-required}
                    :created-at now :updated-at now}]
         (reset! created order)
         (assoc-in state [:commerce :orders tenant order-id] order))))
    (public-order @created)))

(defn order
  [session slug order-id]
  (let [state (store/snapshot)
        [tenant _] (or (storefront-record state slug)
                       (refuse :commerce/storefront-not-found
                               "公開storefrontが見つかりません。"))
        found (or (get-in state [:commerce :orders tenant order-id])
                  (refuse :commerce/order-not-found "注文が見つかりません。"))]
    (when-not (= (:user-id session) (:buyer-user-id found))
      (refuse :commerce/order-forbidden "この注文を読む権限がありません。"))
    (public-order found)))

(defn- fulfillment-on-payment
  "What a paid order becomes.

   Physical stops at :ready-to-pack and waits for a person, because a parcel is
   an act outside this system. Digital has no such act: the reference already
   exists on the product, so the same transaction that captures the payment
   hands it over. There is no state between paid and delivered to sit in, and
   inventing one would only mean an order that was paid for, deliverable, and
   waiting for nobody."
  [state tenant order now]
  (let [record (get-in state [:commerce :stores tenant])]
    (if-not (digital? record)
      {:status :ready-to-pack :effect-boundary :merchant-action-required}
      {:status :delivered
       :effect-boundary :none
       :delivered-at now
       :method (some-> (get-in record [:delivery :method]) name)
       :items (mapv (fn [{:keys [sku quantity]}]
                      {:sku sku :quantity quantity
                       :delivery-ref (get-in record [:products sku :delivery-ref])})
                    (:lines order))})))

(defn- transaction-hash! [value]
  (let [value (some-> value str str/trim str/lower-case)]
    (when-not (and value (re-matches #"0x[0-9a-f]{64}" value))
      (refuse :commerce/invalid-transaction "Base transaction hashが不正です。"))
    value))

(defn- payer-address! [value]
  (let [value (some-> value str str/trim str/lower-case)]
    (when-not (and value (re-matches #"0x[0-9a-f]{40}" value))
      (refuse :commerce/invalid-payer "支払Walletアドレスが不正です。"))
    value))

(defn verify-order-payment!
  [session slug order-id input]
  (let [state (store/snapshot)
        [tenant _] (or (storefront-record state slug)
                       (refuse :commerce/storefront-not-found
                               "公開storefrontが見つかりません。"))
        found (or (get-in state [:commerce :orders tenant order-id])
                  (refuse :commerce/order-not-found "注文が見つかりません。"))
        tx (transaction-hash! (present input :transaction :tx-hash :tx_hash))
        payer (payer-address! (present input :payer :from))]
    (when-not (= (:user-id session) (:buyer-user-id found))
      (refuse :commerce/order-forbidden "この注文を決済する権限がありません。"))
    (if (and (= :paid (:status found))
             (= tx (get-in found [:payment :transaction])))
      (public-order found)
      (do
        (when-not (reservation-active? found (*instant*))
          (refuse :commerce/payment-window-expired
                  "在庫予約の有効期限が切れました。送金せず、カートから注文を作り直してください。"))
        (when-let [used-by (get-in state [:commerce :payment-transactions tx])]
          (when-not (= order-id used-by)
            (refuse :commerce/payment-replayed
                    "このtransactionは別の注文ですでに使用されています。")))
        (let [payment {:x402Version 1 :scheme payment-scheme :network payment-network
                       :payload {:txHash tx :from payer}}
              requirements (get-in found [:payment-request :requirements])
              verdict (*verify-payment!* (get-in found [:payment-request :facilitator])
                                         payment requirements)]
          (when-not (:isValid verdict)
            (throw (ex-info "決済はまだオンチェーンで確認できません。確認後に再試行します。"
                            {:type :commerce/payment-unverified
                             :reason (:invalidReason verdict)})))
          (let [settled (atom nil)]
            (store/transact!
             (fn [current]
               (let [latest (or (get-in current [:commerce :orders tenant order-id])
                                (refuse :commerce/order-not-found "注文が見つかりません。"))
                     used-by (get-in current [:commerce :payment-transactions tx])]
                 (cond
                   (and (= :paid (:status latest))
                        (= tx (get-in latest [:payment :transaction])))
                   (do (reset! settled latest) current)

                   (and used-by (not= order-id used-by))
                   (refuse :commerce/payment-replayed
                           "このtransactionは別の注文ですでに使用されています。")

                   (not (reservation-active? latest (*instant*)))
                   (refuse :commerce/payment-window-expired
                           "在庫予約の有効期限が切れました。サポートへtransaction hashを連絡してください。")

                   :else
                   (let [paid-at (store/now)
                         next-order (-> latest
                                        (assoc :status :paid :paid-at paid-at :updated-at paid-at
                                               :payment {:status :verified-onchain
                                                         :transaction tx :payer payer
                                                         :network payment-network
                                                         :verified-at paid-at})
                                        (assoc-in [:reservation :status] :captured)
                                        (assoc :fulfillment (fulfillment-on-payment
                                                             current tenant latest paid-at)))
                         next-state (reduce
                                     (fn [s {:keys [sku quantity]}]
                                       (let [on-hand (get-in s [:commerce :stores tenant :products sku :inventory])]
                                         (when (< on-hand quantity)
                                           (refuse :commerce/inventory-invariant
                                                   "予約済み在庫を確定できません。手動確認が必要です。"))
                                         (update-in s [:commerce :stores tenant :products sku :inventory]
                                                    - quantity)))
                                     current (:lines latest))]
                     (reset! settled next-order)
                     (-> next-state
                         (assoc-in [:commerce :orders tenant order-id] next-order)
                         (assoc-in [:commerce :payment-transactions tx] order-id)))))))
            (public-order @settled)))))))

(defn merchant-orders [session]
  (let [tenant (:organization-id session)]
    {:orders (->> (get-in (store/snapshot) [:commerce :orders tenant])
                  vals (sort-by :created-at) reverse (mapv public-order))}))

(def ^:private fulfillment-next
  {:ready-to-pack #{:packed}
   :label-ready #{:shipped}
   :shipped #{:delivered}})

(defn- positive-decimal!
  [value field maximum]
  (try
    (let [n (bigdec (str value))]
      (when (or (not (pos? (.signum n))) (pos? (.compareTo n (bigdec maximum))))
        (refuse :commerce/invalid-parcel (str field "は0より大きく" maximum "以下で指定してください。")))
      (.toPlainString (.stripTrailingZeros n)))
    (catch NumberFormatException _
      (refuse :commerce/invalid-parcel (str field "を数値で指定してください。")))))

(defn prepare-shipment!
  "Freeze the parcel and address manifest before a carrier-side effect.

  This is deliberately a durable draft, not a label purchase. A carrier booking
  may only be recorded against this exact manifest, so Chat cannot silently
  claim that packing details which were never fixed were sent to a carrier."
  [session input]
  (when (digital? (get-in (store/snapshot) [:commerce :stores (:organization-id session)]))
    (refuse :commerce/digital-store
            "デジタル納品の注文に配送手続きはありません。支払い検証の時点で納品済みです。"))
  (let [tenant (:organization-id session)
        order-id (text! (present input :order-id :order_id) "注文ID" 100)
        parcel {:weight-kg (positive-decimal!
                            (present input :weight-kg :weight_kg)
                            "重量(kg)" "1000")
                :length-cm (positive-decimal!
                            (present input :length-cm :length_cm)
                            "長さ(cm)" "300")
                :width-cm (positive-decimal!
                           (present input :width-cm :width_cm)
                           "幅(cm)" "300")
                :height-cm (positive-decimal!
                            (present input :height-cm :height_cm)
                            "高さ(cm)" "300")}
        changed (atom nil)]
    (store/transact!
     (fn [state]
       (let [found (or (get-in state [:commerce :orders tenant order-id])
                       (refuse :commerce/order-not-found "注文が見つかりません。"))
             current (get-in found [:fulfillment :status])]
         (when-not (= :packed current)
           (refuse :commerce/shipment-requires-packed
                   "荷姿の確定は梱包済みの注文だけ実行できます。"))
         (let [now (store/now)
               merchant (get-in state [:commerce :stores tenant])
               requested-carrier (or (some-> (present input :carrier)
                                              str str/trim not-empty)
                                      (get-in merchant [:shipping :carrier]))
               existing (get-in found [:fulfillment :shipment])
               shipment {:status :awaiting-carrier-booking
                         :parcel parcel
                         :ship-from (get-in merchant [:shipping :ship-from])
                         :ship-to (:delivery-address found)
                         :requested-carrier requested-carrier
                         :effect-boundary :carrier-booking-not-executed
                         :prepared-at now}
               next-order (-> found
                              (assoc-in [:fulfillment :shipment] shipment)
                              (assoc-in [:fulfillment :effect-boundary]
                                        :carrier-booking-required)
                              (assoc :updated-at now))]
           (cond
             (and (= :awaiting-carrier-booking (:status existing))
                  (= parcel (:parcel existing))
                  (= requested-carrier (:requested-carrier existing)))
             (do (reset! changed found) state)

             existing
             (refuse :commerce/shipment-already-prepared
                     "この注文の荷姿はすでに確定しています。")

             :else
             (do (reset! changed next-order)
                 (assoc-in state [:commerce :orders tenant order-id] next-order)))))))
    (public-order @changed)))

(defn record-carrier-booking!
  "Record references supplied from a carrier or shipping aggregator.

  Recording references is not the carrier call and does not verify authenticity.
  Explicit references are required so a plain fulfillment-status reply cannot
  silently turn an order into label-ready."
  [session input]
  (when (digital? (get-in (store/snapshot) [:commerce :stores (:organization-id session)]))
    (refuse :commerce/digital-store
            "デジタル納品の注文に配送手続きはありません。支払い検証の時点で納品済みです。"))
  (let [tenant (:organization-id session)
        order-id (text! (present input :order-id :order_id) "注文ID" 100)
        carrier (text! (present input :carrier) "配送会社" 120)
        tracking (text! (present input :tracking-number :tracking_number) "追跡番号" 200)
        label-reference (text! (present input :label-reference :label_reference)
                               "送り状参照" 500)
        pickup-reference (when-let [value (present input :pickup-reference :pickup_reference)]
                           (text! value "集荷参照" 500))
        changed (atom nil)]
    (store/transact!
     (fn [state]
       (let [found (or (get-in state [:commerce :orders tenant order-id])
                       (refuse :commerce/order-not-found "注文が見つかりません。"))
             shipment (get-in found [:fulfillment :shipment])
             same? (and (= :label-ready (get-in found [:fulfillment :status]))
                        (= carrier (:carrier shipment))
                        (= tracking (:tracking-number shipment))
                        (= label-reference (:label-reference shipment))
                        (= pickup-reference (:pickup-reference shipment)))]
         (cond
           same? (do (reset! changed found) state)

           (not= :awaiting-carrier-booking (:status shipment))
           (refuse :commerce/shipment-not-awaiting-booking
                   "荷姿確定後、未予約の注文にだけ送り状結果を記録できます。")

           :else
           (let [now (store/now)
                 next-order (-> found
                                (assoc-in [:fulfillment :status] :label-ready)
                                (assoc-in [:fulfillment :carrier] carrier)
                                (assoc-in [:fulfillment :tracking-number] tracking)
                                (assoc-in [:fulfillment :shipment]
                                          (merge shipment
                                                 {:status :label-ready
                                                  :carrier carrier
                                                  :tracking-number tracking
                                                  :label-reference label-reference
                                                  :pickup-reference pickup-reference
                                                  :effect-boundary :external-booking-reference-recorded
                                                  :booked-at now}))
                                (assoc-in [:fulfillment :effect-boundary]
                                          :carrier-booking-reference-recorded)
                                (assoc-in [:fulfillment :updated-at] now)
                                (assoc :updated-at now))]
             (reset! changed next-order)
             (assoc-in state [:commerce :orders tenant order-id] next-order))))))
    (public-order @changed)))

(defn advance-fulfillment!
  [session input]
  (when (digital? (get-in (store/snapshot) [:commerce :stores (:organization-id session)]))
    (refuse :commerce/digital-store
            "デジタル納品の注文に配送状態はありません。支払い検証の時点で納品済みです。"))
  (let [tenant (:organization-id session)
        order-id (text! (present input :order-id :order_id) "注文ID" 100)
        next-status (some-> (present input :status) name keyword)
        changed (atom nil)]
    (store/transact!
     (fn [state]
       (let [found (or (get-in state [:commerce :orders tenant order-id])
                       (refuse :commerce/order-not-found "注文が見つかりません。"))
             current (get-in found [:fulfillment :status])]
         (when-not (contains? (get fulfillment-next current #{}) next-status)
           (refuse :commerce/invalid-fulfillment-transition
                   (str (name current) "から" (name next-status) "へは進めません。")))
         (when (and (= :shipped next-status)
                    (str/blank? (get-in found [:fulfillment :tracking-number])))
           (refuse :commerce/tracking-required "発送済みには送り状由来の追跡番号が必要です。"))
         (let [now (store/now)
               next-order (-> found
                              (assoc-in [:fulfillment :status] next-status)
                              (assoc-in [:fulfillment :updated-at] now)
                              (assoc :updated-at now))]
           (reset! changed next-order)
           (assoc-in state [:commerce :orders tenant order-id] next-order)))))
    (public-order @changed)))

(def tool-definitions
  [{:name "commerce_store_overview"
    :description (str "Read the active tenant's DID-bound shop setup, x402, shipping, "
                      "readiness, and honest publication state.")
    :parameters {:type "object" :properties {}}}
   {:name "commerce_store_configure"
    :description "Configure a corporation or sole proprietor shop and bind it to the correct DID. (write)"
    :parameters {:type "object"
                 :properties {:business_kind {:type "string" :enum ["corporation" "sole_proprietor"]}
                              :fulfillment_kind
                              {:type "string" :enum ["physical" "digital"]
                               :description
                               (str "physical (default) requires ship-from and return addresses and runs the "
                                    "shipment state machine. digital requires a delivery method instead, and a "
                                    "verified payment delivers the order in the same transaction. "
                                    "Immutable once a product exists.")}
                              :display_name {:type "string"}
                              :legal_name {:type "string"}
                              :legal_address
                              {:type "object"
                               :description
                               (str "The merchant's own registered address. Required for both kinds and never "
                                    "published (the public storefront redacts it); it is not a shipping fact.")}}
                 :required ["business_kind" "display_name" "legal_name" "legal_address"]}}
   {:name "commerce_payment_configure_x402"
    :description (str "Bind this Bot's verified external Base wallet as the shop's x402 USDC payTo. "
                      "No private key is stored and no payment is signed. (write)")
    :parameters {:type "object" :properties {}}}
   {:name "commerce_shipping_configure"
    :description (str "Configure ship-from and return addresses and record the discovered fulfillment actor. "
                      "The actor is not connected until marketplace order identity is compatible. "
                      "This does not buy a label or request pickup. (write)")
    :parameters {:type "object"
                 :properties {:ship_from {:type "object"}
                              :return_address {:type "object"}
                              :carrier {:type "string"}}
                 :required ["ship_from" "return_address"]}}
   {:name "commerce_delivery_configure"
    :description (str "Configure how a DIGITAL shop hands over what was bought. No addresses. "
                      "The per-product delivery_ref is what the buyer receives; this is the method "
                      "and the instructions shown with it. Refused on a physical shop. (write)")
    :parameters {:type "object"
                 :properties {:delivery_method
                              {:type "string"
                               :enum ["download_url" "license_key" "api_credential" "content_address"]}
                              :instructions {:type "string"}
                              :support_contact {:type "string"}}
                 :required ["delivery_method" "instructions"]}}
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
                              :inventory {:type "integer"}
                              :delivery_ref
                              {:type "string"
                               :description
                               (str "What the buyer receives on a verified payment: a URL, licence key, "
                                    "credential or content address. REQUIRED on a digital shop and refused "
                                    "on a physical one. Never appears in the public storefront.")}}
                 :required ["sku" "name" "description" "price_usdc" "inventory"]}}
   {:name "commerce_store_publish"
    :description (str "Publish the ready catalog on this Cloud Itonami deployment. "
                      "This does not claim an external DNS or separate deployment. (write)")
    :parameters {:type "object"
                 :properties {:slug {:type "string"}}
                 :required ["slug"]}}
   {:name "commerce_order_list"
    :description (str "List this tenant's paid and unpaid storefront orders, including "
                      "reservation and fulfillment state.")
    :parameters {:type "object" :properties {}}}
   {:name "commerce_shipment_prepare"
    :description (str "Freeze parcel dimensions and the private ship-from/ship-to manifest for a packed order. "
                      "This prepares a carrier request but does not buy a label or request pickup. (write)")
    :parameters {:type "object"
                 :properties {:order_id {:type "string"}
                              :weight_kg {:type "number"}
                              :length_cm {:type "number"}
                              :width_cm {:type "number"}
                              :height_cm {:type "number"}
                              :carrier {:type "string"}}
                 :required ["order_id" "weight_kg" "length_cm" "width_cm" "height_cm"]}}
   {:name "commerce_shipment_record_booking"
    :description (str "Record label/tracking references supplied from an external carrier or shipping aggregator. "
                      "This records merchant-supplied references; it does not perform or verify the booking. (write)")
    :parameters {:type "object"
                 :properties {:order_id {:type "string"}
                              :carrier {:type "string"}
                              :tracking_number {:type "string"}
                              :label_reference {:type "string"}
                              :pickup_reference {:type "string"}}
                 :required ["order_id" "carrier" "tracking_number" "label_reference"]}}
   {:name "commerce_fulfillment_advance"
    :description (str "Advance a paid order through packing, physical handoff, and delivery. "
                      "Shipped is accepted only after carrier booking evidence exists. (write)")
    :parameters {:type "object"
                 :properties {:order_id {:type "string"}
                              :status {:type "string" :enum ["packed" "shipped" "delivered"]}}
                 :required ["order_id" "status"]}}])

(defn tool? [name]
  (str/starts-with? (str name) "commerce_"))

(defn write-tool? [name]
  (and (tool? name)
       (not (contains? #{"commerce_store_overview" "commerce_order_list"}
                       (str name)))))

(defn describe [name input]
  (case (str name)
    "commerce_store_overview" "このTenantのショップ開設状況を読みます。"
    "commerce_store_configure"
    (str (or (:display_name input) (:display-name input))
         "を事業者DIDへ結び、法的名称と住所を記録します。")
    "commerce_payment_configure_x402"
    "このBotのBase Walletをx402/USDC受取先として記録します。秘密鍵や署名は扱いません。"
    "commerce_shipping_configure"
    "発送元・返品先を設定します。fulfillment actor候補は記録しますが、注文ID互換化までは接続済みと扱いません。"
    "commerce_delivery_configure"
    "デジタル納品の方法と案内を設定します。住所は扱いません。支払い検証の時点で納品参照が引き渡されます。"
    "commerce_store_finalize"
    "DID・法的表示・x402・発送設定（デジタルなら納品設定）を検査し、公開準備完了として記録します。公開は実行しません。"
    "commerce_product_upsert"
    (str (or (:sku input) "商品") "の商品名・USDC価格・在庫を公開カタログ候補へ記録します。")
    "commerce_store_publish"
    (str (or (:slug input) "store") "として、このCloud Itonami上のstorefrontを公開します。")
    "commerce_order_list"
    "このTenantの注文、決済確認、在庫予約、発送状態を読みます。"
    "commerce_shipment_prepare"
    (str (or (:order_id input) (:order-id input) "注文")
         "の梱包寸法と配送先を確定し、送り状予約待ちとして記録します。予約自体は実行しません。")
    "commerce_shipment_record_booking"
    (str (or (:order_id input) (:order-id input) "注文")
         "に外部配送サービスが返した送り状・追跡・集荷参照を記録します。")
    "commerce_fulfillment_advance"
    (str (or (:order_id input) (:order-id input) "注文") "を"
         (or (:status input) "次の発送状態") "へ進めます。")
    "Commerce設定を更新します。"))

(defn call-tool!
  [bot name input]
  (let [session (bot-session bot)]
    (case (str name)
      "commerce_store_overview" (overview session)
      "commerce_store_configure" (configure-store! session input)
      "commerce_payment_configure_x402" (configure-x402! session (:bot/id bot))
      "commerce_shipping_configure" (configure-shipping! session input)
      "commerce_delivery_configure" (configure-delivery! session input)
      "commerce_store_finalize" (finalize! session)
      "commerce_product_upsert" (upsert-product! session input)
      "commerce_store_publish" (publish-storefront! session input)
      "commerce_order_list" (merchant-orders session)
      "commerce_shipment_prepare" (prepare-shipment! session input)
      "commerce_shipment_record_booking" (record-carrier-booking! session input)
      "commerce_fulfillment_advance" (advance-fulfillment! session input)
      (refuse :commerce/unknown-tool "未知のCommerce toolです。"))))

(defn bot-summary [bot]
  (overview (bot-session bot)))
