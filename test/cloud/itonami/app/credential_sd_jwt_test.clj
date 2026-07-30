(ns cloud.itonami.app.credential-sd-jwt-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.credential-sd-jwt :as sdvc]
            [clojure.data.json]
            [ed25519.core]
            [jws.core]
            [sd-jwt.core :as sd]
            [sd-jwt-vc.core :as vc]))

(def ^:private subject "did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
(def ^:private org-did "did:web:acme.example")

(defn- issue []
  (sdvc/issue {:organization-did org-did
               :organization-domain "acme.example"
               :organization-name "Acme"
               :subject-did subject
               :role :auditor
               :issued-at 1893450000}))

;; ── the property this format exists for ──────────────────────────────────────

(deftest the-subject-can-be-withheld
  (let [{:keys [issuer-jwt disclosures]} (issue)]
    (is (= 1 (count disclosures)) "exactly one disclosable claim: sub")

    (testing "disclosing it proves who"
      (let [r (sdvc/verify (sdvc/present issuer-jwt disclosures))]
        (is (:valid? r))
        (is (true? (:subject-disclosed? r)))
        (is (= subject (:subject r)))))

    (testing "withholding it still proves the role and the organization — which is
              the whole point: 'someone at Acme is an auditor'"
      (let [r (sdvc/verify (sdvc/present issuer-jwt []))]
        (is (:valid? r))
        (is (false? (:subject-disclosed? r)))
        (is (nil? (:subject r)))
        (is (= "auditor" (:role r)))
        (is (= org-did (:issuer r)))
        (is (= "Acme" (get (:claims r) "organization_name")))))

    (testing "and the two presentations are not linkable by subject, because one
              of them does not contain it"
      (let [with (sdvc/present issuer-jwt disclosures)
            without (sdvc/present issuer-jwt [])]
        (is (str/includes? with (first disclosures)))
        (is (not (str/includes? without (first disclosures))))))))

(deftest the-role-is-not-disclosable
  (testing "a membership credential that could withhold its role would be
            asserting nothing, so role is deliberately always present"
    (let [{:keys [issuer-jwt]} (issue)
          r (sdvc/verify (sdvc/present issuer-jwt []))]
      (is (= "auditor" (get (:claims r) "role"))))))

(deftest protected-claims-are-refused-by-the-library
  (testing "iss/vct/exp are the Verifier's own inputs; the library refuses to
            conceal them and this app relies on that rather than re-checking"
    (is (= :sd-jwt-vc/protected-claim
           (:sd-jwt-vc/error
            (ex-data (try (vc/issue (sdvc/claims {:subject-did subject :role :member
                                                  :issued-at 1
                                                  :organization-did org-did})
                                    [["iss"]]
                                    {:json-encode (fn [_] "{}") :salt-fn (constantly (apply str (repeat 22 "a")))
                                     :alg "EdDSA" :sign (constantly (byte-array 64))})
                          (catch clojure.lang.ExceptionInfo e e))))))))

;; ── shape ────────────────────────────────────────────────────────────────────

(deftest the-credential-is-typed-and-named
  (let [{:keys [issuer-jwt vct]} (issue)]
    (is (= "https://acme.example/credentials/membership/v1" vct))
    (testing "a collision-resistant name under a domain this deployment controls"
      (is (str/starts-with? vct "https://")))
    (testing "and without a domain it is a urn rather than a bare word"
      (is (str/starts-with? (sdvc/vct nil) "urn:cloud-itonami:")))
    (is (= "dc+sd-jwt"
           (get (jws.core/decode-header issuer-jwt
                                        {:json-decode #(clojure.data.json/read-str %)})
                "typ")))))

(deftest no-cnf-means-bearer-presentable-and-it-says-so
  (testing "this app cannot hold a holder key and a Passkey cannot produce one, so
            the honest thing is to state what the token does not prove"
    (let [{:keys [issuer-jwt disclosures]} (issue)
          r (sdvc/verify (sdvc/present issuer-jwt disclosures))]
      (is (true? (:bearer-presentable? r)))
      (is (not (contains? (:claims r) "cnf"))))))

;; ── tamper and malformed ─────────────────────────────────────────────────────

(deftest tampering-is-detected
  (let [{:keys [issuer-jwt disclosures]} (issue)
        [h _ s] (str/split issuer-jwt #"\.")
        forged (str h "."
                    (jws.core/b64url
                     (clojure.data.json/write-str
                      (assoc (sdvc/claims {:subject-did subject :role :owner
                                           :issued-at 1 :organization-did org-did})
                             "_sd_alg" "sha-256")))
                    "." s)
        r (sdvc/verify (sd/present forged disclosures))]
    (is (false? (:valid? r)))))

(deftest malformed-input-is-an-answer
  (doseq [input [nil 42 "" "not.a.jwt" "a~b~"]]
    (let [r (sdvc/verify input)]
      (is (false? (:valid? r)) (str (pr-str input)))
      (is (some? (:reason r)) (str (pr-str input))))))

(deftest a-credential-from-another-issuer-does-not-verify
  (testing "verify uses this app's own key, so a token signed elsewhere fails"
    (let [other-seed (byte-array (repeat 32 (byte 99)))
          issued (vc/issue (sdvc/claims {:subject-did subject :role :member
                                         :issued-at 1 :organization-did org-did})
                           [["sub"]]
                           {:json-encode #(clojure.data.json/write-str %)
                            :json-decode #(clojure.data.json/read-str %)
                            :salt-fn (constantly (apply str (repeat 22 "b")))
                            :alg "EdDSA"
                            :sign (fn [i] (ed25519.core/sign other-seed i))})
          r (sdvc/verify (:presentation issued))]
      (is (false? (:valid? r))))))
