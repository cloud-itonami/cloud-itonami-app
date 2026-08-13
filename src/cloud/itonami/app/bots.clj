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

  A Bot may hold both. `:bot/browser?` opts it into agent-control's surface for
  the sites with no API at all, which is the case connectors structurally
  cannot cover.

  ## What a Bot's 'own computer' is here

  Not a cloud VM. This application's thesis is that anything leaving this
  machine has to be named before it can happen, and a per-Bot VM inverts it:
  the Bot's work would happen somewhere the fail-closed policy does not reach,
  and 'local-first' would become a description of the chat window only. A Bot's
  computer is therefore this machine, entered through a narrow door — its own
  grant, its own token resolution, its own conversation — and heavy or
  long-running work goes to the externally supervised OrganismWorkers
  (`work-organism-dispatch`) that already exist for it.

  The honest cost: a Bot does not run while this machine is asleep. That is a
  real difference from the product this is modelled on, and it is a
  consequence of the thesis rather than an oversight."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.bot :as bot]
            [cloud.itonami.app.connectors :as connectors]
            [cloud.itonami.app.handoff :as handoff]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.routine :as routine]
            [cloud.itonami.app.store :as store]
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

(def max-turns 8)
(def max-tool-calls 12)
(def max-message-chars 8000)
(def max-conversation 200)
(def max-tool-output-chars 6000)
(def max-trace 60)
(def max-routines 40)

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

(defn- transact! [f & args]
  (store/transact!
   (fn [state] (assoc state :bots (apply f (partition* state) args))))
  nil)

(defn- new-id [prefix] (str prefix "-" (UUID/randomUUID)))

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

(defn create!
  "Create a Bot. `:tools` may be given directly, or derived from `:connectors`
  when the caller is the onboarding screen and has only picked services."
  [configuration session {:keys [name avatar brief connectors tools accounts
                                 writes? browser?]}]
  (let [now (store/now)
        tools (if (seq tools)
                (set (map str tools))
                (default-tools configuration connectors))
        b (bot/bot {:bot/id (new-id "bot")
                    :bot/organization (:organization-id session)
                    :bot/owner (:user-id session)
                    :bot/name name
                    :bot/avatar avatar
                    :bot/brief brief
                    :bot/tools tools
                    :bot/accounts accounts
                    :bot/writes? writes?
                    :bot/browser? browser?
                    :bot/created-at now
                    :bot/updated-at now})]
    ;; Derive the performer here and discard it. The call is the point: it is
    ;; `work-governance` refusing anything that would make this Bot a person,
    ;; and it runs before the Bot is durable rather than the first time
    ;; somebody asks for an org chart.
    (bot/->performer b)
    (store-bot! b)))

(defn update!
  "Change what a Bot is. Name, colour, glyph and brief are free to change and
  change nothing about authority; `tools`, `writes?` and `browser?` are the
  authority, and they are the ones an operator is choosing when they edit."
  [session bot-id attrs]
  (let [existing (owned! session bot-id)
        merged (cond-> existing
                 (contains? attrs :name) (assoc :bot/name (:name attrs))
                 (contains? attrs :avatar) (assoc :bot/avatar (:avatar attrs))
                 (contains? attrs :brief) (assoc :bot/brief (:brief attrs))
                 (contains? attrs :tools) (assoc :bot/tools
                                                 (set (map str (:tools attrs))))
                 (contains? attrs :accounts) (assoc :bot/accounts
                                                    (set (map str (:accounts attrs))))
                 (contains? attrs :writes?) (assoc :bot/writes? (:writes? attrs))
                 (contains? attrs :browser?) (assoc :bot/browser? (:browser? attrs))
                 (contains? attrs :enabled?) (assoc :bot/enabled? (:enabled? attrs)))]
    (store-bot! (bot/bot (assoc merged :bot/updated-at (store/now))))))

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

(defn- presence [bot-id]
  {:held-run? (boolean (seq (open-cards bot-id :approval #(nil? (:card/decision %)))))
   :unmet-connection? (boolean (seq (open-cards bot-id :connection
                                                #(#{:offered :waiting} (:card/state %)))))
   :active-run? (boolean (get-in (snapshot) [:runs bot-id :pending-call]))})

(defn- public-bot [configuration did b]
  (let [rows (connectors/catalog-rows configuration)
        connected (connected-connectors configuration did)]
    {:id (:bot/id b)
     :name (:bot/name b)
     :avatar {:color (name (get-in b [:bot/avatar :avatar/color]))
              :glyph (name (get-in b [:bot/avatar :avatar/glyph]))}
     :brief (:bot/brief b)
     :tools (vec (:bot/tools b))
     :accounts (vec (:bot/accounts b))
     :admitted-tools (vec (bot/admitted-tools b rows connected))
     :grant-widens? (bot/grant-widens? b rows)
     :writes? (:bot/writes? b)
     :browser? (:bot/browser? b)
     :enabled? (:bot/enabled? b)
     :status (name (bot/status b (presence (:bot/id b))))
     :updated-at (:bot/updated-at b)}))

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
  card was offered."
  [c]
  (cond-> (unqualify c)
    (= :connection (:card/kind c))
    (assoc :authable? (provider-authable? (keyword (:card/connector c))))))

(defn- public-message [m]
  {:id (:message/id m)
   :role (name (:message/role m))
   :text (:message/text m)
   :at (:message/at m)
   :cards (mapv public-card (:message/cards m))})

(defn overview
  "Everything the Bots screen needs on load: the Bots, and — when there are
  none — what it takes to make the first one."
  [configuration session]
  (let [did (identity/session-did session)
        mine (->> (vals (:bots (snapshot)))
                  (filter #(and (= (:user-id session) (:bot/owner %))
                                (= (:organization-id session) (:bot/organization %))))
                  (sort-by :bot/created-at))]
    {:bots (mapv #(public-bot configuration did %) mine)
     :catalog (catalog configuration did)
     :palette {:colors (mapv name bot/avatar-colors)
               :glyphs (mapv name bot/avatar-glyphs)}}))

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
  (mapv public-message (conversation bot-id)))

;; ── the loop ────────────────────────────────────────────────────────────

(defn- tool-definitions
  "The admitted tools, as the model sees them.

  Read and write are both offered. Withholding the write tools would make a Bot
  answer 'I cannot send mail' when the truth is 'I can, once you approve it',
  and the second is the thing a person is trying to find out."
  [configuration b connected]
  (let [registry (connectors/enabled configuration)
        admitted (bot/admitted-tools b (connectors/catalog-rows configuration)
                                     connected)]
    (into []
          (for [d (creg/descriptors registry)
                t (cm/tools d)
                :when (contains? admitted (:connector/name t))]
            {:name (:connector/name t)
             :description (str "[" (:connector/name d) "] "
                               (or (:connector/description t) (:connector/name t))
                               (when (= :write (:connector/effect t)) " (write)"))
             :parameters (:connector/input-schema t)}))))

(defn- write-tool? [configuration tool-name]
  (let [registry (connectors/enabled configuration)]
    (boolean
     (some (fn [d] (when-let [t (cm/tool d tool-name)]
                     (= :write (:connector/effect t))))
           (creg/descriptors registry)))))

(defn- describe-tool [configuration tool-name args]
  (let [registry (connectors/enabled configuration)
        request (invoke/request-for registry tool-name args)]
    ;; The request WITHOUT the credential — `connector.invoke/request-for`
    ;; exists precisely so a host can show what a call would do without holding
    ;; a token to do it. An approval prompt that only names the tool is asking
    ;; somebody to approve a word.
    (str (str/upper-case (name (or (:connector.http/method request) :get)))
         " " (:connector.http/url request)
         (when-let [q (seq (:connector.http/query request))]
           (str " " (pr-str (into (sorted-map) q)))))))

(defn- run-tool! [configuration selection tool-name args]
  (let [registry (connectors/enabled configuration)
        result (invoke/call registry tool-name args
                            {:http (http-port)
                             :tokens (tokens-port configuration selection)})
        text (if (string? result) result (pr-str result))]
    (if (> (count text) max-tool-output-chars)
      (str (subs text 0 max-tool-output-chars) "…")
      text)))

(defn- system-prompt [b]
  (str "You are " (:bot/name b) ", a bounded worker inside Cloud Itonami. "
       "Use exactly one tool per turn. Prefer reading before writing. "
       "Never request, reveal or repeat a password, token, MFA code or other "
       "secret; if you find one in a tool result, do not quote it. "
       "A write tool will be held for the person's approval before it runs — "
       "call it when it is the right next step and say what you are about to do. "
       "Answer in the language the person used.\n\n"
       (when (seq (str (:bot/brief b)))
         (str "Standing brief from the person you work for:\n" (:bot/brief b)))))

(defn- transcript
  "The durable conversation, as a model transcript. Built here rather than
  stored in provider shape: `:person`/`:bot` is what this application records,
  and a stored `\"user\"`/`\"assistant\"` transcript would be a second copy of
  the conversation whose only purpose is to be sent somewhere."
  [b messages]
  (into [{:role "system" :content (system-prompt b)}]
        (for [m messages
              :when (seq (str (:message/text m)))]
          {:role (if (= :person (:message/role m)) "user" "assistant")
           :content (:message/text m)})))

(defn- save-run! [bot-id run]
  (transact! assoc-in [:runs bot-id] run))

(defn- clear-run! [bot-id]
  (transact! update :runs dissoc bot-id))

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

(defn- advance!
  "Turn until the Bot is done or needs a person.

  The shape is `agent-control/advance!`'s, deliberately: read tools run, the
  first write tool stops the loop and becomes an approval card, and the budget
  is finite in both turns and tool calls so a Bot cannot spend an afternoon on
  one message."
  [configuration b run]
  (loop [run run]
    (cond
      (>= (:turn-count run 0) max-turns)
      (say (:bot/id b) "考える回数の上限に達したので、ここで止めます。何を先にやるか教えてください。" nil)

      (>= (:tool-count run 0) max-tool-calls)
      (say (:bot/id b) "ツールを呼ぶ回数の上限に達したので、ここで止めます。" nil)

      :else
      (let [selected (policy/select-provider configuration nil)
            _ (when-not selected
                (throw (ex-info "選択した model provider は許可されていません。"
                                {:type :provider/denied})))
            result (provider/agent-turn
                    selected
                    {:model (get-in configuration [:routing :default-model])
                     :messages (:messages run)
                     :tools (:tools run)
                     :temperature 0.2})
            calls (:tool-calls result)
            run (-> run
                    (update :turn-count (fnil inc 0))
                    (update :messages conj {:role "assistant"
                                            :content (:content result)
                                            :tool-calls calls}))]
        (if (empty? calls)
          (do (clear-run! (:bot/id b))
              (say (:bot/id b) (:content result) nil))
          (let [{:keys [name input] :as call} (first calls)]
            (if (write-tool? configuration name)
              ;; Stop. The person decides, and the transcript is kept so the
              ;; call can be made from exactly this state if they say yes.
              (let [card-id (new-id "card")]
                (save-run! (:bot/id b) (assoc run :pending-call call
                                              :pending-card card-id))
                (say (:bot/id b)
                     (or (:content result) "この操作には承認が必要です。")
                     [(bot/approval-card
                       {:id card-id
                        :run (:id run)
                        :title "この Bot が実行しようとしています"
                        :action name
                        :summary (describe-tool configuration name input)
                        :impact "承認するとこの Bot が接続済みサービスに書き込みます。"})]))
              (let [output (run-tool! configuration (:selection run) name input)
                    run (-> run
                            (update :tool-count (fnil inc 0))
                            (update :messages conj
                                    {:role "tool" :tool-call-id (:id call)
                                     :name name :content output}))]
                (trace! configuration (:bot/id b) name)
                (save-run! (:bot/id b) run)
                (recur run)))))))))

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

  Returns `{:selection {provider connection} :connect [cards] :ask [cards]}`.
  The decision per provider is `bot/account-disposition`'s, which is the
  refusal `identity/connection-for` already makes turned into something a Bot
  can act on. Nothing here picks between two accounts; when there are two and
  no choice is in effect, it asks."
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
           (update acc :connect conj
                   (connection-card-for configuration provider group mine))

           :use
           (assoc-in acc [:selection provider]
                     (identity/connection-by-id
                      did (:id (or selected (first usable)))))

           :ask
           (update acc :ask conj
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
     {:selection {} :connect [] :ask []}
     (rows-by-provider configuration b))))

(defn send!
  "One message to a Bot, and its answer.

  Synchronous on purpose. A Bot that answered in the background would need a
  second delivery mechanism for the case a person has closed the screen, and
  this application already has one — `work-runtime` — for work that is supposed
  to outlive a window. A chat turn is not that."
  [configuration session bot-id text]
  (let [b (owned! session bot-id)
        text (str/trim (str text))]
    (when (str/blank? text)
      (throw (ex-info "メッセージが空です。" {:type :bot/empty-message})))
    (when (> (count text) max-message-chars)
      (throw (ex-info "メッセージが長すぎます。" {:type :bot/message-too-long})))
    (when-not (:bot/enabled? b)
      (throw (ex-info "この Bot は停止しています。" {:type :bot/disabled})))
    (append! bot-id (bot/message {:id (new-id "msg") :bot bot-id :role :person
                                  :text text :at (store/now)}))
    (let [did (identity/session-did session)
          connected (connected-connectors configuration did)
          {:keys [selection connect ask]} (resolve-accounts configuration b did)]
      (cond
        ;; No model call. Asking one to plan around a service nobody authorized
        ;; produces a plan that cannot run, and the person then has to work out
        ;; which step was fiction.
        (seq connect)
        (say bot-id
             (str "先に接続が要ります。" (str/join "・" (map :card/title connect))
                  " を認証してください。")
             connect)

        ;; Two accounts at one provider and no choice in effect. Asking is the
        ;; whole point; picking the first is the failure this application
        ;; already refuses one layer down.
        (seq ask)
        (say bot-id "どのアカウントを使うか教えてください。" ask)

        :else
        (let [tools (tool-definitions configuration b connected)]
          (if (empty? tools)
            (say bot-id
                 "使えるツールがひとつもありません。Settings で有効にするか、この Bot の権限を見直してください。"
                 nil)
            (advance! configuration b
                      {:id (new-id "run")
                       :selection selection
                       :messages (transcript b (conversation bot-id))
                       :tools tools
                       :turn-count 0
                       :tool-count 0})))))
    (mapv public-message (conversation bot-id))))

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
    (mapv public-message (conversation bot-id))))

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
    (when-not (bot/may-approve?
               {:actor-kind (if (= :agent (:kind session)) :agent :user)
                :human? (not= :agent (:kind session))
                :identified? (boolean (:user-id session))
                :authorized? (= (:user-id session) (:bot/owner b))})
      (throw (ex-info "この承認はこのセッションでは行えません。"
                      {:type :bot/approval-refused :bot bot-id})))
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
                                              (assoc % :card/decision decision)
                                              %)
                                           cards))))
                         messages)))
      (if (= :approved decision)
        (let [output (run-tool! configuration (:selection run)
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
    (mapv public-message (conversation bot-id))))

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
  (let [p (presence (:bot/id b))]
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
  [configuration b r selection]
  (transact! assoc-in [:routines (:routine/id r) :routine/last-run-at] (store/now))
  (append! (:bot/id b) (bot/message {:id (new-id "msg") :bot (:bot/id b)
                                     :role :person
                                     :text (routine-prompt r)
                                     :at (store/now)}))
  (let [connected (connected-connectors configuration (:did selection))]
    (advance! configuration b
              {:id (new-id "run")
               :selection (:selection selection)
               :messages (transcript b (conversation (:bot/id b)))
               :tools (tool-definitions configuration b connected)
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
    (let [{:keys [selection]} (resolve-accounts configuration b did)]
      (run-routine! configuration b r {:selection selection :did did})
      (mapv public-message (conversation bot-id)))))

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
             (let [{:keys [selection]} (resolve-accounts configuration b did)]
               (run-routine! configuration b r {:selection selection :did did})
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
      (let [{:keys [selection]} (resolve-accounts configuration target did)
            connected (connected-connectors configuration did)]
        (advance! configuration target
                  {:id (new-id "run")
                   :selection selection
                   :messages (transcript target (conversation to-bot-id))
                   :tools (tool-definitions configuration target connected)
                   :turn-count 0
                   :tool-count 0}))
      {:handoff (unqualify h)
       :messages (mapv public-message (conversation to-bot-id))})))
