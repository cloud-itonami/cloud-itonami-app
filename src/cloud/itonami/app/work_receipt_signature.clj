(ns cloud.itonami.app.work-receipt-signature
  "Deployment-attested execution receipts. The HMAC key is environment-only;
  EDN stores its name and signature, never the secret."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def key-env "CLOUD_ITONAMI_WORK_RECEIPT_KEY")
(def domain "cloud.itonami.app.work-receipt.v1\u0000")

(defn- hmac [secret value]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8)
                               "HmacSHA256"))
    (apply str (map #(format "%02x" (bit-and 0xff %))
                    (.doFinal mac
                              (.getBytes (str domain (pr-str value))
                                         StandardCharsets/UTF_8))))))

(defn sign [value]
  (when-let [secret (some-> (System/getenv key-env) str/trim not-empty)]
    {:algorithm :hmac-sha256 :key-ref key-env
     :value (hmac secret value)}))

(defn verify? [value signature]
  (let [secret (some-> (System/getenv key-env) str/trim not-empty)]
    (and secret (= :hmac-sha256 (:algorithm signature))
         (= key-env (:key-ref signature))
         (MessageDigest/isEqual
          (.getBytes (str (:value signature)) StandardCharsets/UTF_8)
          (.getBytes (hmac secret value) StandardCharsets/UTF_8)))))
