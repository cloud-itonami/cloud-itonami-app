(ns cloud.itonami.app.credential-trust
  "Verifying a credential issued by SOMEONE ELSE.

  `cloud.itonami.app.credential/verify` only accepts credentials this app itself
  issued: its resolver refuses any `verificationMethod` that is not this app's own
  key. That is correct for a membership credential we minted, and useless for the
  case enterprise use actually needs — a partner organization presents a
  credential and we have to decide whether to believe it.

  ## The trust list is not hardening, it IS the trust model

  `did:web` resolution alone establishes nothing. Anyone can publish a DID
  document at a domain they control and sign a credential with the matching key;
  verifying that signature proves only \"whoever controls that domain said this\".
  Without deciding IN ADVANCE which domains may assert what, a verifier that
  fetches the key named by the credential is checking the forger's arithmetic
  against the forger's own key.

  So `:trusted-issuers` is deny-by-default and there is no way to switch it off.
  `nil` accepts nothing; `[]` accepts nothing and says so deliberately — an
  absent policy is not a permissive policy, the same reading
  `:authorities :voice :allowed-callers` already documents.

  ## Revocation we cannot check is not revocation we can ignore

  A signature proves the issuer said it. It does not prove they still say it. If
  an external credential carries a `credentialStatus` we cannot resolve, this
  returns `:valid? false` with `:revocation :unchecked` rather than reporting a
  good signature as a usable credential. Fetching a stranger's status list is a
  second network dependency and a second trust question, and is deliberately not
  done here.

  ## What is fetched, and the limits on it

  Exactly one URL per issuer domain: `https://<domain>/.well-known/did.json`,
  derived by `did.core/did-web-url` rather than assembled here. HTTPS only, a
  hard timeout, a response size cap, and a refusal to talk to an address that
  resolves inside the network this process sits in — because the app binds
  loopback and \"fetch a URL named by attacker-supplied content\" is otherwise an
  SSRF primitive pointed at everything else on the machine."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [data-integrity.core :as di]
            [did.core :as did]
            [ed25519.core :as ed])
  (:import [java.net InetAddress URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def schema "cloud.itonami.app.credential-trust.v1")

(def default-fetch-timeout-seconds 5)
;; A DID document is a handful of keys. 64 KiB is already generous, and a cap is
;; what stops a trusted-but-compromised domain from streaming until this process
;; runs out of heap.
(def default-max-document-bytes 65536)
(def default-cache-seconds 3600)

(defn- fail! [type msg data]
  (throw (ex-info msg (assoc data :type type))))

(defn- settings [configuration]
  (get configuration :credentials {}))

;; ── the trust list ───────────────────────────────────────────────────────────

(defn trusted-issuers
  "The configured issuer domains. nil and [] both mean \"none\"."
  [configuration]
  (set (or (:trusted-issuers (settings configuration)) [])))

(defn trusted-issuer?
  "Whether `domain` is one this deployment has decided to believe.

  Exact match on the full domain, lower-cased. No suffix matching: allowing
  `*.example.com` would mean trusting whoever can create a subdomain there, which
  in a great many organizations is a different set of people than the ones the
  operator meant to trust."
  [configuration domain]
  (contains? (trusted-issuers configuration)
             (some-> domain str str/trim str/lower-case not-empty)))

;; ── the network boundary ─────────────────────────────────────────────────────

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             ;; Do not follow redirects. A redirect is the trusted domain handing
             ;; the fetch to an address the operator never approved, which is
             ;; precisely what the trust list exists to prevent.
             (.followRedirects java.net.http.HttpClient$Redirect/NEVER)
             .build)))

(defn- internal-address?
  "Whether `host` resolves anywhere this process should not be reaching.

  The trust list already bounds which domains are fetched at all; this is the
  second line, for a trusted domain whose DNS answers with an internal address —
  deliberately or because it was compromised. Checked at fetch time against the
  resolved address, so it also catches a domain that only sometimes answers that
  way."
  [host]
  (try
    (let [addresses (InetAddress/getAllByName host)]
      (boolean (some (fn [^InetAddress a]
                       (or (.isLoopbackAddress a)
                           (.isAnyLocalAddress a)
                           (.isLinkLocalAddress a)
                           (.isSiteLocalAddress a)
                           (.isMulticastAddress a)))
                     addresses)))
    (catch Exception _
      ;; A name that will not resolve is not fetchable either. Treat it as
      ;; refused rather than letting the request fail later with a less specific
      ;; error.
      true)))

(defn fetch-did-document
  "GET `https://<domain>/.well-known/did.json` and parse it.

  Refuses before any request when the domain is not trusted: an untrusted domain
  should not learn that this deployment exists, and a fetch we would ignore the
  answer to is a fetch not worth making."
  [configuration domain]
  (when-not (trusted-issuer? configuration domain)
    (fail! :credential-trust/untrusted-issuer
           (str "did:web:" domain " is not in :credentials :trusted-issuers. "
                "A DID document proves only who controls the domain, so a "
                "verifier must decide which domains it believes before fetching "
                "one.")
           {:domain domain}))
  (let [url (did/did-web-url (str "did:web:" domain))
        uri (URI/create url)]
    (when-not (= "https" (.getScheme uri))
      (fail! :credential-trust/insecure-transport
             "a DID document must be fetched over HTTPS" {:url url}))
    (when (internal-address? (.getHost uri))
      (fail! :credential-trust/internal-address
             (str (.getHost uri) " resolves to an address inside this network")
             {:domain domain}))
    (let [timeout (or (:fetch-timeout-seconds (settings configuration))
                      default-fetch-timeout-seconds)
          max-bytes (or (:max-document-bytes (settings configuration))
                        default-max-document-bytes)
          request (-> (HttpRequest/newBuilder uri)
                      (.timeout (Duration/ofSeconds (long timeout)))
                      (.header "accept" "application/did+json, application/json")
                      .GET
                      .build)
          response (.send @http-client request (HttpResponse$BodyHandlers/ofString))]
      (when-not (= 200 (.statusCode response))
        (fail! :credential-trust/document-unavailable
               (str "did:web document returned HTTP " (.statusCode response))
               {:domain domain :status (.statusCode response) :url url}))
      (let [body (.body response)]
        (when (> (count body) max-bytes)
          (fail! :credential-trust/document-too-large
                 (str "did:web document exceeds " max-bytes " bytes")
                 {:domain domain :bytes (count body)}))
        (try
          (json/read-str body)
          (catch Exception _
            (fail! :credential-trust/document-unparseable
                   "did:web document is not JSON" {:domain domain})))))))

;; ── document -> key ──────────────────────────────────────────────────────────

(defn- as-vector [x]
  (cond (nil? x) [] (vector? x) x (sequential? x) (vec x) :else [x]))

(defn assertion-key
  "The Ed25519 public key `verification-method` refers to, from `document`.

  Requires the method to be listed under `assertionMethod`. A key present in
  `verificationMethod` but not in `assertionMethod` is a key the controller
  published for some OTHER purpose — authentication, key agreement — and
  accepting it for an issuer's assertion would let a key intended for logging in
  sign claims about people."
  [document verification-method]
  (let [methods (as-vector (get document "verificationMethod"))
        assertion-ids (set (map (fn [m] (if (map? m) (get m "id") m))
                                (as-vector (get document "assertionMethod"))))
        entry (some (fn [m] (when (and (map? m)
                                       (= verification-method (get m "id")))
                              m))
                    methods)]
    (when-not entry
      (fail! :credential-trust/method-not-in-document
             "the credential's verificationMethod is not in the issuer's DID document"
             {:verification-method verification-method}))
    (when-not (contains? assertion-ids verification-method)
      (fail! :credential-trust/method-not-an-assertion-method
             (str "the issuer publishes this key, but not for assertions. A key "
                  "published for authentication or key agreement must not sign "
                  "claims about people.")
             {:verification-method verification-method}))
    (let [multibase (get entry "publicKeyMultibase")]
      (when-not (and (string? multibase) (str/starts-with? multibase "z6Mk"))
        (fail! :credential-trust/unsupported-key-type
               (str "only Ed25519 (publicKeyMultibase z6Mk…) is supported; "
                    "eddsa-jcs-2022 is an Ed25519 cryptosuite")
               {:verification-method verification-method
                :public-key-multibase multibase}))
      (ed/did-key->pubkey (str "did:key:" multibase)))))

;; ── cache ────────────────────────────────────────────────────────────────────
;; Keyed by verificationMethod, not by domain: one domain may publish several
;; keys and rotate them independently, and caching per domain would hand back the
;; wrong key after a rotation.
(defonce ^:private key-cache (atom {}))

(defn clear-cache! [] (reset! key-cache {}))

(defn- cached-key [_configuration verification-method]
  (let [now (Instant/now)
        entry (get @key-cache verification-method)]
    (when (and entry (pos? (compare (:expires-at entry) now)))
      (:key entry))))

(defn- cache-key! [configuration verification-method k]
  (let [ttl (or (:cache-seconds (settings configuration)) default-cache-seconds)]
    (swap! key-cache assoc verification-method
           {:key k :expires-at (.plusSeconds (Instant/now) (long ttl))})
    k))

;; ── the resolver ─────────────────────────────────────────────────────────────

(defn resolve-external-key
  "A `:resolve-key` fn for `data-integrity.core/verify`, for OTHER issuers.

  `did:key` is resolved locally and without a fetch — it is self-describing, so
  there is no document to get. Note what that means for trust: a `did:key`
  credential is only as meaningful as the caller's separate decision to believe
  that key, because the key came from the credential itself. This resolver
  returns it, and the trust decision for `did:key` issuers stays with the caller.

  `did:web` goes through the trust list and the fetch above."
  [configuration]
  (fn [verification-method]
    (let [controller (if-let [i (str/index-of verification-method "#")]
                       (subs verification-method 0 i)
                       verification-method)]
      (cond
        (str/starts-with? controller "did:key:")
        (ed/did-key->pubkey controller)

        (str/starts-with? controller "did:web:")
        (or (cached-key configuration verification-method)
            (let [domain (subs controller (count "did:web:"))
                  document (fetch-did-document configuration domain)]
              (when-not (= controller (get document "id"))
                (fail! :credential-trust/document-id-mismatch
                       (str "the DID document at this domain claims to be "
                            (get document "id"))
                       {:expected controller :actual (get document "id")}))
              (cache-key! configuration verification-method
                          (assertion-key document verification-method))))

        :else
        (fail! :credential-trust/unsupported-did-method
               "only did:key and did:web issuers can be resolved"
               {:verification-method verification-method})))))

;; ── verification ─────────────────────────────────────────────────────────────

(defn verify-external
  "Verify a credential issued by someone else.

  Returns `{:verified bool :revocation kw :valid? bool …}`.

  `:valid?` is true only when the signature verifies AND revocation was actually
  determined. A credential that carries a `credentialStatus` this app cannot
  resolve gets `:revocation :unchecked` and `:valid? false`: a signature proves
  the issuer said it, not that they still say it, and reporting a good signature
  as a usable credential is how a revoked credential gets honoured.

  A credential with no `credentialStatus` has nothing to revoke, so there
  `:revocation` is `:not-applicable` and `:valid?` follows `:verified`.

  Malformed input and an untrusted issuer are answers, not exceptions — this is
  fed documents from outside."
  [configuration presented]
  (if-not (map? presented)
    {:verified false :valid? false :reason :credential/not-a-document
     :revocation :not-applicable :schema schema}
    (try
      (let [result (di/verify-credential
                    presented
                    {:resolve-key (resolve-external-key configuration)})]
        (if-not (:verified result)
          (assoc (select-keys result [:verified :reason])
                 :valid? false :revocation :not-applicable :schema schema)
          (let [has-status? (some? (get presented "credentialStatus"))]
            {:verified true
             ;; Deliberately NOT fetched. A stranger's status list is a second
             ;; network dependency and a second trust question; leaving it
             ;; unchecked and saying so is honest, and fetching it silently
             ;; would not be.
             :revocation (if has-status? :unchecked :not-applicable)
             :valid? (not has-status?)
             :issuer (get presented "issuer")
             :subject (get-in presented ["credentialSubject" "id"])
             :verification-method (:verification-method result)
             :schema schema})))
      (catch clojure.lang.ExceptionInfo error
        (let [data (ex-data error)]
          {:verified false
           :valid? false
           :revocation :not-applicable
           :reason (or (:type data) (:data-integrity/error data)
                       :credential-trust/unverifiable)
           :schema schema})))))
