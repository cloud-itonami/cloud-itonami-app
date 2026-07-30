(ns cloud.itonami.app.credential-test
  (:require [clojure.string :as str]
            [clojure.walk]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.did :as app-did]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.web :as web]
            [data-integrity.core :as di]
            [ed25519.core :as ed]
            [status-list.core :as sl]))

;; Every test starts from a known state so that the status-index counter is
;; deterministic — an index allocated by a previous test would make revocation
;; assertions depend on test order.
(defn- reset-credential-state! []
  (store/transact!
   (fn [current]
     ;; :events is reset alongside :credentials because issuance and revocation
     ;; now append to it. Without this, every test in this namespace sees the
     ;; events left by the ones before it — which is exactly how the first
     ;; version of the audit tests failed, reporting 8 events for one issuance.
     (assoc current
            :credentials {:next-status-index 0 :revoked #{} :issued {}}
            :events []))))

(use-fixtures :each (fn [f] (reset-credential-state!) (f)))

(def ^:private subject-did "did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")
(def ^:private org-domain "acme.cloud-itonami.app")
(def ^:private org-did (str "did:web:" org-domain))

;; ── issue / verify ───────────────────────────────────────────────────────────

(deftest issued-membership-credential-verifies
  (let [{:keys [credential status-index]}
        (credential/issue-membership! {:organization-did org-did
                                       :organization-domain org-domain
                                       :organization-name "Acme"
                                       :subject-did subject-did
                                       :role :auditor})
        result (credential/verify credential)]
    (testing "it is a W3C VC with a real Data Integrity proof"
      (is (= ["VerifiableCredential" "OrganizationMembershipCredential"]
             (get credential "type")))
      (is (= "DataIntegrityProof" (get-in credential ["proof" "type"])))
      (is (= "eddsa-jcs-2022" (get-in credential ["proof" "cryptosuite"])))
      (is (= "assertionMethod" (get-in credential ["proof" "proofPurpose"])))
      (is (str/starts-with? (get-in credential ["proof" "proofValue"]) "z")))
    (testing "the claim is about the subject, not the issuer"
      (is (= subject-did (get-in credential ["credentialSubject" "id"])))
      (is (= "auditor" (get-in credential ["credentialSubject" "role"])))
      (is (= org-did (get credential "issuer"))))
    (testing "it verifies and is not revoked"
      (is (:verified result))
      (is (:valid? result))
      (is (not (:revoked? result)))
      (is (= 0 status-index))
      (is (= 0 (:status-index result))))))

(deftest tampering-with-the-role-invalidates-the-credential
  (testing "the whole point: a holder cannot promote themselves"
    (let [{:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :guest})
          promoted (assoc-in credential ["credentialSubject" "role"] "owner")
          result (credential/verify promoted)]
      (is (not (:verified result)))
      (is (not (:valid? result)))
      (is (= :data-integrity/bad-signature (:reason result))))))

(deftest tampering-with-the-subject-invalidates-the-credential
  (testing "a credential cannot be re-pointed at another holder"
    (let [{:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :member})
          stolen (assoc-in credential ["credentialSubject" "id"] "did:key:zSOMEONEELSE")]
      (is (not (:verified (credential/verify stolen)))))))

;; ── revocation ───────────────────────────────────────────────────────────────

(deftest revocation-makes-a-valid-signature-invalid
  (testing ":verified stays true and :valid? goes false — the distinction a
            caller must gate on, since a revoked credential is still correctly
            signed and reporting only :verified is how one gets honoured"
    (let [{:keys [credential status-index]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :admin})]
      (is (:valid? (credential/verify credential)))
      (credential/revoke! status-index)
      (let [after (credential/verify credential)]
        (is (:verified after) "the signature is still good")
        (is (:revoked? after))
        (is (not (:valid? after)) "but it must not be honoured")))))

(deftest revocation-is-idempotent
  (let [{:keys [credential status-index]}
        (credential/issue-membership! {:organization-did org-did
                                       :organization-domain org-domain
                                       :subject-did subject-did
                                       :role :member})]
    (credential/revoke! status-index)
    (credential/revoke! status-index)
    (is (not (:valid? (credential/verify credential))))
    (is (= #{0} (credential/revoked-indices (store/snapshot))))))

(deftest revoking-one-credential-does-not-revoke-another
  (testing "the bit index is per credential, so an off-by-one here would revoke
            an unrelated person's membership"
    (let [a (credential/issue-membership! {:organization-did org-did
                                           :organization-domain org-domain
                                           :subject-did subject-did
                                           :role :member})
          b (credential/issue-membership! {:organization-did org-did
                                           :organization-domain org-domain
                                           :subject-did "did:key:zOTHER"
                                           :role :auditor})]
      (is (= 0 (:status-index a)))
      (is (= 1 (:status-index b)))
      (credential/revoke! (:status-index a))
      (is (not (:valid? (credential/verify (:credential a)))))
      (is (:valid? (credential/verify (:credential b)))))))

(deftest a-status-index-is-allocated-before-signing
  (testing "an issued credential is always revocable: it must carry a
            credentialStatus, or there is no bit to flip"
    (let [{:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :member})
          entry (get credential "credentialStatus")]
      (is (some? entry))
      (is (= "BitstringStatusListEntry" (get entry "type")))
      (is (= "revocation" (get entry "statusPurpose")))
      (is (= "0" (get entry "statusListIndex")))
      (is (= (str "https://" org-domain "/credentials/status/1")
             (get entry "statusListCredential"))))))

;; ── the status list credential itself ────────────────────────────────────────

(deftest status-list-credential-is-signed-and-verifiable
  (testing "it is a credential like any other; an unverified list of zeros would
            un-revoke everything, so it must carry the issuer's proof"
    (credential/issue-membership! {:organization-did org-did
                                   :organization-domain org-domain
                                   :subject-did subject-did
                                   :role :member})
    (credential/revoke! 0)
    (let [unsigned (credential/status-list-credential (store/snapshot) org-domain)
          signed (credential/sign unsigned org-domain)]
      (testing "returned unsigned, so a caller cannot forget the proof by accident"
        (is (not (contains? unsigned "proof"))))
      (is (= ["VerifiableCredential" "BitstringStatusListCredential"]
             (get signed "type")))
      (is (:verified (di/verify-credential
                      signed
                      {:resolve-key (fn [_] (#'credential/local-resolver
                                             (credential/issuer-verification-method
                                              org-domain)))})))
      (testing "and the revoked bit really is in the published list"
        (let [bits (sl/expand (get-in signed ["credentialSubject" "encodedList"]))]
          (is (= 0x80 (first bits)) "index 0 revoked = most significant bit"))))))

;; ── did:web document ─────────────────────────────────────────────────────────

(deftest did-web-document-publishes-the-issuer-key
  (let [doc (credential/did-web-document org-domain)
        key-id (str org-did "#" (credential/issuer-public-key-multibase))]
    (is (= org-did (get doc "id")))
    (is (= [key-id] (get doc "assertionMethod"))
        "the key signs credentials, so it must be an assertionMethod")
    (is (= key-id (get-in doc ["verificationMethod" 0 "id"])))
    (is (= org-did (get-in doc ["verificationMethod" 0 "controller"])))
    (is (= (credential/issuer-public-key-multibase)
           (get-in doc ["verificationMethod" 0 "publicKeyMultibase"])))
    (testing "the key is not offered for key agreement, which it cannot do"
      (is (not (contains? doc "keyAgreement"))))
    (testing "no private key material is in a document meant to be public"
      (is (not (str/includes? (pr-str doc) "privateKey"))))
    (testing "a domain is required: a document for no organization would name a
              key as belonging to nobody"
      (is (thrown? clojure.lang.ExceptionInfo (credential/did-web-document nil)))
      (is (thrown? clojure.lang.ExceptionInfo (credential/did-web-document ""))))))

(deftest the-published-key-is-the-one-that-signs
  (testing "if the DID document advertised a different key than the issuer uses,
            every credential would fail verification at any external verifier
            while passing locally"
    (let [doc (credential/did-web-document org-domain)
          published (get-in doc ["verificationMethod" 0 "publicKeyMultibase"])
          {:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :member})
          vm (get-in credential ["proof" "verificationMethod"])]
      (is (str/ends-with? vm (str "#" published)))
      (is (= (str org-did "#" published) vm)))))

;; ── verificationMethod choice ────────────────────────────────────────────────

(deftest verification-method-falls-back-to-did-key
  (testing "naming an unpublished did:web would make credentials unverifiable
            outside this process, so the self-describing form is the fallback"
    (let [vm (credential/issuer-verification-method nil)]
      (is (str/starts-with? vm "did:key:z"))
      (is (str/includes? vm "#"))
      (testing "and it round-trips: the fragment is the key itself"
        (let [[controller fragment] (str/split vm #"#")]
          (is (= fragment (subs controller (count "did:key:"))))))))

  (testing "with a domain it names did:web, so an external verifier can resolve it"
    (is (= (str "did:web:" org-domain "#" (credential/issuer-public-key-multibase))
           (credential/issuer-verification-method org-domain)))))

(deftest credentials-signed-under-did-key-also-verify
  (testing "a deployment that never publishes did:web still issues usable
            credentials"
    (let [{:keys [credential]}
          (credential/issue-membership! {:subject-did subject-did :role :member})]
      (is (str/starts-with? (get-in credential ["proof" "verificationMethod"])
                            "did:key:"))
      (is (:valid? (credential/verify credential)))
      (testing "and its issuer is the did:key, not an invented did:web"
        (is (= (credential/issuer-did-key) (get credential "issuer")))))))

;; ── input discipline ─────────────────────────────────────────────────────────

(deftest unknown-roles-and-missing-subjects-are-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (credential/membership-credential {:subject-did subject-did
                                                  :role :superuser})))
  (is (thrown? clojure.lang.ExceptionInfo
               (credential/membership-credential {:subject-did nil :role :member})))
  (is (thrown? clojure.lang.ExceptionInfo
               (credential/membership-credential {:subject-did "" :role :member}))))

(deftest a-credential-signed-by-another-key-is-rejected
  (testing "the local resolver accepts only this app's own issuer key, so a
            credential minted elsewhere cannot be passed off as ours"
    (let [other-seed (byte-array (repeat 32 (byte 42)))
          other-did (ed/did-key-from-seed other-seed)
          forged (di/issue-credential
                  {"@context" [credential/credentials-context]
                   "type" ["VerifiableCredential" "OrganizationMembershipCredential"]
                   "issuer" other-did
                   "credentialSubject" {"id" subject-did "role" "owner"}}
                  {:seed other-seed
                   :verification-method (str other-did "#"
                                              (subs other-did (count "did:key:")))
                   :created (credential/now-timestamp)})]
      (is (thrown? clojure.lang.ExceptionInfo (credential/verify forged))))))

;; ── the P-256 / Ed25519 boundary ─────────────────────────────────────────────

(deftest the-subject-did-may-be-p256-because-the-subject-does-not-sign
  (testing "the app's user DID is did:key over P-256 (from WebAuthn ES256) while
            eddsa-jcs-2022 is Ed25519. That is fine for a SUBJECT — it is named,
            not a signer — and this test pins that it really is fine, because the
            reverse assumption is what would break holder-signed presentations."
    (let [p256-did (app-did/did-key-from-p256 (byte-array (repeat 32 (byte 1)))
                                              (byte-array (repeat 32 (byte 2))))
          {:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did p256-did
                                         :role :member})]
      (is (str/starts-with? p256-did "did:key:z"))
      (is (not (str/starts-with? p256-did "did:key:z6Mk"))
          "a P-256 did:key is not the Ed25519 z6Mk form")
      (is (:valid? (credential/verify credential)))
      (is (= p256-did (get-in credential ["credentialSubject" "id"]))))))

(deftest now-timestamp-is-a-valid-xsd-datetime-truncated-to-seconds
  (let [t (credential/now-timestamp)]
    (is (re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$" t)
        (str "expected second precision, got " t))
    (testing "org-w3-vc-data-integrity accepts it, which is the actual requirement"
      (is (:valid? (credential/verify
                    (:credential (credential/issue-membership!
                                  {:subject-did subject-did :role :member
                                   :valid-from t}))))))))

;; ── the register and revocation authorization ────────────────────────────────

(deftest issued-register-records-what-is-needed-to-revoke-and-nothing-more
  (testing "this app keeps the record, not the credential — the holder keeps the
            signed document, which is the point of it verifying without asking
            this server"
    (let [{:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :auditor})
          register (credential/issued-credentials (store/snapshot))
          record (first register)]
      (is (= 1 (count register)))
      (is (= 0 (:status-index record)))
      (is (= subject-did (:subject record)))
      (is (= "auditor" (:role record)))
      (is (= org-did (:issuer record)))
      (is (false? (:revoked? record)))
      (testing "the signed document itself is NOT in the register"
        (is (not (contains? record :credential)))
        (is (not (str/includes? (pr-str register) "proofValue")))
        (is (some? (get-in credential ["proof" "proofValue"]))
            "control: the credential handed back to the holder does have one")))))

(deftest register-reflects-revocation-and-orders-newest-first
  (dotimes [_ 3]
    (credential/issue-membership! {:organization-did org-did
                                   :organization-domain org-domain
                                   :subject-did subject-did
                                   :role :member}))
  (credential/revoke! 1)
  (let [register (credential/issued-credentials (store/snapshot))]
    (is (= [2 1 0] (mapv :status-index register)) "newest index first")
    (is (= [false true false] (mapv :revoked? register)))
    (is (= 3 (credential/issued-count (store/snapshot))))))

(deftest only-owner-and-admin-may-revoke
  (testing "revocation stops another person's credential from being honoured
            anywhere it is presented — strictly more power than a member holds"
    (is (credential/may-revoke? :owner))
    (is (credential/may-revoke? :admin))
    (is (credential/may-revoke? "owner") "string roles from JSON must work too")
    (is (not (credential/may-revoke? :member)))
    (is (not (credential/may-revoke? :auditor)))
    (is (not (credential/may-revoke? :guest)))
    (is (not (credential/may-revoke? nil)))))

;; ── verify-presented: the HTTP boundary ──────────────────────────────────────

(deftest verify-presented-turns-malformed-input-into-an-answer-not-an-error
  (testing "fed attacker-controlled JSON, a throw would surface as a 500 and tell
            the caller the server broke when their credential was junk"
    (doseq [[label input expected-reason]
            [["not a map" "hello" :credential/not-a-document]
             ["nil" nil :credential/not-a-document]
             ["a number" 42 :credential/not-a-document]
             ["empty map" {} :data-integrity/missing-proof]
             ["no proof" {"type" ["VerifiableCredential"]} :data-integrity/missing-proof]]]
      (let [r (credential/verify-presented input)]
        (is (false? (:verified r)) (str label " must not verify"))
        (is (false? (:valid? r)) (str label " must not be valid"))
        (is (= expected-reason (:reason r)) (str label " reason"))))))

(deftest verify-presented-rejects-a-credential-this-app-did-not-issue
  (testing "as an answer, not an exception: someone presenting a foreign
            credential is a normal verification outcome"
    (let [other-seed (byte-array (repeat 32 (byte 9)))
          other-did (ed/did-key-from-seed other-seed)
          foreign (di/issue-credential
                   {"@context" [credential/credentials-context]
                    "type" ["VerifiableCredential"]
                    "issuer" other-did
                    "credentialSubject" {"id" subject-did "role" "owner"}}
                   {:seed other-seed
                    :verification-method (str other-did "#"
                                               (subs other-did (count "did:key:")))
                    :created (credential/now-timestamp)})
          r (credential/verify-presented foreign)]
      (is (false? (:verified r)))
      (is (false? (:valid? r)))
      (is (= :credential/unknown-verification-method (:reason r))))))

(deftest verify-presented-agrees-with-verify-on-a-good-credential
  (let [{:keys [credential status-index]}
        (credential/issue-membership! {:organization-did org-did
                                       :organization-domain org-domain
                                       :subject-did subject-did
                                       :role :member})]
    (is (= (credential/verify credential) (credential/verify-presented credential)))
    (credential/revoke! status-index)
    (let [r (credential/verify-presented credential)]
      (is (:verified r) "still correctly signed")
      (is (false? (:valid? r)) "but revoked, so not to be honoured"))))

(deftest verify-presented-does-not-swallow-programming-errors
  (testing "only ex-info is caught. A credential whose proof is not a map makes
            data-integrity raise ex-info, but a genuine NPE must still propagate,
            or a real bug would hide behind a tidy :valid? false."
    (is (= :data-integrity/missing-proof
           (:reason (credential/verify-presented {"proof" "not-a-map"}))))
    ;; The failure has to happen AFTER the proof checks pass, or the snapshot is
    ;; never touched and the test proves nothing (which is what a first version
    ;; of it did). So: a genuinely valid credential, and a snapshot whose
    ;; :revoked is not seqable — `(set :nope)` raises IllegalArgumentException,
    ;; which is not ex-info and must therefore propagate.
    (let [{:keys [credential]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :member})]
      (is (:valid? (credential/verify-presented credential))
          "control: this credential is fine against a real snapshot")
      (is (thrown? IllegalArgumentException
                   (credential/verify-presented
                    credential
                    {:credentials {:next-status-index 0 :revoked :nope :issued {}}}))))))

;; ── the rendered surface ─────────────────────────────────────────────────────
;; Six endpoints nobody can reach from the app are not a feature, and a UI claim
;; that is not asserted is the same unproven-claim problem as an untested
;; portability claim. These pin the view's existence, the controls that reach each
;; endpoint, and — deliberately — the two honest caveats, because a screen that
;; showed "有効" without them would imply more than a signature establishes.

(deftest the-credentials-view-is-rendered
  (with-redefs [cloud.itonami.app.store/snapshot
                (constantly (cloud.itonami.app.store/initial-state))]
    (let [html (cloud.itonami.app.web/page-html
                (cloud.itonami.app.config/load-config))]
      (testing "the view and its nav entry exist"
        (is (re-find #"data-view-panel=\"credentials\"" html))
        (is (re-find #"data-view=\"credentials\"" html))
        (is (re-find #"id=\"credentials-count\"" html)))

      (testing "a control for every endpoint the plane exposes"
        (is (re-find #"id=\"credential-issue\"" html) "POST /api/credentials/membership")
        (is (re-find #"id=\"credential-list\"" html) "GET /api/credentials")
        (is (re-find #"id=\"credential-verify-form\"" html) "POST /api/credentials/verify")
        (is (re-find #"id=\"credential-verify-external\"" html)
            "POST /api/credentials/verify/external")
        (is (re-find #"id=\"credential-trusted-issuers\"" html)
            "GET /api/credentials/trusted-issuers")
        (testing "revocation has no static control — the buttons are per row, so
                  the register render is what reaches that endpoint"
          (is (re-find #"/revoke" html))))

      (testing "the honest caveats are on the screen, not only in a docstring"
        (is (re-find #"Verifiable Presentation" html)
            "a Passkey cannot produce a Data Integrity proof; say so")
        (is (re-find #"authenticatorData" html) "and say why")
        (is (re-find #"信頼モデル" html)
            "for did:web the trust list IS the trust model"))

      (testing "DADS discipline: app layout is local-* and no raw hex or px type"
        (is (re-find #"class=\"local-actions\"" html))
        (is (nil? (re-find #"class=\"form-actions\"" html))
            "form-actions is not a class this app defines"))

      (testing "the SD-JWT VC format is reachable from the screen, not only over HTTP"
        ;; The previous change added two routes and left them unreachable from the
        ;; UI, which is the dormant-endpoint problem one layer out from the
        ;; dormant-library one.
        (is (re-find #"id=\"credential-issue-sd-jwt\"" html))
        (is (re-find #"id=\"credential-verify-sd-jwt\"" html))
        (is (re-find #"id=\"credential-sd-jwt-result\"" html))
        (testing "and the screen states what the format does NOT prove"
          (is (re-find #"bearer-presentable" html))
          (is (re-find #"所持者拘束" html))
          (is (re-find #"authenticatorData" html))))

      (testing "accessibility: motion is not forced on anyone (WCAG 2.3.3)"
        ;; Measured, not assumed: kotoba-lang/design-quality scored this page
        ;; 87.64 with `reduced-motion` as the ONLY finding, and 100.00 once the
        ;; guard was added. This app is on DADS rather than kotoba-ui, so it gets
        ;; no reduced-motion base layer for free and has to say it itself —
        ;; which means a stylesheet edit can silently take it away again.
        (is (re-find #"prefers-reduced-motion" html))
        (is (re-find #"\.typing span\{animation:none" html))
        (is (re-find #"\.skeleton\{animation:none" html)))

      (testing "accessibility: the status regions announce"
        (is (re-find #"id=\"credential-issue-status\"" html))
        (is (re-find #"aria-live=\"polite\"" html))))))

;; ── the audit ledger ─────────────────────────────────────────────────────────
;; Issuance and revocation are the two security-relevant acts in this plane, and
;; neither of them recorded anything until this was added. The credential itself
;; cannot answer "who did this": its issuer is the organization, not the person.

(defn- credential-events []
  (filterv #(#{:credential/issued :credential/revoked} (:type %))
           (:events (store/snapshot))))

(deftest issuance-is-recorded-with-its-actor
  (let [{:keys [credential status-index]}
        (credential/issue-membership! {:organization-did org-did
                                       :organization-domain org-domain
                                       :subject-did subject-did
                                       :role :auditor
                                       :actor "user-alice"})
        [event & more] (credential-events)]
    (is (empty? more) "exactly one event")
    (is (= :credential/issued (:type event)))
    (is (= "user-alice" (:actor event)) "who pressed the button")
    (is (= subject-did (:subject event)) "about whom")
    (is (= "auditor" (:role event)))
    (is (= status-index (:status-index event)) "which bit can withdraw it")
    (is (= org-did (:issuer event)))
    (is (= (get-in credential ["proof" "verificationMethod"])
           (:verification-method event))
        "which key signed, so a later rotation does not orphan the record")
    (is (= (get credential "validFrom") (:at event)))
    (testing "the register also remembers who issued"
      (is (= "user-alice" (:issued-by (first (credential/issued-credentials
                                             (store/snapshot)))))))))

(deftest revocation-is-recorded-with-its-actor
  (let [{:keys [status-index]}
        (credential/issue-membership! {:organization-did org-did
                                       :organization-domain org-domain
                                       :subject-did subject-did
                                       :role :member
                                       :actor "user-alice"})]
    (credential/revoke! status-index "user-bob")
    (let [events (credential-events)
          revocation (last events)]
      (is (= 2 (count events)))
      (is (= :credential/revoked (:type revocation)))
      (is (= "user-bob" (:actor revocation))
          "revocation stops a credential being honoured anywhere; an
           unattributed one is a hole in the record that matters most")
      (is (= status-index (:status-index revocation)))
      (is (= subject-did (:subject revocation)) "whose credential was withdrawn")
      (is (string? (:at revocation)))
      (is (not (contains? revocation :already-revoked?))))))

(deftest revoking-twice-is-idempotent-in-effect-but-not-in-the-ledger
  (testing "two attempts happened, so two events are recorded — collapsing them
            would lose the fact that somebody tried again, which is the sort of
            thing an auditor is looking for"
    (let [{:keys [status-index]}
          (credential/issue-membership! {:organization-did org-did
                                         :organization-domain org-domain
                                         :subject-did subject-did
                                         :role :member})
          first-result (credential/revoke! status-index "user-bob")
          second-result (credential/revoke! status-index "user-carol")
          revocations (filterv #(= :credential/revoked (:type %)) (credential-events))]
      (testing "the effect is the same both times"
        (is (:revoked? first-result))
        (is (:revoked? second-result))
        (is (= #{status-index} (credential/revoked-indices (store/snapshot)))))
      (testing "but the second is marked and attributed to whoever retried"
        (is (= 2 (count revocations)))
        (is (not (contains? (first revocations) :already-revoked?)))
        (is (true? (:already-revoked? (second revocations))))
        (is (true? (:already-revoked? second-result)))
        (is (= ["user-bob" "user-carol"] (mapv :actor revocations)))))))

(deftest an-actor-is-optional-only-for-callers-that-have-no-session
  (testing "a CLI or a test has no session, so this must not throw — but the
            event then says so with nil rather than inventing an actor"
    (let [{:keys [status-index]}
          (credential/issue-membership! {:subject-did subject-did :role :member})]
      (credential/revoke! status-index)
      (let [events (credential-events)]
        (is (= 2 (count events)))
        (is (every? #(contains? % :actor) events)
            "the key is always present, so a reader cannot mistake absence for
             an actor that was not recorded")
        (is (every? #(nil? (:actor %)) events))))))

(deftest a-keyword-keyed-credential-still-has-its-revocation-checked
  ;; The regression test for a real bug, found by the HTTP suite and reproduced
  ;; here so it does not need a server to catch.
  ;;
  ;; `verify` read `credentialStatus` off its raw argument while
  ;; `di/verify-credential` normalized internally. A keyword-keyed document --
  ;; which is exactly what `server/read-json` produces, since it keywordizes every
  ;; key at every depth -- therefore passed the SIGNATURE check and silently
  ;; skipped the REVOCATION check. A revoked credential verified as valid.
  ;;
  ;; The signature succeeding is what made it invisible: nothing errored, and the
  ;; only wrong field was the one that decides whether to honour the thing.
  (let [{:keys [credential status-index]}
        (credential/issue-membership! {:organization-did org-did
                                       :organization-domain org-domain
                                       :subject-did subject-did
                                       :role :member})
        ;; the same document as it arrives from a keywordizing JSON reader
        keywordized (clojure.walk/keywordize-keys credential)]
    (testing "both key styles verify before revocation"
      (is (:valid? (credential/verify credential)))
      (is (:valid? (credential/verify keywordized))))

    (credential/revoke! status-index)

    (testing "and BOTH must see the revocation afterwards"
      (let [string-keyed (credential/verify credential)
            keyword-keyed (credential/verify keywordized)]
        (is (false? (:valid? string-keyed)))
        (is (false? (:valid? keyword-keyed))
            "this is the assertion that was false before the fix")
        (is (true? (:revoked? keyword-keyed)))
        (is (= (:valid? string-keyed) (:valid? keyword-keyed))
            "the key style must not change the answer")))))
