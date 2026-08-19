(ns cloud.itonami.app.oracle-cases
  "One case table for the shipped decision cores, run on BOTH runtimes.

  ## Why this exists

  The JVM suite was green while the ClojureScript surface could not execute a
  single core, and neither fact could see the other. Two host<->guest
  conversions differ between the runtimes and only one of them throws:

  1. An `:i64` INSIDE a record goes through `value/bounded-typed-value!`, which
     under ClojureScript demands a `js/BigInt` and rejects a `js/Number`. A
     top-level `:i64` is coerced by `kir/execute` and hides this.
  2. An `:i64` RESULT comes back as a `js/BigInt`, which misses every host
     lookup keyed by a number — the host then answers with its `not-found`,
     confidently and wrongly.

  So the cases below are deliberately built the way a host builds them — plain
  host integers handed to `oracle/i64`, results read back through
  `oracle/i64-value` — and they run through `oracle/call`, not `ir/execute`.
  Routing through the seam is the whole point: it is the seam's conversions
  that are under test, and calling the interpreter directly would bypass them.

  ## Coverage is enforced, not aspirational

  `uncovered` reads the exports out of the shipped artifacts. A new
  `(:export …)` that lands without a case here fails the gate rather than
  arriving untested, which is how `bot/account-disposition` reached production
  broken on ClojureScript in the first place."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(def actor-record
  [:record :fleet/actor
   [[:endpoint [:option :string]] [:health-path [:option :string]]
    [:isic [:option :string]] [:isic-rev5 [:option :string]]
    [:isic-rev4 [:option :string]]]])

(def provider-record
  [:record :policy/provider
   [[:enabled :bool] [:reviewed :bool] [:no-egress :bool]
    [:egress-permitted :bool] [:confidential :bool] [:authenticated :bool]]])

(def intent-check-record
  [:record :ao/intent-check
   [[:status-active :bool] [:has-id :bool] [:has-issued-by :bool]
    [:organization-matches :bool] [:worker-matches :bool]
    [:capability-granted :bool] [:has-expires-at :bool]
    [:expires-at :i64] [:now-ms :i64]]])

(def eligibility-record
  [:record :approval/eligibility
   [[:same-organization :bool] [:same-capability :bool] [:same-work-item :bool]
    [:same-content-hash :bool] [:actor-is-person :bool] [:has-eligible-role :bool]
    [:requires-user-verification :bool] [:user-verified :bool]
    [:separation-of-duties :bool] [:actor-is-submitter :bool]]])

(def tally-record
  [:record :approval/tally
   [[:veto-mode :bool] [:rejected-count :i64] [:approved-count :i64]
    [:minimum :i64]]])

(def admission-record
  [:record :bot/admission
   [[:deployment-enabled :bool] [:granted :bool] [:connected :bool] [:writes :bool]]])

(def decision-record
  [:record :bot/decision
   [[:human :bool] [:identified :bool] [:authorized :bool]
    [:delegated :bool]]])

(def peer-pair-record
  [:record :peer/pair
   [[:same-owner :bool] [:source-enabled :bool] [:target-enabled :bool]
    [:distinct-bots :bool]]])

(def peer-decision-record
  [:record :peer/decision
   [[:human :bool] [:identified :bool] [:authorized :bool]]])

(def peer-reach-record
  [:record :peer/reach
   [[:same-owner :bool] [:target-enabled :bool] [:device-known :bool]
    [:device-is-local :bool] [:remote-enabled :bool]]])

(def accounts-record
  [:record :bot/accounts
   [[:connected :i64] [:bound :i64] [:declared :bool] [:selected :bool]]])

(def presence-record
  [:record :bot/presence
   [[:enabled :bool] [:held-run :bool] [:unmet-connection :bool] [:active-run :bool]]])

(def request-record
  [:record :bot/request
   [[:asked-at :i64] [:current :i64] [:answered :bool]]])

(def routine-presence-record
  [:record :routine/presence
   [[:enabled :bool] [:held-run :bool] [:active-run :bool]
    [:steps-admitted :i64] [:steps-recorded :i64]]])

(def routine-tick-record
  [:record :routine/tick [[:tick-enabled :bool] [:session-live :bool]]])

(def handoff-request-record
  [:record :handoff/request
   [[:same-owner :bool] [:source-enabled :bool] [:target-enabled :bool]
    [:distinct-bots :bool] [:depth :i64] [:max-depth :i64]]])

(def session-handoff-claim-record
  [:record :session-handoff/claim
   [[:origin-trusted :bool] [:ready :bool] [:claimed :bool] [:expired :bool]]])

(def handoff-decision-record
  [:record :handoff/decision
   [[:human :bool] [:identified :bool] [:authorized :bool]]])

(defn- some-string [s] [[:option :string] true s])
(def ^:private no-string [[:option :string] false])

(defn- eligibility
  "Ten booleans in declared order."
  [& bs]
  (oracle/record eligibility-record (vec bs)))

;; `:expect` is compared after `:read`, so an `:i64` result is compared as a
;; host number on both runtimes rather than as whatever the guest handed back.
(def binding-facts-record
  [:record :domain-binding/facts
   [[:owner-authorized :bool] [:txt-observed :bool] [:claim-exclusive :bool]
    [:probe-answered :bool] [:probe-confidential :bool] [:probe-fresh :bool]
    [:name-is-service-owned :bool] [:previously-live :bool]]])

(defn- binding-facts
  "Eight booleans in declared order, spelled as a map so a case reads as the
  situation it describes rather than as a row of eight bare trues."
  [m]
  (oracle/record
   binding-facts-record
   (mapv #(boolean (get m %))
         [:owner-authorized :txt-observed :claim-exclusive :probe-answered
          :probe-confidential :probe-fresh :name-is-service-owned
          :previously-live])))

(def mail-facts-record
  [:record :domain-binding/mail-facts
   [[:owner-authorized :bool] [:spf-present :bool] [:spf-closed :bool]
    [:dkim-present :bool] [:dmarc-present :bool] [:dmarc-enforcing :bool]
    [:claim-exclusive :bool] [:name-is-service-owned :bool]
    [:previously-authorized :bool]]])

(defn- mail-facts [m]
  (oracle/record
   mail-facts-record
   (mapv #(boolean (get m %))
         [:owner-authorized :spf-present :spf-closed :dkim-present
          :dmarc-present :dmarc-enforcing :claim-exclusive
          :name-is-service-owned :previously-authorized])))

(def ^:private mail-proven
  "Every record published and the SPF record actually closed."
  {:owner-authorized true :spf-present true :spf-closed true
   :dkim-present true :dmarc-present true :claim-exclusive true})

(def ^:private proven-and-answering
  {:owner-authorized true :txt-observed true :claim-exclusive true
   :probe-answered true :probe-confidential true :probe-fresh true})

(def cases
  "{:oracle :export :args :expect} — `:read` defaults to identity."
  (concat
   ;; ── policy ──────────────────────────────────────────────────────
   [{:oracle :policy :export 'loopback-host? :args ["127.0.0.1"] :expect true}
    {:oracle :policy :export 'loopback-host? :args ["example.com"] :expect false}
    ;; enabled + reviewed + no egress -> admitted
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [true true true false false false])] :expect true}
    ;; SECURITY FIRST: loopback no longer admits on its own. Unreviewed denies.
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [true false true false false false])] :expect false}
    ;; disabled denies whatever else is true
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [false true true true true true])] :expect false}
    ;; egress with everything present -> admitted
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [true true false true true true])] :expect true}
    ;; egress missing the deployment switch / TLS / a credential -> denied
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [true true false false true true])] :expect false}
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [true true false true false true])] :expect false}
    {:oracle :policy :export 'provider-allowed?
     :args [(oracle/record provider-record [true true false true true false])] :expect false}

    ;; ── health ─────────────────────────────────────────────────────
    {:oracle :health :export 'health-route? :args ["GET" "/health"] :expect true}
    {:oracle :health :export 'health-route? :args ["POST" "/health"] :expect false}
    {:oracle :health :export 'health-route? :args ["GET" "/"] :expect false}
    {:oracle :health :export 'health-route? :args ["GET" "/healthz"] :expect false}

    ;; ── oauth-resource (RFC 9728 discovery) ────────────────────────
    {:oracle :oauth-resource :export 'oauth-resource-route?
     :args ["GET" "/.well-known/oauth-protected-resource/mcp"] :expect true}
    {:oracle :oauth-resource :export 'oauth-resource-route?
     :args ["POST" "/.well-known/oauth-protected-resource/mcp"] :expect false}
    {:oracle :oauth-resource :export 'oauth-resource-route?
     :args ["GET" "/health"] :expect false}
    {:oracle :oauth-resource :export 'oauth-resource-route?
     :args ["GET" "/.well-known/did.json"] :expect false}
    {:oracle :oauth-resource :export 'oauth-resource-route?
     :args ["GET" "/.well-known/oauth-protected-resource"] :expect false}

    ;; ── did-web ────────────────────────────────────────────────────
    {:oracle :did-web :export 'did-web-route?
     :args ["GET" "/.well-known/did.json"] :expect true}
    {:oracle :did-web :export 'did-web-route?
     :args ["POST" "/.well-known/did.json"] :expect false}
    {:oracle :did-web :export 'did-web-route?
     :args ["GET" "/health"] :expect false}
    {:oracle :did-web :export 'did-web-route?
     :args ["GET" "/.well-known/oauth-protected-resource/mcp"] :expect false}
    {:oracle :did-web :export 'did-web-route?
     :args ["GET" "/.well-known/did"] :expect false}

    ;; ── fleet-core ──────────────────────────────────────────────────
    {:oracle :fleet-core :export 'catalog-schema-ok?
     :args ["cloud.itonami.fleet-catalog.v1"] :expect true}
    {:oracle :fleet-core :export 'catalog-schema-ok? :args ["nope"] :expect false}
    {:oracle :fleet-core :export 'callable?
     :args [(oracle/record actor-record
                           [(some-string "https://x.example") no-string
                            no-string no-string no-string])]
     :expect true}
    {:oracle :fleet-core :export 'callable?
     :args [(oracle/record actor-record [no-string no-string no-string no-string no-string])]
     :expect false}
    {:oracle :fleet-core :export 'probeable?
     :args [(oracle/record actor-record
                           [(some-string "https://x.example") (some-string "/health")
                            no-string no-string no-string])]
     :expect true}
    {:oracle :fleet-core :export 'probeable?
     :args [(oracle/record actor-record
                           [(some-string "https://x.example") no-string
                            no-string no-string no-string])]
     :expect false}
    {:oracle :fleet-core :export 'isic-of
     :args [(oracle/record actor-record
                           [no-string no-string no-string (some-string "0126") no-string])]
     :expect [[:option :string] true "0126"]}
    {:oracle :fleet-core :export 'isic-of
     :args [(oracle/record actor-record [no-string no-string no-string no-string no-string])]
     :expect [[:option :string] false]}

    ;; ── organism-worker ─────────────────────────────────────────────
    {:oracle :organism-worker :export 'required-value-present?
     :args [(some-string "x")] :expect true}
    {:oracle :organism-worker :export 'required-value-present?
     :args [no-string] :expect false}
    ;; The two `:i64` fields are the reason this file exists. Handed in as
    ;; plain host integers through `oracle/i64`, exactly as the host does.
    {:oracle :organism-worker :export 'rejection-reason
     :args [(oracle/record intent-check-record
                           [true true true true true true true
                            (oracle/i64 2000) (oracle/i64 1000)])]
     :expect :admitted}
    {:oracle :organism-worker :export 'rejection-reason
     :args [(oracle/record intent-check-record
                           [true true true true true true true
                            (oracle/i64 500) (oracle/i64 1000)])]
     :expect :intent-expired}
    {:oracle :organism-worker :export 'rejection-reason
     :args [(oracle/record intent-check-record
                           [false true true true true true true
                            (oracle/i64 2000) (oracle/i64 1000)])]
     :expect :worker-not-active}

    ;; ── approval ────────────────────────────────────────────────────
    {:oracle :approval :export 'status-pending :args [] :expect 0 :read oracle/i64-value}
    {:oracle :approval :export 'status-approved :args [] :expect 1 :read oracle/i64-value}
    {:oracle :approval :export 'status-rejected :args [] :expect 2 :read oracle/i64-value}
    {:oracle :approval :export 'main :args [] :expect 0 :read oracle/i64-value}
    {:oracle :approval :export 'verification-satisfied?
     :args [(eligibility true true true true true true false false false false)]
     :expect true}
    {:oracle :approval :export 'verification-satisfied?
     :args [(eligibility true true true true true true true false false false)]
     :expect false}
    {:oracle :approval :export 'separation-satisfied?
     :args [(eligibility true true true true true true false false true true)]
     :expect false}
    {:oracle :approval :export 'separation-satisfied?
     :args [(eligibility true true true true true true false false true false)]
     :expect true}
    {:oracle :approval :export 'eligible?
     :args [(eligibility true true true true true true false false true false)]
     :expect true}
    {:oracle :approval :export 'eligible?
     :args [(eligibility false true true true true true false false true false)]
     :expect false}
    {:oracle :approval :export 'minimum-met?
     :args [(oracle/i64 2) (oracle/i64 2)] :expect true}
    {:oracle :approval :export 'minimum-met?
     :args [(oracle/i64 1) (oracle/i64 2)] :expect false}
    ;; Three `:i64` fields in one record.
    {:oracle :approval :export 'veto-triggered?
     :args [(oracle/record tally-record
                           [true (oracle/i64 1) (oracle/i64 3) (oracle/i64 2)])]
     :expect true}
    {:oracle :approval :export 'veto-triggered?
     :args [(oracle/record tally-record
                           [false (oracle/i64 1) (oracle/i64 3) (oracle/i64 2)])]
     :expect false}
    {:oracle :approval :export 'status
     :args [(oracle/record tally-record
                           [true (oracle/i64 1) (oracle/i64 3) (oracle/i64 2)])]
     :expect 2 :read oracle/i64-value}
    {:oracle :approval :export 'status
     :args [(oracle/record tally-record
                           [false (oracle/i64 0) (oracle/i64 3) (oracle/i64 2)])]
     :expect 1 :read oracle/i64-value}
    {:oracle :approval :export 'status
     :args [(oracle/record tally-record
                           [false (oracle/i64 0) (oracle/i64 1) (oracle/i64 2)])]
     :expect 0 :read oracle/i64-value}

    ;; ── bot ─────────────────────────────────────────────────────────
    {:oracle :bot :export 'status-disabled :args [] :expect 0 :read oracle/i64-value}
    {:oracle :bot :export 'status-idle :args [] :expect 1 :read oracle/i64-value}
    {:oracle :bot :export 'status-working :args [] :expect 2 :read oracle/i64-value}
    {:oracle :bot :export 'status-waiting-connection :args [] :expect 3 :read oracle/i64-value}
    {:oracle :bot :export 'status-waiting-approval :args [] :expect 4 :read oracle/i64-value}
    {:oracle :bot :export 'account-connect :args [] :expect 0 :read oracle/i64-value}
    {:oracle :bot :export 'account-use :args [] :expect 1 :read oracle/i64-value}
    {:oracle :bot :export 'account-ask :args [] :expect 2 :read oracle/i64-value}
    {:oracle :bot :export 'main :args [] :expect 0 :read oracle/i64-value}
    {:oracle :bot :export 'write-effect? :args ["write"] :expect true}
    {:oracle :bot :export 'write-effect? :args ["read"] :expect false}
    {:oracle :bot :export 'tool-admitted?
     :args [(oracle/record admission-record [true true true true]) "write"] :expect true}
    {:oracle :bot :export 'tool-admitted?
     :args [(oracle/record admission-record [true true true false]) "write"] :expect false}
    {:oracle :bot :export 'may-approve?
     :args [(oracle/record decision-record [true true true false]) "person"] :expect true}
    {:oracle :bot :export 'may-approve?
     :args [(oracle/record decision-record [false true true false]) "person"] :expect false}
    ;; The two halves of ADR-0060, as the shipped artifact answers them. The
    ;; first is the whole lift; the second is what the lift did NOT do, and it
    ;; is the one worth keeping — an agent asserting every human fact about
    ;; itself is still refused when nobody delegated.
    {:oracle :bot :export 'may-approve?
     :args [(oracle/record decision-record [false false false true]) "agent"] :expect true}
    {:oracle :bot :export 'may-approve?
     :args [(oracle/record decision-record [true true true false]) "agent"] :expect false}
    {:oracle :bot :export 'grant-widens?
     :args [(oracle/i64 3) (oracle/i64 2)] :expect true}
    {:oracle :bot :export 'grant-widens?
     :args [(oracle/i64 2) (oracle/i64 2)] :expect false}
    ;; `:bot/accounts` carries two `:i64` fields and the export RETURNS an
    ;; `:i64`, so this one case exercises both directions at once. It is the
    ;; exact call `bot/account-disposition` makes.
    {:oracle :bot :export 'usable-accounts
     :args [(oracle/record accounts-record
                           [(oracle/i64 3) (oracle/i64 2) true false])]
     :expect 2 :read oracle/i64-value}
    {:oracle :bot :export 'account-disposition
     :args [(oracle/record accounts-record
                           [(oracle/i64 0) (oracle/i64 0) false false])]
     :expect 0 :read oracle/i64-value}
    {:oracle :bot :export 'account-disposition
     :args [(oracle/record accounts-record
                           [(oracle/i64 1) (oracle/i64 1) false false])]
     :expect 1 :read oracle/i64-value}
    {:oracle :bot :export 'account-disposition
     :args [(oracle/record accounts-record
                           [(oracle/i64 2) (oracle/i64 2) false false])]
     :expect 2 :read oracle/i64-value}
    {:oracle :bot :export 'status
     :args [(oracle/record presence-record [false false false false])]
     :expect 0 :read oracle/i64-value}
    {:oracle :bot :export 'status
     :args [(oracle/record presence-record [true false false true])]
     :expect 2 :read oracle/i64-value}
    {:oracle :bot :export 'request-open :args [] :expect 0 :read oracle/i64-value}
    {:oracle :bot :export 'request-answered :args [] :expect 1 :read oracle/i64-value}
    {:oracle :bot :export 'request-superseded :args [] :expect 2 :read oracle/i64-value}
    ;; Asked under the direction still in force: open.
    {:oracle :bot :export 'request-standing
     :args [(oracle/record request-record [(oracle/i64 3) (oracle/i64 3) false])]
     :expect 0 :read oracle/i64-value}
    ;; The person has said something else since: superseded.
    {:oracle :bot :export 'request-standing
     :args [(oracle/record request-record [(oracle/i64 3) (oracle/i64 4) false])]
     :expect 2 :read oracle/i64-value}
    ;; Answered outranks direction. A decision the person actually gave is not
    ;; unmade by them going on to say something else — reversing these two
    ;; branches erases it.
    {:oracle :bot :export 'request-standing
     :args [(oracle/record request-record [(oracle/i64 3) (oracle/i64 9) true])]
     :expect 1 :read oracle/i64-value}
    ;; A card written before `:card/direction` existed carries 0, and every
    ;; real direction is 1 or more, so it reads as superseded rather than as a
    ;; request somebody is still waiting on.
    {:oracle :bot :export 'request-standing
     :args [(oracle/record request-record [(oracle/i64 0) (oracle/i64 1) false])]
     :expect 2 :read oracle/i64-value}
    {:oracle :bot :export 'outstanding?
     :args [(oracle/record request-record [(oracle/i64 2) (oracle/i64 2) false])]
     :expect true}
    {:oracle :bot :export 'outstanding?
     :args [(oracle/record request-record [(oracle/i64 2) (oracle/i64 3) false])]
     :expect false}
    {:oracle :bot :export 'outstanding?
     :args [(oracle/record request-record [(oracle/i64 2) (oracle/i64 2) true])]
     :expect false}]

   ;; ── work-transitions ────────────────────────────────────────────
   ;; An older-format artifact: every parameter is an implicit `:i64`, and the
   ;; eleven status constants are what the rest is expressed in.
   (map-indexed
    (fn [i export] {:oracle :work-transitions :export export :args []
                    :expect i :read oracle/i64-value})
    '[status-backlog status-ready status-leased status-running status-held
      status-review status-done status-failed status-rejected status-cancelled])
   [{:oracle :work-transitions :export 'status-count :args [] :expect 10
     :read oracle/i64-value}
    {:oracle :work-transitions :export 'status-known? :args [(oracle/i64 0)] :expect true}
    {:oracle :work-transitions :export 'status-known? :args [(oracle/i64 99)] :expect false}
    {:oracle :work-transitions :export 'terminal? :args [(oracle/i64 6)] :expect true}
    {:oracle :work-transitions :export 'terminal? :args [(oracle/i64 0)] :expect false}
    {:oracle :work-transitions :export 'transition-legal?
     :args [(oracle/i64 0) (oracle/i64 1)] :expect true}
    {:oracle :work-transitions :export 'transition-legal?
     :args [(oracle/i64 0) (oracle/i64 6)] :expect false}]

   ;; ── routine ─────────────────────────────────────────────────────
   ;; `:routine/presence` carries two `:i64` fields, so every case here
   ;; exercises the in-a-record direction that only ClojureScript rejects.
   (map-indexed
    (fn [i export] {:oracle :routine :export export :args []
                    :expect i :read oracle/i64-value})
    '[status-disabled status-idle status-running status-waiting-approval
      status-stale])
   [{:oracle :routine :export 'main :args [] :expect 0 :read oracle/i64-value}
    ;; a narrowed grant is stale; an equal one is not
    {:oracle :routine :export 'stale?
     :args [(oracle/record routine-presence-record
                           [true false false (oracle/i64 2) (oracle/i64 3)])]
     :expect true}
    {:oracle :routine :export 'stale?
     :args [(oracle/record routine-presence-record
                           [true false false (oracle/i64 3) (oracle/i64 3)])]
     :expect false}
    ;; a person may start with a held run outstanding; a schedule may not.
    ;; The pair is the whole difference between the two exports.
    {:oracle :routine :export 'may-start?
     :args [(oracle/record routine-presence-record
                           [true true false (oracle/i64 3) (oracle/i64 3)])]
     :expect true}
    {:oracle :routine :export 'may-fire?
     :args [(oracle/record routine-presence-record
                           [true true false (oracle/i64 3) (oracle/i64 3)])]
     :expect false}
    {:oracle :routine :export 'may-fire?
     :args [(oracle/record routine-presence-record
                           [true false false (oracle/i64 3) (oracle/i64 3)])]
     :expect true}
    {:oracle :routine :export 'status
     :args [(oracle/record routine-presence-record
                           [false false false (oracle/i64 3) (oracle/i64 3)])]
     :expect 0 :read oracle/i64-value}
    {:oracle :routine :export 'status
     :args [(oracle/record routine-presence-record
                           [true true false (oracle/i64 2) (oracle/i64 3)])]
     :expect 3 :read oracle/i64-value}
    {:oracle :routine :export 'status
     :args [(oracle/record routine-presence-record
                           [true false false (oracle/i64 2) (oracle/i64 3)])]
     :expect 4 :read oracle/i64-value}
    ;; An agent session must never drive the clock, whatever else is true.
    {:oracle :routine :export 'tick-admitted?
     :args [(oracle/record routine-tick-record [true true]) "agent"]
     :expect false}
    {:oracle :routine :export 'tick-admitted?
     :args [(oracle/record routine-tick-record [true true]) "passkey"]
     :expect true}
    {:oracle :routine :export 'tick-admitted?
     :args [(oracle/record routine-tick-record [false true]) "passkey"]
     :expect false}
    {:oracle :routine :export 'tick-admitted?
     :args [(oracle/record routine-tick-record [true false]) "passkey"]
     :expect false}]

   ;; ── handoff ─────────────────────────────────────────────────────
   [{:oracle :handoff :export 'main :args [] :expect 0 :read oracle/i64-value}
    {:oracle :handoff :export 'admitted?
     :args [(oracle/record handoff-request-record
                           [true true true true (oracle/i64 0) (oracle/i64 4)])]
     :expect true}
    ;; ownership is the refusal that is not recoverable
    {:oracle :handoff :export 'admitted?
     :args [(oracle/record handoff-request-record
                           [false true true true (oracle/i64 0) (oracle/i64 4)])]
     :expect false}
    {:oracle :handoff :export 'admitted?
     :args [(oracle/record handoff-request-record
                           [true true true true (oracle/i64 4) (oracle/i64 4)])]
     :expect false}
    {:oracle :handoff :export 'budget-exhausted?
     :args [(oracle/record handoff-request-record
                           [true true true true (oracle/i64 4) (oracle/i64 4)])]
     :expect true}
    {:oracle :handoff :export 'budget-exhausted?
     :args [(oracle/record handoff-request-record
                           [true true true true (oracle/i64 0) (oracle/i64 4)])]
     :expect false}
    ;; the refusal the core exists for, and its human counterpart
    {:oracle :handoff :export 'may-approve?
     :args [(oracle/record handoff-decision-record [true true true]) "agent"]
     :expect false}
    {:oracle :handoff :export 'may-approve?
     :args [(oracle/record handoff-decision-record [true true true]) "person"]
     :expect true}
    {:oracle :handoff :export 'next-depth
     :args [(oracle/record handoff-request-record
                           [true true true true (oracle/i64 2) (oracle/i64 4)])]
     :expect 3 :read oracle/i64-value}]

   ;; ── peer (persistent Bot messaging; no depth) ───────────────────
   [{:oracle :peer :export 'main :args [] :expect 0 :read oracle/i64-value}
    {:oracle :peer :export 'may-message?
     :args [(oracle/record peer-pair-record [true true true true])]
     :expect true}
    {:oracle :peer :export 'may-message?
     :args [(oracle/record peer-pair-record [false true true true])]
     :expect false}
    {:oracle :peer :export 'may-message?
     :args [(oracle/record peer-pair-record [true true true false])]
     :expect false}
    {:oracle :peer :export 'computer-shared?
     :args [(oracle/record peer-pair-record [true false false true])]
     :expect true}
    {:oracle :peer :export 'computer-shared?
     :args [(oracle/record peer-pair-record [false true true true])]
     :expect false}
    {:oracle :peer :export 'foreign-memory?
     :args [(oracle/record peer-pair-record [true true true true])]
     :expect true}
    {:oracle :peer :export 'foreign-memory?
     :args [(oracle/record peer-pair-record [true true true false])]
     :expect false}
    ;; The refusal ADR-0060 did NOT lift. `bot_core/may-approve?` asks whether
    ;; this actor may decide a card on the Bot it is acting as, and a delegated
    ;; agent may; this asks whether a message that arrived FROM ANOTHER BOT may
    ;; stand in for one, and nothing makes that true.
    {:oracle :peer :export 'may-approve?
     :args [(oracle/record peer-decision-record [true true true]) "agent"]
     :expect false}
    {:oracle :peer :export 'may-approve?
     :args [(oracle/record peer-decision-record [true true true]) "person"]
     :expect true}
    ;; ADR-0062. The four that matter: a local handle ignores the remote
    ;; switch, a registered remote device needs it, an unregistered one is not
    ;; addressable at all, and another person's Bot is refused whatever else
    ;; is true.
    {:oracle :peer :export 'may-address?
     :args [(oracle/record peer-reach-record [true true false true false])]
     :expect true}
    {:oracle :peer :export 'may-address?
     :args [(oracle/record peer-reach-record [true true true false true])]
     :expect true}
    {:oracle :peer :export 'may-address?
     :args [(oracle/record peer-reach-record [true true true false false])]
     :expect false}
    {:oracle :peer :export 'may-address?
     :args [(oracle/record peer-reach-record [true true false false true])]
     :expect false}
    {:oracle :peer :export 'may-address?
     :args [(oracle/record peer-reach-record [false true true false true])]
     :expect false}
    {:oracle :peer :export 'reaches-another-machine?
     :args [(oracle/record peer-reach-record [true true true false true])]
     :expect true}
    {:oracle :peer :export 'reaches-another-machine?
     :args [(oracle/record peer-reach-record [true true false true true])]
     :expect false}]

   ;; ── session-handoff ─────────────────────────────────────────────
   ;; Unrelated to :handoff above — this one moves an authentication that
   ;; already succeeded into the agent that asked for it. Field order is
   ;; origin-trusted, ready, claimed, expired.
   [{:oracle :session-handoff :export 'main :args [] :expect 0
     :read oracle/i64-value}
    {:oracle :session-handoff :export 'claimable?
     :args [(oracle/record session-handoff-claim-record
                           [true true false false])]
     :expect true}
    ;; each of the four refusals, one at a time, against an otherwise
    ;; admissible claim
    {:oracle :session-handoff :export 'claimable?
     :args [(oracle/record session-handoff-claim-record
                           [false true false false])]
     :expect false}
    {:oracle :session-handoff :export 'claimable?
     :args [(oracle/record session-handoff-claim-record
                           [true false false false])]
     :expect false}
    {:oracle :session-handoff :export 'claimable?
     :args [(oracle/record session-handoff-claim-record
                           [true true true false])]
     :expect false}
    {:oracle :session-handoff :export 'claimable?
     :args [(oracle/record session-handoff-claim-record
                           [true true false true])]
     :expect false}

    ;; ── domain-binding (ADR-0043) ───────────────────────────────────
    {:oracle :domain-binding :export 'state-pending :args [] :expect 0 :read oracle/i64-value}
    {:oracle :domain-binding :export 'state-claimed :args [] :expect 1 :read oracle/i64-value}
    {:oracle :domain-binding :export 'state-live :args [] :expect 2 :read oracle/i64-value}
    {:oracle :domain-binding :export 'state-lapsed :args [] :expect 3 :read oracle/i64-value}
    {:oracle :domain-binding :export 'main :args [] :expect 0 :read oracle/i64-value}

    ;; the one public route this binding owns
    {:oracle :domain-binding :export 'nonce-route?
     :args ["GET" "/.well-known/itonami-domain-binding.json"] :expect true}
    {:oracle :domain-binding :export 'nonce-route?
     :args ["POST" "/.well-known/itonami-domain-binding.json"] :expect false}
    {:oracle :domain-binding :export 'nonce-route?
     :args ["GET" "/.well-known/did.json"] :expect false}
    {:oracle :domain-binding :export 'nonce-route?
     :args ["GET" "/.well-known/itonami-domain-binding"] :expect false}
    {:oracle :domain-binding :export 'nonce-route?
     :args ["get" "/.well-known/itonami-domain-binding.json"] :expect false}

    ;; may-start? — an owner may be told what to publish
    {:oracle :domain-binding :export 'may-start?
     :args [(binding-facts {:owner-authorized true :claim-exclusive true})]
     :expect true}
    {:oracle :domain-binding :export 'may-start?
     :args [(binding-facts {:claim-exclusive true})]
     :expect false}
    ;; a name another tenant already holds: refused at the START, so nobody is
    ;; told to publish a record that could never count
    {:oracle :domain-binding :export 'may-start?
     :args [(binding-facts {:owner-authorized true})]
     :expect false}
    ;; the deployment's own managed suffix. This was a literal that named the
    ;; wrong domain; it is an argument now.
    {:oracle :domain-binding :export 'may-start?
     :args [(binding-facts {:owner-authorized true :claim-exclusive true
                            :name-is-service-owned true})]
     :expect false}

    ;; claim-holds? — the naming right, and deliberately nothing more
    {:oracle :domain-binding :export 'claim-holds?
     :args [(binding-facts {:txt-observed true :claim-exclusive true})]
     :expect true}
    {:oracle :domain-binding :export 'claim-holds?
     :args [(binding-facts {:claim-exclusive true})]
     :expect false}
    {:oracle :domain-binding :export 'claim-holds?
     :args [(binding-facts {:txt-observed true :claim-exclusive true
                            :name-is-service-owned true})]
     :expect false}
    ;; a proven claim is NOT a live name — this is the conflation the core exists
    ;; to prevent
    {:oracle :domain-binding :export 'name-holds?
     :args [(binding-facts {:txt-observed true :claim-exclusive true})]
     :expect false}

    ;; name-holds? — the naming right AND the resolution fact
    {:oracle :domain-binding :export 'name-holds?
     :args [(binding-facts proven-and-answering)]
     :expect true}
    ;; a nonce relayed over a followed redirect or a plaintext hop is not proof
    {:oracle :domain-binding :export 'name-holds?
     :args [(binding-facts (assoc proven-and-answering :probe-confidential false))]
     :expect false}
    {:oracle :domain-binding :export 'name-holds?
     :args [(binding-facts (assoc proven-and-answering :probe-fresh false))]
     :expect false}
    ;; the TXT was pulled while the name still answers: the naming right is gone,
    ;; so the name is gone
    {:oracle :domain-binding :export 'name-holds?
     :args [(binding-facts (assoc proven-and-answering :txt-observed false))]
     :expect false}

    ;; binding-state — the four states, and the two orderings that matter
    {:oracle :domain-binding :export 'binding-state
     :args [(binding-facts {:owner-authorized true :claim-exclusive true})]
     :expect 0 :read oracle/i64-value}
    {:oracle :domain-binding :export 'binding-state
     :args [(binding-facts {:owner-authorized true :txt-observed true
                            :claim-exclusive true})]
     :expect 1 :read oracle/i64-value}
    {:oracle :domain-binding :export 'binding-state
     :args [(binding-facts proven-and-answering)]
     :expect 2 :read oracle/i64-value}
    ;; was live, still holds its TXT, no longer answers. Reporting `:claimed`
    ;; here would be true and would leave the tenant named by a dead address.
    {:oracle :domain-binding :export 'binding-state
     :args [(binding-facts {:owner-authorized true :txt-observed true
                            :claim-exclusive true :previously-live true})]
     :expect 3 :read oracle/i64-value}
    ;; and a lapse is not a life sentence: the probe answers again, the name
    ;; returns. `name-holds?` is asked before `previously-live` for this.
    {:oracle :domain-binding :export 'binding-state
     :args [(binding-facts (assoc proven-and-answering :previously-live true))]
     :expect 2 :read oracle/i64-value}

    ;; ── mail authority, the OTHER proof about a domain ──────────────
    {:oracle :domain-binding :export 'mail-state-pending :args [] :expect 0 :read oracle/i64-value}
    {:oracle :domain-binding :export 'mail-state-authorized :args [] :expect 1 :read oracle/i64-value}
    {:oracle :domain-binding :export 'mail-state-lapsed :args [] :expect 2 :read oracle/i64-value}

    {:oracle :domain-binding :export 'mail-may-start?
     :args [(mail-facts {:owner-authorized true :claim-exclusive true})]
     :expect true}
    {:oracle :domain-binding :export 'mail-may-start?
     :args [(mail-facts {:claim-exclusive true})] :expect false}
    {:oracle :domain-binding :export 'mail-may-start?
     :args [(mail-facts {:owner-authorized true})] :expect false}
    {:oracle :domain-binding :export 'mail-may-start?
     :args [(mail-facts {:owner-authorized true :claim-exclusive true
                         :name-is-service-owned true})]
     :expect false}

    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts mail-proven)] :expect true}
    ;; `v=spf1 +all` authorizes the whole internet. A record that exists and
    ;; says nothing is not a proof, and this is the case that says so.
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :spf-closed false))] :expect false}
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :spf-present false :spf-closed false))]
     :expect false}
    ;; A revoked DKIM key (`p=`) is a record that is present and says the key is
    ;; gone; the host reads that as absent and the core refuses either way.
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :dkim-present false))] :expect false}
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :dmarc-present false))] :expect false}
    ;; `p=none` is monitoring, which is a real posture and not a failure. This
    ;; is the asymmetry with SPF, asserted rather than described.
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :dmarc-enforcing false))] :expect true}
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :claim-exclusive false))] :expect false}
    {:oracle :domain-binding :export 'mail-authorized?
     :args [(mail-facts (assoc mail-proven :name-is-service-owned true))]
     :expect false}

    {:oracle :domain-binding :export 'mail-state
     :args [(mail-facts {:owner-authorized true :claim-exclusive true})]
     :expect 0 :read oracle/i64-value}
    {:oracle :domain-binding :export 'mail-state
     :args [(mail-facts mail-proven)] :expect 1 :read oracle/i64-value}
    {:oracle :domain-binding :export 'mail-state
     :args [(mail-facts (assoc mail-proven :dkim-present false
                               :previously-authorized true))]
     :expect 2 :read oracle/i64-value}
    ;; and a lapse is not permanent here either — the owner republishes and the
    ;; authority returns, because what holds now is asked first.
    {:oracle :domain-binding :export 'mail-state
     :args [(mail-facts (assoc mail-proven :previously-authorized true))]
     :expect 1 :read oracle/i64-value}]))

(defn run-case
  "Execute one case through the seam. Returns {:ok? :actual}.

  A throw is an answer here, not a crash: the `:i64`-in-a-record asymmetry
  presents as one, and a gate that died on the first case would name only that
  case while the rest stayed unmeasured."
  [{:keys [oracle export args expect read]}]
  (try
    (let [actual ((or read identity) (oracle/call oracle export args))]
      {:ok? (= expect actual) :actual actual})
    (catch #?(:clj Exception :cljs :default) e
      {:ok? false :actual (str "threw: " (ex-message e))})))

(defn failures
  "Every case whose answer is not the one recorded, with what it gave instead."
  []
  (into []
        (keep (fn [c]
                (let [{:keys [ok? actual]} (run-case c)]
                  (when-not ok?
                    (assoc c :actual actual)))))
        cases))

(defn uncovered
  "Exports the shipped artifacts declare that no case above exercises.

  Read from the artifacts rather than from a written-down list, so adding an
  `(:export …)` without a case here fails instead of passing silently.

  `:exports` and not `:functions`: an unexported function is not reachable
  through this seam at all (`kir/execute` answers `function is not exported`),
  so requiring a case for one would be requiring a case for something no host
  can call."
  []
  (let [covered (into #{} (map (juxt :oracle #(symbol (name (:export %))))) cases)]
    (into []
          (for [id (keys oracle/cores)
                export (:exports (oracle/kir id))
                :let [k [id (symbol (name export))]]
                :when (not (contains? covered k))]
            k))))
