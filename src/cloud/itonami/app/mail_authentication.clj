(ns cloud.itonami.app.mail-authentication
  "Whether a message is from who it says it is from.

  ## Why this exists

  The abuse ledger published to yabai on 2026-08-06 recorded, in its `:gaps`,
  that impersonation could not be detected **at all** — not unimplemented, but
  structurally impossible, because the sync dropped every header that carries
  the answer. The only usable evidence kind was `:reporter`: a human noticing.

  Meanwhile the heuristics that were available produced nothing but false
  positives. A subject containing 認証 or 至急 is not evidence; the legitimate
  mail is what uses those words. `ftx.com` looked like a scam and was a real
  bankruptcy estate. `三井住友カード` looked like a mismatch and cannot appear
  inside a latin-script domain.

  So the answer was never a cleverer guess. It was to keep the three headers the
  receiving mail server already computed.

  ## What the receiving server already decided

  Gmail authenticates every inbound message and writes the result into
  `Authentication-Results`. That header is not the sender's claim — it is the
  receiver's verdict, computed with DNS the sender does not control:

  - **SPF** — was the connecting IP allowed to send for the envelope domain
  - **DKIM** — is the body signed by a key published in the signing domain's DNS
  - **DMARC** — does an authenticated domain ALIGN with the From: header

  Alignment is the part that matters and the part a heuristic cannot reach. A
  phishing message can pass SPF and DKIM for a domain the attacker controls
  while claiming to be somebody else in From:. DMARC is precisely the check that
  those two domains agree.

  ## What this namespace does and does not claim

  It reports what the receiver found, and it names the one conclusion that
  follows: `:impersonation-suspected` when DMARC failed. It does not invent a
  verdict when the headers are absent — mail synced before this landed has no
  `Authentication-Results` at all, and the honest answer for those is
  `:unknown`, never `:pass`.

  Absence is not failure and must not be rendered as one. That is the whole
  reason `:unknown` is a distinct value rather than nil coerced to false."
  (:require [clojure.string :as str]))

(def schema "cloud.itonami.app.mail-authentication.v1")

(def retained-headers
  "The headers kept from every message, and why each one.

  Deliberately short. Retaining everything would put the whole envelope of
  somebody's mail into the store to answer one question, and these three answer
  it."
  {"authentication-results"
   "the receiving server's own SPF/DKIM/DMARC verdict — the evidence"
   "return-path"
   "the envelope sender, which SPF is evaluated against and which the From: header can disagree with"
   "received-spf"
   "some servers write SPF here instead of, or as well as, the combined header"})

(defn- method-result
  "Pull one method's result out of an Authentication-Results header.

  The header is a list of `method=result` clauses with optional parameters, and
  the result word is what matters: `spf=pass`, `dkim=fail`, `dmarc=none`."
  [value method]
  (when-let [value (not-empty (str/lower-case (str/trim (str value))))]
    (some-> (re-find (re-pattern (str "\\b" method "\\s*=\\s*([a-z]+)")) value)
            second
            keyword)))

(defn parse
  "The receiver's verdict, from the headers a message carries.

  `:unknown` for a method the header does not mention, and `:unknown` for every
  method when the header is absent entirely — which is the state of all mail
  synced before these headers were retained. A missing check is not a failed
  one."
  [headers]
  (let [headers (into {} (map (fn [[k v]] [(str/lower-case (str k)) v])) (or headers {}))
        combined (get headers "authentication-results")
        spf (or (method-result combined "spf")
                (method-result (get headers "received-spf") "")
                (some-> (get headers "received-spf") str str/lower-case str/trim
                        (str/split #"\s+") first not-empty keyword))]
    {:schema schema
     :spf (or spf :unknown)
     :dkim (or (method-result combined "dkim") :unknown)
     :dmarc (or (method-result combined "dmarc") :unknown)
     :envelope-from (some-> (get headers "return-path") str
                            (str/replace #"[<>]" "") str/trim not-empty
                            str/lower-case)
     :evaluated? (boolean (or combined (get headers "received-spf")))}))

(defn- domain-of [address]
  (some-> address str str/lower-case (str/split #"@") second str/trim not-empty))

(defn verdict
  "What follows from the receiver's verdict, and nothing more.

  Four outcomes, and the distance between them is deliberate:

  - `:unknown` — no headers were kept. Says nothing. Most of the existing
    corpus is here and must stay here rather than being upgraded by assumption.
  - `:authenticated` — DMARC passed. The From: domain is aligned with a domain
    that authenticated.
  - `:unaligned` — SPF or DKIM passed but DMARC did not, or DMARC is absent
    while the envelope and From: domains differ. Common and usually benign —
    mailing lists and forwarders do this — so it is NOT called impersonation.
  - `:impersonation-suspected` — DMARC explicitly failed. The receiving server
    computed that the sender does not speak for the domain in From:.

  Suspected, not proven, even here: DMARC failures also come from misconfigured
  senders. It is an observation strong enough to record, which is exactly what
  yabai's ledger distinguishes `:suspected` from `:phishing` for."
  [message]
  (let [{:keys [spf dkim dmarc envelope-from evaluated?]} (parse (:headers message))
        from-domain (domain-of (:from-email message))
        envelope-domain (domain-of envelope-from)]
    (cond
      (not evaluated?)
      {:verdict :unknown :spf spf :dkim dkim :dmarc dmarc
       :reason "この受信には認証ヘッダが保存されていません（保存開始前の同期分）"}

      (= :fail dmarc)
      {:verdict :impersonation-suspected :spf spf :dkim dkim :dmarc dmarc
       :from-domain from-domain :envelope-domain envelope-domain
       :reason "受信サーバーが DMARC 不合格と判定しました（From: のドメインを名乗る権限が無い）"}

      (= :pass dmarc)
      {:verdict :authenticated :spf spf :dkim dkim :dmarc dmarc
       :from-domain from-domain :envelope-domain envelope-domain}

      (and (or (= :pass spf) (= :pass dkim))
           envelope-domain from-domain
           (not= envelope-domain from-domain))
      {:verdict :unaligned :spf spf :dkim dkim :dmarc dmarc
       :from-domain from-domain :envelope-domain envelope-domain
       :reason "認証は通ったが From: と封筒の送信者が別ドメインです（転送やメーリングリストで普通に起こります）"}

      :else
      {:verdict :unaligned :spf spf :dkim dkim :dmarc dmarc
       :from-domain from-domain :envelope-domain envelope-domain
       :reason "DMARC の判定がありません"})))

(defn summarize
  "The verdict counts across a corpus, for the abuse ledger.

  Reports `:unknown` alongside the rest rather than excluding it, because a
  summary that quietly dropped the unevaluated messages would say a mailbox was
  fully checked when most of it was not."
  [messages]
  (let [verdicts (map (comp :verdict verdict) messages)]
    {:schema schema
     :messages (count messages)
     :by-verdict (into (sorted-map) (frequencies verdicts))
     :evaluated (count (remove #{:unknown} verdicts))}))
