(ns cloud.itonami.app.handoff
  "One Bot giving work to another, and the contract that keeps that from being
  a way to get a tool you were not granted.

  Two Bots are worth having because one can pass work to the other: something
  that researches hands what it found to something that writes. The risk is in
  the same sentence. If handing work over also hands over reach, then the way
  to use a connector you were refused is to ask a Bot that has it, and every
  per-Bot grant in this application becomes a suggestion.

  ## The mechanism is an absence

  Look at `->request` and at `handoff` below: neither carries a tool, a grant,
  a scope, or an account. Not \"the host is careful not to copy them across\" —
  there is nothing here to copy. A handed-over task arrives at the target as an
  ordinary message, and the target's own `bot/tool-admitted?` decides what may
  run from the target's own grant, exactly as if the person had typed it. The
  sender's reach is not an input to that answer, and this namespace provides no
  way to make it one.

  This is the same construction `bot_core.kotoba` uses to keep a name from
  becoming authority: the reliable way to guarantee a fact cannot influence a
  decision is to keep it out of the function.

  ## What a handoff carries instead

  Provenance. `:handoff/from` is which Bot sent it and `:handoff/depth` is how
  far down a chain this is, and both exist to be READ by a person rather than
  to widen anything. A message that appears in a Bot's transcript without
  saying which Bot put it there is a message the person cannot audit, and a
  chain nobody can see the length of is a chain nobody notices is a loop.

  ## Where the decisions are

  `handoff_core.kotoba`. This namespace hands it booleans and reads back its
  answers, keeping no second copy of a rule it could quietly disagree with."
  (:require [clojure.string :as str]
            [cloud.itonami.app.kotoba-oracle :as oracle]))

(def schema "cloud.itonami.app.handoff.v1")

(def default-max-depth
  "How many hands one piece of work may pass through.

  Four, because the shapes this exists for — research → write → check, or
  triage → specialist → reply — fit inside it, and because every hop costs a
  model call and compounds drift: a chain that is right 98% of a hop is right
  92% of four and two-thirds of twenty. A ceiling is also what makes a ring
  terminate, which is why there is no separate cycle detector."
  4)

(def max-task 4000)

;; ── the seam to the decision core ────────────────────────────────────

(def ^:private request-record
  "The record `handoff_core.kotoba` declares, in DECLARED field order.

  No tool, no grant, no account, no task text. See the namespace docstring —
  the absence is the mechanism, and adding a field here would remove it."
  [:record :handoff/request
   [[:same-owner :bool] [:source-enabled :bool] [:target-enabled :bool]
    [:distinct-bots :bool] [:depth :i64] [:max-depth :i64]]])

(def ^:private decision-record
  [:record :handoff/decision
   [[:human :bool] [:identified :bool] [:authorized :bool]]])

(defn ->request
  "The six facts the core decides from.

  `source` and `target` are Bots; `owner` is the person the host resolved for
  each. Whether they are the same person is answered HERE, by comparing what
  the host read, rather than passed in as a claim."
  [source target {:keys [source-owner target-owner depth max-depth]}]
  (oracle/record request-record
                 [(boolean (and source-owner target-owner
                                (= source-owner target-owner)))
                  (boolean (:bot/enabled? source))
                  (boolean (:bot/enabled? target))
                  (not= (:bot/id source) (:bot/id target))
                  (long (or depth 0))
                  (long (or max-depth default-max-depth))]))

(defn admitted?
  "May `source` hand work to `target` right now?"
  [source target context]
  (oracle/call :handoff 'admitted? [(->request source target context)]))

(defn budget-exhausted?
  "Has this chain used up its depth?"
  [source target context]
  (oracle/call :handoff 'budget-exhausted? [(->request source target context)]))

(defn next-depth
  "The chain position the accepted handoff records."
  [source target context]
  (oracle/i64-value
   (oracle/call :handoff 'next-depth [(->request source target context)])))

(defn may-approve?
  "May this actor record an approval that arrived by handoff?

  Never, for an agent — which is the whole reason this function exists rather
  than the caller reusing `bot/may-approve?`. Without the refusal restated on
  this path, the way around `bot_core`'s is to hand a held run to a second Bot
  and have that one say yes."
  [{:keys [actor-kind human? identified? authorized?]}]
  (oracle/call
   :handoff 'may-approve?
   [(oracle/record decision-record
                   [(boolean human?) (boolean identified?) (boolean authorized?)])
    (name (or actor-kind :unknown))]))

;; ── the record itself ────────────────────────────────────────────────

(defn handoff
  "One accepted handoff, as it is written into the target's conversation.

  Carries who sent it, how deep the chain is, and what was asked. It does not
  carry what the sender was allowed to do, and `bots.clj` has no way to apply
  it if it did."
  [{:keys [id from to task depth at]}]
  (let [task (str/trim (str task))]
    (when (str/blank? task)
      (throw (ex-info "引き継ぐ内容が空です。" {:type :handoff/empty})))
    (when (> (count task) max-task)
      (throw (ex-info "引き継ぐ内容が長すぎます。" {:type :handoff/too-long})))
    {:handoff/id (str id)
     :handoff/from (str from)
     :handoff/to (str to)
     :handoff/task task
     :handoff/depth (long (or depth 0))
     :handoff/at at}))
