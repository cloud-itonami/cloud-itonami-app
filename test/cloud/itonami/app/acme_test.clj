(ns cloud.itonami.app.acme-test
  "RFC 8555, against a transport that is not a CA.

  A recorded exchange proves this speaks the protocol's shape, not that Let's
  Encrypt accepts it. What it CAN prove, and what the two crypto conversions
  here need proving about, is that the bytes are right: a JWS whose signature
  verifies against the exact input it claims to cover, and a CSR whose signature
  covers exactly the structure the CSR carries.

  Both of those are places where a wrong answer is silent. An ECDSA signature
  reassembled at the wrong offsets is not malformed, it is merely invalid, and
  the CA's error names none of it."
  (:require [asn1.core :as asn1]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.acme :as acme])
  (:import [java.math BigInteger]
           [java.security KeyFactory Signature]
           [java.security.spec X509EncodedKeySpec]
           [java.util Arrays Base64]))

(defn- decode64 ^bytes [s] (.decode (Base64/getUrlDecoder) ^String s))

(defn- public-key [key]
  (.generatePublic (KeyFactory/getInstance "EC")
                   (X509EncodedKeySpec. (decode64 (:public key)))))

(defn- even-hex
  "`BigInteger/toString 16` padded to whole octets.

  `toString` drops a leading zero NIBBLE, so a coordinate whose top four bits
  are zero comes back 63 digits and `unhex` refuses it — correctly, since half
  an octet is not an octet. That happens about one run in eight, which is why
  this arrived as a flake on a tip rather than as a failure on the branch."
  [^BigInteger n]
  (let [hex (.toString n 16)]
    (cond-> hex (odd? (count hex)) (->> (str "0")))))

(defn- verify-es256
  "Verify a JWS `R || S` signature the way a CA does: rebuild the DER the JDK
  wants and hand it the exact signing input.

  `unsigned-integer-from-hex` and not `integer-from-hex`: r and s are positive,
  and the signed reader would encode one whose high bit is set as a NEGATIVE
  INTEGER — a DER signature that is not the one that was made."
  [key ^String input ^String signature]
  (let [raw (decode64 signature)
        r (BigInteger. 1 (Arrays/copyOfRange raw 0 32))
        s (BigInteger. 1 (Arrays/copyOfRange raw 32 64))
        der (asn1/encode (asn1/sequence*
                          [(asn1/unsigned-integer-from-hex (even-hex r))
                           (asn1/unsigned-integer-from-hex (even-hex s))]))]
    (.verify (doto (Signature/getInstance "SHA256withECDSA")
               (.initVerify (public-key key))
               (.update (.getBytes input "UTF-8")))
             der)))

(deftest the-verifier-in-this-file-handles-a-coordinate-with-a-leading-zero
  ;; Asserted directly rather than left to chance. The bug this replaces was
  ;; probabilistic — it passed every run on the branch and failed on the tip —
  ;; and a helper that is only right most of the time cannot be the thing that
  ;; says a signature is valid.
  (is (= "0a" (even-hex (BigInteger. "10"))))
  (is (= "ff" (even-hex (BigInteger. "255"))))
  (is (= "0100" (even-hex (BigInteger. "256")))))

;; ── the two conversions that fail silently ───────────────────────────────────

(deftest a-jws-signature-covers-the-input-it-claims-to
  (let [key (acme/generate-key)
        signed (acme/jws key {:nonce "abc" :url "https://ca.test/x"
                              :payload {"a" 1}})
        header (json/read-str (String. (decode64 (get signed "protected")) "UTF-8"))]
    (is (= #{"protected" "payload" "signature"} (set (keys signed)))
        "flattened JSON, not compact — RFC 8555 posts an object")
    (is (= "ES256" (get header "alg")))
    (is (= "abc" (get header "nonce")))
    (is (= "https://ca.test/x" (get header "url"))
        "the url is signed, so a signed request cannot be replayed at another")
    (is (= 64 (alength (decode64 (get signed "signature"))))
        "R || S, each padded to 32 — a 63-byte signature is merely invalid")
    (is (true? (verify-es256 key (str (get signed "protected") "."
                                      (get signed "payload"))
                             (get signed "signature"))))
    (testing "over many fresh signatures, not one"
      ;; ECDSA picks a random k per signature, so r and s differ every time and
      ;; a single sample exercises one shape of them. Twenty-five samples make
      ;; the ~1-in-8 short-coordinate case a near certainty rather than the
      ;; thing that shows up once on somebody else's branch.
      (dotimes [_ 25]
        (let [k (acme/generate-key)
              jws (acme/jws k {:nonce "n" :url "https://ca.test/x" :payload {}})]
          (is (true? (verify-es256 k (str (get jws "protected") "."
                                          (get jws "payload"))
                                   (get jws "signature")))))))))

(deftest an-account-key-announces-itself-until-it-has-a-kid
  (let [key (acme/generate-key)
        header #(json/read-str (String. (decode64 (get (acme/jws key %) "protected"))
                                        "UTF-8"))]
    (testing "newAccount carries the jwk, because the CA has nothing to look up"
      (is (contains? (header {:nonce "n" :url "u"}) "jwk"))
      (is (not (contains? (header {:nonce "n" :url "u"}) "kid"))))
    (testing "and everything after carries the kid instead"
      (let [h (header {:nonce "n" :url "u" :kid "https://ca.test/acct/1"})]
        (is (= "https://ca.test/acct/1" (get h "kid")))
        (is (not (contains? h "jwk"))
            "both would be a protocol error, not a redundancy")))
    (testing "POST-as-GET is an empty payload and not a missing one"
      (is (= "" (get (acme/jws key {:nonce "n" :url "u"}) "payload"))))))

(deftest a-thumbprint-is-over-the-three-members-in-order
  (let [key (acme/generate-key)
        jwk (acme/jwk key)]
    (is (= ["crv" "kty" "x" "y"] (vec (keys jwk)))
        "RFC 7638 hashes the canonical JSON — member order is the algorithm")
    (is (= 32 (alength (decode64 (get jwk "x")))) "a coordinate is fixed width")
    (is (= 32 (alength (decode64 (get jwk "y")))))
    (is (= (acme/thumbprint key) (acme/thumbprint key)))
    (is (str/starts-with? (acme/key-authorization key "tok")
                          "tok."))))

;; ── the CSR ──────────────────────────────────────────────────────────────────

(deftest a-csr-is-der-and-its-signature-covers-what-it-carries
  (let [key (acme/generate-key)
        der (acme/csr key ["example.com" "www.example.com"])
        decoded (asn1/decode der)
        info (asn1/nth-element decoded 0)]
    (is (true? (asn1/der-round-trips? der))
        "what a CA parses is what was signed, or it is neither")
    (is (= 0 (asn1/integer-value (asn1/nth-element info 0))))
    (is (= "1.2.840.10045.4.3.2"
           (asn1/oid-value (asn1/nth-element (asn1/nth-element decoded 1) 0)))
        "ecdsa-with-SHA256, and no parameters — the NULL is RSA's")
    (is (empty? (:asn1/elements (asn1/nth-element info 1)))
        "an empty subject: every name is in the SAN and asserting one twice is worse")
    (testing "the requested names are dNSName entries in a SAN extension"
      (let [attribute (asn1/nth-element (asn1/nth-element info 3) 0)
            extension (-> attribute (asn1/nth-element 1) (asn1/nth-element 0)
                          (asn1/nth-element 0))
            names (asn1/decode (byte-array
                                (map unchecked-byte
                                     (:asn1/content (asn1/nth-element extension 1)))))]
        (is (= "1.2.840.113549.1.9.14" (asn1/oid-value (asn1/nth-element attribute 0))))
        (is (= "2.5.29.17" (asn1/oid-value (asn1/nth-element extension 0))))
        (is (= ["example.com" "www.example.com"]
               (mapv #(apply str (map char (:asn1/content %)))
                     (:asn1/elements names))))))
    (testing "and the signature is over the CertificationRequestInfo's own bytes"
      ;; The check a hand-rolled encoder could not offer: the structure that was
      ;; signed is byte-for-byte the structure the CSR carries.
      (is (true? (.verify (doto (Signature/getInstance "SHA256withECDSA")
                            (.initVerify (public-key key))
                            (.update (byte-array (map unchecked-byte (:asn1/der info)))))
                          (byte-array (map unchecked-byte
                                           (rest (:asn1/content
                                                  (asn1/nth-element decoded 2)))))))))))

;; ── the order walk ───────────────────────────────────────────────────────────

(def ^:private directory
  {"newNonce" "https://ca.test/nonce"
   "newAccount" "https://ca.test/acct"
   "newOrder" "https://ca.test/order"})

(defn- recording-ca
  "A transport that answers like a CA and remembers what it was asked.

  `authz-statuses` is the sequence the authorization walks through, so a test
  can make an order that is pending once before it is valid — the case the
  polling loop exists for and the one a single-shot fake never exercises."
  [{:keys [authz-statuses order-status] :or {authz-statuses ["valid"]
                                             order-status "valid"}}]
  (let [calls (atom [])
        remaining (atom authz-statuses)]
    {:calls calls
     :transport
     (fn [method url _headers body]
       (swap! calls conj {:method method :url url
                          :body (when body (json/read-str body))})
       (cond
         (= url "https://ca.test/dir")
         {:status 200 :body (json/write-str directory) :headers {}}

         (= url "https://ca.test/nonce")
         {:status 200 :body "" :headers {"replay-nonce" "nonce-0"}}

         (= url "https://ca.test/acct")
         {:status 201 :body "{}"
          :headers {"location" "https://ca.test/acct/1" "replay-nonce" "nonce-1"}}

         (= url "https://ca.test/order")
         {:status 201
          :body (json/write-str
                 {"status" "pending"
                  "authorizations" ["https://ca.test/authz/1"]
                  "finalize" "https://ca.test/finalize/1"})
          :headers {"location" "https://ca.test/order/1" "replay-nonce" "nonce-2"}}

         (= url "https://ca.test/authz/1")
         (let [status (or (first @remaining) "valid")]
           (swap! remaining rest)
           {:status 200
            :body (json/write-str
                   {"identifier" {"type" "dns" "value" "example.com"}
                    "status" status
                    "challenges" [{"type" "dns-01" "url" "https://ca.test/chal/dns"
                                   "token" "dns-token"}
                                  {"type" "http-01" "url" "https://ca.test/chal/1"
                                   "token" "http-token-0123456789"
                                   "error" {"detail" "the CA could not fetch it"}}]})
            :headers {"replay-nonce" "nonce-3"}})

         (= url "https://ca.test/chal/1")
         {:status 200 :body "{}" :headers {"replay-nonce" "nonce-4"}}

         (= url "https://ca.test/finalize/1")
         {:status 200
          :body (json/write-str {"status" order-status
                                 "certificate" "https://ca.test/cert/1"})
          :headers {"replay-nonce" "nonce-5"}}

         (= url "https://ca.test/cert/1")
         {:status 200 :body "-----BEGIN CERTIFICATE-----\nchain\n" :headers {}}

         :else {:status 404 :body "{}" :headers {}}))}))

(deftest an-order-walks-to-a-chain-and-publishes-exactly-one-challenge
  (let [{:keys [calls transport]} (recording-ca {:authz-statuses ["pending" "valid"]})
        key (acme/generate-key)
        published (atom [])
        retracted (atom [])]
    (binding [acme/*transport* transport]
      (let [state (acme/session {:directory-url "https://ca.test/dir" :key key})]
        (acme/register! state)
        (is (= "https://ca.test/acct/1" (:account @state)))
        (let [pem (acme/order-certificate!
                   state ["example.com"]
                   {:publish! (fn [d t k] (swap! published conj [d t k]))
                    :retract! (fn [t] (swap! retracted conj t))
                    :csr-key key
                    :poll {:attempts 5 :sleep-ms 0}
                    :sleep (constantly nil)})]
          (is (str/includes? pem "BEGIN CERTIFICATE")))))
    (testing "the http-01 challenge was published, with the key authorization"
      (is (= [["example.com" "http-token-0123456789"
               (acme/key-authorization key "http-token-0123456789")]]
             @published)
          "and not the dns-01 one, which this deployment cannot answer"))
    (is (= ["http-token-0123456789"] @retracted)
        "and retracted once the order settled")
    (testing "the authorization was polled until it was valid"
      (is (= 2 (count (filter #(= "https://ca.test/authz/1" (:url %)) @calls)))))
    (testing "the CSR posted to finalize is for the domain that was ordered"
      (let [finalize (some #(when (= "https://ca.test/finalize/1" (:url %)) %) @calls)
            ;; The recorded body is the JWS envelope; the CSR is inside the
            ;; signed payload, which is the only place a CA looks either.
            payload (json/read-str (String. (decode64 (get (:body finalize) "payload"))
                                            "UTF-8"))
            der (.decode (Base64/getUrlDecoder) ^String (get payload "csr"))
            info (asn1/nth-element (asn1/decode der) 0)
            extension (-> (asn1/nth-element info 3) (asn1/nth-element 0)
                          (asn1/nth-element 1) (asn1/nth-element 0)
                          (asn1/nth-element 0))
            names (asn1/decode (byte-array
                                (map unchecked-byte
                                     (:asn1/content (asn1/nth-element extension 1)))))]
        (is (= ["example.com"]
               (mapv #(apply str (map char (:asn1/content %)))
                     (:asn1/elements names))))))))

(deftest a-failed-authorization-carries-what-the-ca-said
  ;; `pending` first: the walk reads the authorization once to find the
  ;; challenge before it starts polling, so a one-element sequence would be
  ;; consumed before the poll ever sees it.
  (let [{:keys [transport]} (recording-ca {:authz-statuses ["pending" "invalid"]})
        key (acme/generate-key)
        retracted (atom [])]
    (binding [acme/*transport* transport]
      (let [state (acme/session {:directory-url "https://ca.test/dir" :key key})]
        (acme/register! state)
        (let [thrown (try (acme/order-certificate!
                           state ["example.com"]
                           {:publish! (constantly nil)
                            :retract! (fn [t] (swap! retracted conj t))
                            :csr-key key
                            :poll {:attempts 2 :sleep-ms 0}
                            :sleep (constantly nil)})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :acme/authorization-failed (:type (ex-data thrown))))
          (is (str/includes? (ex-message thrown) "the CA could not fetch it")
              "the CA's own sentence, not a status code"))))
    (is (= ["http-token-0123456789"] @retracted)
        "a token left answering hands out a key authorization long after it proves anything")))

(deftest a-nonce-is-carried-forward-rather-than-refetched
  ;; ACME nonces are single-use and every response carries the next. Refetching
  ;; costs a HEAD per request and races: two requests that both fetched one make
  ;; the second `badNonce`.
  (let [{:keys [calls transport]} (recording-ca {})
        key (acme/generate-key)]
    (binding [acme/*transport* transport]
      (let [state (acme/session {:directory-url "https://ca.test/dir" :key key})]
        (acme/register! state)
        (acme/order! state ["example.com"])))
    (is (= 1 (count (filter #(= :head (:method %)) @calls)))
        "one HEAD for the whole session, not one per POST")))

(deftest a-problem-document-is-read-rather-than-discarded
  (binding [acme/*transport*
            (fn [_m _u _h _b]
              {:status 403
               :body (json/write-str {"type" "urn:ietf:params:acme:error:unauthorized"
                                      "detail" "no account exists with that key"})
               :headers {}})]
    (let [thrown (try (acme/directory "https://ca.test/dir")
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :acme/unexpected-response (:type (ex-data thrown))))
      (is (= "urn:ietf:params:acme:error:unauthorized"
             (:acme-error (ex-data thrown))))
      (is (str/includes? (ex-message thrown) "no account exists with that key")))))
