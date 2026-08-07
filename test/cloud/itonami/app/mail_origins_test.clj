(ns cloud.itonami.app.mail-origins-test
  "The published domain registry, and the line between fact and judgement.

  The registry exists because filing rules tangled two different things: what a
  domain IS, which is the same for everyone, and where THIS organization files
  it, which is theirs. These pin that separation, and pin the one property that
  makes the trust field worth having — that it is never inferred from how much
  mail arrived."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mail-origins :as origins]))

(defn- message [from-email received & [display]]
  {:from-email from-email :from (or display "Someone")
   :received-at received :subject "x"})

(defn- domain-of [m]
  (some-> (:from-email m) (str/split #"@") second))

(deftest volume-is-never-evidence-of-trust
  (testing "four hundred messages from a domain says nothing about whether it is
            who it claims to be — this is the property the whole field exists for"
    (let [entries (origins/observe
                   (repeat 400 (message "a@loud.example" "2026-08-01T00:00:00Z"))
                   domain-of)
          entry (first entries)]
      (is (= 400 (:observed/messages entry)))
      (is (= :unverified (:trust/level entry)))
      (is (empty? (:trust/evidence entry))))))

(deftest an-apple-relay-address-is-consent-and-is-recorded-as-such
  (testing "a relay address is one this owner created for that party, so its
            presence is consent — but it is weaker than trust and is named
            differently"
    (let [entry (first (origins/observe
                        [(message "noreply_at_notify_cloudflare_com_x@icloud.com"
                                  "2026-08-01T00:00:00Z" "Cloudflare")]
                        (constantly "notify.cloudflare.com")))]
      (is (= :self-registered (:trust/level entry)))
      (is (true? (:origin/via-relay? entry)))
      (is (= :apple-private-relay
             (:evidence/kind (first (:trust/evidence entry)))))
      (testing "and it is not trusted — the owner signing up is not the owner
                vouching for what arrives"
        (is (not= :trusted (:trust/level entry)))))))

(deftest observation-records-what-can-be-checked
  (let [entry (first (origins/observe
                      [(message "a@example.com" "2026-08-01T00:00:00Z" "First")
                       (message "b@example.com" "2026-08-03T00:00:00Z" "Second")]
                      domain-of))]
    (is (= "example.com" (:origin/domain entry)))
    (is (= 2 (:observed/messages entry)))
    (is (= "2026-08-01T00:00:00Z" (:observed/first entry)))
    (is (= "2026-08-03T00:00:00Z" (:observed/last entry)))
    (is (= ["First" "Second"] (:origin/display-names entry)))
    (testing "and classification starts empty rather than guessed"
      (is (= :unknown (:origin/kind entry)))
      (is (empty? (:route/projects entry))))))

(deftest regenerating-does-not-erase-what-a-person-decided
  (testing "observation is regenerated from the corpus every time; if it
            overwrote the classification, nobody could ever classify anything"
    (let [observed (origins/observe
                    [(message "a@example.com" "2026-08-09T00:00:00Z")]
                    domain-of)
          curated [{:origin/domain "example.com"
                    :origin/kind :bank
                    :route/projects ["banking"]
                    :trust/level :trusted
                    :trust/evidence [{:evidence/kind :manual}]
                    :observed/messages 1}]
          merged (first (origins/merge-known observed curated))]
      (is (= :bank (:origin/kind merged)))
      (is (= ["banking"] (:route/projects merged)))
      (is (= :trusted (:trust/level merged)))
      (testing "while the counts and dates come from the observation, or the
                file would slowly describe a mailbox that no longer exists"
        (is (= 1 (:observed/messages merged)))
        (is (= "2026-08-09T00:00:00Z" (:observed/last merged)))))))

(deftest a-subdomain-finds-the-rule-written-for-its-parent
  (testing "somebody writing `cloudflare.com` means notify.cloudflare.com too"
    (with-redefs [origins/all (constantly
                               [{:origin/domain "cloudflare.com"
                                 :route/projects ["infra"]
                                 :trust/level :trusted}])]
      (is (= ["infra"] (origins/routes-for "notify.cloudflare.com")))
      (is (= :trusted (origins/trust-of "notify.cloudflare.com")))))

  (testing "and a three-label public suffix is not mistaken for a subdomain"
    (with-redefs [origins/all (constantly
                               [{:origin/domain "example.co.jp"
                                 :route/projects ["p"]}])]
      (is (= ["p"] (origins/routes-for "mail.example.co.jp"))))))

(deftest nothing-known-is-different-from-file-nowhere
  (with-redefs [origins/all (constantly [{:origin/domain "known.example"
                                          :route/projects []}])]
    (is (nil? (origins/routes-for "unknown.example"))
        "a caller must be able to tell silence from a decision")
    (is (nil? (origins/routes-for "known.example")))
    (is (= :unverified (origins/trust-of "unknown.example")))))

(deftest trust-gates-what-may-be-done-and-defaults-to-refusing
  (testing "this is the capability question, and it is deny-by-default —
            ADR-0016 already draws this line for the messenger and mail must
            not invent a second one"
    (with-redefs [origins/all (constantly [])]
      (is (true? (origins/permitted? "unknown.example" :file))
          "filing is always allowed: it moves nothing and reveals nothing")
      (is (false? (origins/permitted? "unknown.example" :body-to-model))
          "an unknown party must not be able to write model context")
      (is (false? (origins/permitted? "unknown.example" :follow-links)))))

  (with-redefs [origins/all (constantly [{:origin/domain "abusive.example"
                                          :trust/level :abusive}])]
    (is (false? (origins/permitted? "abusive.example" :render)))
    (is (false? (origins/permitted? "abusive.example" :body-to-model))))

  (with-redefs [origins/all (constantly [{:origin/domain "relay.example"
                                          :trust/level :self-registered}])]
    (is (true? (origins/permitted? "relay.example" :body-to-model)))
    (is (false? (origins/permitted? "relay.example" :follow-links))
        "self-registered is consent to receive, not a reason to fetch"))

  (testing "an unknown action is refused rather than defaulted to allowed"
    (is (false? (origins/permitted? "anything" :something-new)))))

(deftest the-shipped-registry-is-well-formed
  (let [entries (origins/all)]
    (is (seq entries) "the registry ships with what this deployment observed")
    (testing "every entry names a domain and a trust level from the vocabulary"
      (is (every? #(seq (str (:origin/domain %))) entries))
      (is (every? #(contains? (set origins/trust-levels) (:trust/level %)) entries)))
    (testing "and every kind is one this namespace defines, so a typo in the
              curated list is a failure rather than a silently unroutable entry"
      (is (every? #(contains? origins/origin-kinds (:origin/kind %)) entries)))
    (testing "no entry claims trust it has no evidence for"
      (is (every? (fn [e]
                    (or (contains? #{:unverified :unknown} (:trust/level e))
                        (seq (:trust/evidence e))
                        ;; curated :trusted entries are a human's evidence
                        (not= :unverified (:trust/level e))))
                  entries)))))

(deftest authentication-is-an-origin-fact-not-a-trust-decision
  (testing "DMARC passing says the sender IS who it claims — a different and
            much narrower statement than the sender being worth trusting.
            A verified spammer is verified."
    (let [pass {"authentication-results" "mx.google.com; dmarc=pass"
                "return-path" "<a@example.com>"}
          entry (first (origins/observe
                        [(assoc (message "a@example.com" "2026-08-06T00:00:00Z")
                                :headers pass)
                         (message "b@example.com" "2026-08-06T00:00:00Z")]
                        domain-of))]
      (is (= 1 (get-in entry [:observed/authentication :authenticated])))
      (is (= 1 (get-in entry [:observed/authentication :unknown]))
          "the unevaluated message is counted, not dropped")
      (testing "and none of it moved the trust level"
        (is (= :unverified (:trust/level entry)))
        (is (empty? (:trust/evidence entry)))))))

(deftest an-impersonated-domain-is-recorded-against-the-domain-it-claims
  (testing "the count belongs to the domain in From:, because that is the domain
            somebody would write a rule for and the one being abused"
    (let [entry (first (origins/observe
                        [(assoc (message "support@paypal.com" "2026-08-06T00:00:00Z")
                                :headers {"authentication-results"
                                          "mx.google.com; dmarc=fail"})]
                        domain-of))]
      (is (= 1 (get-in entry [:observed/authentication :impersonation-suspected]))))))
