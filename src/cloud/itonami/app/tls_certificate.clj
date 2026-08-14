(ns cloud.itonami.app.tls-certificate
  "Certificates for the domains this deployment has proven it answers at.

  ADR-0043 measured whether a custom domain presented a valid certificate and
  called obtaining one \"an operator fact\". This is the other half, and the
  order of the two is the whole design: HTTP-01 works by the CA fetching
  `http://<domain>/.well-known/acme-challenge/<token>` from this process, so a
  certificate can only be ordered for a name whose DNS already points here —
  which is exactly what Gate B proved. The naming binding is the precondition,
  not a coincidence.

  ## Three things live here and the reasons they are separate

  - **the challenge surface.** A token this process answers for, briefly. Public
    by necessity: the CA holds no credential for this deployment.
  - **the certificate store.** The PEM chain and its expiry in `state.edn`; the
    private key never there. It goes through `*secret-store*`, which is the
    macOS Keychain the way `mail-account` and `agent-session` already use it,
    and which a test replaces.
  - **the listener.** An OPT-IN HTTPS server that selects a certificate by SNI.
    Without it an issued certificate would be a write-only field — the exact
    thing ADR-0043 spent itself removing — because nothing else in this process
    terminates TLS.

  ## What is deliberately not automatic

  Ordering is an owner's act, not a consequence of proving a name. A deployment
  behind a CDN or a reverse proxy already has certificates and should not be
  asking a CA for more; a rate limit spent on a name somebody else terminates is
  spent for nothing."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cloud.itonami.app.acme :as acme]
            [cloud.itonami.app.store :as store])
  (:import [java.io ByteArrayInputStream]
           [java.net InetSocketAddress]
           [java.security KeyFactory KeyStore]
           [java.security.cert CertificateFactory X509Certificate]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.time Duration Instant]
           [java.util Base64]
           [javax.net.ssl ExtendedSSLSession KeyManagerFactory SNIHostName
            SSLContext TrustManagerFactory X509ExtendedKeyManager]))

(def schema "cloud.itonami.app.tls-certificates.v1")

(def challenge-prefix "/.well-known/acme-challenge/")

;; How long a published challenge token answers. The CA fetches it within
;; seconds; anything longer is a URL handing out a key authorization to whoever
;; asks, long after it proves anything.
(def challenge-ttl (Duration/ofMinutes 10))

;; Renew this far before expiry. Let's Encrypt issues for 90 days and advises 30;
;; the sweep runs every 12 hours, so a certificate has 60 chances to be renewed
;; before it is a problem.
(def renew-within (Duration/ofDays 30))

(def keychain-service "cloud-itonami-app.tls")

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

;; ── where the private key lives, which is not state.edn ──────────────────────

(defn- keychain-write! [account secret]
  (let [{:keys [exit err]} (shell/sh "security" "add-generic-password"
                                     "-U" "-s" keychain-service "-a" account
                                     "-w" secret)]
    (when-not (zero? exit)
      (fail! :tls/keychain-write-failed (str "could not store the key: " err)
             {:account account}))
    account))

(defn- keychain-read [account]
  ;; One named item, never a sweep. `security dump-keychain` would expose every
  ;; unrelated credential this machine holds.
  (let [{:keys [exit out]} (shell/sh "security" "find-generic-password"
                                     "-s" keychain-service "-a" account "-w")]
    (when (zero? exit) (str/trim out))))

(def ^:dynamic *secret-store*
  "`{:write! (fn [account secret]) :read (fn [account])}`.

  A var rather than a protocol because the only thing that ever replaces it is a
  test, and the same shape `mail-account` already uses for passwords."
  {:write! keychain-write! :read keychain-read})

;; ── the challenge surface ────────────────────────────────────────────────────

(defn- challenges [] (get-in (store/snapshot) [:tls :challenges] {}))

(defn publish-challenge!
  "Answer `token` with `key-authorization` for a short window."
  [domain token key-authorization]
  (let [now (store/now)]
    (store/transact!
     (fn [current]
       (assoc-in current [:tls :challenges token]
                 {:token token
                  :domain domain
                  :key-authorization key-authorization
                  :created-at now
                  :expires-at (str (.plus (Instant/parse now) challenge-ttl))})))
    token))

(defn retract-challenge! [token]
  (store/transact! (fn [current] (update-in current [:tls :challenges] dissoc token)))
  token)

(defn key-authorization-for
  "What to serve at `<challenge-prefix><token>`, or nil.

  nil for an expired token as well as an unknown one. A window that is checked
  when the token is written and not when it is read is not a window."
  [token]
  (let [record (get (challenges) token)]
    (when (and record
               (.isBefore (Instant/now) (Instant/parse (:expires-at record))))
      (:key-authorization record))))

(defn challenge-token
  "The token in an ACME challenge path, or nil for any other path."
  [path]
  (when (and (string? path) (str/starts-with? path challenge-prefix))
    (let [token (subs path (count challenge-prefix))]
      (when (re-matches #"[A-Za-z0-9_-]{16,256}" token) token))))

;; ── the certificate store ────────────────────────────────────────────────────

(defn- certificates [] (get-in (store/snapshot) [:tls :certificates] {}))

(defn- parse-chain
  "The PEM chain as X.509 certificates, leaf first."
  [pem]
  (let [factory (CertificateFactory/getInstance "X.509")]
    (vec (.generateCertificates factory
                                (ByteArrayInputStream. (.getBytes (str pem) "UTF-8"))))))

(defn public-record [record]
  (select-keys record [:domain :status :issued-at :not-after :serial :error
                       :last-attempted-at]))

(defn certificate-for [domain] (get (certificates) domain))

(defn renewal-due?
  "Whether this certificate should be replaced now.

  A missing `:not-after` counts as due. A certificate whose expiry this process
  cannot read is not a certificate it can promise anything about, and treating
  the unreadable case as \"fine\" is how a silent failure becomes a green
  check."
  ([record] (renewal-due? record (Instant/now)))
  ([record now]
   ;; ONE decision, and it was two until a break test found that neither of them
   ;; could be told from the other: a missing `:not-after` and an unparseable
   ;; one both reached `true` by different routes, so inverting either changed
   ;; nothing any test could see. Collapsed so that `no readable expiry` is a
   ;; single fact, and breaking it breaks both cases.
   (let [expiry (try (some-> (:not-after record) Instant/parse)
                     (catch Exception _ nil))]
     (or (nil? expiry) (.isAfter (.plus now renew-within) expiry)))))

;; ── ordering ─────────────────────────────────────────────────────────────────

(defn- live-binding?
  "Whether this deployment has PROVEN it answers at `domain` (ADR-0043).

  The precondition for ordering, and not a formality: HTTP-01 asks the CA to
  fetch a URL at that name, so a name this process does not answer at produces
  a failed order and a rate limit spent for nothing."
  [domain]
  (boolean
   (some (fn [record]
           (and (= domain (:domain record)) (= :live (:status record))))
         (vals (get-in (store/snapshot) [:identity :domain-verifications] {})))))

(defn issue!
  "Order a certificate for `domain` and store it.

  `configuration` supplies `[:tls :directory-url]` and `[:tls :contact]`. The
  account key is created once and kept; the certificate key is fresh per order,
  because a certificate and the account that asked for it are different secrets
  with different lifetimes."
  [configuration domain]
  (when-not (live-binding? domain)
    (fail! :tls/domain-not-live
           (str domain " はこの deployment に解決していません。"
                "先にドメイン束縛を有効化してください。")
           {:domain domain}))
  (let [directory-url (or (get-in configuration [:tls :directory-url])
                          (fail! :tls/no-directory
                                 "ACME ディレクトリ URL が設定されていません。" {}))
        ;; One account key for this deployment, kept. A fresh account per order
        ;; would work and would also spend a `newAccount` and a rate limit each
        ;; time, and lose the contact address the CA uses for expiry warnings.
        stored-account ((:read *secret-store*) "acme-account")
        stored-public (get-in (store/snapshot) [:tls :account :public])
        account-key (if (and stored-account stored-public)
                      {:private stored-account :public stored-public}
                      (let [fresh (acme/generate-key)]
                        ((:write! *secret-store*) "acme-account" (:private fresh))
                        (store/transact!
                         (fn [current]
                           (assoc-in current [:tls :account :public] (:public fresh))))
                        fresh))
        certificate-key (acme/generate-key)
        started-at (store/now)]
    (try
      (let [state (acme/session {:directory-url directory-url
                                 :key account-key
                                 :contact (get-in configuration [:tls :contact])})
            _ (acme/register! state)
            pem (acme/order-certificate!
                 state [domain]
                 {:publish! publish-challenge!
                  :retract! retract-challenge!
                  :csr-key certificate-key})
            leaf ^X509Certificate (first (parse-chain pem))]
        ((:write! *secret-store*) (str "cert:" domain) (:private certificate-key))
        (store/transact!
         (fn [current]
           (-> current
               (assoc-in [:tls :certificates domain]
                         {:domain domain
                          :status :issued
                          :pem pem
                          :public (:public certificate-key)
                          :issued-at started-at
                          :serial (str (.getSerialNumber leaf))
                          :not-after (str (.toInstant (.getNotAfter leaf)))
                          :last-attempted-at started-at})
               (update :events conj
                       {:type :tls/certificate-issued :at started-at
                        :domain domain
                        :not-after (str (.toInstant (.getNotAfter leaf)))}))))
        (public-record (certificate-for domain)))
      (catch Exception e
        ;; The failure is RECORDED, with what the CA said. An order that failed
        ;; and left nothing behind is one the next sweep repeats blindly.
        (store/transact!
         (fn [current]
           (update-in current [:tls :certificates domain]
                      merge {:domain domain
                             :status :failed
                             :error (or (ex-message e) (str e))
                             :last-attempted-at started-at})))
        (throw e)))))

(defn renew-all!
  "Order replacements for the certificates that are close to expiry.

  Same evidence floor and same containment as the binding sweeps: `:scanned` is
  reported, and one domain's failure does not stop the others."
  [configuration]
  (let [targets (->> (certificates)
                     vals
                     (filter renewal-due?)
                     (filter #(live-binding? (:domain %)))
                     (sort-by :domain))]
    (reduce
     (fn [summary record]
       (try
         (issue! configuration (:domain record))
         (-> summary (update :scanned inc) (update :renewed conj (:domain record)))
         (catch Exception e
           (-> summary
               (update :scanned inc)
               (update :failed conj {:domain (:domain record)
                                     :error (or (ex-message e) (str e))})))))
     {:scanned 0 :renewed [] :failed []}
     targets)))

;; ── the listener that makes an issued certificate mean something ─────────────

(defn- key-store
  "Every issued certificate in one in-memory `KeyStore`, aliased by domain."
  []
  (let [ks (doto (KeyStore/getInstance "PKCS12") (.load nil nil))
        factory (KeyFactory/getInstance "EC")]
    (doseq [record (vals (certificates))
            :when (= :issued (:status record))
            :let [secret ((:read *secret-store*) (str "cert:" (:domain record)))]
            :when secret]
      (.setKeyEntry ks (:domain record)
                    (.generatePrivate
                     factory
                     (PKCS8EncodedKeySpec.
                      (.decode (Base64/getUrlDecoder) ^String secret)))
                    (char-array 0)
                    (into-array X509Certificate (parse-chain (:pem record)))))
    ks))

(defn sni-key-manager
  "A key manager that picks the certificate the client ASKED for.

  The default one picks by key type and would hand every client whichever entry
  the store happened to yield first — a certificate for the wrong name, which
  every browser refuses and no log explains. `getRequestedServerNames` is the
  question the client actually asked."
  [^KeyStore ks]
  (let [factory (doto (KeyManagerFactory/getInstance
                       (KeyManagerFactory/getDefaultAlgorithm))
                  (.init ks (char-array 0)))
        base ^X509ExtendedKeyManager (first (.getKeyManagers factory))]
    (proxy [X509ExtendedKeyManager] []
      (getClientAliases [t i] (.getClientAliases base t i))
      (chooseClientAlias [t i s] (.chooseClientAlias base t i s))
      (chooseEngineClientAlias [t i e] (.chooseEngineClientAlias base t i e))
      (getServerAliases [t i] (.getServerAliases base t i))
      (chooseServerAlias [t i s] (.chooseServerAlias base t i s))
      (getCertificateChain [alias] (.getCertificateChain base alias))
      (getPrivateKey [alias] (.getPrivateKey base alias))
      (chooseEngineServerAlias [t i engine]
        (let [session (.getHandshakeSession engine)
              requested (when (instance? ExtendedSSLSession session)
                          (.getRequestedServerNames ^ExtendedSSLSession session))
              host (some (fn [name]
                           (when (instance? SNIHostName name)
                             (.getAsciiName ^SNIHostName name)))
                         requested)]
          ;; No SNI, or a name this deployment holds no certificate for, is
          ;; answered by refusing rather than by substituting. A handshake that
          ;; fails here is legible; one that succeeds with the wrong name is not.
          (when (and host (.getPrivateKey base host)) host))))))

(defn ssl-context
  "An `SSLContext` serving every issued certificate, chosen by SNI."
  []
  (doto (SSLContext/getInstance "TLS")
    (.init (into-array javax.net.ssl.KeyManager [(sni-key-manager (key-store))])
           (.getTrustManagers
            (doto (TrustManagerFactory/getInstance
                   (TrustManagerFactory/getDefaultAlgorithm))
              (.init ^KeyStore (cast KeyStore nil))))
           nil)))

(defn https-server
  "An `HttpsServer` on `port` serving `handler`, or nil when not enabled.

  Opt-in (`[:tls :https :enabled?]`, default false) because this application
  binds loopback by default and a deployment behind a terminator neither needs
  this nor should be asked to."
  [configuration handler]
  (when (get-in configuration [:tls :https :enabled?] false)
    (let [port (get-in configuration [:tls :https :port] 8443)
          host (get-in configuration [:tls :https :host] "0.0.0.0")
          server (com.sun.net.httpserver.HttpsServer/create
                  (InetSocketAddress. ^String host (int port)) 0)]
      (.setHttpsConfigurator
       server
       (proxy [com.sun.net.httpserver.HttpsConfigurator] [(ssl-context)]
         (configure [params]
           ;; Rebuilt per connection, so a certificate issued while the process
           ;; is up is served without a restart. A cached context is why
           ;; "renewal worked but the old certificate is still being served" is
           ;; the classic ACME operations bug.
           (.setSSLParameters params (.getDefaultSSLParameters (ssl-context))))))
      (.createContext server "/" handler)
      (.setExecutor server nil)
      (.start server)
      server)))
