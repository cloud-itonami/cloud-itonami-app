(ns cloud.itonami.app.bots
  "The host a Bot runs in: durable record, durable conversation, and the loop
  that reaches the services somebody connected.

  `bot.cljc` decides; this namespace does. It reads and writes `store`, resolves
  access tokens, sends HTTP, and calls a model. Every judgement it makes it asks
  for — nothing here re-decides what `bot`/`bot_core.kotoba` already answered.

  ## What was actually missing before this

  The connector registry has been in this application since ADR-0038: eight
  connectors, 37 tools, each declaring the scopes it needs. `connectors.clj`
  derives the consent catalogue from it and Settings shows it. But measured on
  2026-08-12, **nothing in `src/` ever called `connector.invoke/call`** — the
  registry described tools that no code path could run. `connector.ports`
  requires the host to supply `IHttp` and `ITokens`, and this application
  supplied neither.

  So the registry was a menu. `http-port` and `tokens-port` below are the
  kitchen, and they are the reason a Bot can do anything at all rather than
  only describe what it would do.

  ## The two-tier tool rule, and why it is not agent-control's

  `agent-control` already runs a bounded loop that executes read-only tools and
  holds everything else for a human. This loop follows the same discipline over
  a different surface, and stays a separate loop on purpose: agent-control's
  tools are this MACHINE — an isolated browser, keystrokes into the frontmost
  application — and its approval text, its capability set and its frontmost-app
  check are all about that. A Gmail send and a click on this laptop are not the
  same risk and should not share an approval prompt that has to describe both.

  A Bot may hold both. `:bot/browser?` opts it into agent-control's isolated
  browser for the sites with no API at all, which is the case connectors
  structurally cannot cover. The dispatch is this namespace: the tools join
  the Bot's turn, writes still hold, and the profile is `session-for` of the
  Bot's id so two Bots do not share cookies. Computer-use (frontmost app)
  stays off this path.

  ## What a Bot's 'own computer' is here

  ADR-0051 adds a local OCI virtual computer without weakening the earlier
  rule that effects must remain inside this process's review boundary. It has
  no network or credentials, mounts one admitted standalone Git root, and every
  shell command is held for a human. The host launches Docker with fixed argv;
  only /bin/bash inside the container interprets the command. Multiple Bots may
  have separate containers, while a per-workspace lock prevents concurrent
  mutation of one repository. Heavy or long-running governed work still goes
  to the externally supervised OrganismWorkers (`work-organism-dispatch`).

  The honest cost: a Bot does not run while this machine is asleep. That is a
  real difference from the product this is modelled on, and it is a
  consequence of the thesis rather than an oversight."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.bot :as bot]
            [cloud.itonami.app.connectors :as connectors]
            [cloud.itonami.app.handoff :as handoff]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mail-account :as mail-account]
            [cloud.itonami.app.mail-sync :as mail-sync]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.relay :as relay]
            [cloud.itonami.app.routine :as routine]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.virtual-shell :as virtual-shell]
            [cloud.itonami.app.workspace-tools :as workspace-tools]
            [connector.invoke :as invoke]
            [connector.model :as cm]
            [connector.ports :as cports]
            [connector.registry :as creg])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time Duration]
           [java.util UUID]))

(def schema "cloud.itonami.app.bots.v1")
(defonce ^:private active-turns (atom {}))

(def max-turns 8)
(def max-tool-calls 12)
(def max-message-chars 8000)
(def max-conversation 200)
(def max-tool-output-chars 6000)
(def max-trace 60)
(def max-routines 40)
(def max-turn-history 40)

(defn mailbox-address
  "The stable RFC mailbox for a Bot. The id is immutable, unlike its name."
  [configuration bot-id]
  (str (str/lower-case (str bot-id)) "@"
       (or (get-in configuration [:bots :mail-domain]) "mail.itonami.cloud")))

(defn- mail-destination
  "The one bound mailbox a Bot can receive through, or nil when ambiguous."
  [session b]
  (let [accounts (mail-account/accounts (identity/session-did session))
        bound (:bot/accounts b)
        usable (if (seq bound)
                 (filter #(contains? bound (:connection-id %)) accounts)
                 accounts)]
    (when (= 1 (count usable)) (first usable))))

;; ── ports ───────────────────────────────────────────────────────────────

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
             .build)))

(defn- ->query [url query]
  (if (seq query)
    (str url "?" (str/join "&" (for [[k v] query]
                                 (str (java.net.URLEncoder/encode (str k) "UTF-8")
                                      "="
                                      (java.net.URLEncoder/encode (str v) "UTF-8")))))
    url))

(defn- parse-body
  "`connector.ports` promises a PARSED body: the library reads no JSON, which is
  what keeps every connector's `normalize` a value comparison in its own tests.
  Parsing is therefore the host's job, and a body that is not JSON comes back as
  the string it was rather than as an exception — an HTML error page from a
  proxy is information, and throwing here would replace it with a stack trace."
  [^String body content-type]
  (if (and body (str/includes? (str content-type) "json") (seq (str/trim body)))
    (try (json/read-str body :key-fn keyword)
         (catch Exception _ body))
    body))

(defn http-port
  "`IHttp` over `java.net.http`. The one implementation, so a connector's
  request map means the same thing here as in its own tests."
  []
  (cports/http-fn
   (fn [{:connector.http/keys [method url query headers body]}]
     (let [uri (URI/create (->query url query))
           publisher (if body
                       (HttpRequest$BodyPublishers/ofString
                        (if (string? body) body (json/write-str body)))
                       (HttpRequest$BodyPublishers/noBody))
           builder (reduce (fn [b [k v]] (.header b (str k) (str v)))
                           (-> (HttpRequest/newBuilder uri)
                               (.timeout (Duration/ofSeconds 30))
                               (.method (str/upper-case (name (or method :get)))
                                        publisher))
                           (cond-> headers
                             (and body (not (get headers "content-type")))
                             (assoc "content-type" "application/json")))
           response (.send @http-client (.build builder)
                           (HttpResponse$BodyHandlers/ofString))]
       {:connector.http/status (.statusCode response)
        :connector.http/body (parse-body (.body response)
                                         (-> response .headers
                                             (.firstValue "content-type")
                                             (.orElse "")))}))))

(defn- connector->provider
  "connector id -> the OAuth client it is authorized under. Derived from the
  catalogue rather than written out: the grouping (Drive, Gmail and Calendar
  share one Google client) belongs to `connectors`, and a second copy of it
  here would be a second answer to 'is this connected'."
  ([] (connector->provider nil))
  ([configuration]
   (into {} (keep (fn [row] (when (:provider row)
                              [(str (:id row)) (:provider row)])))
         (connectors/catalog-rows configuration))))

(defn- tool->provider
  "tool name -> the OAuth client it is authorized under.

  One step finer than `connector->provider`, and needed for a different
  question: when a Bot's turn reaches for a tool, which authorization is the
  one to ask for. Browser tools are deliberately absent — the isolated browser
  is this machine, not a connected account — so a lookup that misses is how
  they stay outside the connection question entirely rather than by being
  named here as exceptions."
  [configuration]
  (into {} (for [row (connectors/catalog-rows configuration)
                 :when (:provider row)
                 tool (:tools row)]
             [(:name tool) (:provider row)])))

(defn tokens-port
  "`ITokens` over ONE named account per provider.

  `connector.ports/ITokens` maps a connector id to a token and has no room for
  an account, so the account is bound when the port is built rather than
  guessed when it is asked. `selection` is provider -> connection record, and
  the token comes from `connection-access-token!`, which is keyed by connection
  id — not `provider-access-token!`, whose own docstring says it 'stops being a
  question with an answer as soon as a person connects two Google accounts'.
  Using the coarse form here would have been that exact mistake, one layer
  further from the person who could notice it.

  A connector with no selected account returns nil, and `connector.invoke`
  turns that into a `:connector/not-connected` error value rather than an
  exception — so a Bot gets told, in its own transcript, that it is not
  connected."
  [configuration selection]
  (let [lookup (connector->provider configuration)]
    (cports/token-fn
     (fn [connector-id]
       (when-let [provider (get lookup (str connector-id))]
         (when-let [connection (get selection provider)]
           (try (identity/connection-access-token! connection)
                (catch Exception _ nil))))))))

;; ── durable state ───────────────────────────────────────────────────────

(defn- partition* [state]
  (or (:bots state)
      {:schema schema :bots {} :conversations {} :runs {}}))

(defn- snapshot [] (partition* (store/snapshot)))

(defn- mailbox-registration [bot-id]
  (get-in (snapshot) [:mailboxes bot-id] {:status :pending}))

(defn- transact! [f & args]
  (store/transact!
   (fn [state] (assoc state :bots (apply f (partition* state) args))))
  nil)

(defn- new-id [prefix] (str prefix "-" (UUID/randomUUID)))

;; ── direction ───────────────────────────────────────────────────────────
;;
;; A DIRECTION is one instruction from the person, and everything the Bot does
;; carrying it out. It is the unit an approval is scoped to: `bot/request-standing`
;; retires a held request the moment a later direction exists, because approving
;; it then would be consent for work the person has already moved on from.
;;
;; Counted from 1, so that 0 can mean "before this Bot was ever asked anything"
;; for a card written before this field existed. Such a card is superseded by
;; the first direction, which is the right answer for it: nobody can still be
;; waiting on a request raised by a build that did not record what it was for.

(defn- direction
  "The instruction in force for this Bot."
  [bot-id]
  (get-in (snapshot) [:directions bot-id] 0))

(defn- open-approval-cards
  "The approval cards this Bot has not had a decision recorded on. Says nothing
  about whether they are still ANSWERABLE — that is `bot/request-standing`'s,
  and the two were the same question until a held request could outlive its
  direction."
  [bot-id]
  (for [m (get-in (snapshot) [:conversations bot-id] [])
        c (:message/cards m)
        :when (and (= :approval (:card/kind c)) (nil? (:card/decision c)))]
    c))

;; ── connections ─────────────────────────────────────────────────────────

(defn accounts-by-provider
  "This person's live external accounts, grouped by the OAuth client they are
  held under. One entry per ACCOUNT, so two Google accounts are two."
  [did]
  (group-by :provider (identity/accounts-for did)))

(defn connected-connectors
  "The connector ids this person holds at least one account for.

  Person-bound, so one person's Bot cannot borrow another's Google connection
  by being on the same machine. 'At least one' is the right test here and only
  here: it answers whether a tool is reachable at all, and WHICH account
  reaches it is a separate question that `bot/account-disposition` answers
  rather than this one silently deciding."
  [configuration did]
  (let [providers (set (keys (accounts-by-provider did)))]
    (into #{} (comp (filter #(contains? providers (:provider %)))
                    (map #(str (:id %))))
          (connectors/catalog-rows configuration))))

(defn provider-authable?
  "Whether this deployment could obtain a grant for `provider` if somebody
  asked for one — i.e. an OAuth client is configured on this machine.

  Separate from `connected?`, and the distinction is the whole point:
  'nobody has authorized this yet' is a step away, and 'this installation
  has no client to authorize against' is a dead end. Settings has always
  drawn that line — it disables its connect button and says
  'OAuth クライアント設定が必要です' — and this function is what lets the
  Bots surfaces draw the same one from the same fact."
  [provider]
  (boolean (some-> provider identity/provider-config :configured?)))

(defn catalog
  "Every connector this build carries, with whether it is connected — the
  'What do you use every day?' grid.

  Derived from the registry, so it lists what this deployment can actually
  offer rather than a picture of an integrations page. A connector this build
  does not carry is absent, which is the honest answer.

  `:authable?` is the second reason a row can be unofferable, and it has to be
  reported separately from `:enabled-tool-count` because the two send a person
  to different places: no enabled tool is something an operator turns on in
  this build, no OAuth client is something they configure for this machine.
  Collapsing them into one disabled tile would repeat the mistake this grid's
  own comment warns about — offering an authorization that leads nowhere."
  [configuration did]
  (let [connected (connected-connectors configuration did)]
    (mapv (fn [row]
            (let [tools (:tools row)]
              {:id (str (:id row))
               :name (:name row)
               :summary (:summary row)
               :provider (some-> (:provider row) name)
               :connected? (contains? connected (str (:id row)))
               :configurable? (boolean (:configurable? row))
               :authable? (provider-authable? (:provider row))
               :tool-count (count tools)
               :enabled-tool-count (count (filter :enabled? tools))
               :tools (mapv #(select-keys % [:name :effect :enabled? :description])
                            tools)}))
          (connectors/catalog-rows configuration))))

(defn- default-tools
  "The tools a Bot starts with for the connectors somebody picked: every
  ENABLED tool those connectors offer, and nothing from a connector they did
  not pick. Computed rather than chosen, for the same reason
  `connectors/default-enabled-tools` is."
  [configuration picked]
  (let [picked (into #{} (map str) picked)]
    (into (sorted-set)
          (comp (filter #(contains? picked (str (:id %))))
                (mapcat :tools)
                (filter :enabled?)
                (map :name))
          (connectors/catalog-rows configuration))))

;; ── the record ──────────────────────────────────────────────────────────

(defn- store-bot! [b]
  (transact! assoc-in [:bots (:bot/id b)] b)
  b)

(defn- bot-by-id [bot-id]
  (get-in (snapshot) [:bots bot-id]))

(defn- owned!
  "The Bot, or a refusal. A Bot belongs to the person who created it inside one
  tenant, and both halves are checked: a session in another organization must
  not reach it even if it guessed the id."
  [session bot-id]
  (let [b (bot-by-id bot-id)]
    (when-not b
      (throw (ex-info "Bot が見つかりません。" {:type :bot/not-found :bot bot-id})))
    (when-not (and (= (:user-id session) (:bot/owner b))
                   (= (:organization-id session) (:bot/organization b)))
      (throw (ex-info "この Bot はこのセッションのものではありません。"
                      {:type :bot/forbidden :bot bot-id})))
    b))

(defn- provider-choice!
  "Resolve this Bot's inference route through the same deployment admission
  policy as every other model call. A stored id is a preference, never a way
  around review, TLS, credential, or the deployment egress switch."
  [configuration b]
  (let [requested (:bot/provider-id b)
        selected (policy/select-provider configuration requested)
        model (or (:bot/model b)
                  (:default-model selected)
                  (get-in configuration [:routing :default-model]))]
    (when-not selected
      (throw (ex-info "選択した model provider は許可されていません。"
                      {:type :provider/denied :provider requested})))
    ;; Small host-test configs intentionally omit routing. A running server
    ;; always supplies the loaded routing partition and therefore still fails
    ;; closed on a missing model.
    (when (and (contains? configuration :routing) (str/blank? (str model)))
      (throw (ex-info "この Bot の model が設定されていません。"
                      {:type :provider/model-required
                       :provider (:id selected)})))
    {:provider selected :model model}))

(defn- validate-provider-choice!
  [configuration provider-id model]
  (when (or provider-id model)
    (provider-choice! configuration
                      {:bot/provider-id (some-> provider-id str str/trim not-empty)
                       :bot/model (some-> model str str/trim not-empty)})))

(defn create!
  "Create a Bot. `:tools` may be given directly, or derived from `:connectors`
  when the caller is the onboarding screen and has only picked services."
  [configuration session {:keys [name avatar brief connectors tools accounts
                                 writes? browser? coding? virtual-shell? omakase? workspace
                                 provider-id model]}]
  (validate-provider-choice! configuration provider-id model)
  (let [workspace (cond
                    virtual-shell? (virtual-shell/admit-workspace workspace)
                    coding? (workspace-tools/admit-root workspace))
        now (store/now)
        id (new-id "bot")
        tools (if (seq tools)
                (set (map str tools))
                (default-tools configuration connectors))
        b (bot/bot {:bot/id id
                    :bot/organization (:organization-id session)
                    :bot/owner (:user-id session)
                    :bot/name name
                    :bot/avatar avatar
                    :bot/brief brief
                    :bot/provider-id provider-id
                    :bot/model model
                    :bot/email (mailbox-address configuration id)
                    :bot/tools tools
                    :bot/accounts accounts
                    :bot/writes? writes?
                    :bot/browser? browser?
                    :bot/coding? coding?
                    :bot/virtual-shell? virtual-shell?
                    :bot/omakase? omakase?
                    :bot/workspace workspace
                    :bot/created-at now
                    :bot/updated-at now})]
    ;; Derive the performer here and discard it. The call is the point: it is
    ;; `work-governance` refusing anything that would make this Bot a person,
    ;; and it runs before the Bot is durable rather than the first time
    ;; somebody asks for an org chart.
    (bot/->performer b)
    (store-bot! b)
    ;; Provisioning is deliberately best-effort at creation. The durable Bot
    ;; and its address do not disappear because a laptop is offline; overview
    ;; reports whether the relay has a concrete destination yet.
    (when-let [destination (and (relay/configured? configuration)
                                (mail-destination session b))]
      (try
        (let [result (relay/provision-bot-mailbox!
                      configuration {:bot-id id
                                     :organization (:bot/organization b)
                                     :address (:bot/email b)
                                     :destination (:address destination)})]
          (transact! assoc-in [:mailboxes id]
                     {:status :ready :address (:bot/email b)
                      :destination (:address destination)
                      :provisioned-at (store/now)})
          result)
        (catch Exception error
          (transact! assoc-in [:mailboxes id]
                     {:status :pending :address (:bot/email b)
                      :last-error-at (store/now)
                      :last-error-type (:type (ex-data error))}))))
    b))

(defn update!
  "Change what a Bot is. Name, colour, glyph and brief are free to change and
  change nothing about authority; `tools`, `writes?` and `browser?` are the
  authority, and they are the ones an operator is choosing when they edit."
  ([session bot-id attrs] (update! nil session bot-id attrs))
  ([configuration session bot-id attrs]
   (let [existing (owned! session bot-id)
        next-provider (if (contains? attrs :provider-id)
                        (:provider-id attrs) (:bot/provider-id existing))
        next-model (if (contains? attrs :model)
                     (:model attrs) (:bot/model existing))
        next-coding (if (contains? attrs :coding?)
                      (boolean (:coding? attrs)) (:bot/coding? existing))
        next-virtual-shell (if (contains? attrs :virtual-shell?)
                             (boolean (:virtual-shell? attrs))
                             (:bot/virtual-shell? existing))
        next-workspace (if (contains? attrs :workspace)
                         (:workspace attrs) (:bot/workspace existing))
        next-workspace (cond
                         next-virtual-shell
                         (virtual-shell/admit-workspace next-workspace)

                         next-coding
                         (workspace-tools/admit-root next-workspace))
        _ (when (or (contains? attrs :provider-id) (contains? attrs :model))
            (validate-provider-choice! configuration next-provider next-model))
        merged (cond-> existing
                 (contains? attrs :name) (assoc :bot/name (:name attrs))
                 (contains? attrs :avatar) (assoc :bot/avatar (:avatar attrs))
                 (contains? attrs :brief) (assoc :bot/brief (:brief attrs))
                 (contains? attrs :provider-id) (assoc :bot/provider-id (:provider-id attrs))
                 (contains? attrs :model) (assoc :bot/model (:model attrs))
                 (contains? attrs :tools) (assoc :bot/tools
                                                 (set (map str (:tools attrs))))
                 (contains? attrs :accounts) (assoc :bot/accounts
                                                    (set (map str (:accounts attrs))))
                 (contains? attrs :writes?) (assoc :bot/writes? (:writes? attrs))
                 (contains? attrs :browser?) (assoc :bot/browser? (:browser? attrs))
                 (contains? attrs :omakase?) (assoc :bot/omakase? (:omakase? attrs))
                 (or (contains? attrs :coding?)
                     (contains? attrs :virtual-shell?)
                     (contains? attrs :workspace))
                 (assoc :bot/coding? next-coding
                        :bot/virtual-shell? next-virtual-shell
                        :bot/workspace next-workspace)
                 (contains? attrs :enabled?) (assoc :bot/enabled? (:enabled? attrs)))]
     (store-bot! (bot/bot (assoc merged :bot/updated-at (store/now)))))))

(defn archive!
  "Disable a Bot without deleting its conversation. Deleting would take the
  record of what it did along with the ability to do more, and only the second
  one was asked for."
  [session bot-id]
  (update! session bot-id {:enabled? false}))

;; ── conversation ────────────────────────────────────────────────────────

(defn- conversation [bot-id]
  (vec (get-in (snapshot) [:conversations bot-id] [])))

(defn- append! [bot-id message]
  (transact! update-in [:conversations bot-id]
             (fn [messages]
               (vec (take-last max-conversation (conj (vec messages) message)))))
  message)

(defn- say
  "One Bot turn, appended."
  [bot-id text cards]
  (append! bot-id (bot/message {:id (new-id "msg") :bot bot-id :role :bot
                                :text text :cards cards :at (store/now)})))

;; ── what the Bot is waiting for ─────────────────────────────────────────

(defn- open-cards [bot-id kind pred]
  (for [m (conversation bot-id)
        c (:message/cards m)
        :when (and (= kind (:card/kind c)) (pred c))]
    c))

(defn- connected-providers
  "The provider names this person now holds at least one account for — the
  vocabulary a connection card's `:card/connector` is written in, which is the
  PROVIDER (`google`) rather than the connector id (`com.google.gmail`)."
  [did]
  (into #{} (map name) (keys (accounts-by-provider did))))

(defn- met?
  "Has this connection card been answered by the world since it was written?

  Nothing ever rewrites a stored card's `:card/state`: it is set once, and a
  card written while nothing was connected says `:offered` forever. Read
  literally, that made `unmet-connection?` true for the life of the
  conversation, so a Bot whose Google was authorized ten minutes ago still
  reported itself as `waiting-connection` — the screen kept asking for
  something that had already been done.

  So the state is recomputed from the provider rather than replayed, for the
  same reason `public-card` recomputes `:authable?`: whether a connector is
  connected right now is not something that was SAID, and the stored value
  stays as the record of what was true when the card was offered."
  [providers card]
  (contains? providers (:card/connector card)))

(defn- request-of
  "A stored approval card, as the record `bot/request-standing` decides from."
  [bot-id card]
  {:asked-at (:card/direction card 0)
   :current (direction bot-id)
   :answered? (some? (:card/decision card))})

(defn- presence [bot-id providers]
  {;; Outstanding, not merely undecided. A held write survives the person
   ;; saying something else — the run is replaced and `decide!` refuses the old
   ;; card — so counting undecided cards made a Bot report `waiting-approval`
   ;; for the rest of the conversation, about a request it would no longer
   ;; accept. Measured 2026-08-14 before this changed.
   :held-run? (boolean (seq (filter #(bot/outstanding? (request-of bot-id %))
                                    (open-approval-cards bot-id))))
   :unmet-connection? (boolean (seq (open-cards bot-id :connection
                                                #(and (#{:offered :waiting} (:card/state %))
                                                      (not (met? providers %))))))
   :active-run? (boolean (get-in (snapshot) [:runs bot-id :pending-call]))})

(declare public-turn)

(defn- public-bot [configuration did b]
  (let [rows (connectors/catalog-rows configuration)
        connected (connected-connectors configuration did)
        last-turn (last (get-in (snapshot) [:turn-history (:bot/id b)]))
        local-tools (concat
                     (when (:bot/browser? b)
                       (agent-control/browser-tool-definitions configuration))
                     (when (and (:bot/coding? b) (:bot/workspace b))
                       workspace-tools/tool-definitions)
                     (when (and (:bot/virtual-shell? b) (:bot/workspace b))
                       virtual-shell/tool-definitions))
        admitted (into (bot/admitted-tools b rows connected)
                       (map :name) local-tools)]
    {:id (:bot/id b)
     :name (:bot/name b)
     :avatar {:color (name (get-in b [:bot/avatar :avatar/color]))
              :glyph (name (get-in b [:bot/avatar :avatar/glyph]))}
     :brief (:bot/brief b)
     :provider-id (or (:bot/provider-id b)
                      (get-in configuration [:routing :default-provider]))
     :model (or (:bot/model b)
                (:default-model (policy/select-provider
                                 configuration (:bot/provider-id b)))
                (get-in configuration [:routing :default-model]))
     :tools (vec (:bot/tools b))
     :accounts (vec (:bot/accounts b))
     :email (or (:bot/email b) (mailbox-address configuration (:bot/id b)))
     :mailbox-ready? (= :ready (:status (mailbox-registration (:bot/id b))))
     :admitted-tools (vec (sort admitted))
     :grant-widens? (bot/grant-widens? b rows)
     :writes? (:bot/writes? b)
     :browser? (:bot/browser? b)
     :browser-ready? (boolean (and (:bot/browser? b)
                                   (agent-control/browser-enabled? configuration)))
     :coding? (:bot/coding? b)
     :virtual-shell? (:bot/virtual-shell? b)
     :omakase? (boolean (:bot/omakase? b))
     :virtual-shell-ready? (boolean (and (:bot/virtual-shell? b)
                                         (virtual-shell/available?)))
     :workspace (:bot/workspace b)
     :last-turn (public-turn last-turn)
     :enabled? (:bot/enabled? b)
     :status (name (bot/status b (presence (:bot/id b)
                                           (connected-providers did))))
     :updated-at (:bot/updated-at b)}))

(defn- address-list [value]
  (cond
    (nil? value) []
    (string? value) (->> (str/split value #"[,;]") (map str/trim)
                         (remove str/blank?) vec)
    (sequential? value) (->> value (map (comp str/trim str))
                             (remove str/blank?) vec)
    :else []))

(defn- addressed-to? [address message]
  (let [address (str/lower-case address)]
    (some #(= address (str/lower-case %))
          (re-seq #"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+"
                  (str (:to message))))))

(defn mailbox
  "Mail delivered to this Bot address plus its durable sent receipts."
  [configuration session bot-id]
  (let [b (owned! session bot-id)
        address (or (:bot/email b) (mailbox-address configuration bot-id))
        inbound (->> (mail-sync/messages)
                     (filter #(addressed-to? address %))
                     (mapv #(select-keys % [:id :account-id :thread-id :message-id
                                            :subject :from :from-email :to :snippet
                                            :body :received-at :read? :labels])))
        sent (vec (get-in (store/snapshot) [:bot-mail :sent bot-id] []))]
    {:schema "cloud.itonami.app.bot-mailbox.v1"
     :address address
     :ready? (= :ready (:status (mailbox-registration bot-id)))
     :inbound inbound :sent sent}))

(defn provision-mailbox!
  "Bind the Bot address to exactly one owned external mailbox."
  [configuration session bot-id]
  (let [b (owned! session bot-id)
        destination (mail-destination session b)]
    (when-not destination
      (throw (ex-info "Bot の受信先メールアカウントを1つに特定できません。"
                      {:type :bot/mail-account-required})))
    (let [address (or (:bot/email b) (mailbox-address configuration bot-id))
          result (relay/provision-bot-mailbox!
                  configuration {:bot-id bot-id
                                 :organization (:bot/organization b)
                                 :address address
                                 :destination (:address destination)})]
      (transact! assoc-in [:mailboxes bot-id]
                 {:status :ready :address address
                  :destination (:address destination)
                  :provisioned-at (store/now)})
      result)))

(defn send-mail!
  "Send as this Bot through Resend, never as an arbitrary From address."
  [configuration session bot-id request]
  (let [b (owned! session bot-id)
        _ (when-not (:bot/enabled? b)
            (throw (ex-info "この Bot は停止しています。" {:type :bot/disabled})))
        _ (when-not (:bot/writes? b)
            (throw (ex-info "この Bot には送信権限がありません。"
                            {:type :bot/mail-write-not-granted})))
        to (address-list (:to request))
        cc (address-list (:cc request))
        _ (when (or (empty? to) (str/blank? (str (:subject request))))
            (throw (ex-info "宛先と件名が必要です。" {:type :bot/invalid-mail})))
        address (or (:bot/email b) (mailbox-address configuration bot-id))
        result (relay/send-bot-mail!
                configuration {:bot-id bot-id :organization (:bot/organization b)
                               :from address :name (:bot/name b) :to to :cc cc
                               :subject (str (:subject request))
                               :text (str (:text request))
                               :in-reply-to (:in-reply-to request)})
        sent {:id (:id result) :from address :to to :cc cc
              :subject (str (:subject request)) :sent-at (store/now)}]
    (store/transact! update-in [:bot-mail :sent bot-id] (fnil conj []) sent)
    {:schema "cloud.itonami.app.bot-mail-send.v1" :sent sent}))

(defn- unqualify
  "Drop the namespace from every key, and render keyword VALUES as strings.

  Written out rather than done with a blanket walk because the first version
  was a blanket walk over the top level only: `:card/options` came back with
  its `:option/key` entries untouched, so every option in a choice card
  serialized as `{\"option/key\": \"A\"}` and the client read `undefined` for
  all of them. It rendered — three unlabelled buttons — and only a test that
  looked at the values caught it."
  [m]
  (into {} (map (fn [[k v]]
                  [(keyword (name k))
                   (cond (keyword? v) (name v)
                         (map? v) (unqualify v)
                         (and (sequential? v) (every? map? v)) (mapv unqualify v)
                         :else v)]))
        m))

(defn- public-card
  "A stored card, as the client should see it NOW.

  `:authable?` is recomputed rather than replayed. A card lives inside a
  message, and a message is a record of what was said; whether this machine
  can authorize a provider is not something that was said, it is the state of
  the installation at the moment somebody is looking at the button. A card
  written before this field existed carries no answer at all, and one written
  while a client was configured would keep saying so after it was removed —
  both render a button whose only outcome is
  「OAuth クライアントが未設定です」, which is the failure this field exists
  to prevent. The stored value stays as the record of what was true when the
  card was offered.

  `:state` is recomputed for the same reason and answers the same class of
  complaint from the other side: nothing rewrites a stored card, so one written
  while Google was unauthorized keeps offering the button after somebody
  authorized it — the transcript goes on asking for what is already done. See
  `met?`. `providers` is the set of provider names connected now; an empty set
  leaves every card as it was recorded, which is what a caller that does not
  know should get."
  ([c] (public-card c #{} nil))
  ([c providers] (public-card c providers nil))
  ([c providers bot-id]
   (cond-> (unqualify c)
     (= :connection (:card/kind c))
     (assoc :authable? (provider-authable? (keyword (:card/connector c))))

     (and (= :connection (:card/kind c)) (met? providers c))
     (assoc :state "connected")

     ;; The same recomputation for the other card that carries a button. A
     ;; superseded request must not render an enabled 承認する: pressing it
     ;; reaches `decide!` and comes back as a refusal, which is the failure
     ;; `:authable?` exists to prevent, one card over.
     (and (= :approval (:card/kind c)) (some? bot-id))
     (assoc :standing (name (bot/request-standing (request-of bot-id c)))))))

(defn- public-message
  ([m] (public-message m #{} nil))
  ([m providers] (public-message m providers nil))
  ([m providers bot-id]
   {:id (:message/id m)
    :role (name (:message/role m))
    :text (:message/text m)
    :at (:message/at m)
    :cards (mapv #(public-card % providers bot-id) (:message/cards m))}))

(defn- public-conversation
  "One Bot's conversation, as the client should see it now. Every route that
  returns messages goes through here, so the recomputation in `public-card`
  cannot be had by some callers and not others — which is how `:authable?`
  ended up correct on the Bots screen and stale everywhere else."
  [did bot-id]
  (let [providers (connected-providers did)]
    (mapv #(public-message % providers bot-id) (conversation bot-id))))

(defn overview
  "Everything the Bots screen needs on load: the Bots, and — when there are
  none — what it takes to make the first one."
  [configuration session]
  (let [did (identity/session-did session)
        mine (->> (vals (:bots (snapshot)))
                  (filter #(and (= (:user-id session) (:bot/owner %))
                                (= (:organization-id session) (:bot/organization %))))
                  (sort-by :bot/created-at))
        provider-readiness
        (mapv (fn [candidate]
                (merge {:id (:id candidate)
                        :name (:name candidate)
                        :model (or (:default-model candidate)
                                   (when (= (:id candidate)
                                            (get-in configuration [:routing :default-provider]))
                                     (get-in configuration [:routing :default-model])))}
                       (policy/provider-readiness configuration candidate)))
              (:providers configuration))]
    {:bots (mapv #(public-bot configuration did %) mine)
     :model-providers
     (mapv #(select-keys % [:id :name :model])
           (filter :allowed? provider-readiness))
     :model-provider-readiness provider-readiness
     :catalog (catalog configuration did)
     :palette {:colors (mapv name bot/avatar-colors)
               :glyphs (mapv name bot/avatar-glyphs)}
     :browser-available? (agent-control/browser-enabled? configuration)}))

(defn suggestions
  "Starting points for the connectors somebody picked."
  [picked]
  (mapv (fn [t] {:id (name (:template/id t))
                 :name (:template/name t)
                 :summary (:template/summary t)
                 :brief (:template/brief t)
                 :avatar {:color (name (get-in t [:template/avatar :avatar/color]))
                          :glyph (name (get-in t [:template/avatar :avatar/glyph]))}})
        (bot/suggestions picked)))

(defn messages [session bot-id]
  (owned! session bot-id)
  (public-conversation (identity/session-did session) bot-id))

;; ── the loop ────────────────────────────────────────────────────────────

(defn- browser-tools
  "The isolated-browser tools, when the Bot asked for them AND this machine has
  enabled the browser. Not written into `:bot/tools`: that set is connector
  names, and mixing the two would make `grant-widens?` fire on every ordinary
  browser Bot."
  [configuration b]
  (if (:bot/browser? b)
    (vec (agent-control/browser-tool-definitions configuration))
    []))

(defn- coding-tools [b]
  (into (if (and (:bot/coding? b) (:bot/workspace b))
          workspace-tools/tool-definitions
          [])
        (if (and (:bot/virtual-shell? b) (:bot/workspace b))
          virtual-shell/tool-definitions
          [])))

(defn- tool-definitions
  "The tools the Bot's grant REACHES, as the model sees them.

  Read and write are both offered. Withholding the write tools would make a Bot
  answer 'I cannot send mail' when the truth is 'I can, once you approve it',
  and the second is the thing a person is trying to find out.

  So is a tool whose connector nobody has authorized yet, and for the same
  reason one step further out: a Bot that could not see `gmail_search` would
  answer 'I have no way to read mail', when the truth is 'I have, once you
  authorize Google'. The difference between this set and what may actually run
  is carried by `:runnable` and decided at the call, not here — see
  `turn-admission`.

  Offering a tool is not granting it. `bot/reachable-tools` asks the same core
  as `admitted-tools`, still narrowed by the deployment's enabled set, by the
  grant and by the write permission; only the connected fact is held true, and
  only for the purpose of letting the model reach."
  [configuration b]
  (let [registry (connectors/enabled configuration)
        offerable (bot/reachable-tools b (connectors/catalog-rows configuration))
        connector-tools
        (into []
              (for [d (creg/descriptors registry)
                    t (cm/tools d)
                    :when (contains? offerable (:connector/name t))]
                {:name (:connector/name t)
                 :description (str "[" (:connector/name d) "] "
                                   (or (:connector/description t) (:connector/name t))
                                   (when (= :write (:connector/effect t)) " (write)"))
                 :parameters (:connector/input-schema t)}))]
    (into (into (browser-tools configuration b) (coding-tools b))
          connector-tools)))

(defn- write-tool? [configuration tool-name]
  (or (agent-control/browser-write? tool-name)
      (workspace-tools/write-tool? tool-name)
      (virtual-shell/write-tool? tool-name)
      (let [registry (connectors/enabled configuration)]
        (boolean
         (some (fn [d] (when-let [t (cm/tool d tool-name)]
                         (= :write (:connector/effect t))))
               (creg/descriptors registry))))))

(defn- omakase-tool?
  "The deliberately small effect set covered by the owner's standing
  delegation. Browser interaction and other connector writes still stop for a
  human decision even when the Bot is in omakase mode."
  [tool-name]
  (or (workspace-tools/write-tool? tool-name)
      (virtual-shell/write-tool? tool-name)
      (= "gmail_send_message" (str tool-name))))

(defn- describe-tool [configuration tool-name args]
  (if (or (agent-control/browser-tool? tool-name)
          (workspace-tools/tool? tool-name)
          (virtual-shell/tool? tool-name))
    (cond
      (workspace-tools/tool? tool-name) (workspace-tools/describe tool-name args)
      (virtual-shell/tool? tool-name) (virtual-shell/describe tool-name args)
      :else (agent-control/describe-browser-tool tool-name args))
    (let [registry (connectors/enabled configuration)
          request (invoke/request-for registry tool-name args)]
      ;; The request WITHOUT the credential — `connector.invoke/request-for`
      ;; exists precisely so a host can show what a call would do without holding
      ;; a token to do it. An approval prompt that only names the tool is asking
      ;; somebody to approve a word.
      (str (str/upper-case (name (or (:connector.http/method request) :get)))
           " " (:connector.http/url request)
           (when-let [q (seq (:connector.http/query request))]
             (str " " (pr-str (into (sorted-map) q))))))))

(defn- run-tool! [configuration b selection tool-name args]
  (let [text (if (or (agent-control/browser-tool? tool-name)
                     (workspace-tools/tool? tool-name)
                     (virtual-shell/tool? tool-name))
               (str (cond
                      (workspace-tools/tool? tool-name)
                      (workspace-tools/call! (:bot/workspace b) tool-name args)

                      (virtual-shell/tool? tool-name)
                      (virtual-shell/call! {:bot-id (:bot/id b)
                                            :workspace (:bot/workspace b)}
                                           tool-name args)

                      :else
                      (agent-control/call-browser-tool!
                       configuration (:bot/id b) tool-name args)))
               (let [registry (connectors/enabled configuration)
                     result (invoke/call registry tool-name args
                                         {:http (http-port)
                                          :tokens (tokens-port configuration selection)})]
                 (if (string? result) result (pr-str result))))]
    (if (> (count text) max-tool-output-chars)
      (str (subs text 0 max-tool-output-chars) "…")
      text)))

(defn- system-prompt [b configuration]
  (str "You are " (:bot/name b) ", a bounded worker inside Cloud Itonami. "
       "Use exactly one tool per turn. Prefer reading before writing. "
       "Never request, reveal or repeat a password, token, MFA code or other "
       "secret; if you find one in a tool result, do not quote it. "
       (if (:bot/omakase? b)
         "The owner enabled omakase for local shell, workspace/Git writes, and Gmail send: those admitted tools run immediately with an audit receipt. Other writes still wait for human approval. "
         "A write tool will be held for the person's approval before it runs. ")
       "Call a write only when it is the right next step and say what you are about to do. "
       "Answer in the language the person used.\n\n"
       (when (and (:bot/browser? b)
                  (agent-control/browser-enabled? configuration))
         (str "You have an isolated browser of your own on this machine. "
              "Its cookies are not shared with other Bots. "
              "browser_snapshot reads; opening, clicking and typing wait for "
              "approval. Stay inside the domains Settings has allowed. "
              "If a site asks for a password, 2FA, CAPTCHA or payment, stop "
              "and tell the person — do not try to bypass it.\n\n"))
       (when (and (:bot/coding? b) (:bot/workspace b))
         (str "You may inspect and edit exactly one local Git repository: "
              (:bot/workspace b) ". Use workspace and git tools for bounded "
              "file operations. "
              (when-not (:bot/virtual-shell? b)
                "There is no shell, checkout, reset, push, credential, or remote-write tool. ")
              (if (:bot/omakase? b)
                "File writes and local commits use the owner's omakase delegation.\n\n"
                "File writes and local commits wait for human approval.\n\n")))
       (when (and (:bot/virtual-shell? b) (:bot/workspace b))
         (str "You have a dedicated OCI virtual computer for general shell work. "
              "Its only host mount is this Git root at /workspace; it has no "
              "network, host credentials, Docker socket, or Linux capabilities. "
              (if (:bot/omakase? b)
                "Every virtual_shell command records an omakase approval receipt. "
                "Every virtual_shell command waits for human approval. ")
              "Prefer "
              "small commands with an explicit timeout, inspect results, and "
              "never claim a host or remote action occurred.\n\n"))
       (when (seq (str (:bot/brief b)))
         (str "Standing brief from the person you work for:\n" (:bot/brief b)))))

(defn- transcript
  "The durable conversation, as a model transcript. Built here rather than
  stored in provider shape: `:person`/`:bot` is what this application records,
  and a stored `\"user\"`/`\"assistant\"` transcript would be a second copy of
  the conversation whose only purpose is to be sent somewhere."
  [configuration b messages]
  (into [{:role "system" :content (system-prompt b configuration)}]
        (for [m messages
              :when (seq (str (:message/text m)))]
          {:role (if (= :person (:message/role m)) "user" "assistant")
           :content (:message/text m)})))

(defn- save-run! [bot-id run]
  (transact! assoc-in [:runs bot-id] run))

(defn- clear-run! [bot-id]
  (transact! update :runs dissoc bot-id))

;; ── visible turn lifecycle ─────────────────────────────────────────────

(defn- record-turn!
  "Upsert one bounded, durable lifecycle record for a visible Bot turn.

  The conversation records what was said. This record answers the different
  question of what happened when no answer was said — especially when the
  process stopped between accepting a direction and receiving a model token."
  [bot-id run-id attrs]
  (let [at (store/now)]
    (transact!
     update-in [:turn-history bot-id]
     (fn [turns]
       (let [turns (vec turns)
             previous (some #(when (= run-id (:turn/id %)) %) turns)
             next-turn (merge {:turn/id run-id
                               :turn/bot bot-id
                               :turn/state :running
                               :turn/phase :accepted
                               :turn/started-at at}
                              previous attrs {:turn/updated-at at})]
         (vec (take-last max-turn-history
                         (conj (filterv #(not= run-id (:turn/id %)) turns)
                               next-turn))))))
    nil))

(defn recover-interrupted!
  "Close turns that were running in the previous process.

  Called once during server start, before Bots can accept new work. An empty
  in-memory `active-turns` after process start is evidence that a persisted
  `:running` record cannot still have an owner; reporting it as interrupted is
  recovery, while silently calling it idle loses the user's work."
  []
  (let [at (store/now)]
    (transact!
     update :turn-history
     (fn [by-bot]
       (into {}
             (for [[bot-id turns] (or by-bot {})]
               [bot-id
                (mapv (fn [turn]
                        (if (= :running (:turn/state turn))
                          (assoc turn
                                 :turn/state :interrupted
                                 :turn/phase :interrupted
                                 :turn/updated-at at
                                 :turn/finished-at at
                                 :turn/error-type :server-restarted)
                          turn))
                      turns)])))))
  nil)

(defn- public-turn [turn]
  (when turn
    {:id (:turn/id turn)
     :state (name (:turn/state turn))
     :phase (name (:turn/phase turn))
     :tool (:turn/tool turn)
     :direction (:turn/direction turn)
     :started-at (:turn/started-at turn)
     :updated-at (:turn/updated-at turn)
     :finished-at (:turn/finished-at turn)
     :error-type (some-> (:turn/error-type turn) str (subs 1))}))

;; ── the demonstration ───────────────────────────────────────────────────

(defn- trace!
  "Record that a tool actually RAN.

  Kept separately from the run because a run is cleared the moment it finishes,
  and what a routine is built from is exactly the part that survives that: the
  calls that executed. Not the calls the model proposed and not the plan it
  described — a routine built from a plan is a routine built from a sentence
  nobody checked.

  Capped, and per Bot. The cap is why `record-routine!` takes the most recent
  window rather than a whole history: a demonstration is one piece of work, and
  a person pointing at 'what you just did' means the last few minutes."
  [configuration bot-id tool-name]
  (transact! update-in [:traces bot-id]
             (fn [entries]
               (vec (take-last max-trace
                               (conj (vec entries)
                                     {:trace/tool tool-name
                                      :trace/effect (if (write-tool? configuration tool-name)
                                                      :write :read)
                                      :trace/at (store/now)}))))))

(defn- trace-of [bot-id]
  (vec (get-in (snapshot) [:traces bot-id] [])))

(defn- approval-impact [name]
  (cond
    (agent-control/browser-tool? name)
    "この Bot 専用の分離ブラウザーのページ状態が変わります。"
    (workspace-tools/tool? name)
    "選択した local Git workspace のファイルまたは履歴が変わります。remote へは push しません。"
    (virtual-shell/tool? name)
    "Bot 専用のnetwork-disabled仮想環境内でcommandを実行します。選択したGit workspaceは書き換わる場合があります。"
    :else "接続済みサービスに書き込みます。"))

(defn- approval-request [configuration b run call card-id]
  (bot/approval-card
   {:id card-id :run (:id run) :direction (direction (:bot/id b))
    :title "この Bot が実行しようとしています"
    :action (:name call)
    :summary (describe-tool configuration (:name call) (:input call))
    :impact (approval-impact (:name call))}))

(defn- advance!
  "Turn until the Bot is done or needs a person.

  The shape is `agent-control/advance!`'s, deliberately: read tools run, the
  first write tool stops the loop and becomes an approval card, and the budget
  is finite in both turns and tool calls so a Bot cannot spend an afternoon on
  one message.

  Admission is checked at the CALL, in the same place, because that is where it
  becomes true. A tool whose provider is unresolved stops the loop and becomes
  a connection or choice card; the host used to answer that question before the
  turn instead, and a Bot with an unauthorized connector then could not answer
  anything at all (ADR-0044). `run` carries the facts — `:runnable`, `:blocked`,
  `:tool-provider` — because `turn-admission` assembles them once for all three
  callers."
  ([configuration b run] (advance! configuration b run nil))
  ([configuration b run {:keys [on-event cancelled?]}]
  (loop [run run]
    (when (and cancelled? (cancelled?))
      (throw (ex-info "Bot の実行を中止しました。" {:type :bot/cancelled})))
    (cond
      (>= (:turn-count run 0) max-turns)
      (say (:bot/id b) "考える回数の上限に達したので、ここで止めます。何を先にやるか教えてください。" nil)

      (>= (:tool-count run 0) max-tool-calls)
      (say (:bot/id b) "ツールを呼ぶ回数の上限に達したので、ここで止めます。" nil)

      :else
      (let [{:keys [provider model]} (provider-choice! configuration b)
            request {:model model
                     :conversation-id (:bot/id b)
                     :messages (:messages run)
                     :tools (:tools run)
                     :temperature 0.2}
            _ (when on-event (on-event {:type "phase" :phase "model"}))
            result (if on-event
                     (provider/agent-turn-stream!
                      provider request
                      #(on-event {:type "delta" :content %}))
                     (provider/agent-turn provider request))
            calls (:tool-calls result)
            _ (when (> (count calls) 1)
                (throw (ex-info
                        "model provider が複数のツール呼び出しを返したため、安全のため停止しました。"
                        {:type :agent/multiple-tool-calls
                         :count (count calls)})))
            run (-> run
                    (update :turn-count (fnil inc 0))
                    (update :messages conj {:role "assistant"
                                            :content (:content result)
                                            :tool-calls calls}))]
        (if (empty? calls)
          (do (clear-run! (:bot/id b))
              (say (:bot/id b) (:content result) nil))
          (let [{:keys [name input] :as call} (first calls)
                _ (when on-event
                    (on-event {:type "phase" :phase "tool-proposed"
                               :tool name}))
                ;; The provider this call needs, and — when that provider is
                ;; not resolved for this Bot — the card that resolves it. This
                ;; is the moment the connection question becomes NECESSARY:
                ;; the Bot has reached for the tool. Asking earlier meant
                ;; asking on turns that never touched a connector.
                blocked (get (:blocked run) (get (:tool-provider run) name))]
            (cond
              ;; Checked before `:runnable`, and the order is the decision. A
              ;; provider can be connected — so its tools are admitted — while
              ;; the account to use is still ambiguous, which is `:ask`. Running
              ;; then would resolve no token and reach nothing; taking the first
              ;; account is the failure `connection-for` already refuses.
              blocked
              ;; Cleared rather than held. An approval card can resume, because
              ;; the person's answer is the last thing the call was waiting for;
              ;; an authorization is a round trip through a browser and another
              ;; provider, and a run parked across it would be resumed from a
              ;; transcript written before it. The person says it again, to a
              ;; Bot that can now do it.
              (do (clear-run! (:bot/id b))
                  (say (:bot/id b)
                       (if (= :connection (:card/kind blocked))
                         (str (:card/title blocked)
                              " を認証すると、この続きができます。")
                         (:card/prompt blocked))
                       [blocked]))

              ;; A name the model invented, or one that left the grant between
              ;; the offer and the call. `invoke/call` would fail somewhere
              ;; deeper with a message about a registry; refusing here says the
              ;; true thing in the Bot's own transcript.
              (not (contains? (:runnable run) name))
              (do (clear-run! (:bot/id b))
                  (say (:bot/id b)
                       (str "「" name "」はこの Bot が使えるツールではありません。")
                       nil))

              (write-tool? configuration name)
              (let [card-id (new-id "card")
                    card (approval-request configuration b run call card-id)]
                (if (and (:bot/omakase? b) (omakase-tool? name))
                  ;; The standing delegation never bypasses admission above.
                  ;; It replaces only the wait, and leaves a durable receipt in
                  ;; the same transcript where a human decision would appear.
                  (let [receipt (assoc card
                                       :card/decision :approved
                                       :card/decision-mode :omakase
                                       :card/decided-by :bot
                                       :card/decided-at (store/now))
                        _ (say (:bot/id b)
                               (or (:content result) "おまかせで実行します。")
                               [receipt])
                        output (run-tool! configuration b (:selection run) name input)
                        run (-> run
                                (update :tool-count (fnil inc 0))
                                (update :messages conj
                                        {:role "tool" :tool-call-id (:id call)
                                         :name name :content output}))]
                    (trace! configuration (:bot/id b) name)
                    (save-run! (:bot/id b) run)
                    (recur run))
                  ;; Normal mode stops. The person decides from this exact run.
                  (do
                    (save-run! (:bot/id b) (assoc run :pending-call call
                                                  :pending-card card-id))
                    (say (:bot/id b)
                         (or (:content result) "この操作には承認が必要です。")
                         [card]))))

              :else
              (let [output (run-tool! configuration b (:selection run) name input)
                    run (-> run
                            (update :tool-count (fnil inc 0))
                            (update :messages conj
                                    {:role "tool" :tool-call-id (:id call)
                                     :name name :content output}))]
                (trace! configuration (:bot/id b) name)
                (save-run! (:bot/id b) run)
                (recur run))))))))))

(defn- rows-by-provider
  "The connector rows this Bot's grant actually touches, grouped by the OAuth
  client they are authorized under. Grouped, because that is what a person is
  asked to authorize once: Drive, Gmail and Calendar are one Google consent
  screen, and asking three times would be three requests for one decision."
  [configuration b]
  (let [rows (connectors/catalog-rows configuration)]
    (->> rows
         (filter (fn [row]
                   (some #(contains? (:bot/tools b) (:name %)) (:tools row))))
         (filter :provider)
         (group-by :provider))))

(defn- connection-card-for [configuration provider group accounts]
  (let [client (get-in (connectors/provider-catalog configuration)
                       [provider :name])]
    (bot/connection-card
     {:id (new-id "card")
      :connector (name provider)
      :title (str/join "・" (map :name group))
      ;; The scopes below are the OAuth CLIENT's, not this Bot's — one Google
      ;; consent covers every tool this deployment has enabled, so a card
      ;; titled "Gmail" that lists Calendar and Drive scopes is telling the
      ;; truth and looking like a mistake. Saying whose authorization it is
      ;; costs one line and is the difference between a list somebody skims and
      ;; a list somebody can check.
      :summary (str (when client (str client " の認証です。"))
                    "この app が有効にしているツールぶんの権限をまとめて求めます。"
                    (when-let [s (seq (keep :summary group))]
                      (str " — " (str/join " / " s))))
      :tool-count (count (mapcat :tools group))
      :scopes (connectors/granted-scopes configuration provider)
      :accounts (mapv #(select-keys % [:id :label :email]) accounts)
      ;; A Bot may already hold tools for a provider this machine cannot
      ;; authorize — it was granted them before anyone checked, or the client
      ;; was removed since. The card still has to appear, because the Bot
      ;; genuinely is blocked on it; what it must not do is offer a button
      ;; whose only outcome is 'OAuth クライアントが未設定です'.
      :authable? (provider-authable? provider)})))

(defn- selections [bot-id]
  (get-in (snapshot) [:selections bot-id] {}))

(defn- resolve-accounts
  "Which account this Bot uses at each provider it needs — or what to ask.

  Returns `{:selection {provider connection} :blocked {provider card}}`. The
  decision per provider is `bot/account-disposition`'s, which is the refusal
  `identity/connection-for` already makes turned into something a Bot can act
  on. Nothing here picks between two accounts; when there are two and no choice
  is in effect, it asks.

  `:blocked` is keyed by PROVIDER rather than being a list, because the caller's
  question is no longer 'is anything unresolved' — it is 'the Bot just reached
  for this tool, is the provider behind it resolved'. A list answers the first
  and the first is what made every turn open with a demand."
  [configuration b did]
  (let [held (accounts-by-provider did)
        chosen (selections (:bot/id b))]
    (reduce
     (fn [acc [provider group]]
       (let [mine (get held provider [])
             usable (bot/usable-accounts b mine)
             selected (some #(when (= (get chosen (name provider)) (:id %)) %)
                            usable)]
         (case (bot/account-disposition b mine (some? selected))
           :connect
           (assoc-in acc [:blocked provider]
                     (connection-card-for configuration provider group mine))

           :use
           (assoc-in acc [:selection provider]
                     (identity/connection-by-id
                      did (:id (or selected (first usable)))))

           :ask
           (assoc-in acc [:blocked provider]
                     (bot/choice-card
                      {:id (new-id "card")
                       :prompt (str (str/join "・" (map :name group))
                                    " はどのアカウントで?")
                       :detail "この Bot がこれから使うアカウントです。あとから変えられます。"
                       :subject {:subject/kind :account
                                 :subject/provider (name provider)}
                       :options (mapv (fn [account]
                                        {:option/label (or (:label account)
                                                           (:email account))
                                         :option/value (:id account)})
                                      usable)})))))
     {:selection {} :blocked {}}
     (rows-by-provider configuration b))))

(defn- turn-admission
  "Everything one turn needs to know about reach, in one place.

  `:tools` is what the model may reach for; `:runnable` is what may actually
  run; `:blocked` is, per provider, the card to show if the Bot reaches past
  the second into the first. Three run-builders exist — a message, a routine
  and a handoff — and every one of them calls `advance!`, so the facts it
  decides from are assembled here rather than three times.

  `:runnable` folds the browser tools in beside the connector ones because
  `advance!` asks one question of one set. They are admitted by a different
  gate — `browser-tools` already applied it — and they carry no provider, so
  they can never be blocked on an authorization."
  [configuration b did]
  (let [rows (connectors/catalog-rows configuration)
        connected (connected-connectors configuration did)
        browser (browser-tools configuration b)
        coding (coding-tools b)
        {:keys [selection blocked]} (resolve-accounts configuration b did)]
    {:selection selection
     :blocked blocked
     :tool-provider (tool->provider configuration)
     :runnable (into (into (into #{} (map :name) browser)
                           (map :name) coding)
                     (bot/admitted-tools b rows connected))
     :tools (tool-definitions configuration b)}))

(defn send!
  "One message to a Bot, and its answer.

  Synchronous on purpose. A Bot that answered in the background would need a
  second delivery mechanism for the case a person has closed the screen, and
  this application already has one — `work-runtime` — for work that is supposed
  to outlive a window. A chat turn is not that."
  ([configuration session bot-id text]
   (send! configuration session bot-id text nil))
  ([configuration session bot-id text advance-options]
  (let [b (owned! session bot-id)
        text (str/trim (str text))]
    (when (str/blank? text)
      (throw (ex-info "メッセージが空です。" {:type :bot/empty-message})))
    (when (> (count text) max-message-chars)
      (throw (ex-info "メッセージが長すぎます。" {:type :bot/message-too-long})))
    (when-not (:bot/enabled? b)
      (throw (ex-info "この Bot は停止しています。" {:type :bot/disabled})))
    ;; A new instruction is a new direction, and it starts BEFORE the message is
    ;; recorded — everything from here belongs to it, including the request the
    ;; Bot may raise on this turn. Whatever the previous direction left waiting
    ;; is superseded by the fact of this one existing; nothing is rewritten,
    ;; because the person did not decide anything, they moved on.
    (transact! update-in [:directions bot-id] (fnil inc 0))
    (append! bot-id (bot/message {:id (new-id "msg") :bot bot-id :role :person
                                  :text text :at (store/now)}))
    (let [did (identity/session-did session)
          admission (turn-admission configuration b did)]
      ;; The turn is taken. An unauthorized connector is no longer a reason to
      ;; refuse the message: it used to be, and the cost was a Bot that
      ;; answered "先に接続が要ります" to hello, to thanks, and to every
      ;; question about its own brief — on a grant whose tools that turn was
      ;; never going to touch. The refusal it was protecting — no plan built
      ;; around a service nobody authorized — is kept, one step later and where
      ;; it is true: `advance!` stops at the CALL, before the tool is reached,
      ;; and the card arrives then.
      (if (empty? (:tools admission))
        (say bot-id
             "使えるツールがひとつもありません。Settings で有効にするか、この Bot の権限を見直してください。"
             nil)
        (advance! configuration b
                  (merge admission
                         {:id (new-id "run")
                          :messages (transcript configuration b
                                                (conversation bot-id))
                          :turn-count 0
                          :tool-count 0})
                  advance-options))
      (public-conversation did bot-id)))))

(defn send-stream!
  "Run one visible Bot turn with progress events and a cancellable run id."
  [configuration session bot-id text run-id on-event]
  (owned! session bot-id)
  (let [run-id (str/trim (str run-id))
        cancelled (atom false)
        progress (atom {:turn/phase :accepted})
        entry {:run-id run-id :cancelled cancelled :progress progress
               :thread (Thread/currentThread)}]
    (when (str/blank? run-id)
      (throw (ex-info "run-id が必要です。" {:type :bot/missing-run-id})))
    (locking active-turns
      (when (contains? @active-turns bot-id)
        (throw (ex-info "この Bot はすでに実行中です。" {:type :bot/already-running})))
      (swap! active-turns assoc bot-id entry))
    (record-turn! bot-id run-id
                  {:turn/direction (inc (direction bot-id))
                   :turn/state :running
                   :turn/phase :accepted})
    (try
      (on-event {:type "phase" :phase "accepted"})
      (let [emit! (fn [event]
                    (when (= "phase" (:type event))
                      ;; A live phase belongs to this process and the stream.
                      ;; Keep it in memory so a 14 MB application state is not
                      ;; rewritten for every model/tool boundary. The durable
                      ;; accepted record is enough to detect a lost process;
                      ;; the final write records the last observed progress.
                      (swap! progress merge
                             (cond-> {:turn/phase (keyword (:phase event))}
                               (:tool event) (assoc :turn/tool (:tool event)))))
                    (on-event event))
            messages (send! configuration session bot-id text
                            {:on-event emit! :cancelled? #(deref cancelled)})]
        (record-turn! bot-id run-id
                      (merge @progress
                             {:turn/state (if @cancelled :cancelled :completed)
                              :turn/phase (if @cancelled :cancelled :completed)
                              :turn/finished-at (store/now)}))
        messages)
      (catch Exception error
        (if (or @cancelled (= :bot/cancelled (:type (ex-data error))))
          (do
            (clear-run! bot-id)
            (say bot-id "中止しました。" nil)
            (record-turn! bot-id run-id
                          (merge @progress
                                 {:turn/state :cancelled
                                  :turn/phase :cancelled
                                  :turn/finished-at (store/now)
                                  :turn/error-type :bot/cancelled}))
            (public-conversation (identity/session-did session) bot-id))
          (do
            (record-turn! bot-id run-id
                          (merge @progress
                                 {:turn/state :failed
                                  :turn/phase :failed
                                  :turn/finished-at (store/now)
                                  :turn/error-type (or (:type (ex-data error))
                                                       :internal-error)}))
            (throw error))))
      (finally
        ;; Clear the interrupted flag before this pooled HTTP thread is reused.
        (Thread/interrupted)
        (locking active-turns
          (when (= run-id (get-in @active-turns [bot-id :run-id]))
            (swap! active-turns dissoc bot-id)))))))

(defn cancel!
  "Cancel the matching active turn. Ownership and run id both have to match."
  [session bot-id run-id]
  (owned! session bot-id)
  (let [entry (get @active-turns bot-id)]
    (when-not (and entry (= (str run-id) (:run-id entry)))
      (throw (ex-info "実行中の Bot turn が見つかりません。" {:type :bot/run-not-found})))
    (reset! (:cancelled entry) true)
    (provider/cancel-agent-stream! (:thread entry))
    (virtual-shell/cancel! bot-id)
    (.interrupt ^Thread (:thread entry))
    {:cancelled true :run-id (:run-id entry)}))

(defn cancel-shell!
  "Cancel an approved shell command without granting access to another Bot."
  [session bot-id]
  (owned! session bot-id)
  (let [result (virtual-shell/cancel! bot-id)]
    (when-not (:cancelled result)
      (throw (ex-info "実行中の仮想shellが見つかりません。"
                      {:type :bot/shell-run-not-found})))
    result))

(defn- answered-card [bot-id card-id]
  (some (fn [m] (some #(when (= card-id (:card/id %)) %) (:message/cards m)))
        (conversation bot-id)))

(defn answer!
  "Record an answer to a lettered choice the Bot asked.

  When the choice was the runtime's own — which account to use at a provider —
  the answer is also durable configuration, so it is written to the Bot's
  selections and not only into the transcript. A record of the answer that did
  not change what happens next would make the next turn ask again."
  [configuration session bot-id card-id answer]
  (let [b (owned! session bot-id)]
    (transact! update-in [:conversations bot-id] bot/answer-choice card-id answer)
    (let [card (answered-card bot-id card-id)
          subject (:card/subject card)
          chosen (some #(when (= answer (:option/key %)) (:option/value %))
                       (:card/options card))]
      (when (and (= :account (:subject/kind subject)) chosen)
        (transact! assoc-in
                   [:selections bot-id (:subject/provider subject)] chosen)
        (say bot-id
             (str (or (some #(when (= answer (:option/key %)) (:option/label %))
                            (:card/options card))
                      "そのアカウント")
                  " を使います。")
             nil)))
    (public-conversation (identity/session-did session) bot-id)))

(defn accounts
  "This person's external accounts, and which of them this Bot may use.

  The Bots screen's answer to 'which Google account is this'. `:bound` empty
  means the Bot inherits the person's accounts, which is what somebody with one
  account means — and stays honest when they add a second, because the runtime
  then asks rather than picking."
  [session bot-id]
  (let [b (owned! session bot-id)
        did (identity/session-did session)]
    {:accounts (mapv (fn [account]
                       {:id (:id account)
                        :provider (name (:provider account))
                        :label (:label account)
                        :email (:email account)
                        :bound? (contains? (:bot/accounts b) (:id account))})
                     (identity/accounts-for did))
     :selections (selections bot-id)}))

(defn label-account!
  "Give one of this person's accounts a nickname — 'work', 'personal'."
  [session connection-id label]
  (identity/label-connection! (identity/session-did session)
                              connection-id label))

(defn decide!
  "Approve or reject a held write.

  `bot/may-approve?` is asked first and its refusal is the point: the session
  presented here is a human browser session, and if it ever were not — an agent
  session reaching this route, a future caller passing its own actor — the
  answer is no before anything else is considered."
  [configuration session bot-id card-id decision]
  (let [b (owned! session bot-id)
        decision (keyword decision)]
    (when-not (#{:approved :rejected} decision)
      (throw (ex-info "承認判断が不正です。" {:type :bot/invalid-decision})))
    (when-not (or (and (= :agent (:kind session))
                       (:bot/omakase? b)
                       (omakase-tool?
                        (get-in (snapshot) [:runs bot-id :pending-call :name])))
                  (bot/may-approve?
                   {:actor-kind (if (= :agent (:kind session)) :agent :user)
                    :human? (not= :agent (:kind session))
                    :identified? (boolean (:user-id session))
                    :authorized? (= (:user-id session) (:bot/owner b))}))
      (throw (ex-info "この承認はこのセッションでは行えません。"
                      {:type :bot/approval-refused :bot bot-id})))
    ;; Which refusal the person is owed, when there is one. "There is nothing
    ;; held" and "you have since asked for something else" are different facts,
    ;; and the second used to be reported as the first — so a person who pressed
    ;; 承認する on a card still showing an enabled button was told the Bot had
    ;; nothing waiting, which was true of the run and false of what they were
    ;; looking at.
    (let [card (some #(when (= card-id (:card/id %)) %) (open-approval-cards bot-id))]
      (when (and card (= :superseded (bot/request-standing (request-of bot-id card))))
        (throw (ex-info "この承認はもう古い指示のものです。必要ならもう一度頼んでください。"
                        {:type :bot/superseded :bot bot-id :card card-id}))))
    (let [run (get-in (snapshot) [:runs bot-id])
          call (:pending-call run)]
      (when-not (and call (= card-id (:pending-card run)))
        (throw (ex-info "承認待ちの操作がありません。" {:type :bot/not-held})))
      (transact! update-in [:conversations bot-id]
                 (fn [messages]
                   (mapv (fn [m]
                           (update m :message/cards
                                   (fn [cards]
                                     (mapv #(if (= card-id (:card/id %))
                                              (cond-> (assoc % :card/decision decision
                                                               :card/decided-at (store/now))
                                                (= :agent (:kind session))
                                                (assoc :card/decision-mode :omakase
                                                       :card/decided-by :agent-session))
                                              %)
                                           cards))))
                         messages)))
      (if (= :approved decision)
        (let [output (run-tool! configuration b (:selection run)
                                (:name call) (:input call))
              run (-> run
                      (update :tool-count (fnil inc 0))
                      (update :messages conj {:role "tool"
                                              :tool-call-id (:id call)
                                              :name (:name call)
                                              :content output})
                      (dissoc :pending-call :pending-card))]
          ;; Traced here as well as in `advance!`: an approved write is the
          ;; step a routine most needs to have recorded, and it is the one
          ;; execution path that does not go through the loop's own call site.
          (trace! configuration bot-id (:name call))
          (save-run! bot-id run)
          (advance! configuration b run))
        (do (clear-run! bot-id)
            (say bot-id "わかりました。この操作はしません。" nil))))
    (public-conversation (identity/session-did session) bot-id)))

;; ── routines ────────────────────────────────────────────────────────────

(defn- sha256-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn- address
  "The content address of a routine's steps.

  `routine/canonical` is the portable half — the same value on every runtime —
  and this is the effect the core is not allowed to have. Two Bots given the
  same workflow land on one address; editing produces a different one, so the
  version a schedule points at is still there to compare against."
  [steps]
  (str "sha256:" (sha256-hex (pr-str (routine/canonical steps)))))

(def schedule-kinds
  "The one schedule shape, named so a client cannot invent a second.

  `:every-minutes` and nothing else. A cron expression would be a small
  language to parse, to validate, and to explain in a refusal, and none of the
  work this is for needs one: 'check the inbox every 30 minutes' is the shape,
  and a person who wants 09:00 on Tuesdays is describing an appointment, which
  this application already has in `scheduler`."
  #{:every-minutes})

(def min-schedule-minutes
  "Below this a schedule is a loop with extra steps. A Bot turn costs a model
  call, and `may-fire?` already refuses to overlap runs, so a one-minute
  schedule would mostly measure how long the last run took."
  5)

(defn schedule*
  "Validate a schedule, or nil for one that only runs when asked."
  [spec]
  (when spec
    (let [kind (keyword (or (:kind spec) (:schedule/kind spec)))
          minutes (long (or (:every-minutes spec) (:schedule/every-minutes spec) 0))]
      (when-not (contains? schedule-kinds kind)
        (throw (ex-info "対応していない schedule です。"
                        {:type :routine/invalid-schedule :kind kind})))
      (when (< minutes min-schedule-minutes)
        (throw (ex-info (str "schedule は " min-schedule-minutes "分以上にしてください。")
                        {:type :routine/invalid-schedule :minutes minutes})))
      {:schedule/kind kind :schedule/every-minutes minutes})))

(defn- due?
  "Has enough time passed since this routine last ran?

  A routine that has never run is due the moment it is scheduled — the person
  who set it up asked for it to happen, and making them wait a full interval to
  find out whether it works is how a broken routine stays undiscovered."
  [r now]
  (when-let [s (:routine/schedule r)]
    (if-let [last-run (:routine/last-run-at r)]
      (try
        (>= (.toMinutes (java.time.Duration/between
                         (java.time.Instant/parse last-run)
                         (java.time.Instant/parse now)))
            (:schedule/every-minutes s))
        ;; An unparseable timestamp is a stored value this build does not
        ;; understand, and treating it as 'due' would fire on every tick.
        (catch Exception _ false))
      true)))

(defn- routine-by-id [routine-id]
  (get-in (snapshot) [:routines routine-id]))

(defn- owned-routine!
  "The routine, or a refusal. Ownership is the BOT's — a routine has no
  separate owner, because one that could outlive its Bot's grant would be a
  second place authority is written down."
  [session bot-id routine-id]
  (owned! session bot-id)
  (let [r (routine-by-id routine-id)]
    (when-not (and r (= bot-id (:routine/bot r)))
      (throw (ex-info "routine が見つかりません。"
                      {:type :routine/not-found :routine routine-id})))
    r))

(defn- routine-state
  "The three facts `routine_core` decides from.

  `held-run?` and `active-run?` are the BOT's, not a per-routine pair: a
  routine runs AS its Bot, through the same conversation and the same approval
  cards, so a Bot with a held write is a Bot whose routines are waiting too.
  Tracking a second copy would let the two disagree, and the copy that said
  'idle' is the one a schedule would believe."
  [configuration r b did]
  (let [p (presence (:bot/id b) (connected-providers did))]
    {:held-run? (:held-run? p)
     :active-run? (:active-run? p)
     :admitted (routine/admitted-steps r b
                                       (connectors/catalog-rows configuration)
                                       (connected-connectors configuration did))}))

(defn- public-routine [configuration r b did]
  (let [state (routine-state configuration r b did)]
    {:id (:routine/id r)
     :bot (:routine/bot r)
     :name (:routine/name r)
     :address (:routine/address r)
     :steps (mapv (fn [s] {:tool (:step/tool s)
                           :effect (name (:step/effect s))
                           :intent (:step/intent s)})
                  (:routine/steps r))
     :admitted-steps (count (:admitted state))
     :enabled? (:routine/enabled? r)
     :schedule (:routine/schedule r)
     :status (name (routine/status r b state))
     :stale? (routine/stale? r b state)
     :may-start? (routine/may-start? r b state)
     :created-at (:routine/created-at r)
     :last-run-at (:routine/last-run-at r)}))

(defn routines
  "This Bot's routines, newest first."
  [configuration session bot-id]
  (let [b (owned! session bot-id)
        did (identity/session-did session)]
    (->> (vals (:routines (snapshot)))
         (filter #(= bot-id (:routine/bot %)))
         (sort-by :routine/created-at #(compare %2 %1))
         (mapv #(public-routine configuration % b did)))))

(defn record-routine!
  "Keep what this Bot just did, as a routine.

  The steps come from the TRACE — the calls that executed — not from the
  transcript's prose and not from anything the model offered to do. `intent` is
  the person's, taken once for the whole routine, because the thing they are
  naming is the job rather than each call inside it."
  [configuration session bot-id {:keys [name intent schedule]}]
  (let [b (owned! session bot-id)
        entries (trace-of bot-id)]
    (when (empty? entries)
      (throw (ex-info "まだ何も実行していないので routine にできません。"
                      {:type :routine/no-demonstration :bot bot-id})))
    (when (>= (count (filter #(= bot-id (:routine/bot %))
                             (vals (:routines (snapshot)))))
              max-routines)
      (throw (ex-info "この Bot の routine が上限に達しています。"
                      {:type :routine/too-many :bot bot-id})))
    (let [steps (routine/from-tool-calls
                 (map (fn [e] {:tool (:trace/tool e)
                               :effect (:trace/effect e)
                               :intent (str intent)})
                      entries))
          r (routine/routine {:id (new-id "routine")
                              :bot bot-id
                              :name name
                              :steps steps
                              :address (address steps)
                              :enabled? true
                              :schedule (schedule* schedule)
                              :created-at (store/now)})]
      (transact! assoc-in [:routines (:routine/id r)] r)
      ;; The demonstration has been kept; keeping it a second time would append
      ;; the same calls to the next routine as well.
      (transact! update :traces dissoc bot-id)
      (public-routine configuration r b (identity/session-did session)))))

(defn update-routine!
  "Enable, disable, rename, or re-schedule.

  The STEPS are not editable here. A routine whose steps changed is a different
  routine — it has a different address — and editing them in place would leave
  a schedule pointing at something nobody demonstrated."
  [configuration session bot-id routine-id attrs]
  (let [b (owned! session bot-id)
        existing (owned-routine! session bot-id routine-id)
        merged (cond-> existing
                 (contains? attrs :name) (assoc :routine/name (:name attrs))
                 (contains? attrs :enabled?) (assoc :routine/enabled?
                                                    (boolean (:enabled? attrs)))
                 (contains? attrs :schedule) (assoc :routine/schedule
                                                    (schedule* (:schedule attrs))))]
    (transact! assoc-in [:routines routine-id] merged)
    (public-routine configuration merged b (identity/session-did session))))

(defn forget-routine!
  "Delete a routine. Unlike a Bot this really is deleted: a routine is a
  shortcut, and a disabled shortcut nobody can remove is clutter that looks
  like history."
  [session bot-id routine-id]
  (owned-routine! session bot-id routine-id)
  (transact! update :routines dissoc routine-id)
  {:forgotten true})

(defn- routine-prompt [r]
  (str "保存された routine「" (:routine/name r) "」を実行してください。\n"
       "手順:\n"
       (str/join "\n" (map-indexed (fn [i s]
                                     (str (inc i) ". " (:step/tool s)
                                          " — " (:step/intent s)))
                                   (:routine/steps r)))))

(defn- run-routine!
  "Start one routine as its Bot. The gate is the caller's to choose —
  `may-start?` for a person, `may-fire?` for a schedule — because those differ
  by exactly one fact and the difference is who is watching."
  [configuration b r did]
  (transact! assoc-in [:routines (:routine/id r) :routine/last-run-at] (store/now))
  (append! (:bot/id b) (bot/message {:id (new-id "msg") :bot (:bot/id b)
                                     :role :person
                                     :text (routine-prompt r)
                                     :at (store/now)}))
  (advance! configuration b
            (merge (turn-admission configuration b did)
                   {:id (new-id "run")
                    :messages (transcript configuration b
                                          (conversation (:bot/id b)))
                    :turn-count 0
                    :tool-count 0})))

(defn start-routine!
  "A person running a routine now."
  [configuration session bot-id routine-id]
  (let [b (owned! session bot-id)
        r (owned-routine! session bot-id routine-id)
        did (identity/session-did session)
        state (routine-state configuration r b did)]
    (when-not (routine/may-start? r b state)
      (throw (ex-info (if (routine/stale? r b state)
                        "この routine の手順に、いま使えないツールがあります。"
                        "この routine はいま実行できません。")
                      {:type :routine/refused
                       :routine routine-id
                       :status (name (routine/status r b state))})))
    (run-routine! configuration b r did)
    (public-conversation did bot-id)))

(defn fire-due!
  "The scheduler's side: every routine whose time has come and whose Bot can
  take it.

  `may-fire?` rather than `may-start?` — the one extra refusal is a held run,
  and it is the whole reason an hourly routine that needs an approval does not
  leave a queue of them. Returns what it started and what it skipped, because a
  scheduler that silently does nothing is indistinguishable from one that is
  broken."
  [configuration session now]
  (let [did (identity/session-did session)
        mine (filter #(= (:user-id session) (:bot/owner %))
                     (vals (:bots (snapshot))))
        by-id (into {} (map (juxt :bot/id identity)) mine)]
    (reduce
     (fn [acc r]
       (if-let [b (get by-id (:routine/bot r))]
         (let [state (routine-state configuration r b did)]
           (cond
             (not (due? r now)) acc
             (routine/may-fire? r b state)
             (do (run-routine! configuration b r did)
                 (update acc :started conj (:routine/id r)))
             :else
             (update acc :skipped conj {:routine (:routine/id r)
                                        :status (name (routine/status r b state))})))
         acc))
     {:started [] :skipped []}
     (vals (:routines (snapshot))))))

;; ── handoff ─────────────────────────────────────────────────────────────

(defn hand-off!
  "One Bot giving work to another.

  What crosses is a message and its provenance. What does not cross is any
  part of the sender's grant: `handoff/->request` has no field for it, and the
  target runs the task through `advance!` with ITS OWN tools — the same call
  `send!` makes when a person types. A Bot that could reach a connector by
  asking a Bot that holds it would make every per-Bot grant advisory, and this
  is the one place that could have been arranged."
  [configuration session from-bot-id to-bot-id {:keys [task depth]}]
  (let [source (owned! session from-bot-id)
        target (owned! session to-bot-id)
        did (identity/session-did session)
        context {:source-owner (:bot/owner source)
                 :target-owner (:bot/owner target)
                 :depth (or depth 0)
                 :max-depth handoff/default-max-depth}]
    (when-not (handoff/admitted? source target context)
      (throw (ex-info (cond
                        (= from-bot-id to-bot-id)
                        "Bot は自分自身に引き継げません。"
                        (handoff/budget-exhausted? source target context)
                        "引き継ぎの回数が上限に達しました。"
                        :else "この引き継ぎはできません。")
                      {:type :handoff/refused
                       :from from-bot-id :to to-bot-id})))
    (let [h (handoff/handoff {:id (new-id "handoff")
                              :from from-bot-id
                              :to to-bot-id
                              :task task
                              :depth (handoff/next-depth source target context)
                              :at (store/now)})]
      (transact! update-in [:handoffs to-bot-id]
                 (fn [entries] (vec (take-last max-trace (conj (vec entries) h)))))
      ;; Written into the TARGET's conversation, attributed. A message that
      ;; appeared without saying which Bot put it there is one the person
      ;; cannot audit.
      (append! to-bot-id
               (bot/message {:id (new-id "msg") :bot to-bot-id :role :person
                             :text (str (:bot/name source) " からの引き継ぎ: "
                                        (:handoff/task h))
                             :at (store/now)}))
      (advance! configuration target
                (merge (turn-admission configuration target did)
                       {:id (new-id "run")
                        :messages (transcript configuration target
                                              (conversation to-bot-id))
                        :turn-count 0
                        :tool-count 0}))
      {:handoff (unqualify h)
       :messages (public-conversation did to-bot-id)})))

;; ── the tick ────────────────────────────────────────────────────────────
;;
;; What makes a schedule happen. Everything above it answers "may this run";
;; this answers "who is asking", for the one caller that arrives without a
;; request behind it.
;;
;; ## The authority is found, never minted
;;
;; `fire-due!` needs a session, and a timer has none. The tempting shape is to
;; build one — iterate the Bots, act as their owner — and it is the shape this
;; refuses. A synthesised session is authority that nobody granted, that nobody
;; can see, and that signing out does not take away. So the tick READS the
;; sessions that exist: a person signed in on this machine, that session is
;; live, and it is theirs. If it lapses or they sign out, their schedules stop.
;;
;; That is a real limitation and it is the honest one. `bots.clj`'s own thesis
;; is that a Bot's computer is this machine and 'a Bot does not run while this
;; machine is asleep'; a schedule that also stops thirty days after you last
;; signed in is the same sentence, continued.

(defonce ^:private tick-scheduler (atom nil))

(def default-tick-seconds
  "How often to LOOK. Not how often a routine runs — `may-fire?` and the
  five-minute floor decide that. Looking is a store read, so a wake that
  usually finds nothing is cheap; the interval only bounds how late a due
  routine can be."
  60)

(defn- tick-sessions
  "One live session per person, newest first.

  Per PERSON, not per session: somebody signed in on a laptop and a phone has
  two live sessions and one set of routines, and firing once per session would
  run every schedule twice."
  [configuration]
  (let [enabled? (not (false? (get-in configuration [:bots :tick :enabled?])))]
    (->> (identity/live-sessions)
         (filter (fn [s]
                   (routine/tick-admitted?
                    {:tick-enabled? enabled?
                     :session-live? true
                     :session-kind (or (:kind s) :passkey)})))
         (reduce (fn [acc s]
                   (if (contains? acc (:user-id s)) acc (assoc acc (:user-id s) s)))
                 {})
         vals)))

(defn tick!
  "One pass. Returns what it started and skipped, per person.

  Exceptions are caught per session rather than per pass: one person's expired
  OAuth token must not stop everybody else's schedules, and a timer that dies
  on the first failure is a scheduler that silently stops being one."
  [configuration now]
  (mapv (fn [session]
          (try
            (assoc (fire-due! configuration session now) :user (:user-id session))
            (catch Exception error
              {:user (:user-id session) :started [] :skipped []
               :error (.getMessage error)})))
        (tick-sessions configuration)))

(defn start-tick!
  "Begin looking. Idempotent, and a no-op when the deployment turned it off."
  [configuration]
  (when (and (not @tick-scheduler)
             (not (false? (get-in configuration [:bots :tick :enabled?]))))
    (let [interval (long (or (get-in configuration [:bots :tick :interval-seconds])
                             default-tick-seconds))
          executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
                    (reify java.util.concurrent.ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "cloud-itonami-bot-tick")
                          (.setDaemon true)))))]
      ;; `scheduleWithFixedDelay`, not `atFixedRate`: a pass that takes longer
      ;; than the interval must not have the next one queued behind it. Runs
      ;; are bounded but not instant, and a backlog of ticks would arrive all
      ;; at once the moment a slow pass finished.
      (.scheduleWithFixedDelay
       ^java.util.concurrent.ScheduledExecutorService executor
       ^Runnable (fn [] (try (tick! configuration (store/now))
                             ;; The timer thread must survive anything. An
                             ;; escaping throwable cancels the schedule
                             ;; permanently and silently — the one failure that
                             ;; would leave this looking installed and not be.
                             (catch Throwable _ nil)))
       interval interval java.util.concurrent.TimeUnit/SECONDS)
      (reset! tick-scheduler executor)))
  true)

(defn stop-tick! []
  (when-let [^java.util.concurrent.ScheduledExecutorService executor @tick-scheduler]
    (.shutdownNow executor)
    (reset! tick-scheduler nil))
  true)
