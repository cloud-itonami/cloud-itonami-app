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

  A signature proves the issuer said it. It does not prove they still say it, so
  `credentialStatus` is now resolved rather than skipped — and the resolution
  FAILS CLOSED. Every way of not knowing (no URL, a failed fetch, a list that does
  not verify, a list signed by somebody else, a length error) comes back
  `:revocation :unchecked` and `:valid? false`, never `:current`. Treating \"could
  not ask\" as \"not revoked\" is exactly what makes a revocation list decorative.

  The status list is **verified, and required to be signed by the same controller
  as the credential it is about**. An unverified list is a file at a URL: believing
  one would let whoever can answer that URL publish a list of zeros and un-revoke
  everything the issuer ever withdrew. And a *valid* list from a different issuer
  is a valid statement about somebody else's credentials, so honouring it here
  would let any trusted issuer clear another issuer's revocations.

  ## What is fetched, and the limits on it

  Two URLs, both named by content this app did not author: the issuer's
  `https://<domain>/.well-known/did.json` (derived by `did.core/did-web-url`, not
  assembled here) and the `statusListCredential` its credentials point at. Both go
  through one `fetch-json` — HTTPS only, redirects never followed, a hard timeout,
  a response size cap, and a refusal to talk to an address resolving inside the
  network this process sits in, because the app binds loopback and a guard that
  exists on one of these fetches but not the other is the same as no guard.

  Keys cache for an hour, status lists for five minutes or the list's own `ttl`,
  whichever is shorter: a key rotates rarely, a revocation is the thing being
  asked about."
  (:require [cloud.itonami.app.org-root-did :as org-root-did]
            [clojure.data.json :as json]
            [didwebvh.did :as webvh-did]
            [clojure.string :as str]
            ;; for the pinned @context bytes. One definition shared rather than a
            ;; second read of the same file: an issuer and a verifier that pin
            ;; different bytes for a context URL disagree about the graph, and two
            ;; definitions in one process is the easiest way to arrange that.
            [cloud.itonami.app.credential :as credential]
            [data-integrity.core :as di]
            [data-integrity.ecdsa :as ecdsa]
            [data-integrity.eddsa :as eddsa]
            [data-integrity.eddsa-rdfc :as eddsa-rdfc]
            [did.core :as did]
            [ed25519.core :as ed]
            [status-list.core :as sl])
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

(defn- fetch-string
  "GET `url` as text, under every limit this namespace imposes.

  Extracted so that the JSON fetch and the `did.jsonl` fetch below cannot
  drift: these are the only URLs this app is ever told to fetch by content it
  did not author, and a guard that exists on one of them and not the other is
  the same as no guard.

  Returns `{:status :body}`. A non-200 is DATA here, not an exception — the
  witness file of a DID that has none is a 404, and that is an answer."
  [configuration url {:keys [accept what]
                      :or {accept "application/json" what "document"}}]
  (let [uri (URI/create url)]
    (when-not (= "https" (.getScheme uri))
      (fail! :credential-trust/insecure-transport
             (str "a " what " must be fetched over HTTPS") {:url url}))
    (when (internal-address? (.getHost uri))
      (fail! :credential-trust/internal-address
             (str (.getHost uri) " resolves to an address inside this network")
             {:url url}))
    (let [timeout (or (:fetch-timeout-seconds (settings configuration))
                      default-fetch-timeout-seconds)
          max-bytes (or (:max-document-bytes (settings configuration))
                        default-max-document-bytes)
          request (-> (HttpRequest/newBuilder uri)
                      (.timeout (Duration/ofSeconds (long timeout)))
                      (.header "accept" accept)
                      .GET
                      .build)
          response (.send @http-client request (HttpResponse$BodyHandlers/ofString))
          body (.body response)]
      (when (> (count body) max-bytes)
        (fail! :credential-trust/document-too-large
               (str what " exceeds " max-bytes " bytes")
               {:url url :bytes (count body)}))
      {:status (.statusCode response) :body body})))

(defn fetch-json
  "GET `url` and parse it as JSON."
  [configuration url {:keys [what] :or {what "document"} :as opts}]
  (let [{:keys [status body]} (fetch-string configuration url opts)]
    (when-not (= 200 status)
      (fail! :credential-trust/document-unavailable
             (str what " returned HTTP " status)
             {:status status :url url}))
    (try
      (json/read-str body)
      (catch Exception _
        (fail! :credential-trust/document-unparseable
               (str what " is not JSON") {:url url})))))

(defn didwebvh-fetch
  "A `:fetch` for `org-root-did/resolve-external`, carrying this namespace's
  transport policy: HTTPS only, no internal addresses, one timeout, one size
  cap. The resolver itself makes no request — it is handed this."
  [configuration]
  (fn [url]
    (fetch-string configuration url
                  {:accept "application/jsonl, application/json"
                   :what "did:webvh log"})))

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
  (fetch-json configuration (did/did-web-url (str "did:web:" domain))
              {:accept "application/did+json, application/json"
               :what "did:web document"}))

;; ── cryptosuite selection ────────────────────────────────────────────────────
;; A `publicKeyMultibase` announces its own curve in its prefix, and a proof
;; announces its own cryptosuite. Both must be read, and they must AGREE.
;;
;; Choosing the suite from the KEY would let a substituted key change which
;; algorithm runs. Choosing it from the PROOF alone, without checking the key,
;; turns a suite/curve mismatch into an obscure verification failure that reads
;; like a bad signature. So: the suite comes from the proof, the curve comes from
;; the key, and a disagreement is named as such.
(def cryptosuites
  {"eddsa-jcs-2022" {:suite eddsa/suite :curve :ed25519 :prefix "z6Mk"}
   "ecdsa-jcs-2019" {:suite ecdsa/suite :curve :p256    :prefix "zDna"}
   ;; eddsa-rdfc-2022 is Ed25519 like the jcs variant -- same curve, same key
   ;; prefix; only the transformation differs (RDF canonicalization instead of
   ;; JCS). It is the more widely implemented of the two, so a foreign credential
   ;; is MORE likely to arrive in this form than the other, and rejecting it as
   ;; "not implemented" was turning away the common case.
   ;;
   ;; It needs pinned contexts, which `needs-contexts?` marks so the call sites can
   ;; supply them without testing the suite's name.
   "eddsa-rdfc-2022" {:suite eddsa-rdfc/suite :curve :ed25519 :prefix "z6Mk"}})

(defn suite-for
  "The cryptosuite a proof names, or nil for one this app does not implement."
  [cryptosuite]
  (get cryptosuites cryptosuite))

(defn curve-of
  "The curve a `publicKeyMultibase` announces, by prefix."
  [multibase]
  (cond
    (not (string? multibase)) nil
    (str/starts-with? multibase "z6Mk") :ed25519
    (str/starts-with? multibase "zDna") :p256
    :else nil))

;; ── document -> key ──────────────────────────────────────────────────────────

(defn- as-vector [x]
  (cond (nil? x) [] (vector? x) x (sequential? x) (vec x) :else [x]))

(defn assertion-key
  "The public key `verification-method` refers to, from `document`.

  Requires the method to be listed under `assertionMethod`. A key present in
  `verificationMethod` but not in `assertionMethod` is a key the controller
  published for some OTHER purpose — authentication, key agreement — and
  accepting it for an issuer's assertion would let a key intended for logging in
  sign claims about people.

  `expected-curve` is the curve the proof's cryptosuite implies. When given, a key
  on a different curve is refused by name rather than left to fail later as an
  unexplained bad signature."
  ([document verification-method] (assertion-key document verification-method nil))
  ([document verification-method expected-curve]
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
    (let [multibase (get entry "publicKeyMultibase")
          curve (curve-of multibase)]
      (when-not curve
        (fail! :credential-trust/unsupported-key-type
               (str "only Ed25519 (z6Mk…) and P-256 (zDna…) publicKeyMultibase "
                    "values are supported")
               {:verification-method verification-method
                :public-key-multibase multibase}))
      (when (and expected-curve (not= expected-curve curve))
        ;; The proof said one algorithm and the issuer published a key for
        ;; another. Named rather than left to fail as a bad signature, which is
        ;; what it would look like otherwise.
        (fail! :credential-trust/curve-cryptosuite-mismatch
               (str "the proof's cryptosuite expects a " (name expected-curve)
                    " key, but the issuer publishes a " (name curve) " one")
               {:verification-method verification-method
                :expected expected-curve :actual curve}))
      ;; Each suite's verifier wants a different representation: eddsa takes raw
      ;; public key bytes, ecdsa takes a java.security.PublicKey.
      (case curve
        :ed25519 (ed/did-key->pubkey (str "did:key:" multibase))
        :p256 (ecdsa/did-key->public-key (str "did:key:" multibase)))))))

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

  `did:web` goes through the trust list and the fetch above.

  `expected-curve` selects the key representation, because the two suites want
  different things: eddsa takes raw public key bytes and ecdsa takes a
  java.security.PublicKey. It also makes a curve/cryptosuite disagreement an
  explicit error instead of an unexplained bad signature."
  ([configuration] (resolve-external-key configuration nil))
  ([configuration expected-curve]
   (fn [verification-method]
    (let [controller (if-let [i (str/index-of verification-method "#")]
                       (subs verification-method 0 i)
                       verification-method)]
      (cond
        (str/starts-with? controller "did:key:")
        (case (or expected-curve (curve-of (subs controller (count "did:key:"))))
          :p256 (ecdsa/did-key->public-key controller)
          (ed/did-key->pubkey controller))

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
                          (assertion-key document verification-method
                                         expected-curve))))

        (str/starts-with? controller "did:webvh:")
        (or (cached-key configuration verification-method)
            (let [domain (:domain (webvh-did/parse controller))]
              ;; Same trust gate as did:web, for the same reason: an untrusted
              ;; domain should not learn this deployment exists. That the log
              ;; is self-verifying does not change who we are willing to ask.
              (when-not (trusted-issuer? configuration domain)
                (fail! :credential-trust/untrusted-issuer
                       (str controller " is not in :credentials :trusted-issuers")
                       {:domain domain}))
              (let [resolved (org-root-did/resolve-external
                              controller
                              {:fetch (didwebvh-fetch configuration)
                               :now (quot (System/currentTimeMillis) 1000)})]
                ;; A did:webvh that does not resolve is not a key we can fall
                ;; back from. Below its witness threshold, or with one broken
                ;; link in the chain, the document it serves is exactly the
                ;; thing the method exists to refuse.
                (when-not (:ok? resolved)
                  (fail! :credential-trust/didwebvh-unresolvable
                         (str controller " did not resolve: " (name (or (:error resolved)
                                                                        :unknown)))
                         {:did controller :error (:error resolved)
                          :message (:message resolved)}))
                (cache-key! configuration verification-method
                            (assertion-key (:state resolved) verification-method
                                           expected-curve)))))

        :else
        (fail! :credential-trust/unsupported-did-method
               "only did:key, did:web and did:webvh issuers can be resolved"
               {:verification-method verification-method}))))))

;; ── revocation ───────────────────────────────────────────────────────────────
;; Cached separately from keys and for much less time: a key rotates rarely, a
;; revocation is the thing we are asking about. The list's own `ttl` (RFC
;; milliseconds) wins when it is shorter than ours, because the issuer knows how
;; often it republishes.
(def default-status-cache-seconds 300)
(defonce ^:private status-cache (atom {}))

(defn clear-status-cache! [] (reset! status-cache {}))

(defn- controller-of [verification-method]
  (if-let [i (str/index-of (str verification-method) "#")]
    (subs verification-method 0 i)
    verification-method))

(defn- cached-status-list [url]
  (let [entry (get @status-cache url)]
    (when (and entry (pos? (compare (:expires-at entry) (Instant/now))))
      (:credential entry))))

(defn- cache-status-list! [configuration url credential]
  (let [configured (or (:status-cache-seconds (settings configuration))
                       default-status-cache-seconds)
        ;; `ttl` on a BitstringStatusList is in milliseconds.
        declared (when-let [ms (get-in credential ["credentialSubject" "ttl"])]
                   (when (number? ms) (quot (long ms) 1000)))
        seconds (max 1 (min (long configured) (long (or declared configured))))]
    (swap! status-cache assoc url
           {:credential credential
            :expires-at (.plusSeconds (Instant/now) seconds)})
    credential))

(defn fetch-status-list-credential
  "Fetch and VERIFY the status list credential `url` points at.

  The verification is the whole point, not a formality. An unverified list is a
  file at a URL, and believing one would let anyone who can answer that URL —
  including whoever compromised it — publish a list of zeros and un-revoke every
  credential the issuer ever withdrew.

  It must also be signed by the SAME controller as the credential being checked.
  A valid list from a different issuer is a valid statement about someone else's
  credentials, and honouring it here would let any trusted issuer clear another
  issuer's revocations."
  [configuration url expected-controller]
  (or (cached-status-list url)
      (let [document (fetch-json configuration url
                                 {:accept "application/vc+json, application/json"
                                  :what "status list credential"})
            named (get-in document ["proof" "cryptosuite"])
            chosen (suite-for named)
            _ (when-not chosen
                (fail! :credential-trust/unsupported-cryptosuite
                       (str "the status list at " url " names cryptosuite " named)
                       {:url url :cryptosuite named}))
            result (di/verify-credential
                    document
                    {:suite (:suite chosen)
                     :suite-opts (when (:needs-contexts? (:suite chosen))
                                   {:contexts @credential/pinned-contexts})
                     :resolve-key (resolve-external-key
                                   configuration (:curve chosen))})]
        (when-not (:verified result)
          (fail! :credential-trust/status-list-unverified
                 (str "the status list credential at " url " does not verify. An "
                      "unverified list is just a file at a URL, and believing one "
                      "would let whoever answers that URL un-revoke everything.")
                 {:url url :reason (:reason result)}))
        (when-not (= expected-controller
                     (controller-of (:verification-method result)))
          (fail! :credential-trust/status-list-issuer-mismatch
                 (str "the status list at " url " is signed by "
                      (controller-of (:verification-method result))
                      ", not by the credential's issuer. A valid list from a "
                      "different issuer is a statement about someone else's "
                      "credentials.")
                 {:url url
                  :expected expected-controller
                  :actual (controller-of (:verification-method result))}))
        (cache-status-list! configuration url document))))

(defn check-revocation
  "Resolve a credential's `credentialStatus` against its issuer's status list.

  Returns `{:revocation :current|:revoked|:not-applicable|:unchecked …}`.

  FAILS CLOSED. Every way of not knowing — no URL, a fetch that failed, a list
  that does not verify, a list signed by someone else, a length error — comes back
  `:unchecked`, never `:current`. Treating \"could not ask\" as \"not revoked\" is
  exactly the mistake that makes a revocation list decorative."
  [configuration credential expected-controller]
  (let [entry (get credential "credentialStatus")]
    (cond
      (nil? entry) {:revocation :not-applicable}

      (not (map? entry))
      {:revocation :unchecked :reason :credential-trust/status-entry-malformed}

      :else
      (let [url (get entry "statusListCredential")]
        (if-not (string? url)
          {:revocation :unchecked
           :reason :credential-trust/status-entry-malformed}
          (try
            (let [list-credential (fetch-status-list-credential
                                   configuration url expected-controller)
                  status (sl/check-status entry list-credential
                                          {:expected-purpose
                                           (get entry "statusPurpose")})]
              {:revocation (if (:valid? status) :current :revoked)
               :status (:status status)
               :status-index (:index status)
               :status-list url})
            (catch clojure.lang.ExceptionInfo error
              (let [data (ex-data error)]
                {:revocation :unchecked
                 :status-list url
                 :reason (or (:type data) (:status-list/error data)
                             (:data-integrity/error data)
                             :credential-trust/status-unresolvable)}))))))))

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
      (let [named (get-in presented ["proof" "cryptosuite"])
            chosen (suite-for named)]
        (if-not chosen
          ;; Naming an unimplemented cryptosuite is a legitimate thing for a
          ;; stranger to do, and it is not a forgery — say which one rather than
          ;; reporting a bad signature.
          {:verified false :valid? false :revocation :not-applicable
           :reason :credential-trust/unsupported-cryptosuite
           :cryptosuite named :schema schema}
          (let [result (di/verify-credential
                        presented
                        {:suite (:suite chosen)
                         :suite-opts (when (:needs-contexts? (:suite chosen))
                                       {:contexts @credential/pinned-contexts})
                         :resolve-key (resolve-external-key
                                       configuration (:curve chosen))})]
        (if-not (:verified result)
          (assoc (select-keys result [:verified :reason])
                 :valid? false :revocation :not-applicable :schema schema)
          (let [revocation (check-revocation
                            configuration presented
                            (controller-of (:verification-method result)))]
            (merge
             {:verified true
              ;; :valid? is true ONLY when revocation came back :current.
              ;; :unchecked is not a pass — see check-revocation.
              :valid? (contains? #{:current :not-applicable} (:revocation revocation))
              :issuer (get presented "issuer")
              :subject (get-in presented ["credentialSubject" "id"])
              :verification-method (:verification-method result)
              :schema schema}
             revocation))))))
      (catch clojure.lang.ExceptionInfo error
        (let [data (ex-data error)]
          {:verified false
           :valid? false
           :revocation :not-applicable
           :reason (or (:type data) (:data-integrity/error data)
                       :credential-trust/unverifiable)
           :schema schema})))))
