(ns cloud.itonami.app.mcp
  "One MCP dispatcher used by stdio and hosted Streamable HTTP.

  This is an adapter, not a second tool layer. `cloud.itonami.app.fleet` already
  owns the descriptors and the behaviour — `fleet/tools`, `fleet/search-tool`,
  `fleet/call-tool` — because the in-app agent needed them first. What was
  missing was a way for a client that is not this app's own agent loop to reach
  them, so this namespace translates: `fleet/tools` becomes an MCP manifest via
  `mcp.model`, `mcp.execute/handle` does the JSON-RPC dispatch, and an ITool
  port sends `tools/call` back to the same two functions the agent calls.

  Stdio remains the local-process trust boundary. `cloud.itonami.app.server`
  also hosts this pure dispatcher at `/mcp`; that adapter owns bearer/OAuth,
  Origin, content negotiation and protocol-version checks rather than teaching
  the tool layer about HTTP.

  Scope: the fleet capability, the 事業 (business) surface, plus funding and
  settlement when — and only when — a real app session resolves. Browser and computer tools are not exposed, and
  that is a boundary rather than an omission — they take a screenshot of whatever
  the operator is looking at, and their approval path in agent-control checks the
  frontmost application between approval and action. Neither survives translation
  to a protocol whose consent model belongs to the client. Raw mail, calendar,
  drive and chat connector tools are not exposed either. The Bot adapter is the
  bounded exception: it carries an app agent session to `/api/agent-bots`, can
  submit work to a Bot whose grants were configured by a human, and can decide
  only the local-shell, workspace/Git, or Gmail-send operation covered by that
  Bot's human-enabled omakase delegation.

  The 事業 surface reaches around nothing either: `business-tools` is an HTTP
  client of THIS APP's own /api/business routes, carrying an agent session
  token, so every refusal those routes make is the refusal an agent sees. It
  goes over HTTP rather than calling `business/bind!` in-process because
  `store/state` is read once per process — an MCP server writing in-process
  would write onto a snapshot taken when it started and the resident app's next
  transact! would drop it. `fleet` stays in-process because it touches no store
  at all (measured: no `store/` call in fleet.clj).

  Funding and settlement are the exception that proves that rule rather than
  breaking it. `cloud.itonami.app.payment-tools` does not reach around the
  session — it RESOLVES one from a token the operator placed in the environment
  or the Keychain, requires that its user has enrolled a Passkey, and then acts
  as that session with the same organization scoping and the same refusals as
  the HTTP surface. With no such token there are no funding or payment tools in
  the manifest at all.

  And the gate itself is never exposed: `approve/start` and `approve/finish`
  have no tools, because consent is a WebAuthn user-verifying assertion and
  there is none an agent could produce. An agent may ask (`payment_review`, which
  runs every deterministic refusal), record, carry out what a human already
  approved (`payment_commit`), or record a decline. It cannot approve.

  `agent-control` is not required here even though it owns the same capability
  gate, because it requires `agent.run` and `hil.core`, which resolve only under
  the `:dev` alias's west sibling layout — so requiring it would make this
  namespace unloadable in a plain `-M:test` run. `fleet/tools` lives in fleet for
  that exact reason (see the comment above it). The gate is re-read here instead,
  which is a duplication worth naming."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.domain-tools :as domain-tools]
            [cloud.itonami.app.business-tools :as business-tools]
            [cloud.itonami.app.bot-tools :as bot-tools]
            [cloud.itonami.app.fleet :as fleet]
            [cloud.itonami.app.payment-tools :as payment-tools]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.tenant-tools :as tenant-tools]
            [mcp.execute :as execute]
            [mcp.model :as model]
            [mcp.ports :as ports])
  (:import [java.io BufferedReader]))

(def server-name "cloud-itonami-fleet")
(def server-version "1")
(def latest-protocol-version "2025-11-25")
(def supported-protocol-versions
  #{"2025-03-26" "2025-06-18" latest-protocol-version})

(defn fleet-enabled?
  "The same gate `agent-control/available-tools` applies to `fleet/tools`:
  the configured default, overridden by the operator's persisted toggle.

  Kept in step with `agent-control/settings` by hand — see this namespace's
  docstring for why it cannot call it. Only the fleet branch is mirrored, so the
  drift surface is one flag rather than the whole settings map."
  [configuration]
  (let [saved (get-in (store/snapshot)
                      [:agent-control :settings :fleet :enabled?])]
    (if (some? saved)
      (true? saved)
      (true? (get-in configuration [:agent-control :fleet :enabled?])))))

(defn published-tools
  "The tool descriptors this server currently offers.

  Each group is gated by its own precondition and contributes nothing when that
  precondition is unmet — an operator who has not enabled the fleet, or has not
  bound a session, sees a server without those tools rather than tools that fail
  on call. Publishing a tool that is certain to refuse is a worse contract: it
  invites a client to try, and it says nothing about why."
  [configuration]
  (cond-> []
    (fleet-enabled? configuration) (into fleet/tools)
    (business-tools/available? configuration) (into business-tools/tools)
    (bot-tools/available? configuration) (into bot-tools/tools)
    (tenant-tools/available? configuration) (into tenant-tools/tools)
    (payment-tools/available? configuration) (into payment-tools/tools)
    (domain-tools/available? configuration) (into domain-tools/tools)))

(defn manifest
  [configuration]
  (reduce (fn [m {:keys [name description parameters]}]
            (model/add-tool m name {:description description
                                    :input-schema parameters}))
          (model/server server-name server-version)
          (published-tools configuration)))

(defn- keywordize
  "JSON gives string keys; the fleet tools read keyword keys. Only the top level
  is converted: both tools take flat argument maps, and a deep walk would also
  rewrite values the tools pass through."
  [args]
  (into {} (map (fn [[k v]] [(keyword k) v])) args))

(defn invoke
  "Run one fleet tool. A thrown `ex-info` becomes an `{:error …}` result rather
  than escaping: `mcp.execute` marks such a result `isError` and the client sees
  the reason, whereas an exception would take down the stdio loop mid-session.
  The `:type` is kept because fleet distinguishes \"no such actor\" from \"that
  actor has no endpoint\", and a caller acts differently on each."
  [configuration tool-name args]
  (let [input (keywordize args)
        payment-tool? (contains? (into #{} (map :name) payment-tools/tools)
                                 tool-name)
        domain-tool? (domain-tools/tool? tool-name)]
    (try
      (cond
        ;; Re-checked at call time, not trusted from the manifest: a session can
        ;; expire or be revoked mid-conversation, and a client that cached the
        ;; tool list would otherwise keep calling a surface that is no longer
        ;; bound to anyone.
        payment-tool? (payment-tools/call-tool configuration tool-name input)
        domain-tool? (domain-tools/call-tool configuration tool-name input)
        (bot-tools/tool? tool-name)
        (bot-tools/call-tool configuration tool-name input)
        (business-tools/tool? tool-name)
        (business-tools/call-tool configuration tool-name input)
        (tenant-tools/tool? tool-name)
        (tenant-tools/call-tool configuration tool-name input)
        (= "fleet_search" tool-name) (fleet/search-tool input)
        (= "fleet_call" tool-name)
        (if (fleet-enabled? configuration)
          (fleet/call-tool input)
          {:error "fleet capability is disabled" :type "fleet/disabled"})
        :else {:error (str "unknown tool: " tool-name)
               :type "mcp/unknown-tool"})
      (catch clojure.lang.ExceptionInfo error
        {:error (.getMessage error)
         :type (some-> (:type (ex-data error)) str (str/replace #"^:" ""))})
      (catch Exception error
        {:error (or (.getMessage error) (str (class error)))
         :type "mcp/error"}))))

(defn ports
  "ITool delegating to `invoke`. No ITransport: this server makes no outbound
  JSON-RPC calls, and `mcp.execute` only reaches for one if a tool asks it to."
  [configuration]
  {:tool (reify ports/ITool
           (invoke [_ tool-name args] (invoke configuration tool-name args)))})

(defn respond
  "The JSON-RPC response for one request, or nil when none is owed.

  A JSON-RPC notification has no `id`, and the spec forbids replying to one.
  MCP clients send `notifications/initialized` right after `initialize`, so a
  server that answered every message would put an unsolicited error on the wire
  during the handshake."
  ([configuration request] (respond configuration request nil))
  ([configuration request protocol-version]
   (when (contains? request "id")
     (let [response (execute/handle (ports configuration)
                                    (manifest configuration) request)]
       (if (= "initialize" (get request "method"))
         (assoc-in response ["result" "protocolVersion"]
                   (or protocol-version latest-protocol-version))
         response)))))

(defn- write-line! [writer value]
  (.write writer (json/write-str value))
  (.write writer "\n")
  (.flush writer))

(defn serve!
  "Read newline-delimited JSON-RPC from `reader`, write responses to `writer`.

  Returns when the stream ends. A line that is not JSON gets a -32700 parse
  error with a null id, which is what the spec asks for and is more useful than
  a silent drop when a client's framing is wrong."
  [configuration ^BufferedReader reader writer]
  (loop []
    (when-let [line (.readLine reader)]
      (if (str/blank? line)
        (recur)
        (do
          (if-let [request (try (json/read-str line)
                                (catch Exception _ nil))]
            (when-let [response (respond configuration request)]
              (write-line! writer response))
            (write-line! writer {"jsonrpc" "2.0" "id" nil
                                 "error" {"code" -32700
                                          "message" "parse error"}}))
          (recur))))))

(defn -main [& _]
  (let [configuration (config/load-config)]
    (try
      (serve! configuration
              (BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))
              (java.io.OutputStreamWriter. System/out "UTF-8"))
      ;; Keychain reads use futures to drain the `security` subprocess. Their
      ;; agent executor otherwise keeps a finished stdio server alive until the
      ;; pool's idle timeout, even though the MCP client has closed stdin.
      (finally (shutdown-agents)))))
