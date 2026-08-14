(ns cloud.itonami.app.mail-domain-records-test
  "The two records that exist and are not evidence, on both runtimes."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [cloud.itonami.app.mail-domain-records :as records]))

(deftest a-record-is-picked-by-its-tag-and-never-by-position
  ;; A name can hold many TXT records. Reading the first is how a
  ;; domain-verification token gets parsed as a mail policy.
  (let [values ["itonami-domain-verification=abc123"
                "v=spf1 include:_spf.example.net ~all"
                "some-other-vendor=xyz"]]
    (is (= "v=spf1 include:_spf.example.net ~all" (records/of-kind values "v=spf1")))
    (is (nil? (records/of-kind values "v=dkim1")))
    (is (nil? (records/of-kind [] "v=spf1")))))

(deftest an-spf-record-that-authorizes-everyone-is-not-a-proof
  (is (= {:present? true :closed? false :value "v=spf1 +all"}
         (records/spf ["v=spf1 +all"]))
      "+all authorizes every host on the internet — a blank page, not a signature")
  (is (:closed? (records/spf ["v=spf1 -all"])))
  (is (:closed? (records/spf ["v=spf1 mx ~all"])))
  (is (:closed? (records/spf ["V=SPF1 MX ~ALL"])) "case-insensitively")
  (testing "redirect= is not followed, so it reads as not closed"
    (is (false? (:closed? (records/spf ["v=spf1 redirect=_spf.example.net"])))))
  (is (= {:present? false :closed? false :value nil} (records/spf []))))

(deftest a-revoked-dkim-key-is-a-record-that-says-the-key-is-gone
  (is (:present? (records/dkim ["v=DKIM1; k=rsa; p=MIIBIjANBgkq"])))
  (is (false? (:present? (records/dkim ["v=DKIM1; k=rsa; p="])))
      "an empty p= is a revocation, and it looks exactly like presence")
  (is (false? (:present? (records/dkim ["v=DKIM1; k=rsa"]))))
  (is (= {:present? false :value nil} (records/dkim []))))

(deftest a-dmarc-policy-is-read-for-what-it-says-and-whether-it-enforces
  (is (= "none" (:policy (records/dmarc ["v=DMARC1; p=none; rua=mailto:x@y"]))))
  (is (false? (:enforcing? (records/dmarc ["v=DMARC1; p=none"])))
      "monitoring is a real posture, and the core does not require enforcement")
  (is (:enforcing? (records/dmarc ["v=DMARC1; p=reject"])))
  (is (:enforcing? (records/dmarc ["v=DMARC1;p = quarantine"])) "whitespace and all")
  (is (false? (:present? (records/dmarc ["v=DMARC1; sp=reject"])))
      "a subdomain policy is not the policy")
  (is (= {:present? false :enforcing? false :policy nil :value nil}
         (records/dmarc []))))

(deftest a-refusal-names-the-records-rather-than-the-outcome
  (is (= [] (records/missing {:spf {:present? true :closed? true}
                              :dkim {:present? true}
                              :dmarc {:present? true}})))
  (is (= ["SPF レコードがありません" "DKIM 公開鍵がありません"
          "DMARC ポリシーがありません"]
         (records/missing {:spf {:present? false}
                           :dkim {:present? false}
                           :dmarc {:present? false}})))
  (testing "a present-but-open SPF is its own sentence, not \"missing\""
    (is (= ["SPF が -all / ~all で閉じていません"]
           (records/missing {:spf {:present? true :closed? false}
                             :dkim {:present? true}
                             :dmarc {:present? true}})))))
