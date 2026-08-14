(ns cloud.itonami.app.mail-domain-records
  "SPF, DKIM and DMARC as they READ — given the TXT values, not a resolver.

  The judgements ADR-0048 is actually about live here, and they are string work:
  which record at a name is the one being asked about, whether an SPF policy
  closes, whether a DKIM record announces a key or announces that the key is
  gone, whether a DMARC policy enforces. None of that needs a JVM, and this
  repository's runtime order says new code should not assume one.

  Each function takes the TXT values at the relevant owner name. The lookup —
  which owner name, and how to reach DNS — stays with the host, which is the
  only part that could not move."
  (:require [clojure.string :as str]))

(defn of-kind
  "The first value that announces itself as `prefix`, or nil.

  Announced, not guessed. A zone can hold many TXT records at one name, and
  picking by position rather than by the `v=` tag is how a domain-verification
  token gets parsed as an SPF policy."
  [values prefix]
  (some (fn [value]
          (let [v (str/trim (str value))]
            (when (str/starts-with? (str/lower-case v) prefix) v)))
        values))

(defn spf
  "`{:present? :closed? :value}` for the SPF values at a domain.

  `closed?` is the whole point of reading it. `v=spf1 +all` is a syntactically
  valid record that authorizes every host on the internet to send as the domain;
  counting its presence as proof would be counting a blank page as a signature.

  `redirect=` is not followed, so a domain that delegates its terminal mechanism
  reads as not closed. A real limit, and the way past it is an explicit `-all`
  or `~all`."
  [values]
  (if-let [value (of-kind values "v=spf1")]
    {:present? true
     :closed? (boolean (re-find #"(?i)[-~]all\s*$" value))
     :value value}
    {:present? false :closed? false :value nil}))

(defn dkim
  "`{:present? :value}` for the DKIM values at `<selector>._domainkey.<domain>`.

  An empty `p=` is how a key is REVOKED in DKIM, so a record carrying one is a
  statement that the key is gone — the opposite of what its presence looks
  like."
  [values]
  (if-let [value (of-kind values "v=dkim1")]
    {:present? (boolean (re-find #"(?i)\bp=[A-Za-z0-9+/=]+" value)) :value value}
    {:present? false :value nil}))

(defn dmarc
  "`{:present? :enforcing? :policy :value}` for the values at `_dmarc.<domain>`.

  `enforcing?` is carried and, deliberately, not required by
  `domain_binding_core`: `p=none` is a real posture for a domain still reading
  reports, and refusing it would refuse most domains that have done the work."
  [values]
  (if-let [value (of-kind values "v=dmarc1")]
    (let [policy (some-> (re-find #"(?i)\bp\s*=\s*(none|quarantine|reject)" value)
                         second str/lower-case)]
      {:present? (some? policy)
       :enforcing? (contains? #{"quarantine" "reject"} policy)
       :policy policy
       :value value})
    {:present? false :enforcing? false :policy nil :value nil}))

(defn missing
  "Which of the three proofs is not established, in the words an owner needs.

  Named individually because \"未確認\" is three different problems in three
  different zones wearing one word."
  [{:keys [spf dkim dmarc]}]
  (cond-> []
    (not (:present? spf)) (conj "SPF レコードがありません")
    (and (:present? spf) (not (:closed? spf)))
    (conj "SPF が -all / ~all で閉じていません")
    (not (:present? dkim)) (conj "DKIM 公開鍵がありません")
    (not (:present? dmarc)) (conj "DMARC ポリシーがありません")))
