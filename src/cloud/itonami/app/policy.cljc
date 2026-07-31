(ns cloud.itonami.app.policy
  #?(:clj
     (:require [clojure.edn :as edn]
               [clojure.java.io :as io]
               [kotoba.compiler.core :as compiler]
               [kotoba.wasm-exec :as wasm-exec]))
  #?(:clj
     (:import [java.math BigInteger]
              [java.nio.charset StandardCharsets]
              [java.nio.file Files]
              [java.security KeyFactory MessageDigest Signature]
              [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
              [java.util Base64])))

#?(:clj
   (do
     (def artifact-schema "cloud.itonami.provider-policy-artifact.v1")
     (def artifact-filename "provider-policy.wasm")
     (def manifest-filename "provider-policy.edn")
     (def signature-filename "provider-policy.ed25519")

     (defn- policy-source []
       (or (some-> "cloud/itonami/app/policy.kotoba" io/resource slurp)
           (throw (ex-info "Provider policy source is missing."
                           {:type :policy/source-unavailable}))))

     (defn compile-policy-wasm []
       (let [compiled
             (compiler/compile-source
              (policy-source) :wasm32-kotoba-v1 {})]
         (when-not (bytes? (:bytes compiled))
           (throw
            (ex-info "Provider policy Wasm could not be compiled."
                     {:type :policy/wasm-unavailable})))
         (:bytes compiled)))

     (defn- sha256 [bytes]
       (format "%064x"
               (BigInteger. 1
                            (.digest (MessageDigest/getInstance "SHA-256")
                                     bytes))))

     (defn artifact-manifest [wasm key-id]
       (sorted-map
        :schema artifact-schema
        :artifact artifact-filename
        :artifact-sha256 (sha256 wasm)
        :source-sha256
        (sha256 (.getBytes (policy-source) StandardCharsets/UTF_8))
        :target "wasm32-kotoba-v1"
        :key-id key-id))

     (defn- manifest-bytes [manifest]
       (.getBytes (pr-str (into (sorted-map) manifest))
                  StandardCharsets/UTF_8))

     (defn sign-manifest [manifest private-key-base64]
       (let [private-key
             (.generatePrivate
              (KeyFactory/getInstance "Ed25519")
              (PKCS8EncodedKeySpec.
               (.decode (Base64/getDecoder) private-key-base64)))
             signer (Signature/getInstance "Ed25519")]
         (.initSign signer private-key)
         (.update signer (manifest-bytes manifest))
         (.encodeToString (Base64/getEncoder) (.sign signer))))

     (defn verify-manifest?
       [manifest signature-base64 public-key-base64]
       (try
         (let [public-key
               (.generatePublic
                (KeyFactory/getInstance "Ed25519")
                (X509EncodedKeySpec.
                 (.decode (Base64/getDecoder) public-key-base64)))
               verifier (Signature/getInstance "Ed25519")]
           (.initVerify verifier public-key)
           (.update verifier (manifest-bytes manifest))
           (.verify verifier
                    (.decode (Base64/getDecoder) signature-base64)))
         (catch Exception _ false)))

     (defn write-signed-artifact!
       [directory private-key-base64 key-id]
       (let [directory (io/file directory)
             wasm (compile-policy-wasm)
             manifest (artifact-manifest wasm key-id)
             signature (sign-manifest manifest private-key-base64)]
         (.mkdirs directory)
         (Files/write (.toPath (io/file directory artifact-filename)) wasm
                      (make-array java.nio.file.OpenOption 0))
         (spit (io/file directory manifest-filename) (pr-str manifest))
         (spit (io/file directory signature-filename) signature)
         manifest))

     (defn load-signed-artifact!
       [directory public-key-base64 expected-key-id]
       (let [directory (io/file directory)
             wasm-file (io/file directory artifact-filename)
             manifest (edn/read-string
                       (slurp (io/file directory manifest-filename)))
             signature (slurp (io/file directory signature-filename))
             wasm (Files/readAllBytes (.toPath wasm-file))]
         (when-not (and (= artifact-schema (:schema manifest))
                        (= artifact-filename (:artifact manifest))
                        (= "wasm32-kotoba-v1" (:target manifest))
                        (= expected-key-id (:key-id manifest))
                        (= (sha256 wasm) (:artifact-sha256 manifest))
                        (= (sha256 (.getBytes (policy-source)
                                             StandardCharsets/UTF_8))
                           (:source-sha256 manifest))
                        (verify-manifest? manifest signature
                                          public-key-base64))
           (throw
            (ex-info "Provider policy artifact attestation failed."
                     {:type :policy/attestation-failed
                      :directory (.getPath directory)
                      :key-id (:key-id manifest)})))
         wasm))

     (defn- configured-policy-wasm []
       (if-let [directory
                (some-> (System/getenv
                         "CLOUD_ITONAMI_POLICY_ARTIFACT_DIR")
                        not-empty)]
         (let [public-key
               (some-> (System/getenv
                        "CLOUD_ITONAMI_POLICY_TRUSTED_PUBLIC_KEY")
                       not-empty)
               key-id
               (some-> (System/getenv
                        "CLOUD_ITONAMI_POLICY_TRUSTED_KEY_ID")
                       not-empty)]
           (when-not (and public-key key-id)
             (throw
              (ex-info "Signed policy artifact trust is not configured."
                       {:type :policy/trust-unavailable})))
           (load-signed-artifact! directory public-key key-id))
         (compile-policy-wasm)))

     (defonce ^:private policy-wasm
       (delay (configured-policy-wasm)))

     (defn- flag [value]
       (if value 1 0))

     (defn select-provider-tier
       "Execute the provider decision in capability-free Kotoba Wasm.

       A missing artifact, compile failure, trap, or unexpected result denies
       the provider. The Clojure layer supplies facts but does not reproduce
       the decision."
       [local-ready cloud-requested cloud-enabled]
       (try
         ;; The release-pinned Kotoba exposes instantiate but predates its
         ;; run-export convenience wrapper. Chicory's typed export invocation
         ;; is the same narrow mechanism used by that wrapper.
         (let [instance
               (wasm-exec/instantiate
                @policy-wasm [] {:kotoba.policy/capabilities #{}
                                 :kotoba.policy/fuel 10000})
               function (.export instance "select-provider-tier")
               args (long-array
                     (map flag
                          [local-ready cloud-requested cloud-enabled]))
               tier (aget ^longs (.apply function args) 0)]
           (if (contains? #{0 1 2} tier) tier 0))
         (catch Exception _ 0))))
   :cljs
   (defn select-provider-tier [& _]
     ;; The browser surface has no provider authority.
     0))

(defn loopback-host? [host]
  (contains? #{"127.0.0.1" "localhost" "::1"} host))

(defn provider-allowed?
  "Ask the tendered Kotoba Wasm policy whether this enabled provider tier is
  admitted. There is no implicit fallback from local to cloud."
  [config provider]
  (let [enabled? (true? (:enabled? provider))
        tier (select-provider-tier
              (and enabled? (true? (:local? provider)))
              (and enabled? (not (:local? provider)))
              (and (get-in config [:routing :cloud-enabled?])
                   (get-in config [:privacy :allow-cloud-without-review?])))]
    (if (:local? provider) (= 1 tier) (= 2 tier))))

(defn select-provider [config requested-id]
  (let [provider-id (or requested-id
                        (get-in config [:routing :default-provider]))]
    (some #(when (and (= provider-id (:id %))
                      (provider-allowed? config %))
             %)
          (:providers config))))
