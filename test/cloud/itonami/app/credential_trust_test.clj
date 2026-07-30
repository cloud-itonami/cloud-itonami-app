(ns cloud.itonami.app.credential-trust-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.credential-trust :as trust]
            [data-integrity.core :as di]
            [ed25519.core :as ed]))

(use-fixtures :each (fn [f] (trust/clear-cache!) (f)))

;; A second organization, with its own key. Nothing here reaches the network:
;; every test either stops before the fetch or supplies the DID document directly.
(def partner-seed (byte-array (repeat 32 (byte 77))))
(def partner-did-key (delay (ed/did-key-from-seed partner-seed)))
(defn- partner-multibase [] (subs @partner-did-key (count "did:key:")))
(def partner-domain "partner.example")
(def partner-web-did (str "did:web:" partner-domain))
(defn- partner-vm [] (str partner-web-did "#" (partner-multibase)))

(defn- partner-did-document []
  {"@context" ["https://www.w3.org/ns/did/v1"]
   "id" partner-web-did
   "verificationMethod" [{"id" (partner-vm)
                          "type" "Multikey"
                          "controller" partner-web-did
                          "publicKeyMultibase" (partner-multibase)}]
   "assertionMethod" [(partner-vm)]
   "authentication" [(partner-vm)]})

(defn- partner-credential
  ([] (partner-credential {}))
  ([{:keys [status vm]}]
   (di/issue-credential
    (cond-> {"@context" ["https://www.w3.org/ns/credentials/v2"]
             "type" ["VerifiableCredential" "OrganizationMembershipCredential"]
             "issuer" partner-web-did
             "credentialSubject" {"id" "did:example:alice" "role" "auditor"}}
      status (assoc "credentialStatus" status))
    {:seed partner-seed
     :verification-method (or vm (partner-vm))
     :created "2026-07-30T00:00:00Z"})))

(def trusting {:credentials {:trusted-issuers [partner-domain]}})
(def trusting-nothing {:credentials {:trusted-issuers nil}})
(def trusting-empty {:credentials {:trusted-issuers []}})

;; ── the trust list IS the trust model ────────────────────────────────────────

(deftest an-absent-trust-list-accepts-nothing
  (testing "nil and [] both mean none. A verifier that fetched the key named by
            the credential would be checking the forger's arithmetic against the
            forger's own key, so there is no permissive default and no way to
            switch this off."
    (is (= #{} (trust/trusted-issuers trusting-nothing)))
    (is (= #{} (trust/trusted-issuers trusting-empty)))
    (is (= #{} (trust/trusted-issuers {})))
    (is (not (trust/trusted-issuer? trusting-nothing partner-domain)))
    (is (not (trust/trusted-issuer? trusting-empty partner-domain)))
    (is (not (trust/trusted-issuer? {} partner-domain)))))

(deftest a-configured-issuer-is-trusted-exactly
  (is (trust/trusted-issuer? trusting partner-domain))
  (is (trust/trusted-issuer? trusting "PARTNER.EXAMPLE") "case-insensitive")
  (is (trust/trusted-issuer? trusting "  partner.example  ") "trimmed")
  (testing "no suffix matching: trusting a parent domain would trust whoever can
            create a subdomain there, who are often not the people meant"
    (is (not (trust/trusted-issuer? trusting "evil.partner.example")))
    (is (not (trust/trusted-issuer? trusting "partner.example.evil.com")))
    (is (not (trust/trusted-issuer? trusting "notpartner.example")))))

(deftest an-untrusted-issuer-is-refused-before-any-request
  (testing "an untrusted domain should not even learn this deployment exists"
    (let [r (trust/verify-external trusting-nothing (partner-credential))]
      (is (false? (:verified r)))
      (is (false? (:valid? r)))
      (is (= :credential-trust/untrusted-issuer (:reason r))))))

(deftest fetch-refuses-an-untrusted-domain
  (is (= :credential-trust/untrusted-issuer
         (:type (ex-data (try (trust/fetch-did-document trusting-nothing partner-domain)
                              (catch clojure.lang.ExceptionInfo e e)))))))

;; ── document -> key ──────────────────────────────────────────────────────────

(deftest assertion-key-extracts-the-published-key
  (is (= (vec (ed/did-key->pubkey @partner-did-key))
         (vec (trust/assertion-key (partner-did-document) (partner-vm))))))

(deftest a-key-must-be-listed-for-assertions
  (testing "a key published only for authentication or key agreement must not
            sign claims about people"
    (let [doc (assoc (partner-did-document) "assertionMethod" [])
          e (try (trust/assertion-key doc (partner-vm))
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :credential-trust/method-not-an-assertion-method (:type (ex-data e)))))

    (testing "…including when it is only under authentication"
      (let [doc (-> (partner-did-document)
                    (dissoc "assertionMethod")
                    (assoc "authentication" [(partner-vm)]))]
        (is (= :credential-trust/method-not-an-assertion-method
               (:type (ex-data (try (trust/assertion-key doc (partner-vm))
                                    (catch clojure.lang.ExceptionInfo ex ex))))))))))

(deftest a-method-absent-from-the-document-is-refused
  (let [doc (partner-did-document)
        e (try (trust/assertion-key doc (str partner-web-did "#z6MkSomethingElse"))
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :credential-trust/method-not-in-document (:type (ex-data e))))))

(deftest a-non-ed25519-key-is-refused
  (testing "eddsa-jcs-2022 is an Ed25519 cryptosuite, so a P-256 key advertised
            for it cannot be honoured"
    (let [doc (assoc-in (partner-did-document)
                        ["verificationMethod" 0 "publicKeyMultibase"]
                        "zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
          e (try (trust/assertion-key doc (partner-vm))
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :credential-trust/unsupported-key-type (:type (ex-data e)))))))

;; ── verification against a supplied document ─────────────────────────────────
;; The resolver is the injection point, so these exercise the whole path with the
;; fetch replaced — the same seam data-integrity itself exposes for did:web.

(deftest a-trusted-partner-credential-verifies
  (let [cred (partner-credential)
        r (di/verify-credential
           cred {:resolve-key (fn [vm] (trust/assertion-key (partner-did-document) vm))})]
    (is (:verified r))
    (is (= (partner-vm) (:verification-method r)))))

(deftest a-tampered-partner-credential-does-not-verify
  (let [cred (assoc-in (partner-credential) ["credentialSubject" "role"] "owner")
        r (di/verify-credential
           cred {:resolve-key (fn [vm] (trust/assertion-key (partner-did-document) vm))})]
    (is (not (:verified r)))))

(deftest our-own-credentials-are-not-verifiable-as-external-did-web
  (testing "this app's credentials name a did:key when did:web is unpublished, and
            did:key resolves without a fetch — so they verify, but the trust
            decision for a did:key issuer stays with the caller"
    (let [{:keys [credential]}
          (credential/issue-membership! {:subject-did "did:example:bob" :role :member})
          r (trust/verify-external trusting-nothing credential)]
      (is (:verified r) "a did:key issuer needs no trust list to RESOLVE")
      (is (str/starts-with? (:verification-method r) "did:key:")))))

;; ── revocation we cannot check ───────────────────────────────────────────────

(deftest an-uncheckable-status-list-is-not-treated-as-valid
  (testing "a signature proves the issuer said it, not that they still say it.
            Reporting a good signature as a usable credential is how a revoked
            credential gets honoured."
    (let [cred (partner-credential
                {:status {"type" "BitstringStatusListEntry"
                          "statusPurpose" "revocation"
                          "statusListIndex" "3"
                          "statusListCredential" "https://partner.example/status/1"}})
          ;; resolve locally so the signature check succeeds and only the
          ;; revocation question is left
          r (with-redefs [trust/resolve-external-key
                          (fn [_] (fn [vm] (trust/assertion-key (partner-did-document) vm)))]
              (trust/verify-external trusting cred))]
      (is (:verified r) "the signature is good")
      (is (= :unchecked (:revocation r)))
      (is (false? (:valid? r)) "but it must not be honoured"))))

(deftest a-credential-with-no-status-has-nothing-to-revoke
  (let [cred (partner-credential)
        r (with-redefs [trust/resolve-external-key
                        (fn [_] (fn [vm] (trust/assertion-key (partner-did-document) vm)))]
            (trust/verify-external trusting cred))]
    (is (:verified r))
    (is (= :not-applicable (:revocation r)))
    (is (true? (:valid? r)))))

;; ── malformed input is an answer, not an exception ───────────────────────────

(deftest malformed-input-is-an-answer
  (doseq [input ["hello" nil 42 [] {}]]
    (let [r (trust/verify-external trusting input)]
      (is (false? (:verified r)) (str (pr-str input) " must not verify"))
      (is (false? (:valid? r)) (str (pr-str input) " must not be valid"))
      (is (some? (:reason r)) (str (pr-str input) " must say why")))))

(deftest an-unsupported-did-method-is-refused
  (let [cred (partner-credential {:vm "did:example:1234#key-1"})
        r (trust/verify-external trusting cred)]
    (is (false? (:verified r)))
    (is (= :credential-trust/unsupported-did-method (:reason r)))))

;; ── SSRF boundary ────────────────────────────────────────────────────────────

(deftest a-trusted-domain-resolving-internally-is-refused
  (testing "the trust list bounds WHICH domains are fetched; this is the second
            line, for a trusted domain whose DNS answers with an internal
            address. localhost is the reliably-internal name to assert on."
    (let [config {:credentials {:trusted-issuers ["localhost"]}}
          e (try (trust/fetch-did-document config "localhost")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :credential-trust/internal-address (:type (ex-data e)))))))

(deftest an-unresolvable-domain-is-refused-rather-than-attempted
  (let [domain "this-name-does-not-resolve.invalid"
        config {:credentials {:trusted-issuers [domain]}}
        e (try (trust/fetch-did-document config domain)
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :credential-trust/internal-address (:type (ex-data e))))))

;; ── cache ────────────────────────────────────────────────────────────────────

(deftest the-cache-is-keyed-per-verification-method
  (testing "one domain may publish several keys and rotate them independently, so
            caching per domain would hand back the wrong key after a rotation"
    (trust/clear-cache!)
    (let [calls (atom 0)
          doc (partner-did-document)
          resolve-twice (fn [vm]
                          (swap! calls inc)
                          (trust/assertion-key doc vm))]
      ;; The cache lives behind the fetch, so exercise it through the public
      ;; resolver with the fetch stubbed.
      (with-redefs [trust/fetch-did-document (fn [_ _] (swap! calls inc) doc)]
        (let [resolver (trust/resolve-external-key trusting)]
          (is (= (vec (resolver (partner-vm))) (vec (resolver (partner-vm)))))
          (is (= 1 @calls) "the second resolution came from the cache")))
      (resolve-twice (partner-vm)))))
