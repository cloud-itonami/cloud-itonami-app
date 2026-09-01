(ns cloud.itonami.app.human-work-x402
  "x402 v2 USDC auth-capture adapter for HumanWorkRequest.

  The accepted worker is the onchain receiver. A custom auth-capture operator
  holds USDC until Cloud Itonami sends an authenticated capture or void after
  the evidenced work transition. Signed payer payloads are forwarded once and
  never persisted; only requirements and settlement receipts are retained."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.human-work :as human-work]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration Instant]
           [java.util Base64]))

(def schema "cloud.itonami.app.human-work-x402.v1")
(def ^:dynamic *environment* #(System/getenv %))
(def ^:dynamic *now-epoch* #(quot (System/currentTimeMillis) 1000))

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

(defn- endpoint! [value field]
  (let [value (some-> value str str/trim)]
    (when-not (and value
                   (or (str/starts-with? value "https://")
                       (str/starts-with? value "http://127.0.0.1:")
                       (str/starts-with? value "http://localhost:")))
      (fail! :human-work/payment-unavailable
             (str field " must be a fixed HTTPS endpoint")))
    (str/replace value #"/+$" "")))

(defn- address! [value field]
  (let [value (some-> value str str/trim)]
    (when-not (and value (re-matches #"0x[0-9a-fA-F]{40}" value))
      (fail! :human-work/payment-unavailable
             (str field " must be an EVM address")))
    value))

(defn- settings! [configuration]
  (let [settings (get-in configuration [:human-work :x402])]
    (when-not (:enabled? settings)
      (fail! :human-work/payment-unavailable
             "x402 USDC payments are not enabled"))
    (when-not (= "auth-capture" (or (:scheme settings) "auth-capture"))
      (fail! :human-work/payment-unavailable
             "Human work requires the x402 auth-capture scheme"))
    (when-not (= "custom" (or (:operator-type settings) "custom"))
      (fail! :human-work/payment-unavailable
             "Human work requires a custom auth-capture operator"))
    (when-not (re-matches #"eip155:[1-9][0-9]*" (str (:network settings)))
      (fail! :human-work/payment-unavailable
             "x402 network must be an EVM CAIP-2 identifier"))
    (assoc settings
           :facilitator-url (endpoint! (:facilitator-url settings)
                                       "facilitator-url")
           :operator-url (endpoint! (:operator-url settings) "operator-url")
           :asset (address! (:asset settings) "asset")
           :auth-capture-escrow
           (address! (:auth-capture-escrow settings) "auth-capture-escrow")
           :capture-authorizer
           (address! (:capture-authorizer settings) "capture-authorizer")
           :fee-recipient (address! (:fee-recipient settings) "fee-recipient"))))

(defn- token [settings field]
  (when-let [env-name (some-> (get settings field) str str/trim not-empty)]
    (or (some-> env-name *environment* str/trim not-empty)
        (fail! :human-work/payment-unavailable
               "A configured x402 credential is unavailable"
               {:credential-env env-name}))))

(defn- provider-call!
  [settings {:keys [provider method path body]}]
  (let [[base token-field]
        (case provider
          :facilitator [(:facilitator-url settings) :facilitator-token-env]
          :operator [(:operator-url settings) :operator-token-env])
        builder (-> (HttpRequest/newBuilder (URI/create (str base path)))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Accept" "application/json"))
        _ (when-let [bearer (token settings token-field)]
            (.header builder "Authorization" (str "Bearer " bearer)))
        _ (when body (.header builder "Content-Type" "application/json"))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str body))))
        response (.send (HttpClient/newHttpClient) (.build request)
                        (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        parsed (try (json/read-str (.body response) :key-fn keyword)
                    (catch Exception _ nil))]
    (when-not (<= 200 status 299)
      (fail! :human-work/payment-provider-failed
             "The x402 provider refused the request"
             {:provider provider :status status}))
    (or parsed
        (fail! :human-work/payment-provider-failed
               "The x402 provider returned invalid JSON"
               {:provider provider}))))

(def ^:dynamic *provider-call!* provider-call!)

(defn- request! [id]
  (or (human-work/request id)
      (fail! :human-work/not-found "Human work request was not found")))

(defn- update-request! [id f]
  (let [answer (volatile! nil)]
    (store/transact!
     (fn [state]
       (let [request (or (get-in state [:human-work :requests id])
                         (fail! :human-work/not-found
                                "Human work request was not found"))
             next-request (f request)]
         (vreset! answer next-request)
         (assoc-in state [:human-work :requests id] next-request))))
    @answer))

(defn- encode-header [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (json/write-str value) StandardCharsets/UTF_8)))

(defn- decode-header [value]
  (when (or (str/blank? (str value)) (> (count (str value)) 65536))
    (fail! :human-work/payment-required
           "PAYMENT-SIGNATURE is missing or too large"))
  (try
    (let [bytes (try (.decode (Base64/getDecoder) ^String value)
                     (catch IllegalArgumentException _
                       (.decode (Base64/getUrlDecoder) ^String value)))]
      (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword))
    (catch Exception _
      (fail! :human-work/payment-required
             "PAYMENT-SIGNATURE is not a valid x402 payload"))))

(defn- work-end-epoch [request]
  (.getEpochSecond (Instant/parse (get-in request [:work-window :end]))))

(defn- build-payment-required [settings configuration request]
  (let [now (*now-epoch*)
        timeout (long (or (:max-timeout-seconds settings) 300))
        capture-window (long (or (:capture-window-seconds settings) 2592000))
        refund-window (long (or (:refund-window-seconds settings) 7776000))
        review-grace (long (or (:review-grace-seconds settings) 604800))
        capture-deadline (max (+ now capture-window)
                              (+ (work-end-epoch request) review-grace))
        refund-deadline (max (+ now refund-window)
                             (+ capture-deadline review-grace))
        compensation (:compensation request)
        origin (or (get-in configuration [:server :public-origin])
                   "http://localhost:1338")
        receiver-authorizer
        (some-> (:receiver-authorizer settings) (address! "receiver-authorizer"))
        extra (cond->
               {:name (or (:asset-name settings) "USDC")
                :version (or (:asset-version settings) "2")
                :authCaptureEscrow (:auth-capture-escrow settings)
                :captureAuthorizer (:capture-authorizer settings)
                :operatorType "custom"
                :paymentFlow "escrow"
                :captureMode "deferred"
                :captureDeadline capture-deadline
                :refundDeadline refund-deadline
                :minFeeBps (:platform-fee-bps compensation)
                :maxFeeBps (:platform-fee-bps compensation)
                :feeRecipient (:fee-recipient settings)
                :assetTransferMethod (or (:asset-transfer-method settings)
                                         "eip3009")}
                receiver-authorizer
                (assoc :receiverAuthorizer receiver-authorizer))
        requirements {:scheme "auth-capture"
                      :network (:network compensation)
                      :amount (:amount-atomic compensation)
                      :asset (:asset settings)
                      :payTo (:accepted-payout-address request)
                      :maxTimeoutSeconds timeout
                      :extra extra}
        resource {:url (str origin "/api/workspace/human-work/requests/"
                            (:id request) "/fund")
                  :description (str "Fund human work: " (:title request))
                  :mimeType "application/json"
                  :serviceName "Cloud Itonami"}]
    {:x402Version 2
     :resource resource
     :accepts [requirements]
     :extensions {}}))

(defn- challenge! [settings configuration request-id actor]
  (update-request!
   request-id
   (fn [request]
     (when-not (= actor (:requester-id request))
       (fail! :human-work/forbidden "Only the requester may fund this work"))
     (when-not (and (= "accepted" (:status request))
                    (:compensation request)
                    (= "unfunded" (get-in request
                                           [:compensation :settlement-status])))
       (fail! :human-work/invalid-transition
              "Only accepted, unfunded compensated work may enter escrow"))
     (when-not (= (:network settings)
                  (get-in request [:compensation :network]))
       (fail! :human-work/payment-unavailable
              "The request network is not enabled for x402"))
     (let [existing (get-in request [:compensation :x402 :payment-required])
           existing-valid-before (get-in request
                                         [:compensation :x402 :valid-before])
           [challenge valid-before]
           (if (and existing existing-valid-before
                    (< (*now-epoch*) existing-valid-before))
             [existing existing-valid-before]
             (let [challenge (build-payment-required settings configuration
                                                     request)]
               [challenge (+ (*now-epoch*)
                             (get-in challenge
                                     [:accepts 0 :maxTimeoutSeconds]))]))]
       (-> request
           (assoc-in [:compensation :x402]
                     {:protocol-version 2
                      :scheme "auth-capture"
                      :payment-required challenge
                      :valid-before valid-before})
           (update :audit conj {:action "x402-payment-required"
                                :actor actor :at (store/now)}))))))

(defn- supported! [settings]
  (let [answer (*provider-call!* settings
                                 {:provider :facilitator :method :get
                                  :path "/supported"})
        match? (some #(and (= 2 (:x402Version %))
                           (= "auth-capture" (:scheme %))
                           (= (:network settings) (:network %)))
                     (:kinds answer))]
    (when-not match?
      (fail! :human-work/payment-unavailable
             "The facilitator does not advertise auth-capture on this network"))
    true))

(defn fund!
  "Return an HTTP-shaped 402 challenge or settle a PAYMENT-SIGNATURE into escrow."
  [configuration request-id actor payment-signature]
  (let [settings (settings! configuration)
        challenged (challenge! settings configuration request-id actor)
        payment-required (get-in challenged
                                 [:compensation :x402 :payment-required])]
    (if (str/blank? (str payment-signature))
      {:status 402 :body payment-required
       :headers {"PAYMENT-REQUIRED" (encode-header payment-required)}}
      (let [payload (decode-header payment-signature)
            requirements (first (:accepts payment-required))]
        (when-not (and (= 2 (:x402Version payload))
                       (= requirements (:accepted payload)))
          (fail! :human-work/payment-required
                 "The signed x402 payload does not match this request"))
        (supported! settings)
        (let [settled (*provider-call!*
                       settings
                       {:provider :facilitator :method :post :path "/settle"
                        :body {:x402Version 2
                               :paymentPayload payload
                               :paymentRequirements requirements}})]
          (when-not (and (true? (:success settled))
                         (= (:network requirements) (:network settled))
                         (not (str/blank? (str (:transaction settled)))))
            (fail! :human-work/payment-provider-failed
                   "x402 escrow authorization did not settle"
                   {:reason (:errorReason settled)}))
          (let [payment-reference (or (get-in payload [:payload :salt])
                                      (get-in payload [:payload :authorization :nonce]))
                updated
                (update-request!
                 request-id
                 #(-> %
                      (assoc-in [:compensation :settlement-status] "funded")
                      (assoc-in [:compensation :x402 :payer] (:payer settled))
                      (assoc-in [:compensation :x402 :funding-transaction]
                                (:transaction settled))
                      (assoc-in [:compensation :x402 :payment-reference]
                                payment-reference)
                      (assoc-in [:compensation :x402 :funded-at] (store/now))
                      (update :audit conj
                              {:action "funded"
                               :actor actor :at (store/now)
                               :evidence {:provider "x402"
                                          :scheme "auth-capture"
                                          :network (:network settled)
                                          :transaction (:transaction settled)}})))]
            {:status 200 :body updated
             :headers {"PAYMENT-RESPONSE" (encode-header settled)}}))))))

(defn- lifecycle! [configuration request-id actor action]
  (let [settings (settings! configuration)
        request (request! request-id)
        compensation (:compensation request)
        expected (if (= action "capture") #{"funded"}
                     #{"funded" "refund-required"})]
    (when-not (= actor (:requester-id request))
      (fail! :human-work/forbidden
             "Only the requester may finalize this payment"))
    (when-not (and (if (= action "capture")
                     (= "verified" (:status request))
                     (contains? #{"cancelled" "rejected"} (:status request)))
                   (contains? expected (:settlement-status compensation)))
      (fail! :human-work/invalid-transition
             "The work and escrow states do not permit that settlement"))
    (let [x402 (:x402 compensation)
          result (*provider-call!*
                  settings
                  {:provider :operator :method :post
                   :path (str "/" action)
                   :body {:requestId request-id
                          :network (:network compensation)
                          :asset (:asset settings)
                          :amount (:amount-atomic compensation)
                          :platformFeeBps (:platform-fee-bps compensation)
                          :payTo (get-in x402 [:payment-required :accepts 0 :payTo])
                          :payer (:payer x402)
                          :paymentReference (:payment-reference x402)
                          :fundingTransaction (:funding-transaction x402)
                          :reviewEvidenceRef (get-in request
                                                    [:review :evidence-ref])}})]
      (when-not (and (true? (:success result))
                     (not (str/blank? (str (:transaction result)))))
        (fail! :human-work/payment-provider-failed
               "The x402 escrow operator did not finalize the payment"))
      (update-request!
       request-id
       #(-> %
            (assoc-in [:compensation :settlement-status]
                      (if (= action "capture") "released" "refunded"))
            (assoc-in [:compensation :x402 (keyword (str action "-transaction"))]
                      (:transaction result))
            (assoc-in [:compensation :x402
                       (if (= action "capture") :captured-at :voided-at)]
                      (store/now))
            (update :audit conj
                    {:action (if (= action "capture")
                               "payment-released" "payment-refunded")
                     :actor actor :at (store/now)
                     :evidence {:provider "x402"
                                :scheme "auth-capture"
                                :network (:network compensation)
                                :transaction (:transaction result)}}))))))

(defn release! [configuration request-id actor]
  (lifecycle! configuration request-id actor "capture"))

(defn refund! [configuration request-id actor]
  (lifecycle! configuration request-id actor "void"))
