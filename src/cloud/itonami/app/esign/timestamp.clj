(ns cloud.itonami.app.esign.timestamp
  "An RFC 3161 timestamp over a signature, and the honesty about what it is.

  ## What changes, and what does not

  `cloud.itonami.app.esign` records `timeAttestation: \"app-attested\"` — the
  signing time is this server's word. That is enough for an internal agreement
  and it is **not** what 電子帳簿保存法 names for retained transaction data,
  where the tamper-evidence measure must be one of an accredited timestamp
  (総務大臣認定), a system that keeps a record of corrections and deletions, or
  a written procedure. This namespace supplies the first.

  It does not make every signature compliant, and the field it writes says so.
  A token from a TSA nobody accredited is `:app-attested` upgraded to
  `:tsa-attested`, not to `:accredited`. Only a token whose signer is in this
  deployment's configured accredited set earns that word, and the set is
  configuration rather than a list this code carries — accreditation is a fact
  about a jurisdiction and a date, not about a library.

  ## No default TSA, restated at the app layer

  `org-ietf-rfc3161` ships no default URL, and neither does this. `configured?`
  is false until an operator names one. **A signature made while no TSA is
  configured is not refused** — it is recorded with `:app-attested`, exactly as
  before, and the UI keeps saying so. Refusing would mean an operator who has
  not yet chosen a TSA cannot sign anything internally, which trades a real
  capability for a compliance property they may not need.

  What IS refused is silence: `timestamp!` never returns a token it could not
  verify, and never reports a failure as an absence.

  ## The network call is the caller's risk, and it is bounded

  A TSA is an external service reached over HTTP. The request carries a digest
  and nothing else — not the document, not the outline, not a DID — so a TSA
  learns that something of a certain size was signed at a certain time and
  cannot learn what. That property is worth stating because it is the reason
  timestamping does not undo the erasure design in
  `cloud.itonami.app.esign`: there is nothing at the TSA to erase."
  (:require [cloud.itonami.app.config :as config]
            [clojure.string :as str]
            [asn1.core :as asn1]
            [cms.jvm :as cms-jvm]
            [rfc3161.core :as ts]
            [x509.core :as x509])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.security SecureRandom]
           [java.time Duration]))

(def schema "cloud.itonami.app.esign.timestamp.v1")

(defonce ^:private ^HttpClient http-client
  (-> (HttpClient/newBuilder)
      ;; A redirect to somewhere else is a different TSA answering, and the
      ;; nonce check would not catch it because the redirect target could simply
      ;; forward the request. Refusing is cheaper than reasoning about it.
      (.followRedirects HttpClient$Redirect/NEVER)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn settings
  "`{:url … :policy … :accredited-roots [pem …] :accreditation \"…\"}` or nil.

  From `:esign {:timestamp {…}}` in the deployment's config. Absent means no
  TSA, which is a supported state and not an error."
  ([] (settings (config/load-config)))
  ([configuration] (get-in configuration [:esign :timestamp])))

(defn configured?
  ([] (configured? (config/load-config)))
  ([configuration] (not (str/blank? (str (:url (settings configuration)))))))

(defn- accredited-certificates
  "The TSA certificates this deployment treats as accredited, parsed from the
  configured DER hex.

  DER rather than a name or a fingerprint: a name is not unique and a
  fingerprint says nothing about what the certificate contains, so neither
  supports the EKU and validity checks `rfc3161.core` performs."
  [configuration]
  (mapv #(x509/parse (asn1/unhex %))
        (:accredited-roots (settings configuration))))

(defn accredited?
  "Whether `certificate` chains to — or is — one of the configured accredited
  TSA certificates.

  Deliberately shallow: equality on the DER, or a signature from a configured
  root. It is NOT full path validation, and `org-ietf-x509` says why it will not
  fetch to do more. An operator naming the TSA's own certificate is the exact
  case that matters, and a deeper chain than one link should be configured as
  the intermediate rather than inferred."
  [configuration certificate]
  (let [roots (accredited-certificates configuration)]
    (boolean
     (some (fn [root]
             (or (= (vec (:x509/der certificate)) (vec (:x509/der root)))
                 (:verified (x509/verify-signature certificate root cms-jvm/verify))))
           roots))))

(defn- nonce
  "A 63-bit nonce.

  63 rather than 64: `asn1.core/integer` writes a minimal two's-complement
  INTEGER, so a value with the top bit set becomes negative, and some TSAs
  reject a negative nonce. Losing one bit is cheaper than a rejection an
  operator would have to reproduce to understand."
  []
  (let [bytes (byte-array 8)]
    (.nextBytes (SecureRandom.) bytes)
    (bit-and (reduce (fn [acc b] (+ (* acc 256) (bit-and b 0xff))) 0 bytes)
             0x7fffffffffffffff)))

(defn request-token
  "POST a `TimeStampReq` over `digest` and return the raw response bytes.

  Separated from `timestamp!` so a test can drive verification with a canned
  response and no network."
  [configuration digest nonce-value]
  (let [{:keys [url policy]} (settings configuration)
        body (asn1/ints->bytes
              (ts/request (cond-> {:digest digest :nonce nonce-value :cert-req? true}
                            policy (assoc :policy policy))))
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 20))
                    (.header "Content-Type" "application/timestamp-query")
                    (.POST (HttpRequest$BodyPublishers/ofByteArray body))
                    (.build))
        response (.send http-client request (HttpResponse$BodyHandlers/ofByteArray))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "TSA が " (.statusCode response) " を返しました。")
                      {:type :esign/tsa-http-error
                       :status (.statusCode response)
                       :url url})))
    (.body response)))

(defn attestation-of
  "What a verified token earns: `:accredited` or `:tsa-attested`.

  Two words rather than one because the difference is the whole legal question.
  A verified token from an unaccredited TSA is real evidence of time and is not
  what 電子帳簿保存法 names."
  [_configuration verification]
  (if (and (:verified verification) (true? (:trusted verification)))
    :accredited
    :tsa-attested))

(defn timestamp!
  "Obtain and verify a timestamp over `digest`.

  Returns nil when no TSA is configured — the caller records `:app-attested` and
  says so, which is what the app did before this existed. Throws when a TSA IS
  configured and the round trip fails, because an operator who asked for
  timestamps and is silently not getting them has a compliance gap they cannot
  see.

  The returned map is what an evidence record stores:

    {:timestamp/token-der    ints
     :timestamp/gen-time     \"2026-07-30T13:49:55Z\"
     :timestamp/attestation  :accredited | :tsa-attested
     :timestamp/tsa          the TSA's subject, for a human
     :timestamp/serial-number …
     :timestamp/policy       …}"
  ([digest] (timestamp! (config/load-config) digest))
  ([configuration digest]
   (when (configured? configuration)
     (let [n (nonce)
           response-bytes (request-token configuration digest n)
           response (ts/parse-response (asn1/->ints response-bytes))]
       (when-not (:success? response)
         (throw (ex-info (str "TSA が timestamp を発行しませんでした: "
                              (:status response) " "
                              (pr-str (:failure-info response)))
                         {:type :esign/tsa-rejected
                          :status (:status response)
                          :failure-info (:failure-info response)})))
       (let [token (ts/parse-token (:token-der response))
             verification (ts/verify-token
                           token
                           {:digest digest
                            ;; The nonce as hex, which is how TSTInfo reports it.
                            :nonce (let [h (.toString (biginteger n) 16)]
                                     (if (odd? (count h)) (str "0" h) h))
                            :digest-fn cms-jvm/digest
                            :verify-fn cms-jvm/verify
                            :trusted? #(accredited? configuration %)})]
         ;; A token this app could not verify is never stored. Storing one would
         ;; put a value in the evidence record that reads as a timestamp and is
         ;; not one — worse than the absence it replaced.
         (when-not (:verified verification)
           (throw (ex-info (str "TSA の token を検証できませんでした: "
                                (:reason verification))
                           {:type :esign/tsa-token-invalid
                            :reason (:reason verification)
                            :detail (:detail verification)})))
         {:timestamp/token-der (:token-der response)
          :timestamp/gen-time (:gen-time verification)
          :timestamp/attestation (attestation-of configuration verification)
          :timestamp/tsa (:tsa-subject verification)
          :timestamp/serial-number (:serial-number verification)
          :timestamp/policy (:policy verification)
          :timestamp/note (ts/accredited-note verification)})))))

(defn verify-stored
  "Re-verify a stored timestamp against the digest it should cover.

  Used by evidence verification, which must not take a stored
  `:timestamp/attestation` on trust: it was written by this app and the whole
  point of an evidence record is that a reader does not have to believe this
  app."
  [configuration {:timestamp/keys [token-der]} digest]
  (when token-der
    (let [token (ts/parse-token token-der)]
      (ts/verify-token token
                       {:digest digest
                        ;; No nonce: the request's nonce is not in the evidence
                        ;; record and does not belong there. Replay protection
                        ;; mattered at acquisition; what matters now is that the
                        ;; token covers this digest.
                        :digest-fn cms-jvm/digest
                        :verify-fn cms-jvm/verify
                        :trusted? #(accredited? configuration %)}))))
