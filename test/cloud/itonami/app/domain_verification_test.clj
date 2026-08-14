(ns cloud.itonami.app.domain-verification-test
  "The two gates a custom domain passes before it names a tenant (ADR-0043).

  The decision itself is `domain_binding_core.kotoba` and its truth table lives
  in `oracle-cases` and `domain-binding-kotoba-parity-test`. What this file owns
  is everything the core deliberately does not know: DNS, the probe, the store,
  and the four things the ADR says implementation has to show."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.domain-verification :as verification]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(def ^:private suffix "example.test")

(defn- fixture []
  (let [now (store/now)]
    {:state
     (assoc (store/initial-state)
            :identity
            {:organizations
             {"org-a" {:id "org-a" :tenant/kind :organization
                       :organization-id "acme" :name "Acme"
                       :domain (str "acme." suffix) :domain-source :managed
                       :did (str "did:web:acme." suffix)
                       :status :active}
              "org-b" {:id "org-b" :tenant/kind :organization
                       :organization-id "other" :name "Other"
                       :domain (str "other." suffix) :domain-source :managed
                       :did (str "did:web:other." suffix)
                       :status :active}}
             :users {"user-a" {:id "user-a" :account-id "ada"
                               :did "did:key:zAda" :passkey-enrolled? true}
                     "user-b" {:id "user-b" :account-id "ben"
                               :did "did:key:zBen" :passkey-enrolled? true}}
             :memberships
             {"membership-a" {:id "membership-a" :organization-id "org-a"
                              :user-id "user-a" :role :owner :created-at now}
              "membership-b" {:id "membership-b" :organization-id "org-b"
                              :user-id "user-b" :role :owner :created-at now}}})
     :session-a {:id "session-a" :kind :passkey :issued-via :passkey
                 :authn-level :phishing-resistant :user-id "user-a"
                 :membership-id "membership-a" :organization-id "org-a"}
     :session-b {:id "session-b" :kind :passkey :issued-via :passkey
                 :authn-level :phishing-resistant :user-id "user-b"
                 :membership-id "membership-b" :organization-id "org-b"}}))

(defn- with-state
  "Run `run` against a fresh store and a deployment that publishes did:web.

  The profile matters here in a way it did not before: `service-owned-name?` is
  derived from it, and `membership-credential-context` only names a domain when
  the deployment publishes."
  [run]
  (let [previous @store/state
        previous-profile (identity/identity-profile)
        f (fixture)]
    (try
      (identity/configure! {:identity {:account-domain suffix
                                       :organization-domain-suffix suffix
                                       :publish-did-web? true}})
      ;; The deployment's own web origin, which is a THIRD source of
      ;; service-owned names and the one the retired literal was guarding.
      (verification/configure! {:server {:public-origin "https://itonami.cloud"}})
      (reset! store/state (:state f))
      (run f)
      (finally
        (reset! store/state previous)
        (identity/configure! {:identity previous-profile})
        (verification/configure! {})))))

(defn- record [id] (get-in (store/snapshot) [:identity :domain-verifications id]))
(defn- organization [id] (get-in (store/snapshot) [:identity :organizations id]))

(defn- answering
  "A prober that reports this deployment answering with the right nonce."
  [expected-nonce]
  (fn [_configuration _domain nonce]
    {:answered? (= expected-nonce nonce) :confidential? true :error nil}))

(defn- claim!
  "Start and claim `domain` for a session, with DNS made to agree."
  [session domain]
  (let [started (verification/start! session {:domain domain})]
    (binding [verification/*txt-resolver* (constantly [(:record-value started)])]
      (verification/claim! session {:verification-id (:id started)}))
    (record (:id started))))

(defn- activate! [session id]
  (binding [verification/*txt-resolver*
            (constantly [(:record-value (record id))])
            verification/*prober* (answering (:activation-nonce (record id)))]
    (verification/activate! {} session {:verification-id id})))

;; ── domains and the names this deployment already speaks for ─────────────────

(deftest domains-are-canonical
  (is (= "xn--r8jz45g.jp" (verification/normalize-domain "例え.JP.")))
  (is (nil? (verification/normalize-domain "https://example.com/path")))
  (is (nil? (verification/normalize-domain "com"))))

(deftest service-owned-names-are-refused-from-the-profile-not-a-literal
  ;; The guard used to be the literal `"itonami.cloud"` while
  ;; `:organization-domain-suffix` shipped as `cloud-itonami.app` — it refused a
  ;; name this deployment does not issue and left the ones it does unprotected.
  ;; All three sources are derived now.
  (with-state
    (fn [f]
      (testing "the suffix managed names are issued from"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"サービス管理"
                              (verification/start! (:session-a f)
                                                   {:domain (str "team." suffix)})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"サービス管理"
                              (verification/start! (:session-a f)
                                                   {:domain suffix}))))
      (testing "and the origin this deployment serves itself on"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"サービス管理"
                              (verification/start! (:session-a f)
                                                   {:domain "team.itonami.cloud"}))))
      (testing "a customer's own name is not refused"
        (is (= :pending (:status (verification/start! (:session-a f)
                                                     {:domain "example.com"}))))))))

;; ── Gate A: the naming right ─────────────────────────────────────────────────

(deftest a-human-owner-proves-one-exact-txt-record
  (with-state
    (fn [f]
      (let [started (verification/start! (:session-a f) {:domain "Example.COM."})]
        (is (= :pending (:status started)))
        (is (= "_itonami-verification.example.com" (:record-name started)))
        (is (re-matches #"itonami-domain-verification=[A-Za-z0-9_-]{43}"
                        (:record-value started)))
        (testing "the challenge never leaks the activation nonce"
          (is (nil? (:activation-nonce started)))
          (is (some? (:activation-nonce (record (:id started))))))
        (testing "absence is recorded but never treated as proof"
          (binding [verification/*txt-resolver* (constantly [])]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"まだ確認"
                                  (verification/claim!
                                   (:session-a f)
                                   {:verification-id (:id started)})))))
        (testing "the exact value establishes the claim, and only the claim"
          (binding [verification/*txt-resolver*
                    (fn [owner]
                      (is (= (:record-name started) owner))
                      ["unrelated=value" (:record-value started)])]
            (let [claimed (verification/claim!
                           (:session-a f) {:verification-id (:id started)})]
              (is (= :claimed (:status claimed)))
              (is (= claimed (verification/claim!
                              (:session-a f) {:verification-id (:id started)}))
                  "retrying a successful claim is idempotent"))))))))

(deftest a-proven-claim-does-not-name-the-tenant
  ;; The whole reason the gates are separated. Before ADR-0043 there was one
  ;; gate and its result was written nowhere; the failure mode being prevented
  ;; here is the opposite one — writing it after ONE proof, which publishes a
  ;; `did:web` at a name that answers nothing.
  (with-state
    (fn [f]
      (claim! (:session-a f) "example.com")
      (is (= (str "acme." suffix) (:domain (organization "org-a"))))
      (is (= :managed (:domain-source (organization "org-a"))))
      (is (= (str "did:web:acme." suffix) (:did (organization "org-a")))))))

(deftest ownership-is-tenant-scoped-exclusive-and-human-only
  (with-state
    (fn [f]
      (let [started (verification/start! (:session-a f) {:domain "example.com"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Organization"
                              (verification/claim!
                               (:session-b f) {:verification-id (:id started)})))
        (binding [verification/*txt-resolver*
                  (constantly [(:record-value started)])]
          (verification/claim! (:session-a f) {:verification-id (:id started)}))
        (testing "a claimed name is closed to another tenant at the START"
          ;; Refused before anyone is told to publish a record that could never
          ;; count — `:claimed` reserves the name, not only `:live`.
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Organization"
                                (verification/start! (:session-b f)
                                                     {:domain "example.com"}))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Passkey session"
             (verification/start!
              (assoc (:session-a f) :kind :agent :issued-via :local-ownership)
              {:domain "agent.example"})))))))

;; ── Gate B: the resolution fact ──────────────────────────────────────────────

(deftest activation-needs-the-name-to-answer-here-and-then-names-the-tenant
  (with-state
    (fn [f]
      (let [claimed (claim! (:session-a f) "example.com")]
        (testing "a name that does not resolve here is refused, with the reason"
          (binding [verification/*txt-resolver*
                    (constantly [(:record-value claimed)])
                    verification/*prober*
                    (constantly {:answered? false :confidential? true
                                 :error "document returned HTTP 404"})]
            (let [thrown (try (verification/activate!
                               {} (:session-a f) {:verification-id (:id claimed)})
                              (catch clojure.lang.ExceptionInfo e (ex-data e)))]
              (is (= :domain-verification/not-answering (:type thrown)))
              (is (= "document returned HTTP 404" (:probe-error thrown))
                  "the measurement is reported, not reduced to a boolean")))
          (is (= :claimed (:status (record (:id claimed))))
              "a failed activation leaves the claim standing"))
        (testing "answering with this binding's own nonce makes the name the tenant's"
          (let [live (activate! (:session-a f) (:id claimed))]
            (is (= :live (:status live)))
            (is (= "example.com" (:domain (organization "org-a"))))
            (is (= :verified (:domain-source (organization "org-a"))))
            (is (= "did:web:example.com" (:did (organization "org-a")))
                "the DID moves with the name — that is what proving one is for")))
        (testing "a nonce from somewhere else is not this deployment answering"
          (binding [verification/*txt-resolver*
                    (constantly [(:record-value claimed)])
                    verification/*prober* (answering "a-nonce-from-elsewhere")]
            (verification/recheck! {} (:session-a f)
                                   {:verification-id (:id claimed)}))
          (is (= :lapsed (:status (record (:id claimed))))))))))

(deftest the-nonce-answers-only-for-the-host-that-proved-it
  (with-state
    (fn [f]
      (let [mine (claim! (:session-a f) "example.com")
            theirs (claim! (:session-b f) "other.example")
            pending (verification/start! (:session-a f) {:domain "later.example"})]
        (is (= (:activation-nonce mine) (verification/nonce-for-host "example.com")))
        (is (= (:activation-nonce theirs)
               (verification/nonce-for-host "other.example")))
        (is (not= (:activation-nonce mine)
                  (verification/nonce-for-host "other.example"))
            "one tenant's nonce must never answer for another tenant's name")
        (testing "a port is stripped, as it is for did:web"
          (is (= (:activation-nonce mine)
                 (verification/nonce-for-host "example.com:8443"))))
        (testing "a name with no claim gets nothing — there is no fallback"
          (is (nil? (verification/nonce-for-host "unrelated.example")))
          (is (nil? (verification/nonce-for-host "")))
          (is (nil? (verification/nonce-for-host nil))))
        (testing "pointing your own DNS here before passing Gate A gets nothing"
          ;; The pending binding exists and its domain is known to this
          ;; deployment. Answering for it would let whoever controls that name's
          ;; DNS activate it without ever writing a TXT record.
          (is (= :pending (:status (record (:id pending)))))
          (is (nil? (verification/nonce-for-host "later.example"))))))))

;; ── the two things that must survive, and the one that must be taken back ────

(deftest a-live-name-survives-claiming-an-organization-id
  ;; `configure-organization!` re-derived `:domain` from the slug on every call.
  ;; With a verified name in the field that silently took the domain away while
  ;; the deployment kept answering at it.
  (with-state
    (fn [f]
      (let [claimed (claim! (:session-a f) "example.com")]
        (activate! (:session-a f) (:id claimed))
        (identity/configure-organization! (:session-a f) {:organization-id "acme"})
        (is (= "example.com" (:domain (organization "org-a"))))
        (is (= :verified (:domain-source (organization "org-a"))))
        (is (= "did:web:example.com" (:did (organization "org-a"))))
        (is (= "example.com"
               (:organization-domain
                (identity/membership-credential-context (:session-a f))))
            "a credential issued now names the proven domain")))))

(deftest a-lapse-reverts-the-name-and-retracts-nothing
  (with-state
    (fn [f]
      (let [claimed (claim! (:session-a f) "example.com")]
        (activate! (:session-a f) (:id claimed))
        (let [while-live (identity/membership-credential-context (:session-a f))
              credentials-before (:credentials (store/snapshot))]
          (is (= "example.com" (:organization-domain while-live)))
          (testing "the name stops answering"
            (binding [verification/*txt-resolver*
                      (constantly [(:record-value claimed)])
                      verification/*prober*
                      (constantly {:answered? false :confidential? false
                                   :error "handshake failed"})]
              (let [lapsed (verification/recheck!
                            {} (:session-a f) {:verification-id (:id claimed)})]
                (is (= :lapsed (:status lapsed)))
                (is (some? (:lapsed-at lapsed))))))
          (testing "the tenant reverts to its managed name"
            (is (= (str "acme." suffix) (:domain (organization "org-a"))))
            (is (= :managed (:domain-source (organization "org-a"))))
            (is (= (str "did:web:acme." suffix) (:did (organization "org-a")))))
          (testing "and nothing already issued is touched"
            (is (= credentials-before (:credentials (store/snapshot)))
                "a demotion is not a revocation, and this app has no revocation")
            (is (= "example.com" (:organization-domain while-live))
                "an assertion that was true when it was made stays true")
            (is (= (str "acme." suffix)
                   (:organization-domain
                    (identity/membership-credential-context (:session-a f))))
                "only the NEXT credential names the managed domain"))
          (testing "a lapse is reversible — the owner repoints DNS and activates"
            (activate! (:session-a f) (:id claimed))
            (is (= :live (:status (record (:id claimed)))))
            (is (= "example.com" (:domain (organization "org-a"))))))))))

;; ── the periodic sweep ───────────────────────────────────────────────────────

(defn- txt-from-store
  "A resolver that answers whatever the store says each binding's record is, so
  a sweep over several bindings does not need one resolver per record."
  []
  (fn [owner]
    (->> (vals (get-in (store/snapshot) [:identity :domain-verifications]))
         (keep #(when (= owner (:record-name %)) (:record-value %)))
         vec)))

(deftest the-sweep-visits-every-proven-binding-and-says-how-many-it-saw
  (with-state
    (fn [f]
      (let [mine (claim! (:session-a f) "example.com")
            theirs (claim! (:session-b f) "other.example")
            pending (verification/start! (:session-a f) {:domain "later.example"})]
        (activate! (:session-a f) (:id mine))
        (activate! (:session-b f) (:id theirs))
        (testing "one binding stops answering and only that one is demoted"
          (binding [verification/*txt-resolver* (txt-from-store)
                    verification/*prober*
                    (fn [_config domain nonce]
                      (if (= "example.com" domain)
                        {:answered? false :confidential? true
                         :error "document returned HTTP 404"}
                        {:answered? (= nonce (:activation-nonce (record (:id theirs))))
                         :confidential? true :error nil}))]
            (let [summary (verification/recheck-all! {})]
              (is (= 2 (:scanned summary))
                  "a pending binding has nothing proven to re-measure")
              (is (= [{:verification-id (:id mine) :domain "example.com"
                       :from :live :to :lapsed}]
                     (:changed summary)))
              (is (= [] (:failed summary))))))
        (is (= :lapsed (:status (record (:id mine)))))
        (is (= :live (:status (record (:id theirs)))))
        (is (= :pending (:status (record (:id pending)))))
        (testing "the tenant that lapsed reverted; the other kept its name"
          (is (= (str "acme." suffix) (:domain (organization "org-a"))))
          (is (= "other.example" (:domain (organization "org-b")))))))))

(deftest one-broken-domain-does-not-freeze-every-other-tenants-evidence
  (with-state
    (fn [f]
      (let [mine (claim! (:session-a f) "example.com")
            theirs (claim! (:session-b f) "other.example")]
        (activate! (:session-b f) (:id theirs))
        (binding [verification/*txt-resolver*
                  (fn [owner]
                    (if (= (:record-name mine) owner)
                      (throw (ex-info "DNS timed out" {}))
                      ((txt-from-store) owner)))
                  verification/*prober*
                  (fn [_config _domain nonce]
                    {:answered? (= nonce (:activation-nonce (record (:id theirs))))
                     :confidential? true :error nil})]
          (let [summary (verification/recheck-all! {})]
            (is (= 2 (:scanned summary)) "the sweep did not abort at the first throw")
            (is (= 1 (count (:failed summary))))
            (is (= "DNS timed out" (:error (first (:failed summary))))
                "the message is kept — a count is not something an operator can act on")))
        (is (= :live (:status (record (:id theirs))))
            "the healthy binding was still measured")))))

(deftest a-sweep-that-measured-nothing-is-not-a-sweep-where-all-was-well
  ;; The evidence floor. `{:scanned 0}` and "everything is fine" have to be
  ;; distinguishable in the return value, or an empty store reads as a clean
  ;; bill of health for domains nobody checked.
  (with-state
    (fn [_]
      (is (= {:scanned 0 :changed [] :failed []} (verification/recheck-all! {}))))))

;; ── the pre-check the create route needs ─────────────────────────────────────

(deftest a-domain-is-refused-before-a-tenant-is-created-for-it
  ;; `route-organization-create!` checks first and creates second, so an
  ;; unusable domain does not leave an organization behind. Catching the refusal
  ;; instead would make a rejected domain look exactly like an accepted one.
  (with-state
    (fn [f]
      (is (= "example.com" (verification/assert-claimable! "Example.COM.")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"完全なドメイン名"
                            (verification/assert-claimable! "https://x/y")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"サービス管理"
                            (verification/assert-claimable! (str "x." suffix))))
      (claim! (:session-b f) "taken.example")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Organization"
                            (verification/assert-claimable! "taken.example"))))))

(deftest a-challenge-is-replaced-rather-than-accumulated
  (with-state
    (fn [f]
      (let [first-try (verification/start! (:session-a f) {:domain "example.com"})
            second-try (verification/start! (:session-a f) {:domain "example.com"})]
        (is (= (:id first-try) (:id second-try))
            "one pending challenge per tenant and domain, not one per click")
        (is (not= (:record-value first-try) (:record-value second-try))
            "and a fresh token, so an abandoned value cannot be replayed")
        (is (= 1 (count (get-in (store/snapshot)
                                [:identity :domain-verifications]))))
        (testing "but a challenge is never re-issued over an established claim"
          (binding [verification/*txt-resolver*
                    (constantly [(:record-value second-try)])]
            (verification/claim! (:session-a f)
                                 {:verification-id (:id second-try)}))
          (let [again (verification/start! (:session-a f) {:domain "example.com"})]
            (is (= :claimed (:status again)))
            (is (= (:record-value second-try) (:record-value again))
                "re-issuing a token over a proof would throw the proof away")))))))
