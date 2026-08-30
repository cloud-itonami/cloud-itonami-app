(ns cloud.itonami.app.bot
  "A Bot: a named, durable worker you talk to, and the contract around it.

  Everything a Bot does was already in this application before it existed.
  `agent-control` runs a bounded loop that holds for approval; `connectors`
  says which tools a deployment offers and what each one costs in scopes;
  `work-governance` says what an artificial performer is; `work-approval`
  says a Passkey has to be present for anyone to approve anything; `chronicle`
  says memory is opt-in and device-local. What was missing was something for a
  person to point at. A run is not a colleague. You cannot give a run a
  standing brief, ask it what it did yesterday, or notice that it is stuck.

  So a Bot is not a new capability. It is an IDENTITY placed over capabilities
  that already exist, and the whole difficulty is making sure that is all it
  is.

  ## The name is not the authority

  A Bot has a name, a colour, a glyph, and a brief written in the second
  person. Naming one `administrator` and giving it a stern brief must buy it
  exactly nothing, and 'we would never do that' is not a mechanism. Two things
  make it structural rather than aspirational:

  `->performer` derives a `work-governance` performer of kind `:system` with
  an `:agent` actor, and hands it to `governance/performer` to validate. That
  function refuses to let a `:system` acquire `:dodaf/person`, so a Bot cannot
  become a person by being described as one — and the refusal lives with the
  organizational model rather than being restated here.

  `bot-core.kotoba` decides admission from four booleans, and the Bot's name,
  colour, glyph and brief are not among them. There is no persona field to
  consult, in the file where consulting one would matter.

  ## Approval and omakase

  `:bot/omakase?` is a standing owner delegation, and since ADR-0060 it is
  also what lets an agent record a decision: `may-approve?` takes a fourth
  fact, `delegated?`, and it is the only one its agent branch reads. A Bot
  without that delegation is refused exactly as before. The delegation does
  not widen tools, workspace, accounts, network or credentials — admission is
  computed from the grant either way, so a Bot deciding its own card can still
  only decide a card for something it was already admitted to call.

  ## Where the effects are

  Nowhere in this namespace. It validates, derives and folds; it reads no
  store, calls no model, opens no browser. `bots.clj` is the host that does
  all of that. The split is the same one `work-governance` and `work-runtime`
  already have, for the same reason: a Bot must not become execution authority
  merely by being on screen."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.work-governance :as governance]))

(def schema "cloud.itonami.app.bot.v1")

;; ── the face ────────────────────────────────────────────────────────────
;;
;; Ten colours and eight glyphs, chosen once here so the picker, the avatar
;; renderer and the validator cannot disagree about what exists. They are
;; decoration in the strict sense: nothing downstream branches on them.

(def avatar-colors
  [:clay :red :orange :amber :green :teal :blue :violet :pink :slate])

(def avatar-glyphs
  [:circle :bean :block :wide :wedge :cloud :wave :drop])

(def default-avatar {:avatar/color :blue :avatar/glyph :circle})

(def max-name 60)
(def max-brief 2000)
(def max-provider-id 100)
(def max-model 200)
(def max-context-project-id 80)
(def max-context-refs 12)
(def max-section 60)

(defn- context-refs [value legacy-project-id]
  (let [refs (if (contains? value :bot/context-refs)
               (:bot/context-refs value)
               (when legacy-project-id
                 [{:kind "project" :target legacy-project-id}]))]
    (when (> (count (or refs [])) max-context-refs)
      (throw (ex-info "too many context references"
                      {:type :bot/invalid :field :bot/context-refs})))
    (mapv (fn [ref]
            (let [kind (some-> (:kind ref) name)
                  target (some-> (:target ref) str str/trim)]
              (when-not (and (contains? #{"project" "folder" "document" "dataset"}
                                        kind)
                             (seq target)
                             (<= (count target) 160))
                (throw (ex-info "invalid context reference"
                                {:type :bot/invalid :field :bot/context-refs
                                 :value ref})))
              {:kind kind :target target}))
          (or refs []))))
(def max-workspace 4096)
(def max-responsibilities 12)
(def max-responsibility 1000)
(def max-capability-policy 40)

(def capability-decisions
  #{:autonomous :voice-required :approval-required :blocked})

(declare bounded!)

(defn- responsibilities [values]
  (mapv #(-> % str str/trim (bounded! :bot/responsibility max-responsibility))
        (take max-responsibilities (remove #(str/blank? (str %)) values))))

(defn- capability-policy [values]
  (mapv (fn [entry]
          (let [raw-capability (:capability entry)
                capability (some-> (if (keyword? raw-capability)
                                     (name raw-capability)
                                     (str raw-capability))
                                   not-empty)
                decision (keyword (name (or (:decision entry) :blocked)))]
            (when-not capability
              (throw (ex-info "workforce capability requires a name"
                              {:type :bot/invalid :field :bot/capability-policy})))
            (when-not (contains? capability-decisions decision)
              (throw (ex-info "unknown workforce capability decision"
                              {:type :bot/invalid :field :bot/capability-policy
                               :decision decision})))
            {:capability capability :decision decision
             :note (some-> (:note entry) str str/trim not-empty)}))
        (take max-capability-policy values)))

;; ── the record ──────────────────────────────────────────────────────────

(defn- required! [value field]
  (when (or (nil? value) (and (string? value) (str/blank? value)))
    (throw (ex-info (str field " is required")
                    {:type :bot/invalid :field field})))
  value)

(defn- bounded! [value field limit]
  (when (and value (> (count value) limit))
    (throw (ex-info (str field " is longer than " limit " characters")
                    {:type :bot/invalid :field field :limit limit})))
  value)

(defn- optional-name [value field limit]
  (when-let [value (some-> value str str/trim not-empty)]
    (bounded! value field limit)))

(defn avatar
  "Normalize an avatar, refusing a colour or glyph this build does not have.

  Refusing rather than substituting: a Bot that silently came back a different
  colour from the one someone picked reads as the wrong Bot in a sidebar where
  colour is how you find it.

  Both spellings are read, and that is the fix for the bug this docstring
  described and the code then committed. The wire sends `{color, glyph}` —
  JSON has no namespaces — while this read only `:avatar/color`. So every Bot
  created from the picker got nil, fell through to the default, and came back
  blue: substituted, silently, in the function whose whole point is not to.
  The refusal below could not fire either, because an unknown colour was never
  reached. Nothing in the Clojure suite saw it; a browser did, on the first
  run.

  Reading both here rather than translating in `bots.clj` keeps the property
  where the docstring claims it: any caller, over any transport, either gets
  the avatar it named or an exception."
  [value]
  (let [color (or (:avatar/color value) (:color value)
                  (:avatar/color default-avatar))
        glyph (or (:avatar/glyph value) (:glyph value)
                  (:avatar/glyph default-avatar))
        color (if (keyword? color) color (keyword (str color)))
        glyph (if (keyword? glyph) glyph (keyword (str glyph)))]
    (when-not (some #{color} avatar-colors)
      (throw (ex-info "unknown avatar colour"
                      {:type :bot/invalid :field :avatar/color :value color})))
    (when-not (some #{glyph} avatar-glyphs)
      (throw (ex-info "unknown avatar glyph"
                      {:type :bot/invalid :field :avatar/glyph :value glyph})))
    {:avatar/color color :avatar/glyph glyph}))

(defn bot
  "Validate and normalize one Bot.

  `:bot/status` is deliberately NOT a field. Status is computed by
  `status` from what is outstanding right now, so there is no stored copy to
  go stale — a Bot recorded as `:working` while its run is held waiting for a
  person is the failure the Bots screen exists to prevent, and the cheapest
  way to not have it is to have nowhere to write it."
  [value]
  (let [id (required! (:bot/id value) :bot/id)
        organization (required! (:bot/organization value) :bot/organization)
        owner (required! (:bot/owner value) :bot/owner)
        name (-> (:bot/name value)
                 (required! :bot/name)
                 (bounded! :bot/name max-name))
        brief (bounded! (:bot/brief value) :bot/brief max-brief)
        provider-id (optional-name (:bot/provider-id value)
                                   :bot/provider-id max-provider-id)
        model (optional-name (:bot/model value) :bot/model max-model)
        context-project-id (optional-name (:bot/context-project-id value)
                                          :bot/context-project-id
                                          max-context-project-id)
        context-refs (context-refs value context-project-id)
        email (optional-name (:bot/email value) :bot/email 320)
        workspace (optional-name (:bot/workspace value)
                                 :bot/workspace max-workspace)
        tools (into (sorted-set) (map str) (:bot/tools value))]
    (when (contains? value :bot/status)
      (throw (ex-info "a Bot does not carry a status; it is computed"
                      {:type :bot/invalid :field :bot/status})))
    {:bot/schema schema
     :bot/id id
     :bot/organization organization
     :bot/owner owner
     :bot/name (str/trim name)
     :bot/avatar (avatar (:bot/avatar value))
     :bot/brief (some-> brief str/trim)
     ;; The model provider is capability routing, not part of the Bot's
     ;; persona. Nil preserves pre-ADR-0050 Bots: they follow the deployment's
     ;; routing default until somebody explicitly pins a provider and model.
     :bot/provider-id provider-id
     :bot/model model
     ;; Read-only conversation reference. Tool and workspace admission never
     ;; consults this field; ADR-0074 keeps context separate from authority.
     :bot/context-project-id (some (fn [ref]
                                    (when (= "project" (:kind ref))
                                      (:target ref)))
                                  context-refs)
     :bot/context-refs context-refs
     ;; Stable mailbox identity. It is minted from the immutable Bot id by the
     ;; host and is not editable with the Bot's display name.
     :bot/email email
     :bot/tools tools
     ;; WHICH accounts, not just which services. Empty means "the person's",
     ;; which is what somebody with one account at each provider means and
     ;; which stays correct when they add a second — the count then makes the
     ;; Bot ask rather than quietly pick one.
     :bot/accounts (into (sorted-set) (map str) (:bot/accounts value))
     ;; Three permissions, separate on purpose. `writes?` is about effects;
     ;; `browser?` is a Bot-isolated browser profile; `computer?` is bounded,
     ;; focus-free access to named macOS applications. The machine Settings
     ;; gate browser and computer again, independently.
     :bot/writes? (boolean (:bot/writes? value))
     :bot/browser? (boolean (:bot/browser? value))
     :bot/computer? (boolean (:bot/computer? value))
     ;; ADR-0061/0062. Off by default like every other capability
     ;; here: a Bot that was never opted in cannot be reached by a
     ;; peer and cannot reach one.
     :bot/peers? (boolean (:bot/peers? value))
     :bot/coding? (boolean (:bot/coding? value))
     :bot/virtual-shell? (boolean (:bot/virtual-shell? value))
     ;; Whether a new direct request should keep working until completion or a
     ;; concrete blocker is recorded. This belongs to the Bot, not to the chat
     ;; composer: changing threads must not silently change its work contract.
     ;; Legacy coding Bots retain the old composer default on first load.
     :bot/goal? (if (contains? value :bot/goal?)
                  (boolean (:bot/goal? value))
                  (boolean (or (:bot/coding? value)
                               (:bot/virtual-shell? value))))
     ;; Sidebar placement is presentation state, not execution authority.
     ;; Priority outranks pinning in the client; keeping both flags means a Bot
     ;; returns to the pinned section when priority is later cleared.
     :bot/priority? (boolean (:bot/priority? value))
     :bot/pinned? (boolean (:bot/pinned? value))
     :bot/section (optional-name (:bot/section value) :bot/section max-section)
     :bot/unread? (boolean (:bot/unread? value))
     :bot/hidden? (boolean (:bot/hidden? value))
     ;; Standing delegation, intentionally independent of writes/tool reach.
     ;; It changes WHEN an already-admitted write runs, never WHAT is admitted.
     :bot/omakase? (boolean (:bot/omakase? value))
     :bot/workspace workspace
     ;; Workforce metadata explains a job; it is deliberately not consulted by
     ;; tool admission. `:bot/tools`, workspace/coding and the existing effect
     ;; governor remain the complete execution authority.
     :bot/workforce-key (optional-name (:bot/workforce-key value)
                                       :bot/workforce-key 200)
     :bot/business (:bot/business value)
     :bot/role (:bot/role value)
     :bot/responsibilities (responsibilities (:bot/responsibilities value))
     :bot/capability-policy (capability-policy (:bot/capability-policy value))
     :bot/enabled? (if (contains? value :bot/enabled?)
                     (boolean (:bot/enabled? value))
                     true)
     :bot/created-at (:bot/created-at value)
     :bot/updated-at (:bot/updated-at value)}))

(defn ->performer
  "The `work-governance` performer this Bot is, validated by that namespace.

  Derived rather than stored: a Bot and its performer cannot drift into
  disagreeing about what kind of thing it is, and the rule that an artificial
  worker is a system rather than a person stays where the organizational model
  keeps it."
  [b]
  (governance/performer
   {:performer/id (:bot/id b)
    :performer/organization (:bot/organization b)
    :performer/kind :system
    :performer/persona {:persona/name (:bot/name b)
                        :persona/avatar (:bot/avatar b)}
    :performer/actor {:actor/kind :agent :actor/id (:bot/id b)}}))

(defn ->actor
  "How a Bot identifies itself to `agent-control`, `work-governance` and the
  audit trail. One shape, so a run started from the Bots screen and a run
  started from a WorkItem are the same actor."
  [b]
  {:actor/kind :agent :actor/id (:bot/id b) :actor/label (:bot/name b)})

;; ── admission ───────────────────────────────────────────────────────────
;;
;; The judgements are in `bot_core.kotoba` and RUN from there. What is here is
;; the collection work they are asked over.

(def ^:private admission-record
  "The record `bot_core.kotoba` declares, in DECLARED field order."
  [:record :bot/admission
   [[:deployment-enabled :bool] [:granted :bool]
    [:connected :bool] [:writes :bool]]])

(def ^:private decision-record
  [:record :bot/decision
   [[:human :bool] [:identified :bool] [:authorized :bool]
    [:delegated :bool]]])

(def ^:private accounts-record
  [:record :bot/accounts
   [[:connected :i64] [:bound :i64] [:declared :bool] [:selected :bool]]])

(def account-dispositions
  "The core's `:i64` disposition codes, mapped once."
  {0 :connect 1 :use 2 :ask})

(def ^:private presence-record
  [:record :bot/presence
   [[:enabled :bool] [:held-run :bool]
    [:unmet-connection :bool] [:active-run :bool]]])

(def ^:private request-record
  [:record :bot/request
   [[:asked-at :i64] [:current :i64] [:answered :bool]]])

(def request-standings
  "The core's `:i64` standing codes, mapped once."
  {0 :open 1 :answered 2 :superseded})

(def status-codes
  "The core's `:i64` status codes, mapped once. The numbers are the core's; the
  keywords are this application's."
  {0 :disabled 1 :idle 2 :working 3 :waiting-connection 4 :waiting-approval})

(defn- ->id
  "Connector ids are symbols (`com.google.gmail`) and survive a JSON round trip
  as strings. Compared as strings so a set built on one side of that boundary
  still matches a value from the other."
  [value]
  (when value (str value)))

(defn tool-admitted?
  "May `b` use `tool` right now?

  `tool` is a row from `connectors/catalog-rows` — `:name`, `:effect`,
  `:enabled?`, plus the `:connector` id folded in by `admitted-tools` — and
  `connected-connectors` is the set of connector ids that are actually
  authorized. The host computes that set, because whether a connector is
  connected is a question about a credential in a keychain, and this namespace
  does not read keychains. Every fact crosses as a boolean; the decision is
  the core's."
  [b tool connected-connectors]
  (oracle/call
   :bot 'tool-admitted?
   [(oracle/record admission-record
                   [(boolean (:enabled? tool))
                    (contains? (:bot/tools b) (:name tool))
                    (contains? (into #{} (map ->id) connected-connectors)
                               (->id (:connector tool)))
                    (boolean (:bot/writes? b))])
    (name (or (:effect tool) :write))]))

(defn may-approve?
  "May this actor record an approval decision on a Bot's held write?

  `identified?` is the session having established who it is, not a WebAuthn
  user-verifying assertion. That stronger gate is `work-approval`'s and stays
  there: a Bot write that has to carry assertion-level authority is a governed
  WorkItem, not a card in a chat window. Naming the weaker fact honestly is
  what keeps somebody from later reading this as the stronger one."
  [{:keys [actor-kind human? identified? authorized? delegated?]}]
  (oracle/call
   :bot 'may-approve?
   [(oracle/record decision-record
                   [(boolean human?) (boolean identified?)
                    (boolean authorized?) (boolean delegated?)])
    (name (or actor-kind :unknown))]))

(defn status
  "What this Bot is, right now, from what is outstanding.

  `presence` is `{:held-run? :unmet-connection? :active-run?}` — three
  questions the host answers by looking at runs and connections."
  [b presence]
  (get status-codes
       (oracle/i64-value
        (oracle/call
         :bot 'status
         [(oracle/record presence-record
                         [(boolean (:bot/enabled? b))
                          (boolean (:held-run? presence))
                          (boolean (:unmet-connection? presence))
                          (boolean (:active-run? presence))])]))
       :idle))

(defn request-standing
  "Where one held request stands against the instruction in force now.

  `:open` — still the person's to answer. `:answered` — a decision is recorded.
  `:superseded` — it was raised under an instruction the person has since
  replaced.

  A DIRECTION is one instruction from the person and everything the Bot does
  carrying it out. Approvals are scoped to it, which is the rule this
  application did not have: a held write survived the person saying something
  else, so the card stayed on screen with an enabled button, `decide!` refused
  it as 承認待ちの操作がありません, and the Bot reported `waiting-approval` for
  the rest of the conversation. Consent must not outlive the instruction it was
  asked under."
  [{:keys [asked-at current answered?]}]
  (get request-standings
       (oracle/i64-value
        (oracle/call
         :bot 'request-standing
         [(oracle/record request-record
                         [(oracle/i64 (or asked-at 0))
                          (oracle/i64 (or current 0))
                          (boolean answered?)])]))
       :open))

(defn outstanding?
  "Whether a held request is still something the person has to deal with."
  [request]
  (oracle/call
   :bot 'outstanding?
   [(oracle/record request-record
                   [(oracle/i64 (or (:asked-at request) 0))
                    (oracle/i64 (or (:current request) 0))
                    (boolean (:answered? request))])]))

;; ── when a turn has stopped being a turn ─────────────────────────────

(def ^:private answer-record
  [:record :bot/answer [[:has-content :bool] [:tool-calls :i64]
                        [:empty-turns :i64] [:nudge-limit :i64]]])

(def ^:private repetition-record
  [:record :bot/repetition [[:identical-consecutive :i64] [:limit :i64]]])

(defn- answer-args [{:keys [content tool-calls empty-turns nudge-limit]}]
  (oracle/record answer-record
                 [(boolean (and content (not= "" (str/trim (str content)))))
                  (oracle/i64 (long (or tool-calls 0)))
                  (oracle/i64 (long (or empty-turns 0)))
                  (oracle/i64 (long (or nudge-limit 1)))]))

(defn answer-empty?
  "Did this model turn come back with neither prose nor an action?

  Blank prose is empty prose. A turn whose whole content is whitespace has said
  nothing, and recording it as something said is how an empty message reaches
  the conversation and the picker preview."
  [answer]
  (oracle/call :bot 'answer-empty? [(answer-args answer)]))

(defn may-nudge?
  "Is this empty turn worth asking once more, rather than refusing?"
  [answer]
  (oracle/call :bot 'may-nudge? [(answer-args answer)]))

(defn repetition-exhausted?
  "Has the model proposed the same action with the same arguments too often?

  `identical-consecutive` counts the call about to run, so the first proposal
  of anything is 1 and a limit of 1 would refuse everything. The host keeps the
  count; this only says when it is too many."
  [{:keys [identical-consecutive limit]}]
  (oracle/call :bot 'repetition-exhausted?
               [(oracle/record repetition-record
                               [(oracle/i64 (long (or identical-consecutive 1)))
                                (oracle/i64 (long (or limit 4)))])]))

(defn usable-accounts
  "The accounts a Bot may actually use at one provider: the ones it was bound
  to, or — when it was bound to none — the person's."
  [b provider-accounts]
  (let [bound (:bot/accounts b)
        mine (vec provider-accounts)
        chosen (if (seq bound)
                 (filterv #(contains? bound (:id %)) mine)
                 mine)]
    chosen))

(defn account-disposition
  "Connect, use, or ask — for one provider, right now.

  `provider-accounts` is this person's live connections at that provider, and
  `selected` is whether a choice is already in effect for this turn. Returning
  `:ask` rather than picking is the whole point: `identity/connection-for`
  already refuses to guess between two of somebody's Google accounts, and a Bot
  that guessed would be making the refusal pointless one layer up."
  [b provider-accounts selected]
  (get account-dispositions
       (oracle/i64-value
        (oracle/call
         :bot 'account-disposition
         [(oracle/record accounts-record
                         [(oracle/i64 (count provider-accounts))
                          (oracle/i64 (count (usable-accounts b provider-accounts)))
                          (boolean (seq (:bot/accounts b)))
                          (boolean selected)])]))
       :connect))

(defn admitted-tools
  "The Bot's grant, intersected with what the deployment enables.

  This is the folding the core cannot do — a set operation — and it is the
  only place the grant is narrowed, so a caller cannot get a wider one by
  reading `:bot/tools` directly and skipping this."
  [b catalog-rows connected-connectors]
  (into (sorted-set)
        (comp (mapcat (fn [row]
                        (map #(assoc % :connector (:id row)) (:tools row))))
              (filter #(tool-admitted? b % connected-connectors))
              (map :name))
        catalog-rows))

(defn reachable-tools
  "The tools this Bot would be allowed to call ONCE its connectors are
  authorized.

  The same question `admitted-tools` asks, put to the same core, with
  `connected` held true: still narrowed by what the deployment enables, by the
  grant, and by the write permission — and by nothing about whether anybody has
  authorized anything yet.

  The two sets differ by exactly the tools an authorization would unlock, and
  that difference is what a connection card is FOR. Offering THIS set to the
  model is what lets a Bot discover, by reaching for a tool, that this
  particular turn needs an authorization. The host used to answer that question
  before the turn instead, by demanding every connector the grant touched — so
  a Bot with an unauthorized Gmail refused to say hello, and the demand arrived
  on turns that needed nothing. `admitted-tools` remains the only set that may
  actually RUN; nothing here widens it.

  Distinct from `enabled-grant`, which drops the write permission as well and
  answers a third question — whether the grant names something nobody turned
  on. Three narrowings, three callers, and collapsing any two of them has
  already produced one warning that fired on every ordinary Bot.

  A row with no `:provider` is left out: there is no authorization that would
  ever connect it, so it is not reachable in the sense this function means, and
  offering it would put a tool in front of the model that nothing can grant. A
  row that has a provider but no client configured on this machine DOES stay —
  that Bot really is blocked on an authorization, and the card says so with its
  button disabled rather than by being absent."
  [b catalog-rows]
  (admitted-tools b catalog-rows (keep :id (filter :provider catalog-rows))))

(defn enabled-grant
  "The Bot's grant, intersected with what the deployment enables — and with
  nothing else.

  Distinct from `admitted-tools`, which also requires the connector to be
  connected. Both narrowings are real, they answer different questions, and
  conflating them is a bug this had: see `grant-widens?`."
  [b catalog-rows]
  (into (sorted-set)
        (comp (mapcat :tools)
              (filter :enabled?)
              (map :name)
              (filter (:bot/tools b)))
        catalog-rows))

(defn grant-widens?
  "Does this Bot name a tool the deployment does not enable?

  Not an error here, and deliberately not repaired here either: the two
  readings — an operator turned a tool off that a Bot had, or a tool name was
  written into a Bot that was never offered — need different answers, and both
  need a human to see them. `bots.clj` surfaces it; nothing silently prunes.

  It compares against `enabled-grant`, NOT `admitted-tools`. Comparing against
  the admitted set was the bug: admitted is also narrowed by whether the
  connector is CONNECTED, so a brand-new Bot whose owner had not yet clicked
  Authorize showed 'this Bot names tools this deployment has not enabled' —
  every time, on a Bot with nothing wrong with it. A warning that fires on the
  ordinary case is not a warning, and the tests did not catch it because they
  only ever asked with everything connected."
  [b catalog-rows]
  (oracle/call :bot 'grant-widens?
               [(oracle/i64 (count (:bot/tools b)))
                (oracle/i64 (count (enabled-grant b catalog-rows)))]))

;; ── what a Bot says ─────────────────────────────────────────────────────
;;
;; A conversation is messages, and a message may carry cards. A card is the
;; part of a Bot's turn that is not prose because prose cannot be acted on:
;; a connector to authorize, a choice to make, an approval to give.

(def card-kinds #{:connection :choice :approval :artifact})

(def connection-states
  "`:offered` — the Bot needs it and nobody has started.
   `:waiting` — an authorization was opened and has not come back.
   `:connected` — it answered.
   `:refused` — the person said no; the Bot must plan without it."
  #{:offered :waiting :connected :refused})

(defn connection-card
  "A connector this Bot needs, and the accounts it has there.

  It carries the tool count and the scope summary because 'connect Gmail' and
  'connect Gmail so something can read, draft and label your mail' are
  different requests, and only the second one is consent.

  `:card/accounts` is what makes this a card about ACCOUNTS rather than about a
  service. One person may hold two Google accounts; they are two grants in two
  Keychain slots, and a card that said only 'Google — connected' would be
  answering a question this application decided some time ago is not the
  question. Each entry names the connection a token resolves by, so the card
  can offer to add another without inventing a second way to obtain a grant.

  `:card/authable?` is whether this deployment can obtain that grant AT ALL —
  whether an OAuth client for the provider is configured on this machine. It is
  a fact about the installation, not a state of the request, which is why it is
  a field rather than a fifth `connection-states` member: `:offered` with no
  client is still offered, and would still be offered tomorrow if somebody
  configured one. Defaults to true so a caller that does not know stays
  unchanged; a caller that does know can stop the card from offering an
  authorization that has nowhere to go."
  [{:keys [id connector title summary tool-count scopes state accounts
           authable?]}]
  (let [accounts (vec accounts)
        state (or state (if (seq accounts) :connected :offered))]
    (when-not (connection-states state)
      (throw (ex-info "unknown connection state"
                      {:type :bot/invalid-card :state state})))
    {:card/id (required! id :card/id)
     :card/kind :connection
     :card/connector (required! connector :card/connector)
     :card/title (required! title :card/title)
     :card/summary summary
     :card/tool-count (or tool-count 0)
     :card/scopes (vec (sort scopes))
     :card/accounts accounts
     :card/authable? (if (some? authable?) (boolean authable?) true)
     :card/state state}))

(defn choice-card
  "A question with lettered options, as the Bot asked it.

  Letters rather than free text because the answer is recorded and replayed:
  `:card/answer` has to mean the same thing tomorrow as the option it points
  at, and a label can be edited while a key cannot."
  [{:keys [id prompt detail options answer subject]}]
  (when (< (count options) 2)
    (throw (ex-info "a choice needs at least two options"
                    {:type :bot/invalid-card :card id})))
  (let [options (vec (map-indexed
                      (fn [i o]
                        (cond-> {:option/key (or (:option/key o)
                                                 (str (char (+ (int \A) i))))
                                 :option/label (required! (:option/label o)
                                                          :option/label)}
                          ;; What the option MEANS, when it means something the
                          ;; label does not spell — an account's connection id,
                          ;; say. Kept beside the key rather than parsed back
                          ;; out of the label, which is display text and may be
                          ;; edited.
                          (:option/value o) (assoc :option/value
                                                   (:option/value o))))
                      options))
        keys* (map :option/key options)]
    (when-not (= (count keys*) (count (set keys*)))
      (throw (ex-info "option keys are not unique"
                      {:type :bot/invalid-card :card id})))
    (when (and answer (not (some #{answer} keys*)))
      (throw (ex-info "answer names no option"
                      {:type :bot/invalid-card :card id :answer answer})))
    {:card/id (required! id :card/id)
     :card/kind :choice
     :card/prompt (required! prompt :card/prompt)
     :card/detail detail
     ;; What answering this decides, for a host that has to act on it. Nil for
     ;; a question the Bot asked in the course of a conversation, and
     ;; `{:subject/kind :account :subject/provider :google}` for the one the
     ;; runtime asks when a person holds two accounts at one provider.
     :card/subject subject
     :card/options options
     :card/answer answer}))

(def max-group-members
  "How many Bots one room may hold.

  A ceiling rather than a preference: a person's message costs one model call
  per member per round, so an unbounded room is an unbounded bill arriving from
  a chat window. Eight is the largest number where three rounds still reads as
  a conversation rather than a transcript."
  8)

(defn group
  "A room of this owner's Bots.

  Members are ids in the order they were added, deduplicated. Order is kept
  because a room where the same question is answered in a different sequence
  every time is a different room; dedup is here rather than at the caller
  because a member listed twice would take two turns per round and nothing
  downstream would notice."
  [{:keys [id organization owner name members created-at]}]
  (let [members (into [] (distinct) (map str (or members [])))]
    (when (empty? members)
      (throw (ex-info "グループには最低1体のBotが必要です。"
                      {:type :group/empty})))
    (when (> (count members) max-group-members)
      (throw (ex-info (str "グループのメンバーは" max-group-members "体までです。")
                      {:type :group/too-many :count (count members)})))
    {:group/id (required! id :group/id)
     :group/organization (required! organization :group/organization)
     :group/owner (required! owner :group/owner)
     :group/name (let [n (str/trim (str name))]
                   (if (str/blank? n) "グループ" (subs n 0 (min 60 (count n)))))
     :group/members members
     :group/created-at created-at}))

(defn approval-card
  "A held AgentRun, surfaced where the person is already looking.

  `:card/decision` is nil until a human session writes one. Nothing on this
  card is a decision; it is the request, and `may-approve?` guards the write.

  `:card/direction` is which instruction the Bot was carrying out when it
  stopped to ask. It is what lets `request-standing` retire the card when the
  person says something else instead of answering — the card is left exactly as
  written and the standing is recomputed, for the same reason `:card/state` is
  recomputed on a connection card. Defaults to 0, which every later direction
  supersedes: a card written before this field existed cannot be shown to still
  be waiting for something, because nothing recorded what it was waiting for."
  [{:keys [id run title summary impact action decision direction]}]
  {:card/id (required! id :card/id)
   :card/kind :approval
   :card/run (required! run :card/run)
   :card/title (or title "承認が必要です")
   :card/summary summary
   :card/impact impact
   :card/action action
   :card/direction (or direction 0)
   :card/decision decision})

(def artifact-kinds
  "What a Bot can have LEFT BEHIND that outlives the sentence describing it.

  `:file` — a path in the admitted workspace, and how many bytes were written.
  `:commit` — a local revision, its message, and the paths it named.

  Only these two, because only two tools write: `workspace_write_file` and
  `git_commit`. There is deliberately no `:pull-request` -- `git_commit` never
  pushes, so a Bot on this surface cannot open one, and a kind nothing can
  produce is a promise the screen would keep making and never keep."
  #{:file :commit})

(defn artifact-card
  "Something the Bot made, recorded as a value rather than described in prose.

  A card is the part of a turn that is not prose because prose cannot be acted
  on -- and until now that meant only things asking the PERSON to act. This is
  the other half: what the Bot already did. `workspace_write_file` answered
  `\"wrote src/foo.clj (1234 bytes)\"` and `git_commit` answered
  `\"committed <sha>\"`, so the path, the byte count and the revision existed
  at the moment of the call and were spent on a sentence. Reading them back out
  of that sentence would be parsing our own print format, which is the same
  mistake `run-tool!` names about `pr-str`.

  Every field is what the tool already knew. Nothing here is derived from the
  model's summary, so a card cannot claim work that did not happen: a Bot that
  says it committed and did not has no receipt, and therefore no card."
  [{:keys [id kind path bytes revision message paths]}]
  (when-not (contains? artifact-kinds kind)
    (throw (ex-info "unknown artifact kind"
                    {:type :bot/invalid-card :card id :artifact-kind kind})))
  (cond-> {:card/id (required! id :card/id)
           :card/kind :artifact
           :card/artifact-kind kind}
    path (assoc :card/path path)
    (number? bytes) (assoc :card/bytes (long bytes))
    revision (assoc :card/revision revision)
    message (assoc :card/message message)
    (seq paths) (assoc :card/paths (vec paths))))

(defn message
  "One turn. `:message/role` is `:person` or `:bot` — not `:user`/`:assistant`,
  which are the model transcript's words for the same two speakers and would
  invite this durable record to be passed to a provider as-is."
  [{:keys [id bot role text cards at direction context-id source handoff-id
           from-bot from]}]
  (when-not (#{:person :bot} role)
    (throw (ex-info "a message is from a person or a bot"
                    {:type :bot/invalid-message :role role})))
  (cond-> {:message/id (required! id :message/id)
           :message/bot (required! bot :message/bot)
           :message/role role
           :message/text (or text "")
           :message/cards (vec cards)
           :message/at at}
    (some? direction) (assoc :message/direction (long direction))
    context-id (assoc :message/context-id (str context-id))
    source (assoc :message/source (keyword source))
    handoff-id (assoc :message/handoff-id (str handoff-id))
    from-bot (assoc :message/from-bot (str from-bot))
    ;; A peer's mailbox address (ADR-0061). `:message/from-bot` is the
    ;; handoff SOURCE; who sent this and whose work this continues are
    ;; two questions, so they stay two fields rather than one that means
    ;; whichever arrived last.
    from (assoc :message/from (str from))))

(defn answer-choice
  "Record an answer onto a choice card inside a conversation, returning the
  messages. Rejects an answer to a card that already has one — a choice that
  can be silently changed after the Bot acted on it is not a record of what
  was decided."
  [messages card-id answer]
  (mapv
   (fn [m]
     (update m :message/cards
             (fn [cards]
               (mapv (fn [c]
                       (if (and (= card-id (:card/id c))
                                (= :choice (:card/kind c)))
                         (if (:card/answer c)
                           (throw (ex-info "this choice was already answered"
                                           {:type :bot/choice-answered
                                            :card card-id}))
                           (choice-card (assoc c :id (:card/id c)
                                               :prompt (:card/prompt c)
                                               :detail (:card/detail c)
                                               :subject (:card/subject c)
                                               :options (:card/options c)
                                               :answer answer)))
                         c))
                     cards))))
   messages))

;; ── what to make first ──────────────────────────────────────────────────

(def suggestion-templates
  "Starting points, each declaring the connectors it needs.

  Offered rather than listed: `suggestions` shows only the ones whose
  connectors the person actually picked, so the first screen cannot propose a
  Bot that would be blocked on an authorization before its first turn."
  [{:template/id :inbox-triage
    :template/name "Inbox Triage"
    :template/summary "受信箱を読んで、返事が要るものだけを拾い上げる"
    :template/connectors #{"com.google.gmail"}
    :template/avatar {:avatar/color :amber :avatar/glyph :circle}
    :template/brief
    "毎朝わたしの受信箱を見て、返事が要るものと待てるものを分けて。
勝手に返信はしない — 下書きを出して、送るかどうかはわたしが決める。"}
   {:template/id :meeting-notes
    :template/name "Meeting Notes"
    :template/summary "予定を見て、終わった会議の記録を残す"
    :template/connectors #{"com.google.calendar"}
    :template/avatar {:avatar/color :teal :avatar/glyph :cloud}
    :template/brief
    "カレンダーの終わった会議について、決まったことと次にやることを書き出して。
出席していないものは推測しないで、空欄のままにして。"}
   {:template/id :deck-designer
    :template/name "Deck Designer"
    :template/summary "書きかけのメモを、体裁の整った資料にする"
    :template/connectors #{"com.google.drive"}
    :template/avatar {:avatar/color :blue :avatar/glyph :block}
    :template/brief
    "Drive にあるわたしのメモを、章立てのある資料の下書きにして。
事実を足さないこと — 元のメモに無いことは書かない。"}
   {:template/id :channel-digest
    :template/name "Channel Digest"
    :template/summary "チャンネルを要約して、自分宛のものを立てる"
    :template/connectors #{"com.slack"}
    :template/avatar {:avatar/color :violet :avatar/glyph :wave}
    :template/brief
    "見ていない間のチャンネルを要約して、わたしの名前が出たものを先に見せて。"}
   {:template/id :repo-watch
    :template/name "Repo Watch"
    :template/summary "レビュー待ちと落ちている検査を集める"
    :template/connectors #{"com.github"}
    :template/avatar {:avatar/color :slate :avatar/glyph :wedge}
    :template/brief
    "わたしのレビュー待ちと、落ちている検査を集めて。
直し方までは書かなくていい — どれが止まっているかだけ知りたい。"}])

(defn suggestions
  "The templates that `picked` can actually run today.

  A template whose connectors are all picked is offered; one that needs
  something absent is not shown at all rather than shown and broken."
  [picked]
  (let [picked (into #{} (map ->id) picked)]
    (into []
          (comp (filter #(set/subset? (:template/connectors %) picked))
                (map #(dissoc % :template/connectors)))
          suggestion-templates)))

(defn from-template
  "A Bot request built from a template, ready for `bot`. The name and avatar
  are starting points; the person may change both, and neither changes what
  the Bot may do."
  [template {:keys [id organization owner tools]}]
  {:bot/id id
   :bot/organization organization
   :bot/owner owner
   :bot/name (:template/name template)
   :bot/avatar (:template/avatar template)
   :bot/brief (:template/brief template)
   :bot/tools tools})
