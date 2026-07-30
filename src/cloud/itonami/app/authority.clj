(ns cloud.itonami.app.authority
  "The proposal -> consent -> commit spine for governed outward authorities.

  This app is a SURFACE and a CONSENT BOUNDARY. It is not the authority for card
  issuing, eSIM provisioning or voice reception: each of those is a governed
  actor with its own Governor and its own append-only ledger. What the app owns
  is (a) a deterministic pre-check, (b) a Passkey-bound human consent, and (c)
  handing the consented proposal to the authority and recording what came back.

  This is not a new pattern. `cloud.itonami.app.bitcoin-wallet` already proves it
  for PSBTs:

    create-psbt-review!    deterministic pre-check (max-fee-sats / max-fee-ratio)
                           rejects BEFORE any approval is requested
    start-psbt-approval!   passkey/start-authorization! with a typed context
    finish-psbt-approval!  verifies context type, proposal id AND a content digest
    attach-signed-psbt!    the signed artifact comes from outside; no keys here

  This namespace generalises those four stages so a domain supplies only what is
  domain-specific, and every domain inherits the same four properties:

  1. ORDER. `start-approval!` only accepts a proposal produced by `review!`.
     A consent cannot be requested for something that was never pre-checked.

  2. CONTENT BINDING. `review!` hashes the domain's own material and stores the
     digest; `finish-approval!` re-verifies it. The human approved THIS content,
     and it cannot be substituted between start and finish. Without this, a
     proposal mutated after approval would still validate -- the reason
     bitcoin-wallet binds :unsigned-tx-sha256 into the Passkey context.

  3. THE TWO GATES DO NOT SUBSTITUTE. Passkey consent answers 'did the human
     agree?'. The authority's Governor answers 'is this admissible for the
     licensed operator?'. `commit!` therefore records an authority REFUSAL as a
     refusal (`:authority-refused`), never as an approval. Passkey consent is not
     a licence, and a governor clearance is not consent.

  4. SINGLE USE. An approved proposal commits at most once. Replaying a consent
     is refused.

  What this namespace deliberately does NOT do: implement any domain logic, hold
  any credential, or actuate anything. `:authority/commit!` hands the proposal to
  an actor that is itself propose-only today (see ADR-2607300300's ceiling
  section), so a committed proposal here means a governed proposal was recorded --
  NOT that a card was issued, a profile downloaded, or a call answered. Callers
  and UI must not present it as more than that."
  (:require [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.authority.v1")

(def statuses
  "A proposal's lifecycle in this app. Terminal: :committed, :authority-refused,
  :rejected. `:awaiting-passkey` is the only state consent may be started from,
  and `:approved` the only one a commit may be attempted from."
  #{:awaiting-passkey :approved :committed :authority-refused :rejected})

;; ---------------------------------------------------------------------------
;; Domain contract
;; ---------------------------------------------------------------------------
;;
;; A domain is a plain map, not a protocol -- the app's other modules are plain
;; functions over maps, and a map is easier to build in a test than a reify.
;;
;;   {:authority/key          :esim                 ; store partition
;;    :authority/context-type (fn [op] kw)          ; typed Passkey context
;;    :authority/pre-check    (fn [config session request] proposal-value)
;;    :authority/material     (fn [proposal-value] "stable string")
;;    :authority/commit!      (fn [config session proposal] result)}
;;
;; :authority/pre-check MUST be deterministic: no model, no network, no clock
;; beyond the store's own recorded state. It either returns the proposal value or
;; throws ex-info with a `:type`. Anything requiring judgement or law belongs to
;; the actor's Governor, not here.
;;
;; :authority/commit! returns {:authority/ok? true :authority/record m} or
;; {:authority/ok? false :authority/refusal m}.

(defn- require-fn [domain k]
  (let [f (get domain k)]
    (when-not (fn? f)
      (throw (ex-info (str "authority domain is missing " k)
                      {:type :authority/domain-invalid :missing k})))
    f))

(defn valid-domain?
  "True when `domain` supplies everything the spine needs."
  [domain]
  (and (map? domain)
       (keyword? (:authority/key domain))
       (every? #(fn? (get domain %))
               [:authority/context-type :authority/pre-check
                :authority/material :authority/commit!])))

;; ---------------------------------------------------------------------------
;; Content digest
;; ---------------------------------------------------------------------------

(defn digest
  "SHA-256, base64url, of a STRING.

  A string is required rather than a map on purpose. Clojure's map iteration
  order is only stable below the array-map threshold -- this workspace has
  already been bitten by exactly that (`css.core/declarations` reorders past 8
  entries), so hashing `(pr-str some-map)` would produce a digest that silently
  depends on how many keys the map happens to have. A domain must therefore say
  explicitly, and stably, what content the human is consenting to."
  ;; Deliberately NOT hinted ^String in the parameter vector: the runtime guard
  ;; below is the contract, and a parameter hint makes it impossible to even
  ;; write a test that passes a map -- clj-kondo rejects the call site, so the
  ;; guard would go uncovered. The hint moves inside, after the check.
  [material]
  (when-not (string? material)
    (throw (ex-info "authority digest material must be a string"
                    {:type :authority/material-invalid})))
  (let [^String s material]
    (-> (MessageDigest/getInstance "SHA-256")
        (.digest (.getBytes s StandardCharsets/UTF_8))
        (->> (.encodeToString (.withoutPadding (Base64/getUrlEncoder)))))))

;; ---------------------------------------------------------------------------
;; Stage 1 -- review (deterministic pre-check)
;; ---------------------------------------------------------------------------

(defn- proposal-path [proposal-id]
  [:authority :proposals proposal-id])

(defn proposal
  "The stored proposal, or nil."
  [proposal-id]
  (get-in (store/snapshot) (proposal-path proposal-id)))

(defn- owned-proposal!
  "The proposal, asserted to belong to this session and to be in `expected`
  status. Refuses rather than returning nil, so no caller can proceed on a
  proposal it does not own."
  [session proposal-id expected]
  (let [p (proposal proposal-id)]
    (when-not (and p
                   (= (:user-id session) (:user-id p))
                   (= expected (:status p)))
      (throw (ex-info "authority proposal not found in the expected state"
                      {:type :authority/proposal-not-found
                       :expected expected
                       :actual (:status p)})))
    p))

(defn review!
  "Run the domain's deterministic pre-check and record a proposal awaiting
  consent. Throws the domain's own ex-info when the pre-check refuses -- nothing
  is stored and no consent is requested for a proposal that cannot proceed.

  Returns the proposal without its internal ownership fields."
  [domain configuration session request]
  (when-not (valid-domain? domain)
    (throw (ex-info "authority domain is invalid" {:type :authority/domain-invalid})))
  (when-not (:user-id session)
    (throw (ex-info "authority requires an authenticated user"
                    {:type :identity/unauthenticated})))
  (let [pre-check ((require-fn domain :authority/pre-check)
                   configuration session request)
        material  ((require-fn domain :authority/material) pre-check)
        id        (str (UUID/randomUUID))
        p {:schema        schema
           :id            id
           :authority     (:authority/key domain)
           :op            (:op request)
           :value         pre-check
           :digest        (digest material)
           :status        :awaiting-passkey
           :user-id       (:user-id session)
           :organization-id (:organization-id session)
           :created-at    (store/now)}]
    (store/transact! assoc-in (proposal-path id) p)
    (dissoc p :user-id :organization-id)))

;; ---------------------------------------------------------------------------
;; Stage 2/3 -- Passkey-bound consent
;; ---------------------------------------------------------------------------

(defn start-approval!
  "Begin a user-verifying Passkey assertion bound to this proposal's digest.

  Only a proposal produced by `review!` and still `:awaiting-passkey` can reach
  here, which is what makes the pre-check unskippable."
  [domain session proposal-id rp-id origin]
  (let [p (owned-proposal! session proposal-id :awaiting-passkey)
        ctype ((require-fn domain :authority/context-type) (:op p))]
    (passkey/start-authorization!
     (:user-id session)
     {:type ctype
      :authority (:authority/key domain)
      :proposal-id proposal-id
      :digest (:digest p)}
     rp-id origin)))

(defn approval-match-issues
  "Return a seq of reasons this Passkey assertion does not authorise this
  proposal, empty when it does. Pure, so the security-critical comparison is
  unit-testable without a real WebAuthn assertion -- the alternative was leaving
  the digest binding covered only by an integration path that needs a hardware
  authenticator, which in practice means covered by nothing.

  Every clause is a separate issue rather than one boolean, so a refusal says
  which check failed."
  [{:keys [expected-type expected-authority expected-proposal-id
           session-user-id proposal result context]}]
  (cond-> []
    (not= session-user-id (:user-id result))
    (conj {:authority/issue :user/assertion-mismatch})

    (not= session-user-id (:user-id proposal))
    (conj {:authority/issue :user/proposal-not-owned})

    (not= expected-type (:type context))
    (conj {:authority/issue :context/type-mismatch
           :authority/expected expected-type :authority/actual (:type context)})

    (not= expected-authority (:authority context))
    (conj {:authority/issue :context/authority-mismatch})

    (not= expected-proposal-id (:proposal-id context))
    (conj {:authority/issue :context/proposal-mismatch})

    ;; The binding that makes substitution impossible. A proposal edited between
    ;; start-approval! and finish-approval! passes every check above and fails
    ;; only this one.
    (not= (:digest proposal) (:digest context))
    (conj {:authority/issue :context/digest-mismatch})

    (nil? (:digest context))
    (conj {:authority/issue :context/digest-absent})))

(defn finish-approval!
  "Complete the assertion and mark the proposal approved.

  Verifies, all of them: the session, the assertion's own user, the proposal's
  owner, the context type, the authority, the proposal id, AND the digest -- see
  `approval-match-issues`, which is where those comparisons live so they can be
  tested directly."
  [domain session proposal-id transaction-id credential]
  (let [result  (passkey/finish-authorization! transaction-id credential)
        context (:authorization-context result)
        p       (owned-proposal! session proposal-id :awaiting-passkey)
        ctype   ((require-fn domain :authority/context-type) (:op p))
        issues  (approval-match-issues
                 {:expected-type ctype
                  :expected-authority (:authority/key domain)
                  :expected-proposal-id proposal-id
                  :session-user-id (:user-id session)
                  :proposal p
                  :result result
                  :context context})]
    (when (seq issues)
      (throw (ex-info "Passkey approval does not match this proposal"
                      {:type :authority/approval-mismatch
                       :issues (mapv :authority/issue issues)})))
    (let [approved (assoc p
                          :status :approved
                          :approved-at (store/now)
                          :passkey-credential-id (:credential-id result))]
      (store/transact! assoc-in (proposal-path proposal-id) approved)
      (dissoc approved :user-id :organization-id))))

(defn reject!
  "Record that the human declined. Terminal."
  [session proposal-id]
  (let [p (owned-proposal! session proposal-id :awaiting-passkey)
        rejected (assoc p :status :rejected :rejected-at (store/now))]
    (store/transact! assoc-in (proposal-path proposal-id) rejected)
    (dissoc rejected :user-id :organization-id)))

;; ---------------------------------------------------------------------------
;; Stage 4 -- hand to the authority
;; ---------------------------------------------------------------------------

(defn commit!
  "Hand an approved proposal to its authority and record the outcome.

  The authority's Governor decides independently of the human's consent, so this
  records a refusal AS a refusal. A caller must not read `:committed` off a
  proposal whose authority refused it, and must not retry a refused proposal as
  though the consent still stood -- both statuses are terminal.

  A committed proposal means a GOVERNED PROPOSAL WAS RECORDED by the authority.
  Every authority in this fleet is propose-only today, so it does not mean a card
  was issued, a profile downloaded, or a call answered."
  [domain configuration session proposal-id]
  (let [p (owned-proposal! session proposal-id :approved)
        outcome ((require-fn domain :authority/commit!) configuration session p)
        ok? (true? (:authority/ok? outcome))
        final (assoc p
                     :status (if ok? :committed :authority-refused)
                     :committed-at (store/now)
                     :authority-record (:authority/record outcome)
                     :authority-refusal (:authority/refusal outcome))]
    (store/transact! assoc-in (proposal-path proposal-id) final)
    (dissoc final :user-id :organization-id)))

;; ---------------------------------------------------------------------------
;; Read models
;; ---------------------------------------------------------------------------

(defn proposals
  "This session's proposals, newest first. Optionally filtered to one authority."
  ([session] (proposals session nil))
  ([session authority-key]
   (->> (vals (get-in (store/snapshot) [:authority :proposals]))
        (filter #(= (:user-id session) (:user-id %)))
        (filter #(or (nil? authority-key) (= authority-key (:authority %))))
        (sort-by :created-at)
        reverse
        (mapv #(dissoc % :user-id :organization-id)))))

(defn pending?
  "True when this proposal still needs a human."
  [p]
  (= :awaiting-passkey (:status p)))

(defn terminal?
  "True when nothing further will happen to this proposal."
  [p]
  (contains? #{:committed :authority-refused :rejected} (:status p)))
