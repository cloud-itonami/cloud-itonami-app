(ns cloud.itonami.app.receipt-export
  "Privacy-preserving, independently signed WebAuthn receipt export."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store])
  (:import [java.net InetAddress URI]
           [java.net.http HttpClient HttpRequest
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security KeyFactory MessageDigest Signature]
           [java.security.spec X509EncodedKeySpec]
           [java.time Duration]
           [java.util Base64]))

(def payload-schema "cloud.itonami.authorization-receipt-export-payload.v1")
(def envelope-schema "cloud.itonami.authorization-receipt-export.v1")
(def signer-request-schema
  "cloud.itonami.authorization-receipt-signing-request.v1")
(def ^:private maximum-response-bytes (* 256 1024))
(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 8))
      .build))

(defn- canonical [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (map (fn [[key item]] [key (canonical item)]))
          value)

    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn payload-digest [value]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes (pr-str (canonical value))
                          StandardCharsets/UTF_8))
      (->> (.encodeToString
            (.withoutPadding (Base64/getUrlEncoder))))))

(defn signing-bytes [key-id payload-digest]
  (.getBytes
   (str envelope-schema "\n" key-id "\n" payload-digest)
   StandardCharsets/UTF_8))

(defn verify-envelope?
  "Verify an exported envelope against a separately distributed public key."
  [envelope public-key-base64]
  (try
    (let [payload (:payload envelope)
          payload-digest (payload-digest payload)
          public-key
          (.generatePublic
           (KeyFactory/getInstance "Ed25519")
           (X509EncodedKeySpec.
            (.decode (Base64/getDecoder) public-key-base64)))
          verifier (Signature/getInstance "Ed25519")]
      (.initVerify verifier public-key)
      (.update verifier
               (signing-bytes (:key-id envelope) payload-digest))
      (and (= envelope-schema (:schema envelope))
           (= payload-schema (:schema payload))
           (= "Ed25519" (:algorithm envelope))
           (= payload-digest (:payload-digest envelope))
           (passkey/authorization-receipt-valid?
            (:authorization-receipt payload))
           (.verify verifier
                    (.decode (Base64/getDecoder)
                             (:signature envelope)))))
    (catch Exception _ false)))

(defn envelope
  "Build and verify an export from a signature returned by an independent signer."
  [payload key-id signature public-key-base64]
  (let [payload (canonical payload)
        result {:schema envelope-schema
                :algorithm "Ed25519"
                :key-id key-id
        :payload-digest (payload-digest payload)
                :payload payload
                :signature signature}]
    (when-not (verify-envelope? result public-key-base64)
      (throw (ex-info "Receipt signer returned an invalid signature."
                      {:type :receipt-export/invalid-signature
                       :key-id key-id})))
    result))

(defn- loopback-host? [host]
  (try
    (every? #(.isLoopbackAddress ^InetAddress %)
            (InetAddress/getAllByName host))
    (catch Exception _ false)))

(defn- signer-uri! [url]
  (let [uri (URI/create (str url))
        scheme (.getScheme uri)
        host (.getHost uri)]
    (when-not (and host
                   (or (= "https" scheme)
                       (and (= "http" scheme) (loopback-host? host)))
                   (nil? (.getUserInfo uri))
                   (nil? (.getFragment uri)))
      (throw (ex-info "Receipt signer must use HTTPS or loopback HTTP."
                      {:type :receipt-export/invalid-signer})))
    uri))

(defn- response-body! [stream]
  (with-open [input stream]
    (let [bytes (.readNBytes input (inc maximum-response-bytes))]
      (when (> (alength bytes) maximum-response-bytes)
        (throw (ex-info "Receipt signer response exceeded the size limit."
                        {:type :receipt-export/response-too-large})))
      (String. bytes StandardCharsets/UTF_8))))

(defn- request-signature!
  [{:keys [url key-id access-token-env]} payload-digest]
  (let [token (some-> access-token-env System/getenv str/trim not-empty)]
    (when-not (and url key-id token)
      (throw (ex-info "Independent receipt signer is not configured."
                      {:type :receipt-export/not-configured})))
    (let [request
          (-> (HttpRequest/newBuilder (signer-uri! url))
              (.timeout (Duration/ofSeconds 20))
              (.header "Authorization" (str "Bearer " token))
              (.header "Accept" "application/json")
              (.header "Content-Type" "application/json")
              (.POST
               (HttpRequest$BodyPublishers/ofString
                (json/write-str
                 {:schema signer-request-schema
                  :key-id key-id
                  :payload-digest payload-digest})))
              .build)
          response
          (.send client request (HttpResponse$BodyHandlers/ofInputStream))
          body (response-body! (.body response))]
      (when-not (<= 200 (.statusCode response) 299)
        (throw (ex-info "Independent receipt signer refused the request."
                        {:type :receipt-export/signer-failed
                         :status (.statusCode response)})))
      (:signature (json/read-str body :key-fn keyword)))))

(defn- owned-source! [actor source-type source-id]
  (let [source
        (case source-type
          "authority"
          (get-in (store/snapshot) [:authority :proposals source-id])

          "bitcoin-psbt"
          (get-in (store/snapshot) [:bitcoin :psbt-proposals source-id])

          nil)]
    (when-not (and source (= actor (:user-id source)))
      (throw (ex-info "Authorization receipt was not found."
                      {:type :receipt-export/not-found})))
    (let [receipt (:approval-receipt source)]
      (when-not (passkey/authorization-receipt-valid? receipt)
        (throw (ex-info "Authorization receipt is absent or invalid."
                        {:type :receipt-export/invalid-receipt})))
      {:source source :receipt receipt})))

(defn export!
  "Export an actor-owned receipt. Only its digest leaves the local app."
  [configuration actor source-type source-id]
  (let [{:keys [source receipt]}
        (owned-source! actor source-type source-id)
        signer (:receipt-signer configuration)
        payload
        {:schema payload-schema
         :source-type source-type
         :source-id source-id
         :actor actor
         :organization-id (:organization-id source)
         :authorization-receipt receipt}
        digest (payload-digest payload)
        signature (request-signature! signer digest)]
    (envelope payload (:key-id signer) signature (:public-key signer))))
