(ns cloud.itonami.app.awai-manifest
  "Validate the immutable public manifest block named by an Awai app release.

  The publisher's L5 graph remains the authoring/history surface. A release
  exports the exact :kotoba.app/* entity as one canonical DAG-CBOR block and
  signs that block CID in app/release. Public hosts read this release artifact;
  they do not need the publisher's private graph decryption key."
  (:require [ipld.core :as ipld]
            [kotoba.protocol.app :as app]
            [kotoba.protocol.cid :as cid]
            [kotoba.protocol.vocab :as vocab]))

(def schema "kotoba.app.manifest.v1")
(def maximum-manifest-bytes (* 256 1024))
(def maximum-manifest-attributes 32)

(defn- byte-array? [value]
  #?(:clj (bytes? value)
     :cljs (instance? js/Uint8Array value)))

(defn- byte-count [value]
  #?(:clj (alength value)
     :cljs (.-length value)))

(defn- byte-values [value]
  (mapv #(bit-and (int %) 0xff) (seq value)))

(defn- keywordize-map-keys [value]
  (cond
    (map? value)
    (reduce-kv (fn [out key child]
                 (if (string? key)
                   (assoc out (keyword key) (keywordize-map-keys child))
                   (reduced nil)))
               {} value)

    (vector? value)
    (mapv keywordize-map-keys value)

    :else value))

(defn- valid-dag-cbor-cid? [value]
  (let [{:keys [codec error]} (when (string? value) (cid/parse-cid value))]
    (and (not error) (= :dag-cbor codec))))

(defn- valid-entity? [entity expected-app-id]
  (and (map? entity)
       (<= (count entity) maximum-manifest-attributes)
       (every? (fn [attribute]
                 (and (keyword? attribute)
                      (= "kotoba.app" (namespace attribute))
                      (vocab/known-attribute? attribute)))
               (keys entity))
       (= expected-app-id (:kotoba.app/id entity))
       (empty? (app/validate-manifest entity))
       (vocab/cid? (:kotoba.app/bundle-cid entity))))

(defn verify-block
  "Verify and decode one public release manifest block.

  `manifest-cid` must be the CID from an independently verified Awai
  app/release record. This function checks that the bytes actually have that
  canonical DAG-CBOR CID and that the closed wire envelope contains a valid
  Kotoba app entity for `expected-app-id`. The Awai adapter separately
  authenticates the release record and its publisher signature."
  [manifest-cid bytes expected-app-id]
  (let [cid-result (when (string? manifest-cid)
                     (cid/parse-cid manifest-cid))]
    (cond
      (not (and (map? cid-result)
                (not (:error cid-result))
                (= :dag-cbor (:codec cid-result))))
      {:decision :refuse :error :manifest-cid-must-be-dag-cbor}

      (not (and (string? expected-app-id)
                (vocab/reverse-dns-id? expected-app-id)))
      {:decision :refuse :error :invalid-app-id}

      (not (and (byte-array? bytes)
                (<= 1 (byte-count bytes) maximum-manifest-bytes)))
      {:decision :refuse :error :invalid-manifest-bytes}

      (not= manifest-cid (ipld/cid bytes))
      {:decision :refuse :error :manifest-cid-mismatch}

      :else
      (try
        (let [wire (ipld/decode bytes)
              canonical-bytes (ipld/encode wire)
              wire-keys (set (keys wire))
              entity (keywordize-map-keys (get wire "entity"))]
          (cond
            (not= (byte-values bytes) (byte-values canonical-bytes))
            {:decision :refuse :error :noncanonical-manifest-cbor}

            (not= #{"schema" "entity"} wire-keys)
            {:decision :refuse :error :invalid-manifest-envelope}

            (not= schema (get wire "schema"))
            {:decision :refuse :error :unsupported-manifest-schema}

            (not (valid-entity? entity expected-app-id))
            {:decision :refuse :error :invalid-app-manifest}

            :else
            {:decision :verified
             :manifest-cid manifest-cid
             :app-id expected-app-id
             :manifest entity}))
        (catch #?(:clj Exception :cljs :default) _
          {:decision :refuse :error :invalid-manifest-cbor})))))
