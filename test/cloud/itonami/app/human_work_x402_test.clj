(ns cloud.itonami.app.human-work-x402-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.human-work :as human-work]
            [cloud.itonami.app.human-work-x402 :as x402]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def configuration
  {:server {:public-origin "https://itonami.example"}
   :human-work
   {:x402 {:enabled? true
           :scheme "auth-capture"
           :network "eip155:8453"
           :asset "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
           :asset-name "USDC" :asset-version "2"
           :asset-transfer-method "eip3009"
           :facilitator-url "https://facilitator.example"
           :operator-type "custom"
           :operator-url "https://operator.example"
           :auth-capture-escrow "0x13AC3b34322D12FE27D5e192D0c2b2266d4F29CB"
           :capture-authorizer "0x2222222222222222222222222222222222222222"
           :fee-recipient "0x3333333333333333333333333333333333333333"
           :max-timeout-seconds 300}}})

(use-fixtures
  :each
  (fn [run]
    (let [counter (atom 0)]
      (with-redefs [store/transact! (fn [f & args]
                                      (apply swap! store/state f args))
                    store/now (constantly "2026-09-01T00:00:00Z")]
        (binding [human-work/*now* (constantly "2026-09-01T00:00:00Z")
                  human-work/*new-id* (fn [prefix]
                                        (str prefix "-" (swap! counter inc)))
                  x402/*now-epoch* (constantly 1788220800)]
          (reset! store/state (store/initial-state))
          (run))))))

(defn- open-and-accept! []
  (human-work/register-worker!
   {:display-name "Worker"
    :payout-address "0x1111111111111111111111111111111111111111"
    :locations [{:location-id "remote" :country "JP"
                 :work-modes ["remote"] :service-areas []
                 :evidence-ref "location"}]
    :availability [{:start "2026-09-01T00:00:00Z"
                    :end "2026-10-01T00:00:00Z"}]
    :credentials []}
   "worker")
  (human-work/record-identity-assurance!
   "worker" {:provider-id "identity-provider"
             :provider-reference "person-1"
             :level "substantial" :status "verified"
             :checked-at "2026-09-01T00:00:00Z"
             :valid-until "2026-10-01T00:00:00Z"
             :evidence-ref "identity:receipt:1"})
  (let [request
        (human-work/create-request!
         {:organization-id "org-1" :visibility "public"
          :title "Inspect a site" :summary "Physical inspection"
          :category "inspection" :work-mode "remote"
          :location {:country "JP"}
          :work-window {:start "2026-09-02T00:00:00Z"
                        :end "2026-09-02T01:00:00Z"}
          :requirements {:credentials []}
          :evidence-contract ["report"]
          :compensation {:amount-atomic "10000000"
                         :network "eip155:8453"
                         :platform-fee-bps 500}}
         "requester")]
    (human-work/publish! (:id request) "requester")
    (human-work/accept! (:id request) "worker")))

(defn- encode [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (json/write-str value) StandardCharsets/UTF_8)))

(defn- provider [calls]
  (fn [_ call]
    (swap! calls conj call)
    (case [(:provider call) (:method call) (:path call)]
      [:facilitator :get "/supported"]
      {:kinds [{:x402Version 2 :scheme "auth-capture"
                :network "eip155:8453"}]}

      [:facilitator :post "/settle"]
      {:success true :payer "0x4444444444444444444444444444444444444444"
       :transaction "0xfunding" :network "eip155:8453"}

      [:operator :post "/capture"]
      {:success true :transaction "0xcapture"}

      [:operator :post "/void"]
      {:success true :transaction "0xvoid"})))

(defn- fund! [id calls]
  (binding [x402/*provider-call!* (provider calls)]
    (let [challenge (x402/fund! configuration id "requester" nil)
          required (:body challenge)
          accepted (first (:accepts required))
          payload {:x402Version 2
                   :resource (:resource required)
                   :accepted accepted
                   :payload {:signature "must-not-persist"
                             :salt (str "0x" (apply str (repeat 64 "a")))}}
          funded (x402/fund! configuration id "requester" (encode payload))]
      {:challenge challenge :funded funded})))

(deftest x402-auth-capture-funds-usdc-without-retaining-the-signature
  (let [calls (atom []) request (open-and-accept!) id (:id request)
        {:keys [challenge funded]} (fund! id calls)
        accepted (get-in challenge [:body :accepts 0])
        stored (human-work/request id)]
    (is (= 402 (:status challenge)))
    (is (string? (get-in challenge [:headers "PAYMENT-REQUIRED"])))
    (is (= "auth-capture" (:scheme accepted)))
    (is (= "USDC" (get-in accepted [:extra :name])))
    (is (= "escrow" (get-in accepted [:extra :paymentFlow])))
    (is (= "deferred" (get-in accepted [:extra :captureMode])))
    (is (= "0x1111111111111111111111111111111111111111"
           (:payTo accepted)))
    (is (= "10000000" (:amount accepted)))
    (is (= 200 (:status funded)))
    (is (string? (get-in funded [:headers "PAYMENT-RESPONSE"])))
    (is (= "funded" (get-in stored [:compensation :settlement-status])))
    (is (= "0xfunding"
           (get-in stored [:compensation :x402 :funding-transaction])))
    (is (not (str/includes? (pr-str stored) "must-not-persist")))
    (is (= [[:facilitator :get "/supported"]
            [:facilitator :post "/settle"]]
           (mapv (juxt :provider :method :path) @calls)))))

(deftest verified-work-captures-and-cancelled-work-voids-the-escrow
  (let [capture-calls (atom []) request (open-and-accept!) id (:id request)]
    (fund! id capture-calls)
    (human-work/start! id {} "worker")
    (human-work/submit! id {:summary "done" :evidence {:report "proof"}}
                        "worker")
    (human-work/review-submission!
     id {:decision "verified" :verification-evidence-ref "review-proof"}
     "requester")
    (binding [x402/*provider-call!* (provider capture-calls)]
      (is (= "released"
             (get-in (x402/release! configuration id "requester")
                     [:compensation :settlement-status]))))
    (is (= "/capture" (:path (last @capture-calls)))))
  (let [void-calls (atom []) request (open-and-accept!) id (:id request)]
    (fund! id void-calls)
    (human-work/cancel! id "requester")
    (binding [x402/*provider-call!* (provider void-calls)]
      (is (= "refunded"
             (get-in (x402/refund! configuration id "requester")
                     [:compensation :settlement-status]))))
    (is (= "/void" (:path (last @void-calls))))))
