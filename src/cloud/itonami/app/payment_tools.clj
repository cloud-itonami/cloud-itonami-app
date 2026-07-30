(ns cloud.itonami.app.payment-tools
  "Funding and settlement as agent tools, and the boundary that makes exposing
  them defensible.

  `cloud.itonami.app.mcp` says why mail, calendar, drive and chat are NOT on the
  MCP surface: they sit behind the Passkey session on `/api/*`, and reaching
  around that from a surface with no session would weaken a gate the app means.
  Money is a stronger version of that objection, not a weaker one. So this
  namespace does not reach around the session -- it RESOLVES one:

    an app session token (env or Keychain) -> identity/session -> require-passkey!

  No token, an expired or revoked token, or a user who never enrolled a Passkey,
  and there are no tools in the manifest at all. The agent acts AS a real
  session, with the same organization scoping, the same store, and the same
  refusals as the HTTP surface. Nothing here has its own authority.

  ## What an agent may and may not do

    ask            payment_review runs the full deterministic pre-check
    record         funding_link_account, funding_record_balance
    carry out      payment_commit, but ONLY for a proposal a human already
                   approved with their Passkey
    decline        payment_reject

  **An agent cannot approve.** `approve/start` and `approve/finish` are
  deliberately absent, and this is not merely a policy: consent is a WebAuthn
  user-verifying assertion from an authenticator the operator holds, and there
  is no assertion an agent could produce. The gap is structural, and listing the
  tools would only invite a client to try.

  `payment_commit` is exposed because it acts on a proposal whose exact digest a
  human already signed. The consent has happened; carrying the approved thing to
  the authority is errand-running, not deciding. It cannot reach an unapproved
  proposal -- the spine refuses anything not in `:approved`.

  The deterministic gates are unchanged and are the point: an agent proposing a
  payment the balance does not cover is refused BEFORE a human is asked, exactly
  as a human proposing it would be."
  (:require [clojure.string :as str]
            [cloud.itonami.app.authority.api :as authority-api]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.identity :as identity])
  (:import [java.util.concurrent TimeUnit]))

(def keychain-service "cloud-itonami-app.mcp")
(def keychain-account "session-token")

(defn- keychain-secret
  "Read the MCP session token from the login Keychain.

  One named item, fetched by service and account -- never an enumeration. A
  `security dump-keychain` style sweep would expose unrelated credentials'
  metadata to answer a question about exactly one of them."
  []
  (try
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "find-generic-password"
                        "-s" keychain-service "-a" keychain-account "-w"])
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when (and completed? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 500 "")))))
    (catch Exception _ nil)))

(def session-token
  "The configured token, resolved once per process.

  Memoized because `available?` runs on every `tools/list`, and an unmemoized
  fallback would shell out to `security` on each one -- slow, and noisy against
  the operator's Keychain for a question whose answer cannot change without a
  restart.

  Caching the TOKEN is not caching the SESSION. `identity/session` re-resolves it
  against the store on every call, so expiry and revocation still take effect
  immediately; what is cached is only which token string to ask about."
  (memoize
   (fn [configuration]
     (or (some-> (get-in configuration [:mcp :session-token-env])
                 System/getenv str/trim not-empty)
         (keychain-secret)))))

(defn session
  "The app session this server acts as, or nil.

  Returns nil rather than throwing for every failure mode -- absent token,
  unknown token, expired, revoked, no Passkey -- because the caller's response to
  all of them is the same: publish no tools. Distinguishing them in the manifest
  would tell an unauthenticated client which of its guesses was closest."
  [configuration]
  (when-let [token (session-token configuration)]
    (let [s (identity/session token)]
      (when (and s (identity/passkey-enrolled? s))
        s))))

(defn available?
  [configuration]
  (some? (session configuration)))

;; ---------------------------------------------------------------------------
;; descriptors
;; ---------------------------------------------------------------------------

(def ^:private amount-note
  "amount-minor is an integer in the currency's MINOR unit. JPY has exponent 0, so ¥38,500 is 38500. USD has exponent 2, so $38.50 is 3850.")

(def tools
  [{:name "funding_accounts"
    :description
    (str "List the bank accounts linked to this organization, with the last "
         "recorded balance and how usable it is. A balance that was never "
         "recorded is null and its status is never-recorded -- it is NOT zero, "
         "and must not be reported as zero. A balance older than the configured "
         "window is stale. Both refuse a payment. Use this to find the "
         "funding-account-id that payment_review needs.")
    :parameters {:type "object" :properties {}}}

   {:name "funding_link_account"
    :description
    (str "Link a bank account to this organization. The account NUMBER is not "
         "stored: only its last four digits and a SHA-256 digest are kept, which "
         "is enough to confirm an account and not enough to move money. "
         "Linking records a fact; it does not authorise anything.")
    :parameters
    {:type "object"
     :properties
     {:institution {:type "string" :description "Bank name, e.g. PayPay銀行."}
      :account-type {:type "string" :enum ["ordinary" "current" "savings"]
                     :description "普通 / 当座 / 貯蓄. Refused if absent — it appears on the transfer form."}
      :branch {:type "string" :description "Branch name, optional."}
      :holder {:type "string" :description "Account holder, optional."}
      :number {:type "string" :description "Account number, optional. Fingerprinted immediately and never stored."}
      :label {:type "string" :description "Display label. Defaults to the institution."}
      :currency {:type "string" :enum ["JPY" "USD" "EUR"] :description "Defaults to JPY. An unlisted currency is refused rather than defaulted."}}
     :required ["institution" "account-type"]}}

   {:name "funding_record_balance"
    :description
    (str "Record what an account held, as of the instant THE BANK stated. "
         "as-of is required and is not defaulted to now: a figure copied from a "
         "three-day-old statement is three days old however recently it was "
         "typed in. There is no bank connector — this records a reading, it does "
         "not fetch one. Never invent a figure: if the balance is not known, do "
         "not call this, and let payment_review refuse with balance-unknown. "
         amount-note)
    :parameters
    {:type "object"
     :properties
     {:funding-account-id {:type "string" :description "From funding_accounts."}
      :amount-minor {:type "integer" :description amount-note}
      :currency {:type "string" :enum ["JPY" "USD" "EUR"] :description "Must match the account's currency."}
      :as-of {:type "string" :description "ISO-8601 instant the BANK stated, e.g. 2026-07-30T09:00:00Z. Not the time of this call."}
      :source {:type "string" :enum ["owner-attested" "statement" "api"]
               :description "Where the figure came from."}
      :source-detail {:type "string" :description "Free text, e.g. which statement."}}
     :required ["funding-account-id" "amount-minor" "as-of" "source"]}}

   {:name "payment_review"
    :description
    (str "Propose settling a payable. Runs the deterministic pre-check and, if "
         "it passes, records a proposal AWAITING A HUMAN'S PASSKEY. This does "
         "not pay anything and does not approve anything. It refuses — before a "
         "human is asked — when the recorded balance does not cover the amount, "
         "when that balance is unknown or stale, when this reference was already "
         "settled by anyone in the organization, or when an eSIM ownership "
         "transfer currently holds spend for this subject. The balance, its "
         "freshness and the settlement history are read from the store and "
         "override anything sent here. " amount-note)
    :parameters
    {:type "object"
     :properties
     {:funding-account-id {:type "string" :description "Which account it is drawn on. From funding_accounts."}
      :amount-minor {:type "integer" :description amount-note}
      :currency {:type "string" :enum ["JPY" "USD" "EUR"] :description "Must match the funding account's currency."}
      :reference {:type "string" :description "Invoice number or equivalent. Required — it is what makes a duplicate detectable."}
      :payee {:type "object"
              :description "Who is paid. The payee's account number is fingerprinted in the pre-check and never reaches the stored proposal."
              :properties
              {:name {:type "string"}
               :institution {:type "string"}
               :branch {:type "string"}
               :account-type {:type "string" :enum ["ordinary" "current" "savings"]}
               :number {:type "string"}}
              :required ["name"]}
      :due-date {:type "string" :description "e.g. 2026-08-31."}
      :memo {:type "string" :description "What this settles, for the human reading the approval."}}
     :required ["funding-account-id" "amount-minor" "reference" "payee"]}}

   {:name "payment_proposals"
    :description
    (str "List this session's settlement proposals with their status. "
         "awaiting-passkey needs a human. approved is consented and ready for "
         "payment_commit. committed means a GOVERNED SETTLEMENT RECORD was "
         "accepted — it does NOT mean money moved; a human makes the transfer in "
         "their bank. authority-refused and rejected are terminal.")
    :parameters {:type "object" :properties {}}}

   {:name "payment_commit"
    :description
    (str "Hand a proposal a human ALREADY APPROVED with their Passkey to the "
         "settlement authority, and record what came back. Refuses anything not "
         "in the approved state, so this cannot skip consent. A refusal from the "
         "authority is a normal result with status authority-refused, not an "
         "error: the human consented and the licensed operator still said no.")
    :parameters {:type "object"
                 :properties {:proposal-id {:type "string"}}
                 :required ["proposal-id"]}}

   {:name "payment_reject"
    :description
    "Record that the human declined a proposal awaiting their Passkey. Terminal. Only call this when a human actually declined — it is a record of their decision, not a way to clear a queue."
    :parameters {:type "object"
                 :properties {:proposal-id {:type "string"}}
                 :required ["proposal-id"]}}])

;; ---------------------------------------------------------------------------
;; invocation
;; ---------------------------------------------------------------------------

(defn- keywordize-payee
  "`mcp/keywordize` converts only the top level, on purpose -- both fleet tools
  take flat maps and a deep walk would rewrite values they pass through. This
  tool has exactly one nested object, so it is converted here rather than by
  loosening the shared helper."
  [payee]
  (when (map? payee)
    (into {} (map (fn [[k v]] [(keyword k) v])) payee)))

(defn- account-view [configuration session' record]
  (let [{:keys [account balance freshness]}
        (funding/account-view configuration session' record)]
    {:funding-account-id (:id account)
     :label (:label account)
     :institution (:institution account)
     :branch (:branch account)
     :account-type (:account-type account)
     :number-last4 (:number-last4 account)
     :currency (:currency account)
     :status (:status account)
     ;; nil, never 0 -- see the tool description.
     :balance-minor (:amount-minor balance)
     :balance-as-of (:as-of balance)
     :balance-source (:source balance)
     :balance-status (:funding/status freshness)
     :balance-age-seconds (:funding/age-seconds freshness)}))

(defn call-tool
  "Run one funding/payment tool as the resolved session.

  Refuses rather than defaulting when no session resolves. Callers translate a
  thrown ex-info into an MCP error result, so every deterministic refusal
  reaches the agent with its `:type` intact -- an agent that sees
  `payment/insufficient-funds` can act differently from one that sees
  `payment/balance-unknown`, and collapsing them into one failure would remove
  the only useful thing about them."
  ([configuration tool-name arguments]
   (let [s (session configuration)]
     (when-not s
       (throw (ex-info
               (str "MCP session が解決できません。app の session token を "
                    (get-in configuration [:mcp :session-token-env])
                    " または Keychain（service " keychain-service
                    " / account " keychain-account "）に設定し、その user が"
                    "Passkey を登録済みであることを確認してください。")
               {:type :mcp/session-unavailable})))
     (call-tool configuration s tool-name arguments)))
  ;; Session as a parameter, like every other module in this app
  ;; (`funding/*`, `authority-api/*`). The arity above is the only place that
  ;; resolves one, so there is exactly one door and it is the one that checks.
  ([configuration s tool-name arguments]
   (case tool-name
      "funding_accounts"
      {:accounts (mapv #(account-view configuration s %) (funding/accounts s))
       :balance-max-age-seconds (funding/max-age-seconds configuration)}

      "funding_link_account"
      (let [record (funding/link-account! s arguments)]
        (account-view configuration s record))

      "funding_record_balance"
      (let [b (funding/record-balance! s (:funding-account-id arguments)
                                       arguments)]
        {:funding-account-id (:account-id b)
         :balance-minor (:amount-minor b)
         :currency (:currency b)
         :as-of (:as-of b)
         :source (:source b)
         :recorded-at (:recorded-at b)})

      "payment_review"
      (let [p (authority-api/review!
               configuration s :payment
               (-> arguments
                   (assoc :op :payment/settle)
                   (update :payee keywordize-payee)))]
        (assoc p :next "awaiting-passkey: a human must approve this in the app. An agent cannot."))

      "payment_proposals"
      (authority-api/proposals configuration s :payment)

      "payment_commit"
      (authority-api/commit! configuration s :payment (:proposal-id arguments))

      "payment_reject"
      (authority-api/reject! configuration s :payment (:proposal-id arguments))

      (throw (ex-info (str "unknown tool: " tool-name)
                      {:type :mcp/unknown-tool})))))
