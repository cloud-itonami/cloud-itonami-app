(ns cloud.itonami.app.tls-certificate-test
  "The certificate surface (ADR-0045).

  Three things are worth pinning and one of them needs a real handshake. The
  ACME challenge is a URL this process answers for a few minutes and must stop
  answering; renewal has to treat an unreadable expiry as due rather than as
  fine; and SNI selection has to hand a client the certificate it ASKED for,
  because the default key manager picks by key type and would serve whichever
  entry the store yielded first — a certificate for the wrong name, which every
  browser refuses and no log explains.

  The two certificates below are self-signed fixtures, generated once with
  `openssl` and dated far out so they do not rot. Nothing here reaches a CA."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.tls-certificate :as tls]
            [cloud.itonami.app.store :as store])
  (:import [java.io ByteArrayInputStream]
           [java.security KeyStore]
           [java.security.cert CertificateFactory X509Certificate]
           [java.time Duration Instant]
           [javax.net.ssl SNIHostName SSLContext SSLParameters SSLSocket
            TrustManagerFactory]))

(def ^:private a-key "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgZdTHDq6iPqHZH3F7VCfSggesFjU9bi4vEJgcTblN_MWhRANCAARFyp6cGocse3_QNOoyBlbEuWEyoya3SVT3alcoGZ6lzjAPz3FN8H9HbLoY2A_-sGKruyzbHbwOCRlwqzo0f3AY")
(def ^:private a-pem
  (str/join "\n"
  ["-----BEGIN CERTIFICATE-----"
  "MIIBlDCCATugAwIBAgIUWJUkhOg4vnY4kbAXN4YKQU+NQ8swCgYIKoZIzj0EAwIw"
  "FDESMBAGA1UEAwwJYS5leGFtcGxlMCAXDTI2MDgxNDA5NTAxM1oYDzIxMjYwNzIx"
  "MDk1MDEzWjAUMRIwEAYDVQQDDAlhLmV4YW1wbGUwWTATBgcqhkjOPQIBBggqhkjO"
  "PQMBBwNCAARFyp6cGocse3/QNOoyBlbEuWEyoya3SVT3alcoGZ6lzjAPz3FN8H9H"
  "bLoY2A/+sGKruyzbHbwOCRlwqzo0f3AYo2kwZzAdBgNVHQ4EFgQUR8qMo6jjepWB"
  "z8dQbcmLzMV9HPMwHwYDVR0jBBgwFoAUR8qMo6jjepWBz8dQbcmLzMV9HPMwDwYD"
  "VR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglhLmV4YW1wbGUwCgYIKoZIzj0EAwID"
  "RwAwRAIgZyk8o8pctsBnik/rPLMGcUwWeRMXM04cPyu8sGyi4k8CIA/X+nmKjmX8"
  "PxfRv4ajcsPqcgOIrbeO2a1RbFtIN5Xi"
  "-----END CERTIFICATE-----"]))

(def ^:private b-key "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgl4QSuO2q_1CrTKTDMqkE7UOuJ-kLDMbiO2Qi68fHel2hRANCAARWCxOnJ5u5Of31uLvFiK0JsOr88Lfv-iDGENPYvrJMPV7_JvQCrbAlvN7hkCT4USgR6gFj--iyjsVVrI4pr85c")
(def ^:private b-pem
  (str/join "\n"
  ["-----BEGIN CERTIFICATE-----"
  "MIIBljCCATugAwIBAgIULJx+43Gg2czLtQn/xgyNcgM0qrQwCgYIKoZIzj0EAwIw"
  "FDESMBAGA1UEAwwJYi5leGFtcGxlMCAXDTI2MDgxNDA5NTAxM1oYDzIxMjYwNzIx"
  "MDk1MDEzWjAUMRIwEAYDVQQDDAliLmV4YW1wbGUwWTATBgcqhkjOPQIBBggqhkjO"
  "PQMBBwNCAARWCxOnJ5u5Of31uLvFiK0JsOr88Lfv+iDGENPYvrJMPV7/JvQCrbAl"
  "vN7hkCT4USgR6gFj++iyjsVVrI4pr85co2kwZzAdBgNVHQ4EFgQUWDFreWruch3v"
  "yi5bsI+obd7nZFswHwYDVR0jBBgwFoAUWDFreWruch3vyi5bsI+obd7nZFswDwYD"
  "VR0TAQH/BAUwAwEB/zAUBgNVHREEDTALggliLmV4YW1wbGUwCgYIKoZIzj0EAwID"
  "SQAwRgIhAOaENqcMad3MP6xukxjJ1KqsSOOq0GgkfJPdYpgaDK2RAiEA23IiBE9U"
  "hC0jblLP3GDZsijc80+0DO6imO+hPaDDcKA="
  "-----END CERTIFICATE-----"]))

(defn- with-store
  "A fresh store and an in-memory secret store, so no test touches the Keychain."
  [certificates run]
  (let [previous @store/state
        secrets (atom {"cert:a.example" a-key "cert:b.example" b-key})]
    (try
      (reset! store/state (assoc (store/initial-state) :tls {:certificates certificates}))
      (binding [tls/*secret-store* {:read (fn [account] (get @secrets account))
                                    :write! (fn [account secret]
                                              (swap! secrets assoc account secret)
                                              account)}]
        (run))
      (finally (reset! store/state previous)))))

(def ^:private issued
  {"a.example" {:domain "a.example" :status :issued :pem a-pem
                :not-after "2126-01-01T00:00:00Z"}
   "b.example" {:domain "b.example" :status :issued :pem b-pem
                :not-after "2126-01-01T00:00:00Z"}})

;; ── the challenge surface ────────────────────────────────────────────────────

(deftest a-challenge-path-yields-its-token-and-nothing-else-does
  (is (= "abcdefghijklmnop"
         (tls/challenge-token "/.well-known/acme-challenge/abcdefghijklmnop")))
  (is (nil? (tls/challenge-token "/.well-known/acme-challenge/short")))
  (is (nil? (tls/challenge-token "/.well-known/acme-challenge/../../etc/passwd"))
      "a token is a token, not a path")
  (is (nil? (tls/challenge-token "/.well-known/did.json")))
  (is (nil? (tls/challenge-token "/")))
  (is (nil? (tls/challenge-token nil))))

(deftest a-published-challenge-answers-briefly-and-then-stops
  (with-store
    {}
    (fn []
      (let [token "token-0123456789abcdef"]
        (is (nil? (tls/key-authorization-for token))
            "an unknown token is not something to guess at")
        (tls/publish-challenge! "a.example" token "token.thumbprint")
        (is (= "token.thumbprint" (tls/key-authorization-for token)))
        (testing "the window is checked on READ, not only when it was written"
          (store/transact!
           (fn [current]
             (assoc-in current [:tls :challenges token :expires-at]
                       (str (.minus (Instant/now) (Duration/ofMinutes 1))))))
          (is (nil? (tls/key-authorization-for token))))
        (testing "and retraction removes it outright"
          (tls/publish-challenge! "a.example" token "token.thumbprint")
          (tls/retract-challenge! token)
          (is (nil? (tls/key-authorization-for token))))))))

;; ── renewal ──────────────────────────────────────────────────────────────────

(deftest an-expiry-this-process-cannot-read-counts-as-due
  (let [now (Instant/parse "2026-08-14T00:00:00Z")]
    (is (false? (boolean (tls/renewal-due? {:not-after "2026-12-01T00:00:00Z"} now)))
        "months out — nothing to do")
    (is (true? (boolean (tls/renewal-due? {:not-after "2026-09-01T00:00:00Z"} now)))
        "inside the renewal window")
    (is (true? (boolean (tls/renewal-due? {:not-after nil} now)))
        "a certificate with no recorded expiry is not one to promise anything about")
    (is (true? (boolean (tls/renewal-due? {:not-after "not a timestamp"} now)))
        "and neither is one whose expiry will not parse")))

;; ── SNI ──────────────────────────────────────────────────────────────────────

(defn- trusting-context
  "A client context that trusts exactly these two fixtures."
  []
  (let [factory (CertificateFactory/getInstance "X.509")
        ks (doto (KeyStore/getInstance "PKCS12") (.load nil nil))]
    (doseq [[alias pem] [["a" a-pem] ["b" b-pem]]]
      (.setCertificateEntry
       ks alias
       (.generateCertificate factory (ByteArrayInputStream. (.getBytes ^String pem "UTF-8")))))
    (doto (SSLContext/getInstance "TLS")
      (.init nil
             (.getTrustManagers (doto (TrustManagerFactory/getInstance
                                       (TrustManagerFactory/getDefaultAlgorithm))
                                  (.init ks)))
             nil))))

(defn- served-subject
  "The subject of the certificate this server presents for `sni`, or nil when
  the handshake is refused."
  [port sni]
  (try
    (let [socket ^SSLSocket (.createSocket (.getSocketFactory (trusting-context))
                                           "127.0.0.1" (int port))]
      (try
        (.setSSLParameters socket (doto ^SSLParameters (.getSSLParameters socket)
                                    (.setServerNames [(SNIHostName. ^String sni)])))
        (.startHandshake socket)
        (str (.getSubjectX500Principal
              ^X509Certificate (first (.getPeerCertificates (.getSession socket)))))
        (finally (.close socket))))
    (catch Exception _ nil)))

(deftest a-client-is-served-the-certificate-it-asked-for
  (with-store
    issued
    (fn []
      (let [server (tls/https-server
                    {:tls {:https {:enabled? true :host "127.0.0.1" :port 0}}}
                    (reify com.sun.net.httpserver.HttpHandler
                      (handle [_ exchange]
                        (.sendResponseHeaders exchange 204 -1)
                        (.close exchange))))]
        (is (some? server) "the listener is opt-in and this configuration opted in")
        (try
          (let [port (.getPort (.getAddress server))]
            (is (= "CN=a.example" (served-subject port "a.example")))
            (is (= "CN=b.example" (served-subject port "b.example"))
                "the default key manager would have served the first entry to both")
            (testing "a name this deployment holds no certificate for is refused"
              ;; Refused rather than substituted. A handshake that fails here is
              ;; legible; one that succeeds with the wrong name is not.
              (is (nil? (served-subject port "unknown.example")))))
          (finally (.stop server 0)))))))

(deftest the-listener-is-off-unless-a-deployment-asks-for-it
  (with-store
    issued
    (fn []
      (is (nil? (tls/https-server {} (reify com.sun.net.httpserver.HttpHandler
                                       (handle [_ _] nil))))
          "this app binds loopback; a TLS socket is not something to open by default"))))
