(ns cloud.itonami.app.domain-verification
  "The two proofs that let one tenant be CALLED by a domain (ADR-0043).

  A Passkey-authenticated human owner starts a short-lived challenge bound to
  the session's active tenant and to one exact domain. From there the binding
  passes through two gates that are kept apart because they establish different
  things:

  - **the naming right.** A TXT record at the exact owner name issued here. A
    record on a parent domain is not inherited. The claim is exclusive across
    tenants. This is `:claimed`, and it does NOT name the tenant yet.
  - **the resolution fact.** This process answers at
    `https://<domain>/.well-known/itonami-domain-binding.json` with this
    binding's own nonce — which needs DNS pointing here AND a publicly trusted
    certificate for the name. This is `:live`, and it is what writes the domain
    onto the tenant.

  Collapsing them would publish a `did:web` that answers nothing, which
  `identity/membership-credential-context` already goes out of its way to avoid.
  Gate A still earns its place above Gate B, which is the stronger statement of
  control: an owner has to be able to reserve a name BEFORE cutting production
  DNS over to it, and two tenants wanting one name have to be separated before
  either does.

  A `:live` name is re-checked, and a check that fails moves it to `:lapsed` —
  the tenant reverts to its managed name and stops being named by an address
  that stopped answering. Nothing here retracts a credential: one already issued
  names the domain that was live when it was issued and stays true.

  This is deliberately not Domain Connect. Domain Connect is an optional DNS
  provider automation layer; the TXT proof is the authority boundary it would
  automate, and works with every DNS host without giving Cloud Itonami write
  access to the customer's zone.

  The rule that adds the facts up is `domain_binding_core.kotoba`. DNS, the
  outbound probe, the store writes and every `throw` are here — an exception is
  an effect and the core has none."
  (:require [clojure.string :as str]
            [cloud.itonami.app.credential-trust :as credential-trust]
            [cloud.itonami.app.domain-binding :as binding]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store])
  (:import [java.net IDN URI]
           [java.security SecureRandom]
           [java.time Duration Instant]
           [java.util Base64 UUID]
           [javax.naming.directory InitialDirContext]))

(def schema "cloud.itonami.app.domain-verifications.v2")
(def nonce-schema "cloud.itonami.app.domain-binding-nonce.v1")

;; The route Gate B fetches. Public and unauthenticated by necessity, like
;; `/.well-known/did.json`: a prober that had to authenticate could not prove
;; anything about a name it is being pointed at for the first time. It carries a
;; random nonce and no secret.
(def binding-path "/.well-known/itonami-domain-binding.json")

(def ^:private challenge-prefix "itonami-domain-verification=")
(def ^:private challenge-ttl (Duration/ofHours 24))

;; How long a successful probe keeps counting. A name that resolved here a
;; quarter ago is not evidence that it resolves here now, and `:live` is a claim
;; about the present tense — `identity` publishes a `did:web` from it.
(def probe-freshness (Duration/ofDays 7))

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

;; ── what this deployment already answers for ─────────────────────────────────

(defonce ^:private runtime-service-hosts (atom #{}))

(defn configure!
  "Record the hostnames this deployment answers for under its own name.

  Derived from `:public-origin` rather than written down, because the literal
  was the bug: the guard here read `\"itonami.cloud\"` while
  `:organization-domain-suffix` shipped as `cloud-itonami.app`, so it refused a
  name this deployment does not issue and did not refuse the ones it does.

  Called next to `identity/configure!`. An unconfigured process refuses only
  what the identity profile names, which is the fail-safe direction: it can
  under-refuse a name the operator owns anyway, never over-claim a customer's."
  [configuration]
  (reset! runtime-service-hosts
          (into #{}
                (keep (fn [origin]
                        (try
                          (some-> (URI/create (str origin)) .getHost
                                  str/lower-case not-empty)
                          (catch Exception _ nil))))
                [(:public-origin configuration)
                 (get-in configuration [:server :public-origin])])))

(defn service-owned-name?
  "Whether `domain` is one this deployment already speaks for.

  Three sources, all derived: the suffix it issues managed names from, the
  domain it addresses accounts at, and the origin it serves itself on. Subdomains
  count — a tenant proving `team.<suffix>` would be proving control of the
  operator's zone, not of its own."
  [domain]
  (let [profile (identity/identity-profile)
        own (into @runtime-service-hosts
                  (keep #(some-> % str str/lower-case not-empty))
                  [(:organization-domain-suffix profile)
                   (:account-domain profile)])]
    (boolean (some (fn [host]
                     (or (= domain host)
                         (str/ends-with? (str domain) (str "." host))))
                   own))))

;; ── domains ──────────────────────────────────────────────────────────────────

(defn normalize-domain
  "Return a lower-case ASCII DNS name, or nil when the input is not a domain.
  URL syntax, ports, paths, wildcards and public-suffix-only names are refused."
  [value]
  (try
    (let [domain (-> (str value) str/trim (str/replace #"\.$" "")
                     (IDN/toASCII IDN/USE_STD3_ASCII_RULES)
                     str/lower-case)
          labels (str/split domain #"\.")]
      (when (and (<= 3 (count domain) 253)
                 (<= 2 (count labels))
                 (every? #(and (<= 1 (count %) 63)
                               (re-matches #"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" %))
                         labels))
        domain))
    (catch Exception _ nil)))

(defn- random-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- verification-name [domain]
  (str "_itonami-verification." domain))

;; ── DNS ──────────────────────────────────────────────────────────────────────

(defn- clean-txt [value]
  ;; JNDI renders a multi-string TXT record as adjacent quoted strings.
  (-> (str value) (str/replace #"\"" "") str/trim))

(defn dns-txt-values
  "Resolve TXT values at one exact owner name. Kept replaceable in tests so a
  test never depends on public DNS or propagation timing."
  [owner-name]
  (let [context (InitialDirContext.)]
    (try
      (if-let [attribute (.get (.getAttributes context owner-name
                                               (into-array String ["TXT"]))
                               "TXT")]
        (loop [values [] entries (.getAll attribute)]
          (if (.hasMore entries)
            (recur (conj values (clean-txt (.next entries))) entries)
            values))
        [])
      (catch javax.naming.NameNotFoundException _ [])
      (finally (.close context)))))

(def ^:dynamic *txt-resolver* dns-txt-values)

;; ── the probe ────────────────────────────────────────────────────────────────

(defn- tls-failure?
  "Whether this throwable is a transport failure rather than a routing one.

  Walked over the cause chain because `HttpClient` wraps a handshake failure in
  an `IOException`. The distinction is real and it is the reason
  `probe-confidential` is a separate fact from `probe-answered`: a name whose
  certificate does not validate did answer, and answered over a channel this
  deployment cannot vouch for."
  [^Throwable e]
  (loop [t e]
    (cond
      (nil? t) false
      (or (instance? javax.net.ssl.SSLException t)
          (instance? java.security.cert.CertificateException t)) true
      :else (recur (.getCause t)))))

(defn probe-domain
  "Fetch this binding's nonce at `domain` and report what came back.

  Never throws. A probe is a MEASUREMENT and its failure is the answer — a
  caller that had to catch would be deciding what a failure means, which is the
  core's job.

  The error TEXT is kept rather than reduced to a boolean. Which of DNS, TLS and
  the route is wrong is written in that sentence, and an owner who is told only
  \"activation failed\" has to guess at all three.

  Reuses `credential-trust/fetch-json` and adds no second egress path: HTTPS
  only, redirects never followed, a hard timeout, a response size cap, and a
  refusal to talk to an address that resolves inside this network. Those guards
  exist for the other place this app fetches a URL it did not author, and a
  guard on one of them and not the other is the same as no guard."
  [configuration domain nonce]
  (let [url (str "https://" domain binding-path)]
    (try
      (let [body (credential-trust/fetch-json configuration url
                                              {:what "domain binding nonce"})
            observed (get body "nonce")
            match? (and (string? observed) (= nonce observed))]
        {:answered? match?
         :confidential? true
         :error (when-not match?
                  (str "the name answered but not with this binding's nonce"
                       " — it resolves to something other than this deployment"))})
      (catch Exception e
        {:answered? false
         :confidential? (not (tls-failure? e))
         :error (or (ex-message e) (str e))}))))

(def ^:dynamic *prober* probe-domain)

;; ── records ──────────────────────────────────────────────────────────────────

(defn- records [] (get-in (store/snapshot) [:identity :domain-verifications] {}))

(defn- public-record
  "What a caller may see. An allowlist, and `:activation-nonce` is not on it —
  the nonce is the whole content of Gate B, and a caller who could read it could
  serve it from anywhere."
  [record]
  (-> record
      (select-keys [:id :organization-id :domain :status :method
                    :record-type :record-name :record-value :created-at
                    :expires-at :claimed-at :activated-at :lapsed-at
                    :last-checked-at])
      (assoc :probe (select-keys (:probe record) [:at :answered? :error])
             :activation-url (str "https://" (:domain record) binding-path))))

(defn- claim-exclusive?
  "Whether no OTHER tenant holds this name.

  `:claimed` counts as holding it, not only `:live`: the reservation is the
  point of separating the gates, and a name two tenants can both reserve is a
  name neither of them can safely be told to point at this deployment."
  [all record]
  (not (boolean
        (some (fn [other]
                (and (not= (:id record) (:id other))
                     (= (:domain record) (:domain other))
                     (contains? #{:claimed :live} (:status other))))
              (vals all)))))

(defn- probe-fresh? [record now]
  (boolean
   (when-let [at (get-in record [:probe :at])]
     (.isAfter (Instant/parse at) (.minus ^Instant now probe-freshness)))))

(defn facts
  "The eight booleans the decision core is asked about, all established here.

  `owner-authorized?` is passed in because it is about the CALLER and not about
  the record. Everything else is read off the record and its neighbours, so a
  state computed twice from the same store gives the same answer."
  [all record {:keys [owner-authorized? now]}]
  {:owner-authorized (boolean owner-authorized?)
   :txt-observed (boolean (get-in record [:txt :observed?]))
   :claim-exclusive (claim-exclusive? all record)
   :probe-answered (boolean (get-in record [:probe :answered?]))
   :probe-confidential (boolean (get-in record [:probe :confidential?]))
   :probe-fresh (probe-fresh? record (or now (Instant/now)))
   :name-is-service-owned (service-owned-name? (:domain record))
   :previously-live (boolean (:activated-at record))})

;; ── authorization ────────────────────────────────────────────────────────────

(defn- owner-session!
  "The session's ACTIVE tenant, and only if this is its human Passkey owner."
  [session]
  (identity/require-passkey! session)
  (when-not (identity/human-session? session)
    (fail! :domain-verification/human-required
           "ドメイン確認の開始と完了にはブラウザの Passkey session が必要です。"))
  (let [state (:identity (store/snapshot))
        membership (get-in state [:memberships (:membership-id session)])
        organization (get-in state [:organizations (:organization-id session)])]
    (when-not (and membership organization
                   (= (:user-id session) (:user-id membership))
                   (= (:id organization) (:organization-id membership)))
      (fail! :domain-verification/membership-required
             "この Organization への有効な membership がありません。"))
    (when-not (= :owner (:role membership))
      (fail! :domain-verification/owner-required
             "ドメイン確認は Organization owner だけが実行できます。"))
    {:membership membership :organization organization}))

(defn- record-for-session! [session verification-id]
  (let [{:keys [organization]} (owner-session! session)
        record (get (records) verification-id)]
    (when-not record
      (fail! :domain-verification/not-found "ドメイン確認が見つかりません。"))
    (when-not (= (:id organization) (:organization-id record))
      (fail! :domain-verification/forbidden
             "このドメイン確認は別の Organization に属します。"))
    {:organization organization :record record}))

;; ── reads ────────────────────────────────────────────────────────────────────

(defn list-for-session [session]
  (let [{:keys [organization]} (owner-session! session)]
    {:schema schema
     :verifications (->> (records)
                         vals
                         (filter #(= (:id organization) (:organization-id %)))
                         (sort-by (juxt :created-at :id))
                         (mapv public-record))}))

(defn nonce-for-host
  "The activation nonce to serve at this request's `Host`, or nil.

  Answers ONLY for a binding whose naming right currently holds — so an attacker
  who points their own DNS at this deployment gets nothing: they would have to
  pass Gate A for that name first, which needs a TXT record in a zone they do
  not control.

  Resolved from the Host and never from \"the deployment's binding\", the same
  reading `identity/did-web-domain-for-host` takes for `did:web` (ADR-0025).
  With no match there is no fallback: handing one tenant's nonce to a request
  about another name is how a name gets activated for a tenant that never
  proved it."
  [host]
  (let [hostname (-> (str host) str/trim str/lower-case
                     (str/replace #":\d+$" ""))
        all (records)
        now (Instant/now)]
    (when-not (str/blank? hostname)
      (some (fn [record]
              (when (and (= hostname (str (:domain record)))
                         (binding/claim-holds?
                          (facts all record {:owner-authorized? false :now now})))
                (:activation-nonce record)))
            (vals all)))))

;; ── writes ───────────────────────────────────────────────────────────────────

(defn- refuse-unless-startable!
  "Let the core decide admission; explain the refusal here.

  The core answers one boolean, which is the right shape for a decision and the
  wrong shape for a message — \"somebody else already holds this\" and \"that is
  our own name\" are different things for an owner to be told, and neither is
  \"no\"."
  [all candidate]
  (when-not (binding/may-start? (facts all candidate {:owner-authorized? true}))
    (if (service-owned-name? (:domain candidate))
      (fail! :domain-verification/managed-domain
             (str "このドメインはサービス管理ドメインであり、"
                  "外部所有権確認の対象ではありません。")
             {:domain (:domain candidate)})
      (fail! :domain-verification/already-claimed
             "このドメインは別の Organization で確認済みです。"
             {:domain (:domain candidate)}))))

(defn assert-claimable!
  "Refuse `domain` the way a challenge would, and return it normalized.

  For the caller that has to check BEFORE the tenant exists: creating an
  organization and then discovering its domain is unusable would leave the
  organization behind, and swallowing that refusal to avoid it would make a
  rejected domain indistinguishable from an accepted one. So the order is check,
  create, issue."
  [domain]
  (let [normalized (normalize-domain domain)]
    (when-not normalized
      (fail! :domain-verification/invalid-domain
             "確認する完全なドメイン名を入力してください。"
             {:domain domain}))
    (refuse-unless-startable! (records)
                              {:id ::unclaimed
                               :organization-id ::unclaimed
                               :domain normalized})
    normalized))

(defn start-for-organization!
  "Issue a TXT challenge for `domain` on behalf of an explicitly named tenant.

  Split out from `start!` so a tenant that is not the session's ACTIVE one can
  be given a challenge — the organization a create call just made. Authorization
  is derived from the membership table by `identity/owner-of-tenant?` rather
  than taken as an argument: a caller that could pass `:authorized? true` would
  be the authority, which is the arrangement this whole namespace exists to
  avoid.

  Replaces an existing `:pending` challenge for the same tenant and domain
  rather than accumulating one per click. A `:claimed` or `:live` binding is
  returned untouched — re-issuing a challenge over a proof would throw the proof
  away."
  [{:keys [user-id organization-record-id domain human?]}]
  (when-not human?
    (fail! :domain-verification/human-required
           "ドメイン確認の開始にはブラウザの Passkey session が必要です。"))
  (when-not (identity/owner-of-tenant? user-id organization-record-id)
    (fail! :domain-verification/owner-required
           "ドメイン確認は Organization owner だけが実行できます。"))
  (let [domain (normalize-domain domain)]
    (when-not domain
      (fail! :domain-verification/invalid-domain
             "確認する完全なドメイン名を入力してください。"))
    (let [all (records)
          mine (filter #(and (= organization-record-id (:organization-id %))
                             (= domain (:domain %)))
                       (vals all))]
      (if-let [established (some #(when (contains? #{:claimed :live} (:status %)) %)
                                 mine)]
        (public-record established)
        (let [id (or (some #(when (= :pending (:status %)) (:id %)) mine)
                     (str "domain-verification-" (UUID/randomUUID)))
              created-at (store/now)
              candidate {:id id
                         :organization-id organization-record-id
                         :domain domain
                         :status :pending
                         :method :dns-txt
                         :record-type "TXT"
                         :record-name (verification-name domain)
                         :record-value (str challenge-prefix (random-token))
                         :activation-nonce (random-token)
                         :created-at created-at
                         :expires-at (str (.plus (Instant/parse created-at)
                                                 challenge-ttl))}]
          (refuse-unless-startable! all candidate)
          (store/transact!
           (fn [current]
             (-> current
                 (assoc-in [:identity :domain-verifications id] candidate)
                 (update :events conj
                         {:type :identity/domain-verification-started
                          :at created-at
                          :organization-id organization-record-id
                          :domain domain :verification-id id}))))
          (public-record candidate))))))

(defn start!
  "Issue or replace a TXT challenge for the session's ACTIVE tenant."
  [session {:keys [domain]}]
  (let [{:keys [organization]} (owner-session! session)]
    (start-for-organization! {:user-id (:user-id session)
                              :organization-record-id (:id organization)
                              :domain domain
                              :human? true})))

(defn claim!
  "Read public DNS and establish the NAMING RIGHT, exclusively.

  This is Gate A and it stops there: `:claimed` reserves the name and does not
  make the tenant answer to it. `activate!` is the other half."
  [session {:keys [verification-id]}]
  (let [{:keys [record]} (record-for-session! session verification-id)]
    (if (contains? #{:claimed :live} (:status record))
      (public-record record)
      (do
        (when-not (= :pending (:status record))
          (fail! :domain-verification/invalid-state
                 "確認待ちのドメインだけを検証できます。"
                 {:status (:status record)}))
        ;; The challenge window bounds how long an UNPROVEN challenge stays
        ;; answerable. It deliberately is not one of the core's facts: folding
        ;; it in would expire a claim that has already been proven.
        (when-not (.isBefore (Instant/now) (Instant/parse (:expires-at record)))
          (fail! :domain-verification/expired
                 "ドメイン確認の有効期限が切れました。新しい確認を開始してください。"))
        (let [checked-at (store/now)
              observed (set (*txt-resolver* (:record-name record)))
              seen? (contains? observed (:record-value record))
              result
              (store/transact!
               (fn [current]
                 (let [all (get-in current [:identity :domain-verifications] {})
                       measured (-> (get all verification-id)
                                    (assoc :txt {:at checked-at :observed? seen?}
                                           :last-checked-at checked-at))
                       state (binding/binding-state
                              (facts (assoc all verification-id measured)
                                     measured
                                     {:owner-authorized? true}))
                       next (cond-> (assoc measured :status state)
                              (= :claimed state) (assoc :claimed-at checked-at))]
                   (cond-> (assoc-in current
                                     [:identity :domain-verifications
                                      verification-id]
                                     next)
                     (= :claimed state)
                     (update :events conj
                             {:type :identity/domain-claimed :at checked-at
                              :organization-id (:organization-id record)
                              :domain (:domain record)
                              :verification-id verification-id})))))
              claimed (get-in result [:identity :domain-verifications
                                      verification-id])]
          (when-not (= :claimed (:status claimed))
            (if seen?
              (fail! :domain-verification/already-claimed
                     "このドメインは別の Organization で確認済みです。"
                     {:domain (:domain record)})
              (fail! :domain-verification/record-not-found
                     "指定された TXT レコードをまだ確認できません。"
                     {:record-name (:record-name record)})))
          (public-record claimed))))))

(defn- check!
  "Re-measure both gates for one binding and write whatever they now say.

  The single place a binding's state is recomputed from live measurements, so
  activation and re-checking cannot drift into two rules. Returns the record as
  it now stands.

  `owner-authorized?` is not one of the inputs: it gates who may CAUSE this, not
  what the measurements mean."
  [configuration verification-id]
  (let [record (get (records) verification-id)
        _ (when-not record
            (fail! :domain-verification/not-found "ドメイン確認が見つかりません。"))
        checked-at (store/now)
        observed (set (*txt-resolver* (:record-name record)))
        seen? (contains? observed (:record-value record))
        probe (*prober* configuration (:domain record) (:activation-nonce record))
        result
        (store/transact!
         (fn [current]
           (let [all (get-in current [:identity :domain-verifications] {})
                 measured (-> (get all verification-id)
                              (assoc :txt {:at checked-at :observed? seen?}
                                     :probe (assoc probe :at checked-at)
                                     :last-checked-at checked-at))
                 state (binding/binding-state
                        (facts (assoc all verification-id measured)
                               measured
                               {:owner-authorized? false}))
                 was (:status (get all verification-id))
                 next (cond-> (assoc measured :status state)
                        (and (= :live state) (nil? (:activated-at measured)))
                        (assoc :activated-at checked-at)
                        (= :lapsed state) (assoc :lapsed-at checked-at))]
             (cond-> (assoc-in current
                               [:identity :domain-verifications verification-id]
                               next)
               (not= was state)
               (update :events conj
                       {:type :identity/domain-binding-state-changed
                        :at checked-at
                        :organization-id (:organization-id record)
                        :domain (:domain record)
                        :verification-id verification-id
                        :from was :to state
                        ;; The measurement, not just the verdict. A route that
                        ;; recorded only the state would throw away the sentence
                        ;; that says which of DNS, TLS or routing is wrong.
                        :probe-error (:error probe)})))))
        settled (get-in result [:identity :domain-verifications verification-id])]
    ;; The name follows the state, and `identity` owns the write. Both
    ;; directions, so a binding that stops answering does not keep the tenant
    ;; pointed at it.
    (case (:status settled)
      :live (identity/bind-verified-domain! (:organization-id settled)
                                            (:domain settled))
      :lapsed (when (= :verified (get-in (store/snapshot)
                                         [:identity :organizations
                                          (:organization-id settled)
                                          :domain-source]))
                (identity/revert-to-managed-domain! (:organization-id settled)))
      nil)
    settled))

(defn activate!
  "Measure Gate B and, if this process answers at the name, make it the tenant's.

  Refuses a binding that has not passed Gate A: probing a name nobody proved
  would activate whatever happens to point here."
  [configuration session {:keys [verification-id]}]
  (let [{:keys [record]} (record-for-session! session verification-id)]
    (when-not (contains? #{:claimed :live :lapsed} (:status record))
      (fail! :domain-verification/invalid-state
             "先に TXT レコードで所有権を確認してください。"
             {:status (:status record)}))
    (let [settled (check! configuration verification-id)]
      (when-not (= :live (:status settled))
        (fail! :domain-verification/not-answering
               (str "このドメインはまだこの deployment に解決していません。"
                    "DNS をこの deployment に向け、証明書が有効になってから"
                    "再試行してください。")
               {:domain (:domain settled)
                :status (:status settled)
                :probe-error (get-in settled [:probe :error])}))
      (public-record settled))))

(defn recheck!
  "Re-measure a binding that already passed both gates, and demote it if it no
  longer does.

  Exposed to the owner rather than run on a timer: this application has no
  scheduler to hang it on, and a demotion path that nothing can invoke would be
  a state the store can hold and nobody can reach."
  [configuration session {:keys [verification-id]}]
  (record-for-session! session verification-id)
  (public-record (check! configuration verification-id)))
