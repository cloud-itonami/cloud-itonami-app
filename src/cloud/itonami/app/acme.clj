(ns cloud.itonami.app.acme
  "RFC 8555 — asking a CA for a certificate for a domain this deployment answers
  at.

  ADR-0043 called a publicly trusted certificate \"an operator fact\" and left
  Gate B to measure it. This is the other half: the deployment can now obtain
  one itself, for exactly the domains it has already proven it answers at. The
  order is not an accident — HTTP-01 works by the CA fetching
  `http://<domain>/.well-known/acme-challenge/<token>` from this process, which
  is only possible for a name whose DNS already points here. Gate B's proof is
  the precondition, not a coincidence.

  ## The transport is injected

  `*transport*` takes `[method url headers body]` and returns
  `{:status :headers :body}`. Everything else here is pure: the JWS, the
  nonces, the state walk, the CSR. That is what makes the protocol testable
  without a CA, and it is also the honest limit — a recorded exchange proves
  this speaks the shape, not that Let's Encrypt accepts it.

  ## ES256, because the CA has to accept the account key

  Ed25519 is the workspace default and Let's Encrypt does not take it for
  account keys. So the account and certificate keys are EC P-256, signed with
  `SHA256withECDSA` — whose output is a DER `SEQUENCE {r, s}` while JWS wants
  the raw 64 bytes `R || S`. Converting between them is the one place a
  silently-wrong signature could live, so it is done through `asn1` rather than
  by slicing bytes at offsets.

  ## The CSR is built with the DER encoder this workspace already has

  `asn1.core` is the encoding layer under every signed structure here. Building
  PKCS#10 by hand next to it would have been a second encoder to be wrong in,
  and the round-trip property (`der-round-trips?`) is exactly the check a
  hand-rolled one could not offer."
  (:require [asn1.core :as asn1]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.math BigInteger]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security KeyFactory KeyPairGenerator MessageDigest Signature]
           [java.security.interfaces ECPublicKey]
           [java.security.spec ECGenParameterSpec PKCS8EncodedKeySpec
            X509EncodedKeySpec]
           [java.time Duration]
           [java.util Base64]))

(def schema "cloud.itonami.app.acme.v1")

;; Let's Encrypt's production and staging directories. Named rather than
;; defaulted: a deployment that has not chosen one is not silently ordering real
;; certificates against its rate limit.
(def staging-directory "https://acme-staging-v02.api.letsencrypt.org/directory")
(def production-directory "https://acme-v02.api.letsencrypt.org/directory")

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

;; ── base64url ────────────────────────────────────────────────────────────────

(defn b64u [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- sha256 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

;; ── keys ─────────────────────────────────────────────────────────────────────

(defn generate-key
  "A fresh P-256 key pair, as the two encodings that survive a store.

  `:private` is PKCS#8 and `:public` is X.509 SubjectPublicKeyInfo — the second
  is what a CSR carries verbatim, which is why it is kept rather than derived."
  []
  (let [generator (doto (KeyPairGenerator/getInstance "EC")
                    (.initialize (ECGenParameterSpec. "secp256r1")))
        pair (.generateKeyPair generator)]
    {:private (b64u (.getEncoded (.getPrivate pair)))
     :public (b64u (.getEncoded (.getPublic pair)))}))

(defn- decode64 ^bytes [s] (.decode (Base64/getUrlDecoder) (str s)))

(defn- private-key [key]
  (.generatePrivate (KeyFactory/getInstance "EC")
                    (PKCS8EncodedKeySpec. (decode64 (:private key)))))

(defn- public-key ^ECPublicKey [key]
  (.generatePublic (KeyFactory/getInstance "EC")
                   (X509EncodedKeySpec. (decode64 (:public key)))))

(defn- coordinate
  "One EC coordinate as the fixed 32 bytes JWS requires.

  `BigInteger/toByteArray` is variable width and may carry a leading zero for
  sign. A JWK coordinate that is 31 or 33 bytes is a thumbprint that does not
  match, and therefore a key authorization the CA rejects for reasons that name
  none of this."
  [^BigInteger n]
  (let [full (.toByteArray n)
        len (alength full)]
    (cond
      (= 32 len) full
      (< 32 len) (java.util.Arrays/copyOfRange full (- len 32) len)
      :else (let [padded (byte-array 32)]
              (System/arraycopy full 0 padded (- 32 len) len)
              padded))))

(defn jwk
  "The public JWK for an account key, with its members in the order RFC 7638
  requires for a thumbprint — lexicographic, and it is not an accident here."
  [key]
  (let [point (.getW (public-key key))]
    (array-map "crv" "P-256"
               "kty" "EC"
               "x" (b64u (coordinate (.getAffineX point)))
               "y" (b64u (coordinate (.getAffineY point))))))

(defn thumbprint
  "RFC 7638 JWK thumbprint — the half of a key authorization the CA computes
  for itself."
  [key]
  (b64u (sha256 (utf8 (json/write-str (jwk key))))))

(defn- raw-signature
  "A `SHA256withECDSA` signature as the 64 bytes JWS wants.

  The JDK emits `SEQUENCE { INTEGER r, INTEGER s }`; JWS wants `R || S`, each
  left-padded to 32. Read through the DER decoder rather than by slicing at
  offsets, because the lengths vary with the values and an off-by-one here
  produces a signature that is merely invalid — no error names it."
  [^bytes der]
  (let [decoded (asn1/decode der)
        ;; `integer-hex`, not `integer-value`: r and s are 256-bit and
        ;; `integer-value` refuses anything it cannot represent exactly — which
        ;; is the right refusal and the reason it is not the function to use
        ;; here. Both are positive by construction, so reading the hex as an
        ;; unsigned magnitude is correct including the DER sign padding.
        component (fn [i]
                    (coordinate
                     (BigInteger. ^String (asn1/integer-hex
                                           (asn1/nth-element decoded i))
                                  16)))
        out (byte-array 64)]
    (System/arraycopy (component 0) 0 out 0 32)
    (System/arraycopy (component 1) 0 out 32 32)
    out))

(defn- sign ^bytes [key ^bytes input]
  (let [signature (doto (Signature/getInstance "SHA256withECDSA")
                    (.initSign (private-key key))
                    (.update input))]
    (.sign signature)))

(defn jws
  "One flattened-JSON JWS, as ACME sends them.

  Flattened and not compact: RFC 8555 posts `{protected, payload, signature}`.
  `payload` is the empty string for POST-as-GET, which is a real request and not
  a missing one."
  [key {:keys [nonce url kid payload]}]
  (let [header (cond-> {"alg" "ES256" "nonce" nonce "url" url}
                 kid (assoc "kid" kid)
                 (not kid) (assoc "jwk" (jwk key)))
        protected (b64u (utf8 (json/write-str header)))
        body (if (nil? payload) "" (b64u (utf8 (json/write-str payload))))
        input (utf8 (str protected "." body))]
    {"protected" protected
     "payload" body
     "signature" (b64u (raw-signature (sign key input)))}))

;; ── transport ────────────────────────────────────────────────────────────────

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects java.net.http.HttpClient$Redirect/NEVER)
             .build)))

(defn http-transport
  "The real one. HTTPS only and no redirects, like every other outbound path
  here."
  [method url headers body]
  (let [uri (URI/create url)]
    (when-not (= "https" (.getScheme uri))
      (fail! :acme/insecure-transport "ACME directories must be HTTPS" {:url url}))
    (let [builder (-> (HttpRequest/newBuilder uri)
                      (.timeout (Duration/ofSeconds 30)))
          _ (doseq [[k v] headers] (.header builder (name k) (str v)))
          request (case method
                    :get (.GET builder)
                    :head (.method builder "HEAD" (HttpRequest$BodyPublishers/noBody))
                    :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                          (or body ""))))
          response (.send @http-client (.build request)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)]))
                      (.map (.headers response)))
       :body (.body response)})))

(def ^:dynamic *transport* http-transport)

(defn- json-body [response]
  (try (json/read-str (str (:body response)))
       (catch Exception _ nil)))

(defn- expect!
  "Refuse a response the protocol does not allow, carrying what the CA said.

  ACME problem documents name the failure precisely (`urn:ietf:params:acme:
  error:unauthorized` and a detail sentence). Reducing that to a status code is
  the discard this repository keeps finding in its own error paths."
  [response allowed what]
  (when-not (contains? allowed (:status response))
    (let [problem (json-body response)]
      (fail! :acme/unexpected-response
             (str what " returned HTTP " (:status response)
                  (when-let [detail (get problem "detail")] (str " — " detail)))
             {:status (:status response)
              :acme-error (get problem "type")
              :detail (get problem "detail")})))
  response)

;; ── the protocol ─────────────────────────────────────────────────────────────

(defn directory [url]
  (-> (*transport* :get url {} nil)
      (expect! #{200} "the ACME directory")
      json-body))

(defn- nonce! [state]
  (or (:nonce @state)
      (let [response (*transport* :head (:new-nonce @state) {} nil)]
        (get-in response [:headers "replay-nonce"]))))

(defn- post!
  "One signed POST, keeping the fresh nonce the CA hands back.

  ACME nonces are single-use and every response carries the next one. Dropping
  it costs an extra HEAD per request and, worse, races: two requests that both
  fetched a nonce make one of them `badNonce`."
  [state url payload]
  (let [signed (jws (:key @state) {:nonce (nonce! state)
                                   :url url
                                   :kid (:account @state)
                                   :payload payload})
        response (*transport* :post url
                              {"content-type" "application/jose+json"}
                              (json/write-str signed))]
    (swap! state assoc :nonce (get-in response [:headers "replay-nonce"]))
    response))

(defn session
  "Everything the flow carries between requests."
  [{:keys [directory-url key contact]}]
  (let [d (directory directory-url)]
    (atom {:key key
           :contact contact
           :new-nonce (get d "newNonce")
           :new-account (get d "newAccount")
           :new-order (get d "newOrder")})))

(defn register!
  "Create or find the account, and remember its URL as the `kid`."
  [state]
  (let [contact (:contact @state)
        response (post! state (:new-account @state)
                        (cond-> {"termsOfServiceAgreed" true}
                          (seq contact) (assoc "contact" (vec contact))))]
    (expect! response #{200 201} "newAccount")
    (swap! state assoc :account (get-in response [:headers "location"]))
    (:account @state)))

(defn order!
  "Ask for a certificate covering `domains`."
  [state domains]
  (let [response (post! state (:new-order @state)
                        {"identifiers" (mapv (fn [d] {"type" "dns" "value" d})
                                             domains)})]
    (expect! response #{201} "newOrder")
    (assoc (json-body response) "url" (get-in response [:headers "location"]))))

(defn- fetch [state url]
  (-> (post! state url nil) (expect! #{200} "a POST-as-GET") json-body))

(defn http-01
  "The HTTP-01 challenge in an authorization, or nil.

  nil rather than a throw: an authorization may legitimately offer only
  challenge types this deployment cannot answer, and the caller decides what
  that means."
  [authorization]
  (some (fn [challenge]
          (when (= "http-01" (get challenge "type")) challenge))
        (get authorization "challenges")))

(defn key-authorization
  "`<token>.<thumbprint>` — what the CA expects to find at the challenge URL."
  [key token]
  (str token "." (thumbprint key)))

;; ── the CSR ──────────────────────────────────────────────────────────────────

(def ^:private oid-extension-request "1.2.840.113549.1.9.14")
(def ^:private oid-subject-alt-name "2.5.29.17")
(def ^:private oid-ecdsa-sha256 "1.2.840.10045.4.3.2")

(defn- subject-alt-name
  "`GeneralNames` holding one `dNSName` per domain.

  `[2] IMPLICIT IA5String`. Implicit and not explicit: an explicitly tagged
  IA5String is a different encoding, and a CA reading it finds a name it cannot
  parse rather than a name it disagrees with."
  [domains]
  (asn1/sequence* (mapv #(asn1/implicit 2 (asn1/ia5-string %)) domains)))

(defn- certification-request-info [key domains]
  (asn1/sequence*
   [(asn1/integer 0)
    ;; An EMPTY subject, deliberately. Every name being requested is in the SAN
    ;; extension; putting the first one in a CN as well would be asserting it
    ;; twice, and CAs have been ignoring the CN for years.
    (asn1/sequence* [])
    ;; The SubjectPublicKeyInfo as the JDK already encoded it. Decoded and
    ;; re-embedded rather than rebuilt, so the curve parameters are exactly what
    ;; the key says they are.
    (asn1/decode (decode64 (:public key)))
    (asn1/implicit
     0 (asn1/set-of
        [(asn1/sequence*
          [(asn1/oid oid-extension-request)
           (asn1/set-of
            [(asn1/sequence*
              [(asn1/sequence*
                [(asn1/oid oid-subject-alt-name)
                 (asn1/octet-string (asn1/encode (subject-alt-name domains)))])])])])]))]))

(defn csr
  "A PKCS#10 CertificationRequest for `domains`, signed by `key`.

  Built with `asn1.core` rather than by hand. The signature covers the DER of
  the `CertificationRequestInfo` and nothing else, which is why that structure
  is encoded once and both signed and embedded — re-encoding it for the
  signature would be a second chance to encode it differently."
  [key domains]
  (let [info (certification-request-info key domains)
        signature (sign key (asn1/encode info))]
    (asn1/encode
     (asn1/sequence*
      [info
       ;; ecdsa-with-SHA256 carries no parameters. An explicit NULL here is what
       ;; RSA needs and what ECDSA forbids.
       (asn1/sequence* [(asn1/oid oid-ecdsa-sha256)])
       (asn1/bit-string signature)]))))

;; ── the order ────────────────────────────────────────────────────────────────

(defn order-certificate!
  "Walk one order to a PEM chain.

  `publish!` is handed `[domain token key-authorization]` and must make this
  deployment answer at `/.well-known/acme-challenge/<token>` before it returns;
  `retract!` is called for each token once the order settles, whatever the
  outcome. The challenge is an effect and it belongs to the caller — this
  function has the protocol and no store.

  `poll` is `{:attempts :sleep-ms}` and `sleep` is injectable so a test walks a
  pending order without waiting."
  [state domains {:keys [publish! retract! csr-key poll sleep]
                  :or {poll {:attempts 30 :sleep-ms 2000}
                       sleep #(Thread/sleep (long %))}}]
  (let [order (order! state domains)
        tokens (atom [])]
    (try
      (doseq [url (get order "authorizations")]
        (let [authorization (fetch state url)
              domain (get-in authorization ["identifier" "value"])
              challenge (or (http-01 authorization)
                            (fail! :acme/no-http-challenge
                                   (str domain " offers no http-01 challenge")
                                   {:domain domain}))
              token (get challenge "token")]
          (swap! tokens conj token)
          (publish! domain token (key-authorization (:key @state) token))
          (expect! (post! state (get challenge "url") {}) #{200 202}
                   "the challenge response")
          ;; Poll the AUTHORIZATION and not the challenge: `valid` on the
          ;; authorization is the statement the order is waiting for, and a
          ;; challenge can report `processing` after the authorization has
          ;; already failed.
          (loop [attempt 0]
            (let [current (fetch state url)
                  status (get current "status")]
              (cond
                (= "valid" status) :ok
                (>= attempt (:attempts poll))
                (fail! :acme/authorization-timeout
                       (str domain " did not become valid") {:domain domain})
                (contains? #{"invalid" "revoked" "deactivated"} status)
                (fail! :acme/authorization-failed
                       (str domain " failed validation: "
                            (get-in (http-01 current) ["error" "detail"]))
                       {:domain domain :status status})
                :else (do (sleep (:sleep-ms poll)) (recur (inc attempt))))))))
      (let [finalized (post! state (get order "finalize")
                             {"csr" (b64u (csr csr-key domains))})
            _ (expect! finalized #{200} "finalize")
            certificate-url
            (loop [attempt 0 current (json-body finalized)]
              (let [status (get current "status")]
                (cond
                  (= "valid" status) (get current "certificate")
                  (>= attempt (:attempts poll))
                  (fail! :acme/order-timeout "the order did not become valid" {})
                  (= "invalid" status)
                  (fail! :acme/order-failed "the order was rejected" {})
                  :else (do (sleep (:sleep-ms poll))
                            (recur (inc attempt) (fetch state (get order "url")))))))]
        (-> (post! state certificate-url nil)
            (expect! #{200} "the certificate")
            :body))
      (finally
        ;; Whatever happened. A token left answering is a URL that hands a key
        ;; authorization to anyone who asks, long after it proves anything.
        (when retract! (doseq [token @tokens] (retract! token)))))))
