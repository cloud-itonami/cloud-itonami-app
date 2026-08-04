(ns cloud.itonami.app.kotobase-federation
  "Passkey-rooted, server-attested hand-off to authn.kotobase.net.

  WebAuthn P-256 credentials cannot sign the Ed25519 CACAO wire format used by
  Kotobase. This module signs a short assertion only after a local human
  passkey session and names the P-256 did:key as its external subject."
  (:require [cacao.core :as cacao]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ed25519.core :as ed])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.kotobase-federation.v1")
(def audience "https://authn.kotobase.net")
(def session-resource "kotoba://can/kotobase:session")
(def datomic-query-resource "kotoba://can/kotobase:datomic-query")
(def git-read-resource "kotoba://can/kotobase:git-read")
(def subject-prefix "urn:kotobase:federation:cloud-itonami:subject:")
(def assertion-seconds 120)

(defn subject-resource [subject-did]
  (str subject-prefix subject-did))

(defn- key-file []
  (io/file (config/data-dir) "kotobase-federation-issuer.key"))

(defn- read-seed []
  (let [f (key-file)]
    (when (.isFile f)
      (.decode (Base64/getDecoder) ^String (str/trim (slurp f))))))

(defn- write-seed! [^bytes seed]
  (let [f (key-file)]
    (.mkdirs (.getParentFile f))
    (spit f (.encodeToString (Base64/getEncoder) seed))
    (doto f (.setReadable false false) (.setReadable true true)
            (.setWritable false false) (.setWritable true true))
    seed))

(defn issuer-seed []
  (or (read-seed)
      (write-seed! (let [seed (byte-array 32)]
                     (.nextBytes (SecureRandom.) seed)
                     seed))))

(defn issuer-did []
  (ed/did-key-from-seed (issuer-seed)))

(defn- session-subject-did [session]
  (get-in (store/snapshot) [:identity :users (:user-id session) :did]))

(defn mint-assertion
  ([session] (mint-assertion session (Instant/now)))
  ([session now]
   (when-not (= :passkey (or (:kind session) :passkey))
     (throw (ex-info "Kotobase 連携には Passkey session が必要です。"
                     {:type :kotobase-federation/passkey-required})))
   (let [subject-did (session-subject-did session)]
     (when-not (and (string? subject-did)
                    (str/starts-with? subject-did "did:key:z"))
       (throw (ex-info "Kotobase 連携に使える Passkey DID がありません。"
                       {:type :kotobase-federation/no-subject-did})))
     (let [iat (Instant/ofEpochSecond (.getEpochSecond ^Instant now))
           exp (.plusSeconds iat assertion-seconds)
           resources [session-resource datomic-query-resource git-read-resource
                      (subject-resource subject-did)]
           minted (cacao/mint {:seed (issuer-seed) :aud audience
                               :iat (str iat) :exp (str exp)
                               :nonce (str (UUID/randomUUID))
                               :domain "cloud.itonami" :resources resources})]
       {:schema schema :cacao_b64 (:cacao-b64 minted)
        :issuer (:iss minted) :subject subject-did :audience audience
        :resources resources :expires_at (str exp)
        :exchange_url (str audience "/v1/federation/session")}))))
