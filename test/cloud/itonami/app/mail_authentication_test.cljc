(ns cloud.itonami.app.mail-authentication-test
  "Whether a message is from who it says it is from.

  The property these exist to hold is that absence is never upgraded into a
  pass. Most of the corpus was synced before these headers were kept, and a
  verdict function that answered `:authenticated` for a message it had no
  evidence about would be worse than the heuristics it replaced — those at least
  looked uncertain."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mail-authentication :as authentication]))

(defn- message [from headers]
  {:from-email from :headers headers})

(def ^:private gmail-pass
  "The shape Gmail actually writes."
  {"authentication-results"
   (str "mx.google.com; dkim=pass header.i=@stripe.com header.s=s1 header.b=Ab1;"
        " spf=pass (google.com: domain of bounce@stripe.com designates"
        " 192.0.2.1 as permitted sender) smtp.mailfrom=bounce@stripe.com;"
        " dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=stripe.com")
   "return-path" "<bounce@stripe.com>"})

(deftest a-message-with-no-headers-is-unknown-and-stays-unknown
  (testing "everything synced before these headers were kept is here, and
            calling it authenticated would be worse than the heuristics this
            replaced — those at least looked uncertain"
    (let [answer (authentication/verdict (message "a@example.com" nil))]
      (is (= :unknown (:verdict answer)))
      (is (= :unknown (:spf answer)))
      (is (= :unknown (:dkim answer)))
      (is (= :unknown (:dmarc answer)))
      (is (str/includes? (:reason answer) "保存"))))

  (testing "an empty header map is the same answer as no map at all"
    (is (= :unknown (:verdict (authentication/verdict (message "a@x.com" {})))))))

(deftest a-dmarc-pass-is-what-authenticated-means
  (let [answer (authentication/verdict (message "billing@stripe.com" gmail-pass))]
    (is (= :authenticated (:verdict answer)))
    (is (= :pass (:spf answer)))
    (is (= :pass (:dkim answer)))
    (is (= :pass (:dmarc answer)))
    (is (= "stripe.com" (:from-domain answer)))
    (is (= "stripe.com" (:envelope-domain answer)))))

(deftest a-dmarc-fail-is-the-one-conclusion-worth-drawing
  (testing "this is the check a heuristic cannot reach: a phishing message can
            pass SPF and DKIM for a domain the attacker controls while claiming
            somebody else in From:, and DMARC is exactly the test that those two
            agree"
    (let [answer (authentication/verdict
                  (message "support@paypal.com"
                           {"authentication-results"
                            (str "mx.google.com; spf=pass smtp.mailfrom=bounce@attacker.example;"
                                 " dkim=pass header.i=@attacker.example;"
                                 " dmarc=fail (p=REJECT) header.from=paypal.com")
                            "return-path" "<bounce@attacker.example>"}))]
      (is (= :impersonation-suspected (:verdict answer)))
      (is (= "paypal.com" (:from-domain answer)))
      (is (= "attacker.example" (:envelope-domain answer)))
      (testing "SPF and DKIM passing does NOT soften it — that is the attack"
        (is (= :pass (:spf answer)))
        (is (= :pass (:dkim answer)))))))

(deftest suspected-is-not-proven-even-here
  (testing "the word is `:impersonation-suspected`, not `:phishing`, because
            DMARC also fails for misconfigured senders. yabai's ledger keeps
            :suspected and :phishing apart for this reason"
    (let [answer (authentication/verdict
                  (message "a@example.com"
                           {"authentication-results" "mx.google.com; dmarc=fail"}))]
      (is (= :impersonation-suspected (:verdict answer)))
      (is (not= :phishing (:verdict answer))))))

(deftest a-forwarded-message-is-unaligned-and-not-accused
  (testing "mailing lists and forwarders break alignment constantly and are not
            impersonation — calling them so would make the signal useless within
            a week"
    (let [answer (authentication/verdict
                  (message "member@example.org"
                           {"authentication-results"
                            "mx.google.com; spf=pass smtp.mailfrom=list@mailinglist.example; dkim=none"
                            "return-path" "<list@mailinglist.example>"}))]
      (is (= :unaligned (:verdict answer)))
      (is (not= :impersonation-suspected (:verdict answer))))))

(deftest spf-is-read-from-either-header
  (testing "some servers write Received-SPF instead of, or as well as, the
            combined header"
    (is (= :pass (:spf (authentication/parse {"received-spf" "pass (google.com: …)"}))))
    (is (= :fail (:spf (authentication/parse
                        {"authentication-results" "mx.google.com; spf=fail"}))))))

(deftest header-names-are-matched-case-insensitively
  (testing "header case is not significant in mail, and a case-sensitive lookup
            would silently see no headers on a server that capitalises them"
    (let [answer (authentication/verdict
                  (message "a@stripe.com"
                           {"Authentication-Results" "mx.google.com; dmarc=pass"
                            "Return-Path" "<b@stripe.com>"}))]
      (is (= :authenticated (:verdict answer))))))

(deftest the-envelope-sender-is-unwrapped
  (is (= "bounce@stripe.com"
         (:envelope-from (authentication/parse {"return-path" "<bounce@stripe.com>"}))))
  (is (= "bounce@stripe.com"
         (:envelope-from (authentication/parse {"return-path" " BOUNCE@Stripe.com "})))))

(deftest a-summary-counts-the-unevaluated-rather-than-dropping-them
  (testing "a summary that excluded the unknowns would say a mailbox was fully
            checked when most of it was not"
    (let [summary (authentication/summarize
                   [(message "a@x.com" nil)
                    (message "b@x.com" nil)
                    (message "c@stripe.com" gmail-pass)])]
      (is (= 3 (:messages summary)))
      (is (= 1 (:evaluated summary)))
      (is (= 2 (get-in summary [:by-verdict :unknown])))
      (is (= 1 (get-in summary [:by-verdict :authenticated]))))))

(deftest the-retained-header-list-is-short-and-says-why
  (testing "keeping the whole envelope would put somebody's entire mail metadata
            in the store to answer one question"
    (is (= 3 (count authentication/retained-headers)))
    (is (contains? authentication/retained-headers "authentication-results"))
    (is (every? #(seq (str %)) (vals authentication/retained-headers))
        "each entry states its reason, so a later reader can tell whether a
         fourth header is warranted")))
