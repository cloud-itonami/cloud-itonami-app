(ns cloud.itonami.app.mail-domain-authority-test
  "The other authority a tenant can prove about a domain (ADR-0043).

  What is worth pinning here is not that three DNS records can be read — it is
  the two places where a record that EXISTS is not evidence: an SPF policy
  ending in `+all`, which authorizes the whole internet, and a DKIM record whose
  `p=` is empty, which is how a key is revoked. Both look like presence and are
  its opposite."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.domain-verification :as naming]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mail-domain-authority :as authority]
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
                       :status :active}
              "org-b" {:id "org-b" :tenant/kind :organization
                       :organization-id "other" :name "Other"
                       :domain (str "other." suffix) :domain-source :managed
                       :status :active}}
             :users {"user-a" {:id "user-a" :did "did:key:zAda"
                               :passkey-enrolled? true}
                     "user-b" {:id "user-b" :did "did:key:zBen"
                               :passkey-enrolled? true}}
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

(defn- with-state [run]
  (let [previous @store/state
        previous-profile (identity/identity-profile)
        f (fixture)]
    (try
      (identity/configure! {:identity {:account-domain suffix
                                       :organization-domain-suffix suffix}})
      (naming/configure! {})
      (reset! store/state (:state f))
      (run f)
      (finally
        (reset! store/state previous)
        (identity/configure! {:identity previous-profile})
        (naming/configure! {})))))

(defn- zone
  "A resolver over a literal map of owner name -> TXT values."
  [m]
  (fn [owner] (get m owner [])))

(def ^:private published
  {"example.com" ["v=spf1 include:_spf.example.net -all"]
   "sel._domainkey.example.com" ["v=DKIM1; k=rsa; p=MIIBIjANBgkq"]
   "_dmarc.example.com" ["v=DMARC1; p=quarantine; rua=mailto:d@example.com"]})

(defn- record [id] (get-in (store/snapshot) [:identity :mail-domain-authorities id]))

;; ── reading the three records ────────────────────────────────────────────────

(deftest an-spf-record-that-authorizes-everyone-is-not-a-proof
  (binding [naming/*txt-resolver*
            (zone {"open.example" ["v=spf1 include:_spf.example.net +all"]
                   "closed.example" ["v=spf1 -all"]
                   "soft.example" ["v=spf1 ~all"]
                   "noise.example" ["itonami-domain-verification=abc"
                                    "v=spf1 mx ~all"]})]
    (is (= {:present? true :closed? false
            :value "v=spf1 include:_spf.example.net +all"}
           (authority/spf "open.example"))
        "+all is a record that exists and says nothing")
    (is (:closed? (authority/spf "closed.example")))
    (is (:closed? (authority/spf "soft.example")))
    (is (= false (:present? (authority/spf "absent.example"))))
    (testing "picked by its v= tag, not by position"
      ;; A zone holds many TXT records at one name. Reading the first would let
      ;; a verification token be parsed as a mail policy.
      (is (:closed? (authority/spf "noise.example"))))))

(deftest a-revoked-dkim-key-is-a-record-that-says-the-key-is-gone
  (binding [naming/*txt-resolver*
            (zone {"s._domainkey.live.example" ["v=DKIM1; k=rsa; p=MIIBIjAN"]
                   "s._domainkey.revoked.example" ["v=DKIM1; k=rsa; p="]})]
    (is (:present? (authority/dkim "live.example" "s")))
    (is (= false (:present? (authority/dkim "revoked.example" "s")))
        "an empty p= is a revocation, and it looks exactly like presence")
    (is (= false (:present? (authority/dkim "absent.example" "s"))))))

(deftest a-dmarc-policy-is-read-for-what-it-says-and-whether-it-enforces
  (binding [naming/*txt-resolver*
            (zone {"_dmarc.monitor.example" ["v=DMARC1; p=none; rua=mailto:x@y"]
                   "_dmarc.strict.example" ["v=DMARC1; p=reject"]
                   "_dmarc.quarantine.example" ["v=DMARC1;p = quarantine"]})]
    (is (= {:present? true :enforcing? false :policy "none"
            :value "v=DMARC1; p=none; rua=mailto:x@y"}
           (authority/dmarc "monitor.example")))
    (is (:enforcing? (authority/dmarc "strict.example")))
    (is (:enforcing? (authority/dmarc "quarantine.example")))
    (is (= false (:present? (authority/dmarc "absent.example"))))))

;; ── the lifecycle ────────────────────────────────────────────────────────────

(deftest authority-needs-all-three-and-says-which-one-is-missing
  (with-state
    (fn [f]
      (let [started (authority/start! (:session-a f)
                                      {:domain "Example.COM." :selector "SEL"})]
        (is (= :pending (:status started)))
        (is (= "example.com" (:domain started)))
        (is (= "sel" (:selector started)))
        (is (= {:spf "example.com"
                :dkim "sel._domainkey.example.com"
                :dmarc "_dmarc.example.com"}
               (:expected started))
            "the owner is told the three owner names, not just that it failed")
        (testing "a refusal names the records, not the outcome"
          (binding [naming/*txt-resolver*
                    (zone (assoc published "sel._domainkey.example.com"
                                 ["v=DKIM1; k=rsa; p="]))]
            (let [thrown (try (authority/verify! (:session-a f)
                                                 {:authority-id (:id started)})
                              (catch clojure.lang.ExceptionInfo e e))]
              (is (= :mail-domain-authority/not-authorized
                     (:type (ex-data thrown))))
              (is (re-find #"DKIM 公開鍵がありません" (ex-message thrown))))))
        (testing "an SPF record that does not close is refused by name"
          (binding [naming/*txt-resolver*
                    (zone (assoc published "example.com" ["v=spf1 +all"]))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"-all / ~all"
                                  (authority/verify!
                                   (:session-a f)
                                   {:authority-id (:id started)})))))
        (testing "all three published"
          (binding [naming/*txt-resolver* (zone published)]
            (let [authorized (authority/verify! (:session-a f)
                                                {:authority-id (:id started)})]
              (is (= :authorized (:status authorized)))
              (is (some? (:authorized-at authorized)))
              (is (= "org-a" (authority/authorized-holder "example.com"))))))))))

(deftest a-monitoring-dmarc-policy-is-a-posture-and-not-a-failure
  ;; The asymmetry with SPF, at the level a deployment actually sees it: a
  ;; domain publishing `p=none` has done the work and is reading reports.
  (with-state
    (fn [f]
      (let [started (authority/start! (:session-a f)
                                      {:domain "example.com" :selector "sel"})]
        (binding [naming/*txt-resolver*
                  (zone (assoc published "_dmarc.example.com"
                               ["v=DMARC1; p=none; rua=mailto:d@example.com"]))]
          (is (= :authorized (:status (authority/verify!
                                       (:session-a f)
                                       {:authority-id (:id started)}))))
          (is (= false (get-in (record (:id started))
                               [:observed :dmarc :enforcing?]))
              "and the posture is still reported for what it is"))))))

(deftest one-tenant-holds-a-mail-domain-at-a-time
  (with-state
    (fn [f]
      (let [mine (authority/start! (:session-a f)
                                   {:domain "example.com" :selector "sel"})]
        (binding [naming/*txt-resolver* (zone published)]
          (authority/verify! (:session-a f) {:authority-id (:id mine)}))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Organization"
                              (authority/start! (:session-b f)
                                                {:domain "example.com"
                                                 :selector "sel"})))
        (testing "and a service-owned name is refused from the profile"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"サービス管理"
                                (authority/start! (:session-a f)
                                                  {:domain (str "mail." suffix)
                                                   :selector "sel"}))))
        (testing "a selector is required and validated"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"セレクタ"
                                (authority/start! (:session-a f)
                                                  {:domain "other.example"}))))))))

(deftest a-new-selector-does-not-inherit-the-old-selectors-proof
  (with-state
    (fn [f]
      (let [started (authority/start! (:session-a f)
                                      {:domain "example.com" :selector "sel"})]
        (binding [naming/*txt-resolver* (zone published)]
          (authority/verify! (:session-a f) {:authority-id (:id started)}))
        (is (= :authorized (:status (record (:id started)))))
        (let [rotated (authority/start! (:session-a f)
                                        {:domain "example.com" :selector "new"})]
          (is (= (:id started) (:id rotated)) "one record per tenant and domain")
          (is (= :pending (:status rotated))
              "a different key is a different claim — the old proof is about something else"))))))

(deftest a-pulled-record-lapses-on-the-sweep-and-returns-when-republished
  (with-state
    (fn [f]
      (let [started (authority/start! (:session-a f)
                                      {:domain "example.com" :selector "sel"})]
        (binding [naming/*txt-resolver* (zone published)]
          (authority/verify! (:session-a f) {:authority-id (:id started)}))
        (binding [naming/*txt-resolver*
                  (zone (dissoc published "_dmarc.example.com"))]
          (let [summary (authority/recheck-all!)]
            (is (= 1 (:scanned summary)))
            (is (= [{:authority-id (:id started) :domain "example.com"
                     :from :authorized :to :lapsed}]
                   (:changed summary)))))
        (is (nil? (authority/authorized-holder "example.com"))
            "a lapsed authority reserves nothing")
        (binding [naming/*txt-resolver* (zone published)]
          (authority/recheck-all!))
        (is (= :authorized (:status (record (:id started)))))
        (is (= "org-a" (authority/authorized-holder "example.com")))))))

(deftest a-sweep-that-measured-nothing-says-so
  (with-state
    (fn [_]
      (is (= {:scanned 0 :changed [] :failed []} (authority/recheck-all!))))))

;; ── the enforcement ──────────────────────────────────────────────────────────

(deftest a-proven-mail-domain-is-not-available-to-another-tenants-user
  (with-state
    (fn [f]
      (let [started (authority/start! (:session-a f)
                                      {:domain "example.com" :selector "sel"})]
        (binding [naming/*txt-resolver* (zone published)]
          (authority/verify! (:session-a f) {:authority-id (:id started)}))
        (testing "a member of the holding tenant is unaffected"
          (is (= "ada@example.com"
                 (authority/assert-sender-permitted! "ada@example.com"
                                                     "did:key:zAda"))))
        (testing "somebody else is refused, and told why"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"別の Organization"
               (authority/assert-sender-permitted! "ben@example.com"
                                                   "did:key:zBen"))))
        (testing "a domain nobody has proven is nobody's business"
          ;; Nearly every domain. Gating on unclaimed names would refuse
          ;; ordinary mail to make a point.
          (is (= "ben@gmail.com"
                 (authority/assert-sender-permitted! "ben@gmail.com"
                                                     "did:key:zBen"))))
        (testing "and a lapsed authority stops reserving the domain"
          (binding [naming/*txt-resolver*
                    (zone (dissoc published "sel._domainkey.example.com"))]
            (authority/recheck-all!))
          (is (= "ben@example.com"
                 (authority/assert-sender-permitted! "ben@example.com"
                                                     "did:key:zBen"))))))))
