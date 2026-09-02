(ns cloud.itonami.app.app-client
  "Talking to the running server as an agent session.

  One expression of it, used by `cli` and by `mcp`. Two spellings of one rule is
  how they drift — measured on 2026-07-31, when `require-passkey!` learned about
  agent sessions and `payment-tools/session` did not because it asked the same
  question by a different name.

  ## Why a client and not the store

  `store/state` is `(defonce state (atom (load-state)))` — read once when a
  process starts and never re-read. A second process holding the same data
  directory therefore reads a snapshot frozen at ITS start and writes on top of
  whatever the server has done since. Reads go stale and writes are lost, in
  both directions, with no error.

  That is not a hypothetical for the MCP server: it runs as its own process
  (`-M:mcp`) beside the resident app, and its funding and settlement tools
  write. So anything that touches state goes over HTTP to the process that owns
  the store.

  ## What is NOT a client of this

  `fleet/search-tool` and `fleet/call-tool` read the shipped catalog resource
  and touch no store — measured: `fleet.clj` contains no `store/` call at all.
  They stay in-process, because routing a pure read of a resource through HTTP
  would add a running-server requirement to the one capability that does not
  need one."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.agent-session :as agent-session])
  (:import [java.net ConnectException URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      .build))

(def ^:dynamic *environment* #(System/getenv %))
(def ^:dynamic *token*
  "Incoming hosted-MCP bearer token. nil for the stdio server and CLI."
  nil)
(def ^:dynamic *base-url*
  "Resident server address for an HTTP MCP request invoking its own API."
  nil)

(defn remote-api-url
  "The hosted control plane this process was pointed at, or nil for the local
  one.

  Public because `server-process` must not start a local server for a CLI that
  was aimed somewhere else: spawning one would boot a second install against
  this data directory and then send the command to a machine that never saw it."
  []
  (when-let [value (some-> (*environment* "CLOUD_ITONAMI_API_URL") str/trim not-empty)]
    (let [uri (URI/create value)
          scheme (.getScheme uri)
          host (.getHost uri)
          loopback? (contains? #{"localhost" "127.0.0.1" "::1"} host)]
      (when-not (or (= "https" scheme) (and (= "http" scheme) loopback?))
        (throw (ex-info "CLOUD_ITONAMI_API_URLはHTTPSまたはloopbackを指定してください"
                        {:type :app-client/insecure-api-url})))
      (str/replace value #"/+$" ""))))

(defn base-url
  "The address the server actually binds, NOT `:public-origin`.

  `:public-origin` defaults to `http://localhost:1338` in the shipped config, so
  preferring it sends every command to whatever is listening on 1338 — which, on
  a machine running a second install on another port, is a different store than
  the data directory the token was read from. Measured while building the CLI: a
  probe server on 1351 was enrolled against and the request went to 1338.

  `:public-origin` is what a browser is told the app is called. This connects
  directly, so the bound host and port are the truth."
  [configuration]
  (or *base-url*
      (remote-api-url)
      (str "http://" (get-in configuration [:server :host])
           ":" (get-in configuration [:server :port]))))

(defn token
  "The agent-session token, from the environment or the Keychain."
  [configuration]
  (or *token* (agent-session/session-token configuration)))

(defn call
  "One request. Returns `{:status :body}`; throws only if the server cannot be
  reached at all, which is a different failure from a refusal and should not be
  reported as one."
  [configuration method path {:keys [body token timeout-seconds]}]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str (base-url configuration) path)))
                    (.timeout (Duration/ofSeconds (long (or timeout-seconds 30))))
                    (.header "Content-Type" "application/json"))]
    (when token
      (.header builder "Authorization" (str "Bearer " token)))
    (let [built (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))]
      (try
        (let [response (.send client (.build built)
                              (HttpResponse$BodyHandlers/ofString))]
          {:status (.statusCode response)
           :body (try (json/read-str (.body response) :key-fn keyword)
                      (catch Exception _ {:raw (.body response)}))})
        (catch ConnectException _
          (throw (ex-info (str "cloud-itonami-app に接続できません: "
                               (base-url configuration))
                          {:type :app-client/unreachable})))
        (catch java.io.IOException e
          (throw (ex-info (str "cloud-itonami-app への通信に失敗しました: "
                               (ex-message e))
                          {:type :app-client/unreachable})))))))

(defn unwrap
  "The body on success; otherwise throw carrying the server's own message and
  type. The server already says which refusal fired, so restating it here would
  only give the operator a second, vaguer sentence to read."
  [{:keys [status body]}]
  (if (<= 200 status 299)
    body
    (throw (ex-info (or (get-in body [:error :message]) (str "HTTP " status))
                    {:type (or (some-> (get-in body [:error :type]) keyword)
                               :app-client/error)
                     :status status}))))

(defn request!
  "`call` + `unwrap` with the resolved token, refusing early with a message that
  names the fix when no token is configured."
  [configuration method path & [body]]
  (let [t (or (token configuration)
              (throw (ex-info (str "agent session がありません。"
                                   "deps.edn に :cli エイリアスはない。"
                                   "leftover JVM CLI (`cli.clj`) はランパスではない。")
                              {:type :app-client/no-session})))]
    (unwrap (call configuration method path {:body body :token t}))))

(defn request-with-timeout!
  "Like request!, for bounded agent turns which legitimately outlive a normal
  control-plane request. The caller still supplies a finite timeout."
  [configuration method path timeout-seconds & [body]]
  (let [t (or (token configuration)
              (throw (ex-info "agent session がありません。先に auth login を実行してください"
                              {:type :app-client/no-session})))]
    (unwrap (call configuration method path
                  {:body body :token t :timeout-seconds timeout-seconds}))))

(defn available?
  "Whether this process can act: a token exists AND the server accepts it.

  Both halves, because a token that was revoked, expired, or minted against a
  different install is indistinguishable from a good one until something asks
  the server. `/api/agent-session` is the cheapest route that requires exactly
  the same gate as the tools do."
  [configuration]
  (boolean
   (when (token configuration)
     (try (some? (request! configuration :get "/api/agent-session"))
          (catch Exception _ false)))))
