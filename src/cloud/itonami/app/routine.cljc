(ns cloud.itonami.app.routine
  "A routine: work a Bot did once, kept so it can be done again.

  A person asks a Bot for something, the Bot uses some tools, it works. The
  next week they want it again. A routine is that transcript with the parts
  that were incidental removed — what ran, in what order, to what end — so it
  can run on a schedule or on request without anybody retyping the brief.

  ## What is kept, and what is deliberately not

  Steps are SEMANTIC: a tool name, whether it reads or writes, and the intent
  in the person's own words. Not a coordinate, not a DOM path, not a screenshot
  offset. A routine recorded as `click(241, 382)` survives exactly until
  somebody moves a button, and then it does not fail — it clicks something
  else. `canonical` is what fixes this: it keeps the three fields that describe
  intent and drops everything a run happened to carry, so the same workflow
  recorded twice is the same value both times.

  ## The identity of a routine is its content

  `canonical` is deterministic and total, which is what lets the host address a
  routine by the hash of its steps rather than by a row id. Two Bots given the
  same workflow reference one address instead of holding two copies; editing a
  routine produces a different address instead of mutating the one a schedule
  is already pointing at, so the version that ran last Tuesday is still there
  to compare against and still there to go back to.

  The hashing is NOT here. A digest is an effect, and this namespace has none —
  `routines.clj` is the host that computes the address and stores it. What
  lives here is the part that has to be identical on every runtime for the
  address to mean anything: the canonical form.

  ## Where the decisions are

  Not here either. `routine_core.kotoba` decides `stale?`, `may-start?`,
  `may-fire?` and `status`, and this namespace hands it booleans and reads back
  its codes — the same seam `bot.cljc` uses, for the same reason. The host does
  not keep a second copy of a rule it could quietly disagree with.

  ## Why staleness is a refusal and not a filter

  A routine is recorded with the tools its Bot held that day. Grants change. A
  routine that simply skipped the steps it may no longer take would still
  report success, having done part of a job — and the part it skipped is
  exactly the part somebody revoked, which is to say the part that mattered.
  `admitted-steps` counts; the core compares; a short count refuses."
  (:require [clojure.string :as str]
            [cloud.itonami.app.bot :as bot]
            [cloud.itonami.app.kotoba-oracle :as oracle]))

(def schema "cloud.itonami.app.routine.v1")

(def max-name 60)
(def max-intent 200)
(def max-steps 40)

;; ── the shape of a step ──────────────────────────────────────────────

(def step-effects
  "What a step does to the world. Anything a build does not recognise is a
  write, for the same reason `bot_core/write-effect?` says so: an unknown
  effect is not a licence to assume it only reads."
  #{:read :write})

(defn- trimmed [value limit]
  (let [s (str/trim (str value))]
    (if (<= (count s) limit) s (subs s 0 limit))))

(defn step
  "One recorded step. `tool` is a connector tool name as `bot/tool-admitted?`
  spells it, `effect` is `:read` or `:write`, and `intent` is why this step is
  in the routine — kept because it is what a person reads when deciding whether
  to approve the whole thing, and because a diff of two routines that shows
  only tool names does not say what changed."
  [{:keys [tool effect intent]}]
  {:step/tool (trimmed tool max-name)
   :step/effect (if (contains? step-effects effect) effect :write)
   :step/intent (trimmed intent max-intent)})

(defn from-tool-calls
  "The demonstration, turned into steps.

  `calls` is what actually ran during the turn being kept — not what the model
  proposed and not what it planned. A routine built from a plan is a routine
  built from a sentence nobody checked; a routine built from the calls that
  executed describes something that has, at least once, worked.

  Order is preserved and repeats are kept: a workflow that reads two mailboxes
  really does call the same tool twice, and collapsing that would silently
  halve it."
  [calls]
  (into [] (comp (map step)
                 (remove #(str/blank? (:step/tool %)))
                 (take max-steps))
        calls))

(defn canonical
  "The content of a routine, in the one form every runtime agrees on.

  A vector of `[tool effect intent]` triples: sorted by nothing, because order
  IS the workflow, and carrying no id, timestamp, author or run outcome,
  because two recordings of the same workflow must produce the same value or
  content addressing buys nothing. `pr-str` of this is what the host hashes."
  [steps]
  (mapv (fn [s] [(:step/tool s) (name (:step/effect s)) (:step/intent s)])
         steps))

;; ── the seam to the decision core ────────────────────────────────────

(def ^:private presence-record
  "The record `routine_core.kotoba` declares, in DECLARED field order."
  [:record :routine/presence
   [[:enabled :bool] [:held-run :bool] [:active-run :bool]
    [:steps-admitted :i64] [:steps-recorded :i64]]])

(def status-codes
  "The core's `:i64` status codes, mapped once. The numbers are the core's; the
  keywords are this application's."
  {0 :disabled 1 :idle 2 :running 3 :waiting-approval 4 :stale})

(defn admitted-steps
  "The steps of `r` that `bot` may still take, right now.

  Every step is asked the question `bot/tool-admitted?` answers, against the
  live catalogue and the live set of connected connectors — so a tool the
  deployment turned off, a grant that was narrowed, and a connector that was
  disconnected all reduce this count, and the core turns a reduced count into a
  refusal."
  [r b catalog-rows connected-connectors]
  (let [by-name (into {} (map (juxt :name identity)) catalog-rows)]
    (filterv (fn [s]
               (when-let [tool (get by-name (:step/tool s))]
                 (bot/tool-admitted? b tool connected-connectors)))
             (:routine/steps r))))

(defn- presence [r b {:keys [held-run? active-run? admitted]}]
  (oracle/record presence-record
                 [(boolean (and (:routine/enabled? r) (:bot/enabled? b)))
                  (boolean held-run?)
                  (boolean active-run?)
                  (count admitted)
                  (count (:routine/steps r))]))

(defn stale?
  "Has the grant narrowed under this routine since it was recorded?"
  [r b state]
  (oracle/call :routine 'stale? [(presence r b state)]))

(defn may-start?
  "May a person run this routine right now?"
  [r b state]
  (oracle/call :routine 'may-start? [(presence r b state)]))

(defn may-fire?
  "May a schedule start this routine right now?

  Stricter than `may-start?` by exactly one fact — a held run — because nobody
  is watching a schedule. See the core."
  [r b state]
  (oracle/call :routine 'may-fire? [(presence r b state)]))

(defn status
  "What this routine is, right now."
  [r b state]
  (get status-codes
       (oracle/i64-value (oracle/call :routine 'status [(presence r b state)]))
       :idle))

;; ── the record itself ────────────────────────────────────────────────

(defn routine
  "Validate and normalise one routine. The address is the host's to compute and
  is carried, not derived here — this namespace cannot hash."
  [{:keys [id bot name steps address enabled? schedule created-at]}]
  (when (str/blank? (str id))
    (throw (ex-info "routine に id がありません。" {:type :routine/invalid})))
  (when (str/blank? (str bot))
    (throw (ex-info "routine に Bot がありません。" {:type :routine/invalid})))
  (let [steps (vec steps)]
    (when (empty? steps)
      (throw (ex-info "routine に手順がありません。" {:type :routine/empty})))
    {:routine/id (str id)
     :routine/bot (str bot)
     :routine/name (trimmed name max-name)
     :routine/steps steps
     :routine/address address
     :routine/enabled? (if (some? enabled?) (boolean enabled?) true)
     :routine/schedule schedule
     :routine/created-at created-at}))
