(ns cloud.itonami.app.model-routing
  "Which model answers, for which task, for which Bot.

  This application calls a model from four places. They were never the same
  work, but until now they read the same two configuration keys, so every one
  of them ran on the same model and there was no sentence a person could say to
  change that for one of them alone.

  A ROUTING ASSIGNMENT is that sentence. It names a TASK, a SCOPE, a provider
  and a model, and the four tasks it can name are the four call sites that
  exist:

  | task       | what it is                       | where the assignment is read |
  |------------|----------------------------------|------------------------------|
  | `:bot`     | a Bot's own turn                 | `bots/provider-choice!`      |
  | `:room`    | Bots answering each other        | `bots/group-send!`           |
  | `:machine` | the loop that drives this laptop | `agent-control/create-run!`  |
  | `:chat`    | the plain chat surface           | `service/chat-route`         |

  The right-hand column is checked, not documentation: `every-auxiliary-task-
  names-a-function-that-exists` resolves each one, so a rename that leaves this
  table behind fails rather than turning a row into a lie.

  ## Why the list is exactly four, and why it is not Hermes's list

  This surface reproduces one from a product whose auxiliary tasks are vision,
  compaction, title generation, skill search and a dozen more. Those are its
  real helper call sites. Copying the NAMES would have produced a screen
  offering to route a vision model in an application that never analyses an
  image — a menu with no kitchen, which is the failure `bots.clj` was written
  to end rather than one to repeat in a new place.

  So the mechanism is reproduced and the list is measured. Adding a fifth task
  means adding a fifth model call, and the two commits are the same commit.

  ## `:bot` is main; the other three are auxiliary

  Only `:bot` can be assigned per-Bot, because only `:bot` HAS a Bot: a room
  round is many Bots at once, the machine loop belongs to the workstation, and
  the chat surface predates Bots entirely. The three auxiliary tasks take one
  deployment-wide assignment each, and with none they run on whatever `:bot`
  resolved to for the Bot in hand — which is what all four did before this
  namespace existed, so a deployment that never opens the screen keeps exactly
  the behaviour it has.

  ## What this namespace refuses to do

  Fall back. An auxiliary override naming a provider this deployment will not
  admit RAISES, and `model_routing_core.kotoba` holds that decision. The header
  there has the argument; the short form is that a silent fallback bills the
  main model while the screen names the cheap one, and nothing in the output
  tells the two apart.

  It also does not decide admission. `policy/select-provider` does, first and
  unchanged: an assignment is a preference and never a route around review,
  TLS, a credential, or the deployment egress switch."
  (:require [clojure.string :as str]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.policy :as policy]))

(def schema "cloud.itonami.app.model-routing.v1")

;; ── the tasks, which are the call sites ──────────────────────────────

(def main-task
  "The task a Bot's own turn runs under. The only one with a per-Bot scope."
  :bot)

(def auxiliary-tasks
  "Every model call this application makes that is NOT a Bot's own turn.

  Ordered as the screen shows them. Each `:source` is the namespace and
  function that actually issues the call — the field exists so that a task
  which stops being called cannot keep its row on the screen without somebody
  noticing the function it names is gone."
  [{:task :room
    :label "Bot同士の会話"
    :hint "Bot が互いに答える一巡"
    :source "bots/group-send!"}
   {:task :machine
    :label "この機械の操作"
    :hint "ブラウザとデスクトップを動かすループ"
    :source "agent-control/create-run!"}
   {:task :chat
    :label "チャット"
    :hint "Bot を介さない素のチャット画面"
    :source "service/chat-route"}])

(def auxiliary-task-set
  (into #{} (map :task) auxiliary-tasks))

(def tasks
  "Every assignable task, main first."
  (into [main-task] (map :task) auxiliary-tasks))

(defn task
  "Normalise a wire task name, or nil if this application has no such call."
  [value]
  (let [k (some-> value name str/trim not-empty keyword)]
    (when (or (= k main-task) (contains? auxiliary-task-set k)) k)))

(defn auxiliary-task? [value]
  (contains? auxiliary-task-set (task value)))

;; ── scopes ───────────────────────────────────────────────────────────

(def default-scope
  "The scope that covers every Bot without an assignment of its own.

  A string rather than a keyword because it shares a key space with Bot ids on
  the wire and in storage, and a scope that is sometimes `:default` and
  sometimes `\"default\"` is two scopes that look like one."
  "default")

(defn scope
  "Normalise a scope: `default-scope`, or a Bot id."
  [value]
  (or (some-> value name str/trim not-empty) default-scope))

(defn default-scope? [value]
  (= default-scope (scope value)))

;; ── the decisions ────────────────────────────────────────────────────

(def ^:private scope-record
  [:record :routing/scope [[:bot-assigned :bool] [:default-assigned :bool]]])

(def ^:private auxiliary-record
  [:record :routing/auxiliary [[:has-override :bool] [:override-admitted :bool]]])

(def ^:private submitted-record
  [:record :routing/submitted [[:has-provider :bool] [:has-model :bool]]])

(def scope-codes
  {0 :bot 1 :default 2 :provider})

(def auxiliary-codes
  {0 :override 1 :main 2 :refused})

(defn route-scope
  "Where a `:bot`-task model comes from for this Bot: `:bot`, `:default` or
  `:provider`."
  [{:keys [bot-assigned? default-assigned?]}]
  (get scope-codes
       (oracle/i64-value
        (oracle/call :model-routing 'route-scope
                     [(oracle/record scope-record
                                     [(boolean bot-assigned?)
                                      (boolean default-assigned?)])]))
       :provider))

(defn auxiliary-route
  "What one auxiliary task should do: `:override`, `:main` or `:refused`."
  [{:keys [has-override? override-admitted?]}]
  (get auxiliary-codes
       (oracle/i64-value
        (oracle/call :model-routing 'auxiliary-route
                     [(oracle/record auxiliary-record
                                     [(boolean has-override?)
                                      (boolean override-admitted?)])]))
       :main))

(defn assignment-complete?
  "Does this submission carry both halves of an assignment?"
  [{:keys [provider-id model]}]
  (oracle/call :model-routing 'assignment-complete?
               [(oracle/record submitted-record
                               [(boolean (not (str/blank? (str provider-id))))
                                (boolean (not (str/blank? (str model))))])]))

;; ── the record ───────────────────────────────────────────────────────

(def max-model 200)

(defn assignment
  "Validate and normalise one assignment as a screen submitted it.

  Rejects a half-filled pair before it reaches storage, so `route-scope` may
  treat an assignment it finds as complete without asking again.

  This normalises BOTH kinds and stores neither. Where the result lands is the
  host's, and the two destinations are different on purpose: a `:bot` task with
  a Bot scope is written to that Bot's record, because `:bot/provider-id` and
  `:bot/model` are where this application has always kept it; everything else
  is a deployment-wide row for `index`. `index` drops the first kind if it ever
  sees one, so the two cannot both hold a copy."
  [submitted]
  (let [t (task (:task submitted))
        s (scope (:scope submitted))
        provider-id (some-> (:provider-id submitted) str str/trim not-empty)
        model (some-> (:model submitted) str str/trim not-empty)]
    (when-not t
      (throw (ex-info "この application にその model task はありません。"
                      {:type :routing/unknown-task :task (:task submitted)})))
    (when (and (auxiliary-task? t) (not (default-scope? s)))
      (throw (ex-info "補助タスクは Bot ごとに割り当てられません。"
                      {:type :routing/scope-not-assignable :task t :scope s})))
    (when-not (assignment-complete? {:provider-id provider-id :model model})
      (throw (ex-info "provider と model の両方が必要です。"
                      {:type :routing/incomplete :task t :scope s})))
    (when (> (count model) max-model)
      (throw (ex-info "model 名が長すぎます。"
                      {:type :routing/invalid :task t})))
    {:routing/task t
     :routing/scope s
     :routing/provider-id provider-id
     :routing/model model}))

(def store-path
  "Where the deployment-wide assignments live in the durable store.

  Named once, here, because three namespaces read it and a path literal copied
  into three places is three places that can drift apart while all three keep
  answering."
  [:bots :routing])

(defn index
  "Deployment-wide assignments as `{[task scope] assignment}`.

  Drops three kinds of row, each for a reason that is not tidiness:

  - a task this application no longer calls. The row outlives the code, and a
    removed task must not keep routing from something the screen cannot show.
  - a half-filled pair, which `assignment` refuses on the way in; a row that
    predates that check must not resolve to half a destination.
  - a Bot-scoped `:bot` row. That belongs on the Bot's record and only there —
    carrying a second copy here is how the two disagree and neither screen says
    which one the model actually ran on."
  [assignments]
  (into {}
        (keep (fn [a]
                (let [t (task (:routing/task a))
                      s (scope (:routing/scope a))
                      p (some-> (:routing/provider-id a) str str/trim not-empty)
                      m (some-> (:routing/model a) str str/trim not-empty)]
                  (when (and t p m (default-scope? s))
                    [[t s] {:routing/task t
                            :routing/scope s
                            :routing/provider-id p
                            :routing/model m}]))))
        assignments))

;; ── resolution ───────────────────────────────────────────────────────

(defn resolve-main
  "Where the `:bot` task's provider and model come from for one Bot.

  The Bot's OWN assignment is the pair already on its record — `:bot/provider-id`
  and `:bot/model`, which this application has stored since Bots existed. This
  namespace deliberately does not keep a second copy: two places a Bot's model
  can be written is the state where one of them is stale and neither screen
  says which, and it is the failure `route-scope`'s header names.

  So only the DEFAULT scope is new storage. What is returned is what to use
  when the Bot's record is silent — per field, because the two fields have
  always been independently settable here and a Bot carrying a provider but no
  model must keep the provider it was given.

  `:scope` says which of the three answered, for the screen: `:bot` when the
  record carried both halves, `:default` when a deployment assignment exists,
  `:provider` when neither does — which is every deployment that has never
  opened the screen, and is not a failure."
  [idx b]
  (let [own-provider (some-> (:bot/provider-id b) str str/trim not-empty)
        own-model (some-> (:bot/model b) str str/trim not-empty)
        fallback (get idx [main-task default-scope])
        from (route-scope {:bot-assigned? (boolean (and own-provider own-model))
                           :default-assigned? (boolean fallback)})]
    {:scope from
     :provider-id (or own-provider (:routing/provider-id fallback))
     :model (or own-model (:routing/model fallback))}))

(defn resolve-auxiliary
  "The route for one auxiliary task, given the deployment configuration.

  `:override` carries the assigned pair. `:main` carries none — the caller runs
  whatever `:bot` resolved to. `:refused` carries the provider that was named
  and could not be admitted, and callers raise on it rather than choosing
  something else; see the core."
  [configuration idx task-key]
  (let [t (task task-key)
        override (get idx [t default-scope])
        admitted (when override
                   (policy/select-provider configuration
                                           (:routing/provider-id override)))
        route (auxiliary-route {:has-override? (boolean override)
                                :override-admitted? (boolean admitted)})]
    (case route
      :override {:route :override
                 :provider admitted
                 :provider-id (:routing/provider-id override)
                 :model (:routing/model override)}
      :refused {:route :refused
                :provider-id (:routing/provider-id override)
                :model (:routing/model override)}
      {:route :main})))

(defn index-in
  "The assignments held by one store snapshot."
  [state]
  (index (vals (get-in state store-path))))

(defn auxiliary-choice!
  "Resolve one auxiliary task to `{:provider :model}`, or raise.

  `main` is what the `:bot` task resolved to for whatever Bot is in hand, and
  is returned unchanged when the task has no override. A refusal names the
  provider so the message can say which destination stopped being admissible,
  rather than reporting the task as broken."
  [configuration idx task-key main]
  (let [{:keys [route provider provider-id model]}
        (resolve-auxiliary configuration idx task-key)]
    (case route
      :override {:provider provider :model model}
      :main main
      (throw (ex-info
              (str "補助タスク " (name (task task-key))
                   " に割り当てた model provider は許可されていません。")
              {:type :routing/auxiliary-denied
               :task (task task-key)
               :provider provider-id
               :model model})))))
