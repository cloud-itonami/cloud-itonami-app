(ns cloud.itonami.app.kotobase-federation
  "Passkey-rooted, server-attested hand-off to authn.kotobase.net.

  WebAuthn P-256 credentials cannot sign the Ed25519 CACAO wire format used by
  Kotobase. This module signs a short assertion only after a local human
  passkey session and names the stable Principal separately from the active
  controller DID."
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
(def targets
  "The only authentication origins this issuer may hand a Principal to.

  Each target is a distinct WebAuthn RP. They receive the same stable
  Principal and active controller DID, never the raw itonami.cloud Passkey.
  Complete origins live here so request data cannot turn this signer into an
  open federation issuer."
  {:kotobase {:audience "https://auth.kotobase.net"
              :exchange-url "https://auth.kotobase.net/v1/federation/session"
              :return-to (str "https://auth.kotobase.net/sign-in?return_to="
                              "https%3A%2F%2Fkotobase.net%2F")}
   :murakumo {:audience "https://auth.murakumo.cloud"
              :exchange-url "https://auth.murakumo.cloud/v1/federation/session"
              :return-to (str "https://auth.murakumo.cloud/sign-in?return_to="
                              "https%3A%2F%2Fmurakumo.cloud%2F")}})

;; Backward-compatible public value for callers that only knew Kotobase.
(def audience (get-in targets [:kotobase :audience]))
(def session-resource "kotoba://can/kotobase:session")
(def datomic-query-resource "kotoba://can/kotobase:datomic-query")
(def git-read-resource "kotoba://can/kotobase:git-read")
(def subject-prefix "urn:kotobase:federation:cloud-itonami:subject:")
(def controller-prefix "urn:kotobase:federation:cloud-itonami:controller:")
(def assertion-seconds 120)
(def ^:private seed-lock (Object.))

(defn subject-resource [principal-id]
  (str subject-prefix principal-id))

(defn controller-resource [controller-did]
  (str controller-prefix controller-did))

(defn- target! [target]
  (let [target (cond
                 (keyword? target) target
                 (string? target) (keyword target)
                 (nil? target) :kotobase
                 :else nil)]
    (or (get targets target)
        (throw (ex-info "接続先が許可されていません。"
                        {:type :kotobase-federation/invalid-target
                         :target target})))))

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
  (locking seed-lock
    (or (read-seed)
        (write-seed! (let [seed (byte-array 32)]
                       (.nextBytes (SecureRandom.) seed)
                       seed)))))

(defn issuer-did []
  (ed/did-key-from-seed (issuer-seed)))

(defn- session-principal-id [session]
  (let [user (get-in (store/snapshot) [:identity :users (:user-id session)])]
    (or (:principal-id user) (:did user))))

(defn- session-controller-did [session]
  (or (:active-did session)
      (get-in (store/snapshot) [:identity :users (:user-id session) :did])))

(defn- passkey-rooted-session? [session]
  (or (= :passkey (:kind session))
      (and (= :federated (:kind session))
           (= :itonami-cloud (:issued-via session))
           (= :itonami-cloud (:authn-provider session))
           (= :phishing-resistant (:authn-level session))
           (= :authenticated (:authn-decision session))
           (= [:webauthn] (:authn-factors session)))))

(defn mint-assertion
  ([session] (mint-assertion session (Instant/now)))
  ([session target-or-now]
   (if (instance? Instant target-or-now)
     (mint-assertion session :kotobase target-or-now)
     (mint-assertion session target-or-now (Instant/now))))
  ([session target now]
   (when-not (passkey-rooted-session? session)
     (throw (ex-info "サービス連携には Passkey session が必要です。"
                     {:type :kotobase-federation/passkey-required})))
   (let [target-key (if (keyword? target) target (keyword (or target "kotobase")))
         {:keys [audience exchange-url return-to]} (target! target-key)
         principal-id (session-principal-id session)
         controller-did (session-controller-did session)]
     (when-not (and (string? principal-id)
                    (or (str/starts-with? principal-id "did:")
                        (str/starts-with? principal-id "urn:kotoba:principal:")))
       (throw (ex-info "Kotobase 連携に使える Principal がありません。"
                       {:type :kotobase-federation/no-principal})))
     (when-not (and (string? controller-did)
                    (str/starts-with? controller-did "did:"))
       (throw (ex-info "Kotobase 連携に使える controller DID がありません。"
                       {:type :kotobase-federation/no-controller-did})))
     (let [iat (Instant/ofEpochSecond (.getEpochSecond ^Instant now))
           exp (.plusSeconds iat assertion-seconds)
           resources [session-resource datomic-query-resource git-read-resource
                      (subject-resource principal-id)
                      (controller-resource controller-did)]
           minted (cacao/mint {:seed (issuer-seed) :aud audience
                               :iat (str iat) :exp (str exp)
                               :nonce (str (UUID/randomUUID))
                               :domain "cloud.itonami" :resources resources})]
       {:schema schema :cacao_b64 (:cacao-b64 minted)
        :target (name target-key)
        :issuer (:iss minted) :subject principal-id
        :controller controller-did :audience audience
        :resources resources :expires_at (str exp)
        :exchange_url exchange-url
        :return_to return-to}))))
