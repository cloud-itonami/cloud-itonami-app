(ns cloud.itonami.app.credential-rdf-test
  "Proves the membership credential's CLAIMS survive RDF canonicalization.

  This file exists because of a defect that no other test here could see. Under
  `eddsa-jcs-2022` — the suite this app signs with — JCS canonicalizes the JSON, so
  every key is protected whether or not it means anything. Under a `-rdfc-`
  cryptosuite the document is expanded to RDF first, and **expansion drops a term it
  cannot resolve**.

  `credentials/v2` defines none of this app's terms and sets no top-level `@vocab`.
  So before `membership-context` existed, the canonical RDF of a membership
  credential contained no `role`: it asserted that a subject was an
  `OrganizationMembershipCredential` and said nothing about what role they held.
  Signing that produces a perfectly verifiable credential asserting nothing.

  Every assertion below is on the canonical N-Quads — the bytes a `-rdfc-` suite
  hashes — rather than on the JSON. Checking the JSON is what let the defect hide."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.credential :as credential]
            [data-integrity.core :as di]
            [data-integrity.eddsa :as eddsa]
            [data-integrity.eddsa-rdfc :as eddsa-rdfc]
            [ed25519.core :as ed]
            [json-ld-api.core :as jld]
            [json-ld-api.to-rdf :as tordf]
            [rdf-canon.core :as c14n]))

;; The one context that is a remote reference. Supplied explicitly because
;; org-w3-json-ld-api never fetches: a fetched @context lets its host change what a
;; signature covers. Only the terms this app actually uses are pinned here — a
;; deployment that signs with a -rdfc- suite must pin the full published bytes.
;;
;; One visible consequence of using a subset: `BitstringStatusListEntry` resolves
;; here into https://www.w3.org/ns/credentials/ rather than the .../status# IRI the
;; real context gives it. That is this fixture's limit, not the app's — verified
;; against the full published context, the status terms resolve correctly. The
;; subset is deliberately minimal so the assertions below cannot pass by accident
;; on terms it happens to define.
(def ^:private credentials-v2-subset
  {"@context"
   {"@protected" true
    "id" "@id"
    "type" "@type"
    "VerifiableCredential"
    {"@id" "https://www.w3.org/2018/credentials#VerifiableCredential"
     "@context" {"@protected" true
                 "id" "@id" "type" "@type"
                 "credentialSubject"
                 {"@id" "https://www.w3.org/2018/credentials#credentialSubject"
                  "@type" "@id"}
                 "issuer" {"@id" "https://www.w3.org/2018/credentials#issuer"
                           "@type" "@id"}
                 "validFrom" {"@id" "https://www.w3.org/2018/credentials#validFrom"
                              "@type" "http://www.w3.org/2001/XMLSchema#dateTime"}
                 "credentialStatus"
                 {"@id" "https://www.w3.org/2018/credentials#credentialStatus"
                  "@type" "@id"}}}}})

(defn- canonical
  "The exact bytes a `-rdfc-` cryptosuite would hash."
  [document]
  (-> document
      (jld/expand {:contexts {credential/credentials-context credentials-v2-subset}})
      (tordf/to-rdf)
      (c14n/canonicalize)))

(def ^:private a-credential
  (credential/membership-credential
   {:organization-did "did:web:hooks.itonami.cloud:orgs:acme"
    :organization-domain "hooks.itonami.cloud"
    :subject-did "did:key:zDnaeVN8k7Yc9QKcVXbEXAMPLE"
    :role "auditor"
    :organization-name "Acme Co"
    :status-index 4
    :valid-from "2026-07-31T00:00:00Z"}))

;; ── the substance is in the signed graph ─────────────────────────────────────

(deftest the-role-survives-canonicalization
  (testing "the role IS the credential. Without membership-context this assertion
            failed: the canonical RDF had ten quads and none mentioned a role."
    (let [nq (canonical a-credential)]
      (is (str/includes? nq (str "<" credential/membership-vocabulary "role>"))
          (str "no role predicate in the signed graph:\n" nq))
      (is (str/includes? nq "\"auditor\"")
          "and the role's value, not merely its predicate"))))

(deftest the-organization-name-and-organization-survive
  (let [nq (canonical a-credential)]
    (is (str/includes? nq (str "<" credential/membership-vocabulary "organizationName>")))
    (is (str/includes? nq "\"Acme Co\""))
    (testing "and the organization is a node reference, not a string, because it is
              named by a DID — a DID as an opaque literal is not the same claim"
      (is (str/includes? nq
                         (str "<" credential/membership-vocabulary "organization> "
                              "<did:web:hooks.itonami.cloud:orgs:acme>"))))))

(deftest the-credential-type-is-in-this-apps-namespace-not-w3cs
  (testing "with no definition the type resolved into https://www.w3.org/ns/credentials/,
            a namespace this app does not own. That is wrong however the credential
            is signed."
    (let [nq (canonical a-credential)]
      (is (str/includes? nq (str "<" credential/membership-vocabulary
                                 "OrganizationMembershipCredential>")))
      (is (not (str/includes? nq "https://www.w3.org/ns/credentials/OrganizationMembership"))
          "must not claim a type IRI in W3C's namespace"))))

;; ── the regression this file was written to catch ────────────────────────────

(deftest without-the-context-the-claims-vanish
  (testing "the defect itself, pinned. If someone drops membership-context from the
            credential, the claims disappear from the signed graph and every proof
            still verifies — so this asserts the OLD shape really is lossy, and the
            tests above are not merely restating the implementation."
    (let [stripped (assoc a-credential "@context" [credential/credentials-context])
          nq (canonical stripped)]
      (is (not (str/includes? nq "auditor"))
          "the old shape genuinely lost the role — this is the bug, reproduced")
      (is (not (str/includes? nq "Acme Co")))
      (testing "while the credential still canonicalizes and would still sign
                cleanly, which is precisely why it was invisible"
        (is (pos? (count (str/split-lines nq))))
        (is (str/includes? nq "credentials#VerifiableCredential"))))))

;; ── the canonical form, printed once ─────────────────────────────────────────

(deftest the-canonical-form-is-readable
  (testing "the bytes a -rdfc- suite hashes, shown rather than described"
    (let [nq (canonical a-credential)]
      (println "\n  canonical N-Quads of a membership credential:")
      (doseq [l (str/split-lines nq)] (println "   " l))
      (testing "and every line is well-formed canonical N-Quads"
        (doseq [l (remove str/blank? (str/split-lines nq))]
          (is (str/ends-with? l " .") l))))))

;; ── stability, which is the reason to pay for canonicalization ───────────────

(deftest the-graph-is-stable-under-json-reordering
  (testing "a -rdfc- suite signs the graph, so a credential survives being
            reordered or re-serialized in transit — unlike JCS, which signs bytes"
    (let [reordered (into (sorted-map-by #(compare %2 %1)) a-credential)]
      (is (= (canonical a-credential) (canonical reordered)))))
  (testing "while a changed role changes the graph"
    (is (not= (canonical a-credential)
              (canonical (assoc-in a-credential ["credentialSubject" "role"] "guest"))))))

;; ── the app can now VERIFY an rdfc-signed credential ─────────────────────────
;; The allowlist containing a name proves nothing. This issues with the suite and
;; verifies through the app's own options, which is the path a real credential takes.

(deftest the-app-verifies-both-cryptosuites-through-its-own-options
  (let [seed (byte-array (repeat 32 (byte 31)))
        did (ed/did-key-from-seed seed)
        vm (str did "#" (subs did (count "did:key:")))
        base {:seed seed :verification-method vm :created "2026-07-31T00:00:00Z"}
        ;; a credential with only terms the pinned context defines, so this test is
        ;; about the cryptosuite rather than about term resolution
        doc {"@context" [credential/credentials-context]
             "id" "urn:uuid:99999999-8888-7777-6666-555555555555"
             "type" ["VerifiableCredential"]
             "issuer" did
             "validFrom" "2026-07-31T00:00:00Z"
             "credentialSubject" {"id" "did:example:someone"}}
        jcs (di/issue-credential doc base)
        rdfc (di/issue-credential doc (assoc base
                                             :suite eddsa-rdfc/suite
                                             :suite-opts {:contexts @credential/pinned-contexts}))
        ;; the app's shared options, with this test's key rather than the app's
        opts (credential/verify-opts {:resolve-key (fn [_] (ed/did-key->pubkey did))})]
    (is (= "eddsa-jcs-2022" (get-in jcs ["proof" "cryptosuite"])))
    (is (= "eddsa-rdfc-2022" (get-in rdfc ["proof" "cryptosuite"])))

    (testing "both verify under the SAME options — this is what makes a cutover
              possible, because a mixed corpus is what any cutover produces"
      (is (:verified (di/verify-credential jcs opts)))
      (is (:verified (di/verify-credential rdfc opts))))

    (testing "and a tampered rdfc credential still fails"
      (is (false? (:verified (di/verify-credential
                              (assoc-in rdfc ["credentialSubject" "id"] "did:example:other")
                              opts)))))

    (testing "a cryptosuite outside the allowlist is refused as a result, not a crash"
      (let [r (di/verify-credential rdfc (assoc opts :accept-suites [eddsa/suite]))]
        (is (false? (:verified r)))
        (is (= :data-integrity/unacceptable-cryptosuite (:reason r)))))))

(deftest the-pinned-context-is-the-published-one
  (testing "pinned BYTES, not a fetch: a fetched @context lets its host change what a
            signature covers after signing. The consequence is that these bytes are
            now part of this app's verification behaviour — if they change,
            credentials signed against the old ones may stop verifying."
    (let [ctx (get @credential/pinned-contexts credential/credentials-context)]
      (is (map? ctx))
      (is (contains? ctx "@context"))
      (testing "and it really is the VC 2.0 context, not a stub"
        (let [terms (get ctx "@context")]
          (is (contains? terms "VerifiableCredential"))
          (is (contains? terms "VerifiablePresentation"))
          (is (true? (get terms "@protected"))
              "@protected, so a later context cannot redefine these terms"))))))
