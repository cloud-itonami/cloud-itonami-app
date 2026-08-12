(ns cloud.itonami.app.server
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.kaiyu-local :as kaiyu-local]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.app-client :as app-client]
            [cloud.itonami.app.authority.api :as authority-api]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.canvas :as canvas]
            [cloud.itonami.app.capture :as capture]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.chronicle :as chronicle]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.contracts :as contracts]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.credential-sd-jwt :as credential-sd-jwt]
            [cloud.itonami.app.credential-trust :as credential-trust]
            [cloud.itonami.app.presentation-request :as presentation-request]
            [webauthn.assurance :as credential-assurance]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.domain-verification :as domain-verification]
            [docs.html :as docs-html]
            [cloud.itonami.app.esign :as esign]
            [cloud.itonami.app.esign.retention :as esign-retention]
            [cloud.itonami.app.executor :as executor]
            [cloud.itonami.app.filecoin :as filecoin]
            [cloud.itonami.app.folder-sync :as folder-sync]
            [cloud.itonami.app.fleet :as fleet]
            [cloud.itonami.app.operator :as operator]
            [cloud.itonami.app.pageview :as pageview]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.fax :as fax]
            [cloud.itonami.app.lawfirm :as lawfirm]
            [cloud.itonami.app.kotobase-federation :as kotobase-federation]
            [cloud.itonami.app.loops :as loops]
            [cloud.itonami.app.metrics :as business-metrics]
            [cloud.itonami.app.mcp :as mcp]
            [cloud.itonami.app.oauth-resource :as oauth-resource]
            [cloud.itonami.app.messenger :as messenger]
            [cloud.itonami.app.portfolio :as portfolio]
            [cloud.itonami.app.mail-age-key :as age-key]
            [cloud.itonami.app.mail-authentication :as authentication]
            [cloud.itonami.app.mail-projects :as mail-projects]
            [cloud.itonami.app.project-repository :as project-repository]
            [cloud.itonami.app.project-remote :as project-remote]
            [cloud.itonami.app.project-transfer :as project-transfer]
            [cloud.itonami.app.organism-gateway :as organism-gateway]
            [cloud.itonami.app.organism-messenger-transport :as organism-messenger-transport]
            [cloud.itonami.app.relay :as relay]
            [cloud.itonami.app.repos :as business-repos]
            [cloud.itonami.app.mailbox :as app-mailbox]
            [cloud.itonami.app.mail-account :as mail-account]
            [cloud.itonami.app.mail-send :as mail-send]
            [cloud.itonami.app.mail-sync :as mail-sync]
            [cloud.itonami.app.scheduler :as scheduler]
            [cloud.itonami.app.service :as service]
            [cloud.itonami.app.sites :as sites]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.tenant-connection :as tenant-connection]
            [cloud.itonami.app.tenant-repository :as tenant-repository]
            [cloud.itonami.app.tenant-tools :as tenant-tools]
            [cloud.itonami.app.updater :as updater]
            [cloud.itonami.app.web :as web]
            [cloud.itonami.app.worker :as worker]
            [cloud.itonami.app.work-approval :as work-approval]
            [cloud.itonami.app.work-reconciler :as work-reconciler]
            [cloud.itonami.app.work-runtime :as work-runtime]
            [cloud.itonami.app.workspace :as workspace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream OutputStreamWriter]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defonce server (atom nil))
(defonce ^:private active-config (atom nil))

(defn- read-json [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))]
    (if (str/blank? body) {} (json/read-str body :key-fn keyword))))

(defn- read-governance-body [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))
        content-type (or (.getFirst (.getRequestHeaders exchange)
                                    "Content-Type") "")]
    (if (str/includes? (str/lower-case content-type) "application/edn")
      (if (str/blank? body) {} (edn/read-string body))
      (if (str/blank? body) {} (json/read-str body :key-fn keyword)))))

(defn- symbolic [value]
  (if (string? value) (keyword value) value))

(defn- organization-wire->edn [graph]
  (update
   (update graph :org/performers
           (fn [performers]
             (mapv (fn [performer]
                     (cond-> (update performer :performer/kind symbolic)
                       (:performer/actor performer)
                       (update-in [:performer/actor :actor/kind] symbolic)
                       (:performer/dodaf-types performer)
                       (update :performer/dodaf-types
                               (fn [types] (mapv symbolic types)))))
                   performers)))
   :org/assignments
   (fn [assignments]
     (mapv (fn [assignment]
             (-> assignment
                 (update :org.assignment/roles
                         (fn [roles] (set (map symbolic roles))))
                 (update :org.assignment/status symbolic)))
           assignments))))

(defn- organization-structure-wire->edn [graph]
  (-> graph
      (update :org/units
              (fn [units]
                (mapv #(update % :org.unit/kind symbolic) units)))
      (update :org/roles
              (fn [roles]
                (mapv #(-> %
                           (update :org.role/id symbolic)
                           (update :org.role/capabilities
                                   (fn [capabilities]
                                     (set (map symbolic capabilities)))))
                      roles)))))

(defn- organization-body->edn [graph]
  (-> graph organization-wire->edn organization-structure-wire->edn))

(defn- approval-policy-wire->edn [policy]
  (-> policy
      (update :approval.policy/capability symbolic)
      (update :approval.policy/eligible-roles
              (fn [roles] (set (map symbolic roles))))
      (update :approval.policy/rejection-mode symbolic)))

(defn- webhook-signature [secret ^bytes body]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8)
                               "HmacSHA256"))
    (str "sha256="
         (apply str (map #(format "%02x" (bit-and 0xff %))
                         (.doFinal mac body))))))

(defn- verify-github-webhook! [exchange config body]
  (let [settings (get-in config [:work-governance :github-webhook])
        secret (some-> (:secret-env settings) System/getenv)
        supplied (.getFirst (.getRequestHeaders exchange)
                            "X-Hub-Signature-256")]
    (when-not (and (:enabled? settings) (not (str/blank? secret)))
      (throw (ex-info "GitHub Projects webhook is disabled"
                      {:type :github-projects/webhook-disabled})))
    (when-not (and supplied
                   (MessageDigest/isEqual
                    (.getBytes supplied StandardCharsets/UTF_8)
                    (.getBytes (webhook-signature secret body)
                               StandardCharsets/UTF_8)))
      (throw (ex-info "GitHub webhook signature is invalid"
                      {:type :github-projects/webhook-signature-invalid})))
    true))

(defn- read-json-raw
  "The request body with its keys left alone.

  `read-json` keywordizes every key at every depth, which is right for the
  fixed-shape requests around it and destroys a document payload: a Sheets
  tab is keyed by its id and a cell by `\"[1 1]\"`, and turning those into
  `:plan` and `:[1 1]` loses the only thing that made them addressable."
  [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))]
    (if (str/blank? body) {} (json/read-str body))))

(defn- read-json-limited
  "Read a remote control-plane JSON object without allowing an unbounded body
  to be materialized before tenant budget checks can run."
  [^HttpExchange exchange maximum-bytes key-fn]
  (let [buffer (byte-array 8192)
        output (ByteArrayOutputStream.)]
    (with-open [input (.getRequestBody exchange)]
      (loop [total 0]
        (let [read (.read input buffer)]
          (when-not (neg? read)
            (let [next-total (+ total read)]
              (when (> next-total maximum-bytes)
                (throw (ex-info "request body is too large"
                                {:type :http/payload-too-large
                                 :maximum-bytes maximum-bytes})))
              (.write output buffer 0 read)
              (recur next-total))))))
    (let [body (.toString output StandardCharsets/UTF_8)]
      (if (str/blank? body)
        {}
        (if key-fn
          (json/read-str body :key-fn key-fn)
          (json/read-str body))))))

(defn- send!
  ([exchange status body] (send! exchange status body {}))
  ([^HttpExchange exchange status body headers]
  (let [bytes (.getBytes (json/write-str body) StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json; charset=utf-8")
      (.set "Cache-Control" "no-store"))
    (doseq [[header value] headers]
      (.set (.getResponseHeaders exchange) header value))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes)))))

(defn- read-body-bytes
  "The request body as a byte array.

  Every other reader here slurps into a string, which is right for JSON and
  wrong for a PPTX: decoding a zip as UTF-8 and encoding it back does not
  give you the zip."
  ^bytes [^HttpExchange exchange]
  (.readAllBytes (.getRequestBody exchange)))

(defn- send-bytes!
  "A binary response with a filename.

  `filename*=UTF-8''…` as well as the plain form: a Japanese title in a
  Content-Disposition header is not ASCII, and RFC 5987 is how that is said."
  [^HttpExchange exchange media-type filename ^bytes body]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" media-type)
    (.set "Cache-Control" "no-store")
    (.set "Content-Disposition"
          (str "attachment; filename*=UTF-8''"
               (-> (java.net.URLEncoder/encode ^String filename StandardCharsets/UTF_8)
                   (str/replace "+" "%20")))))
  (.sendResponseHeaders exchange 200 (alength body))
  (with-open [out (.getResponseBody exchange)]
    (.write out body)))

(defn- session-cookie [token]
  (str identity/cookie-name "=" token
       "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
       identity/session-seconds))

(defn- expired-session-cookie []
  (str identity/cookie-name "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))

(defn- redirect!
  ([exchange location] (redirect! exchange location {}))
  ([^HttpExchange exchange location headers]
   (doto (.getResponseHeaders exchange)
     (.set "Location" location)
     (.set "Cache-Control" "no-store")
     (.set "Referrer-Policy" "no-referrer"))
   (doseq [[key value] headers]
     (.set (.getResponseHeaders exchange) (name key) (str value)))
   (.sendResponseHeaders exchange 303 -1)
   (.close exchange)))

(defn- send-icon! [^HttpExchange exchange]
  (if-let [resource (io/resource "cloud/itonami/app/icon.png")]
    (let [bytes (with-open [in (io/input-stream resource)
                            out (java.io.ByteArrayOutputStream.)]
                  (io/copy in out)
                  (.toByteArray out))]
      (doto (.getResponseHeaders exchange)
        (.set "Content-Type" "image/png")
        ;; The icon changes only when the manifest's does, and a stale tab icon
        ;; is a poor reason to re-read half a megabyte on every load.
        (.set "Cache-Control" "public, max-age=86400"))
      (.sendResponseHeaders exchange 200 (alength bytes))
      (with-open [out (.getResponseBody exchange)]
        (.write out bytes)))
    (send! exchange 404 {:error "icon-missing"})))

(defn- send-html! [^HttpExchange exchange html]
  ;; 回遊 (local only): every HTML surface this app serves passes through here,
  ;; so the counter lives here rather than in `handler`'s dispatch — one place
  ;; instead of one per route, and the dispatch method is already at the JVM's
  ;; 64 KB ceiling (adding two branches there failed to compile, which is how
  ;; this landed in the right place).
  ;;
  ;; Nothing in cloud.itonami.app.kaiyu-local has a network writer; its own
  ;; test asserts the absence rather than trusting the docstring.
  (kaiyu-local/record-view! (.getPath (.getRequestURI exchange)))
  (let [bytes (.getBytes html StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "text/html; charset=utf-8")
      (.set "Cache-Control" "no-store")
      ;; `img-src 'self'` — and only 'self'.
      ;;
      ;; It was absent, so `default-src 'none'` applied and this page could
      ;; not display an image at all. That made `documents/previewable?`, the
      ;; `/preview` route and the whole allowlist behind it dead code in the
      ;; browser: an uploaded PNG has been offered as a preview and rendered
      ;; as a broken image since the day it shipped. Found while working out
      ;; what a PDF page would need, which is the only reason anybody looked.
      ;;
      ;; `'self'` rather than `data:`, which is the narrower of the two and
      ;; the one ADR-0007 left open. A same-origin path can only reach a
      ;; route on this server, and every route that returns an image requires
      ;; the session and answers through the Drive ACL. `data:` would let any
      ;; string in the page become an image, which is a larger permission for
      ;; no gain here.
      (.set "Content-Security-Policy"
            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'")
      (.set "Permissions-Policy"
            "publickey-credentials-create=(self), publickey-credentials-get=(self)"))
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- send-site-html!
  "Serve authored markup without granting it the application's origin authority.

  A CSP sandbox on the response applies even when the site is opened as a top
  level document. Script stays disabled in this first Sites contract; styles,
  HTTPS images and links are enough for a static project site without exposing
  the Passkey session to authored code."
  [^HttpExchange exchange status html]
  (let [bytes (.getBytes (str html) StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "text/html; charset=utf-8")
      (.set "Cache-Control" "no-store")
      (.set "X-Content-Type-Options" "nosniff")
      (.set "Referrer-Policy" "no-referrer")
      (.set "Content-Security-Policy"
            (str "sandbox; default-src 'none'; style-src 'unsafe-inline'; "
                 "img-src data: https:; font-src data: https:; "
                 "form-action 'none'; base-uri 'none'; frame-ancestors 'self'")))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- query-params [^HttpExchange exchange]
  (let [query (.getRawQuery (.getRequestURI exchange))]
    (if (str/blank? query)
      {}
      (into {}
            (map (fn [entry]
                   (let [[key value] (str/split entry #"=" 2)]
                     [(keyword (URLDecoder/decode key StandardCharsets/UTF_8))
                      (URLDecoder/decode (or value "") StandardCharsets/UTF_8)])))
            (str/split query #"&")))))

(defn- cookie-value [^HttpExchange exchange cookie-name]
  (some (fn [entry]
          (let [[key value] (str/split (str/trim entry) #"=" 2)]
            (when (= cookie-name key) value)))
        (some-> exchange .getRequestHeaders (.getFirst "Cookie")
                (str/split #";"))))

(defn- origin [config]
  (or (get-in config [:server :public-origin])
      (str "http://" (get-in config [:server :host]) ":"
           (get-in config [:server :port]))))

(defn- rp-id [config]
  (or (get-in config [:server :webauthn-rp-id]) "localhost"))

(defn- bearer-token
  "The token from `Authorization: Bearer …`, or nil.

  Only a non-browser client sends this. A page cannot attach an Authorization
  header to a cross-origin request without a CORS preflight, and this server
  sends no CORS headers, so the browser refuses before the request is made.
  That is what lets the two checks below stand down for a bearer request."
  [^HttpExchange exchange]
  (some-> exchange .getRequestHeaders (.getFirst "Authorization")
          str/trim
          (as-> header (when (str/starts-with? (str/lower-case header) "bearer ")
                         (str/trim (subs header 7))))
          not-empty))

(defn- require-origin!
  "Origin must match — for a cookie-borne request.

  A bearer request has no Origin to check and needs none: the header was set by
  a process that already held the token, not by a browser acting on a page's
  behalf."
  [exchange config]
  (when-not (bearer-token exchange)
    (when-not (= (origin config)
                 (.getFirst (.getRequestHeaders exchange) "Origin"))
      (throw (ex-info "リクエスト元を確認できません。"
                      {:type :identity/invalid-origin})))))

(defn- oauth-scope-for [^HttpExchange exchange]
  (let [method (.getRequestMethod exchange)
        path (.getPath (.getRequestURI exchange))]
    (cond
      (= path "/mcp") "mcp:tools"
      (re-matches #"/v1/tenant-connections/[^/]+/repository/publish" path)
      "repository:write"
      (re-matches #"/v1/tenant-connections/[^/]+/repository" path)
      (if (= method "GET") "repository:read" "repository:write")
      (or (= path "/v1/tenants")
          (str/starts-with? path "/v1/tenant-connections"))
      "tenant:connect"
      :else nil)))

(defn- require-session!
  "The session behind this request, from a bearer token or the cookie.

  Bearer first, so a CLI that also happens to carry a stale browser cookie acts
  as the token it presented rather than as whoever last logged in."
  [exchange]
  (or (some-> (bearer-token exchange) identity/session)
      (when-let [scope (and @active-config (oauth-scope-for exchange))]
        (oauth-resource/session @active-config (bearer-token exchange) scope
                                (oauth-resource/resource-url @active-config)))
      (identity/session (cookie-value exchange identity/cookie-name))
      (throw (ex-info "認証が必要です。" {:type :identity/unauthenticated}))))

(defn- require-app-session! [exchange]
  (identity/require-passkey! (require-session! exchange)))

(defn- require-human-session!
  "`require-app-session!`, minus agent sessions.

  The money surface. `payment-tools` refuses an agent session on purpose
  (ADR-0009): an agent session is rooted in being able to read a file in the
  data directory, and the decision that made that enough was about the business
  and portfolio surface, not funding and settlement.

  That refusal was held in ONE place — the MCP adapter — while these routes went
  on using `require-app-session!`, which passes an agent session. So the moment
  bearer auth landed, a token could reach the money routes over HTTP while the
  MCP surface it was minted for refused it. A boundary enforced in the client
  and not at the route is not a boundary.

  Approval is separate again and stricter still: `approve/finish` needs a
  WebAuthn user-verifying assertion, which nothing here can substitute for."
  [exchange]
  (let [session (require-session! exchange)]
    (when-not (identity/human-session? session)
      (throw (ex-info
              (str "この操作は agent session では実行できません。"
                   "資金・決済面はブラウザの Passkey session が必要です。")
              {:type :identity/agent-session-forbidden})))
    (identity/require-passkey! session)))

(defn- require-csrf-header! [exchange session]
  (when-not (= (:csrf session)
               (.getFirst (.getRequestHeaders exchange) "X-CLOUD-ITONAMI-CSRF"))
    (throw (ex-info "CSRF token が一致しません。"
                    {:type :identity/invalid-csrf}))))

(defn- require-csrf!
  "CSRF token must match — for a cookie-borne request.

  CSRF exists because a browser attaches the cookie by itself. Nothing attaches
  a bearer token by itself, so there is no confused deputy to defend against and
  requiring the header would only mean the CLI has to fetch and echo a value
  that proves nothing."
  [exchange session]
  (when-not (bearer-token exchange)
    (require-csrf-header! exchange session)))

(defn- route-kotobase-federation! [exchange config]
  (let [session (require-human-session! exchange)]
    (require-origin! exchange config)
    (require-csrf! exchange session)
    (send! exchange 200 (kotobase-federation/mint-assertion session))))

(defn- send-empty! [^HttpExchange exchange status headers]
  (doseq [[header value] headers]
    (.set (.getResponseHeaders exchange) header value))
  (.sendResponseHeaders exchange status -1)
  (.close exchange))

(defn- require-mcp-origin! [^HttpExchange exchange configuration]
  (when-let [request-origin (some-> exchange .getRequestHeaders
                                    (.getFirst "Origin") not-empty)]
    (let [allowed (conj (set (get-in configuration [:mcp :allowed-origins]))
                        (origin configuration))]
      (when-not (contains? allowed request-origin)
        (throw (ex-info "MCP Origin is not allowed"
                        {:type :mcp-http/invalid-origin})))))
  true)

(defn- negotiated-mcp-version [^HttpExchange exchange request]
  (let [header (.getFirst (.getRequestHeaders exchange)
                          "MCP-Protocol-Version")
        initialize? (= "initialize" (get request "method"))
        requested (or header
                      (when initialize?
                        (get-in request ["params" "protocolVersion"]))
                      "2025-03-26")]
    (when-not (contains? mcp/supported-protocol-versions requested)
      (throw (ex-info "unsupported MCP protocol version"
                      {:type :mcp-http/unsupported-version
                       :protocol-version requested})))
    requested))

(defn- require-mcp-content! [^HttpExchange exchange request]
  (let [accept (or (.getFirst (.getRequestHeaders exchange) "Accept") "")
        content-type (or (.getFirst (.getRequestHeaders exchange)
                                   "Content-Type") "")
        routed-method (.getFirst (.getRequestHeaders exchange) "Mcp-Method")
        routed-name (.getFirst (.getRequestHeaders exchange) "Mcp-Name")]
    (when-not (and (str/includes? accept "application/json")
                   (str/includes? accept "text/event-stream"))
      (throw (ex-info "MCP Accept must include JSON and event-stream"
                      {:type :mcp-http/not-acceptable})))
    (when-not (str/starts-with? (str/lower-case content-type)
                                "application/json")
      (throw (ex-info "MCP request must be application/json"
                      {:type :mcp-http/unsupported-media-type})))
    ;; 2026 routing headers are accepted now and fail closed when present.
    ;; They remain optional until the release candidate becomes a stable
    ;; protocol version.
    (when (and routed-method (not= routed-method (get request "method")))
      (throw (ex-info "Mcp-Method does not match the JSON-RPC body"
                      {:type :mcp-http/routing-header-mismatch})))
    (when (and routed-name
               (= "tools/call" (get request "method"))
               (not= routed-name (get-in request ["params" "name"])))
      (throw (ex-info "Mcp-Name does not match the tool call"
                      {:type :mcp-http/routing-header-mismatch})))
    true))

(defn- require-mcp-tool-scope! [session request]
  (when-let [granted (:oauth/scopes session)]
    (when (= "tools/call" (get request "method"))
      (let [tool (get-in request ["params" "name"])
            required (cond
                       (= tool "tenant_repository_read") "repository:read"
                       (contains? #{"tenant_repository_write"
                                    "tenant_repository_publish"} tool)
                       "repository:write"
                       (str/starts-with? (or tool "") "tenant_")
                       "tenant:connect"
                       :else nil)]
        (when (and required (not (contains? granted required)))
          (throw (ex-info "OAuth access token lacks the tool scope"
                          {:type :oauth-resource/insufficient-scope
                           :required-scope required}))))))
  true)

(defn- send-mcp-http! [^HttpExchange exchange configuration]
  (try
    (require-mcp-origin! exchange configuration)
    (let [session (require-app-session! exchange)]
      (if-not (= "POST" (.getRequestMethod exchange))
        (send-empty! exchange 405 {"Allow" "POST"})
        (let [request (read-json-limited exchange (* 2 1024 1024) nil)
              _ (require-mcp-content! exchange request)
              _ (require-mcp-tool-scope! session request)
              protocol-version (negotiated-mcp-version exchange request)]
          (binding [app-client/*token* (bearer-token exchange)
                    app-client/*base-url*
                    (str "http://127.0.0.1:"
                         (.getPort (.getLocalAddress exchange)))
                    tenant-tools/*authenticated?* true]
            (if-let [response (mcp/respond configuration request
                                           protocol-version)]
              (send! exchange 200 response
                     {"MCP-Protocol-Version" protocol-version})
              (send-empty! exchange 202
                           {"MCP-Protocol-Version" protocol-version}))))))
    (catch clojure.lang.ExceptionInfo error
      (let [type (:type (ex-data error))
            insufficient? (= :oauth-resource/insufficient-scope type)
            unauthenticated? (contains? #{:identity/unauthenticated
                                          :oauth-resource/invalid-token
                                          :oauth-resource/invalid-audience
                                          :oauth-resource/unknown-subject
                                          :oauth-resource/not-configured
                                          :oauth-resource/introspection-failed}
                                        type)
            status (cond
                     insufficient? 403
                     unauthenticated? 401
                     (= :mcp-http/invalid-origin type) 403
                     (= :mcp-http/not-acceptable type) 406
                     (= :mcp-http/unsupported-media-type type) 415
                     (= :http/payload-too-large type) 413
                     :else 400)
            required-scope (or (:required-scope (ex-data error)) "mcp:tools")
            challenge (str (oauth-resource/challenge configuration required-scope)
                           (when insufficient?
                             ", error=\"insufficient_scope\""))]
        (send! exchange status
               {"jsonrpc" "2.0" "id" nil
                "error" {"code" (if unauthenticated? -32001 -32600)
                         "message" (.getMessage error)}}
               (cond-> {}
                 (or unauthenticated? insufficient?)
                 (assoc "WWW-Authenticate" challenge)))))
    (catch Exception _
      (send! exchange 400
             {"jsonrpc" "2.0" "id" nil
              "error" {"code" -32700 "message" "parse error"}}))))

(defn- provider-from-path [path pattern]
  (some-> (re-matches pattern path) second keyword))

(defn- id-from-path [path pattern]
  (some-> (re-matches pattern path) second))

;; ── one page of an uploaded PDF, as markup ───────────────────────────────────
;;
;; Out of the request `cond` rather than in it, and that is a fact about the
;; JVM rather than a preference: the `cond` compiles to one method, a method
;; is capped at 64 KB of bytecode, and writing these two routes inline is what
;; first exceeded it. The `cond` keeps one delegating clause.
;;
;; JSON rather than an `image/svg+xml` response, which is the whole difference
;; from `/preview`. An SVG *document* served from this origin is the hazard
;; `documents/previewable-media-types` exists to avoid; a JSON field the client
;; puts into the page is markup this server generated — the same category as
;; the workbook charts `sheets.chart` already draws. `pageview` says why the
;; fragment is inert and why no CSP had to be widened for it.

(def ^:private pages-pattern #"/api/workspace/drive/documents/([^/]+)/pages")
(def ^:private page-pattern #"/api/workspace/drive/documents/([^/]+)/pages/(\d+)")
(def ^:private page-image-pattern
  #"/api/workspace/drive/documents/([^/]+)/pages/(\d+)/images/(\d+)")

(defn- page-route? [method path]
  (and (= method "GET")
       (or (re-matches pages-pattern path)
           (re-matches page-pattern path)
           (re-matches page-image-pattern path))
       true))

(defn- page-routes! [exchange path]
  (let [session (require-app-session! exchange)]
    (if-let [[_ id page index] (re-matches page-image-pattern path)]
      ;; Bytes, not JSON, and that is the one place this differs from the
      ;; page itself. The picture is already an image format — a JPEG
      ;; straight out of the file, or a PNG this encoded — so base64 inside
      ;; a JSON field would cost a third of its size to say nothing. The SVG
      ;; goes the other way for a reason that does not apply here: an SVG
      ;; DOCUMENT served from this origin can carry script and a raster
      ;; cannot.
      (let [out (pageview/image id (:user-id session)
                                (parse-long page) (parse-long index))]
        (doto (.getResponseHeaders exchange)
          (.set "Content-Type" (:media-type out))
          (.set "Cache-Control" "no-store")
          (.set "X-Content-Type-Options" "nosniff")
          ;; The same belt and braces as `/preview`: even for bytes this
          ;; server generated, nothing in this response may load or run
          ;; anything.
          (.set "Content-Security-Policy"
                "default-src 'none'; style-src 'unsafe-inline'; sandbox")
          (.set "Content-Disposition" "inline"))
        (.sendResponseHeaders exchange 200 (alength ^bytes (:bytes out)))
        (with-open [o (.getResponseBody exchange)]
          (.write o ^bytes (:bytes out))))
      (if-let [[_ id index] (re-matches page-pattern path)]
        (send! exchange 200 (pageview/page id (:user-id session) (parse-long index)))
        (send! exchange 200 (pageview/document (id-from-path path pages-pattern)
                                               (:user-id session)))))))

;; /api/authority/<authority>/... -- the authority key is a path segment so a
;; disabled or unknown authority is refused by name rather than by inspecting a
;; body. `authority-api/review!` etc. refuse an unknown key rather than
;; defaulting, so a typo cannot reach a different authority.
(defn- authority-from-path [path pattern]
  (some-> (re-matches pattern path) second keyword))

(defn- authority+id-from-path [path pattern]
  (when-let [[_ a i] (re-matches pattern path)]
    [(keyword a) i]))

;; A signer is named by DID, not by user id: a commitment names the key that
;; will sign and a user with two Passkeys has two DIDs. `:principal` is still the
;; user id, because that is what the Drive's ACL is keyed on — `cloud.itonami.app.esign`
;; needs both and neither substitutes for the other.
(defn- project-scope
  "The organization/user/project triple `project-repository` addresses by.

  `project-id` defaults to `default` rather than being required, because the
  catalogue read has no project yet and `storage-owner` hashes all three — a nil
  there would put every organization's listing in one storage owner."
  [session project-id]
  {:organization-id (:organization-id session)
   :user-id (:user-id session)
   :project-id (or (not-empty (str/trim (str project-id))) "default")})

(defn- esign-who [session]
  {:principal (:user-id session)
   :did (get-in (store/snapshot) [:identity :users (:user-id session) :did])})

(defn- public-session [session-id]
  {:schema "cloud.itonami.app.session.v1"
   :id session-id
   :messages (mapv #(select-keys % [:id :role :content :at])
                   (store/session-messages session-id))})

(defn- scoped-chat-session-id [session logical-id project-id]
  (let [logical-id (or (not-empty (str/trim (str logical-id))) "desktop")
        project-id (not-empty (str/trim (str project-id)))]
    (if project-id
      (str "project\u0000" (:organization-id session) "\u0000" (:user-id session)
           "\u0000" project-id "\u0000" logical-id)
      logical-id)))

(defn- identity-context [exchange]
  (identity/public-state (or (bearer-token exchange)
                             (cookie-value exchange identity/cookie-name))))

(defn- auth-lifecycle-path? [path]
  (contains? #{"/api/auth/sessions" "/api/auth/sessions/revoke"
               "/api/auth/signout" "/api/auth/identities/unlink"}
             path))

(defn- handle-auth-lifecycle! [exchange config method path]
  (case [method path]
    ["GET" "/api/auth/sessions"]
    (let [session (require-app-session! exchange)]
      (send! exchange 200 {:sessions (identity/user-sessions session)}))

    ["POST" "/api/auth/sessions/revoke"]
    (let [session (require-app-session! exchange)
          request (read-json exchange)
          result (do
                   (require-origin! exchange config)
                   (require-csrf! exchange session)
                   (identity/revoke-session! session (:session-id request)))]
      (send! exchange 200 result
             (if (:current? result)
               {"Set-Cookie" (expired-session-cookie)} {})))

    ["POST" "/api/auth/signout"]
    (let [session (require-session! exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (identity/sign-out! session)
      (send! exchange 200 {:signed-out true}
             {"Set-Cookie" (expired-session-cookie)}))

    ["POST" "/api/auth/identities/unlink"]
    (let [session (require-app-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200 (identity/unlink-login-identity! session request)))

    (send! exchange 405 {:error {:type "method_not_allowed"}})))

(defn- messenger-actor [session]
  (if (= :agent (:kind session))
    (str "agent:" (:id session))
    (:user-id session)))

(defn- organization-directory-principals [organization-id organization-slug]
  (let [identity-state (:identity (store/snapshot))
        humans (for [membership (vals (:memberships identity-state))
                     :when (= organization-id (:organization-id membership))
                     :let [user (get-in identity-state [:users (:user-id membership)])]
                     :when user]
                 [(:id user)
                  {:id (:id user) :did (:did user)
                   :name (or (:display-name user) (:email user) (:id user))
                   :kind "human"}])
        agents (for [agent (agent-session/sessions)
                     :when (= organization-id (:organization-id agent))]
                 [(str "agent:" (:id agent))
                  {:id (str "agent:" (:id agent))
                   :name (or (:label agent) (:id agent)) :kind "agent"
                   :status (if (or (:revoked? agent) (:expired? agent))
                             "inactive" "active")}])
        organisms (for [worker (:items (organism-gateway/directory organization-slug))]
                    [(str "organism:" (:ao.worker/id worker))
                     {:id (str "organism:" (:ao.worker/id worker))
                      :did (:ao.worker/subject worker)
                      :name (:ao.worker/id worker) :kind "organism"
                      :status (name (:ao.worker/status worker))}])]
    (into {} (concat humans agents organisms))))

(defn- messenger-principals
  "The addressable identities in the active organization.

  WorkerRuns are deliberately absent: they are restart-ephemeral executions,
  not identities. Agent sessions and OrganismWorkers are stable enough to be
  addressed independently and remain visibly non-human."
  [exchange session]
  (let [context (identity-context exchange)
        organization-id (:active-organization-id context)
        organization-slug (get-in context [:organization :organization-id])
        context-humans
        (into {}
              (map (fn [user]
                     [(:id user)
                      {:id (:id user) :did (:did user)
                       :name (or (:display-name user) (:email user) (:id user))
                       :kind "human"}]))
              (get-in context [:organization :users]))
        principals (merge (organization-directory-principals
                           organization-id organization-slug)
                          context-humans)
        actor (messenger-actor session)]
    ;; A test or a newly enrolled session can be valid before its directory
    ;; projection catches up. It may address itself, never somebody invented.
    (cond-> principals
      (not (contains? principals actor))
      (assoc actor {:id actor
                    :name (or (:label session) actor)
                    :kind (if (= :agent (:kind session)) "agent" "human")}))))

(defn- organism-messenger-context [exchange]
  (let [transport (organism-messenger-transport/authenticate (bearer-token exchange))]
    (when-not transport
      (throw (ex-info "有効なOrganismWorker messenger credentialが必要です。"
                      {:type :identity/unauthenticated})))
    (let [organization (:organization transport)
          organization-id
          (some (fn [[id record]]
                  (when (= organization (:organization-id record)) id))
                (get-in (store/snapshot) [:identity :organizations]))
          principals (organization-directory-principals organization-id organization)]
      (assoc transport :principals
             (cond-> principals
               (not (contains? principals (:principal transport)))
               (assoc (:principal transport)
                      {:id (:principal transport)
                       :name (:worker-id transport) :kind "organism"}))))))

(defn- messenger-context [exchange]
  (let [session (require-app-session! exchange)
        context (identity-context exchange)
        organization (get-in context [:organization :organization-id])]
    (when (str/blank? (str organization))
      (throw (ex-info "Messengerにはactive Organizationが必要です。"
                      {:type :identity/organization-required})))
    {:session session
     :organization organization
     :actor (messenger-actor session)
     :principals (messenger-principals exchange session)}))

(defn share-candidates
  "Who this actor could share a document with: the other members of their
  active organization.

  A convenience for the picker, not a permission boundary — `documents/grant!`
  takes any principal string, and a name that is not in this list is still a
  name it will accept. The list exists so the common case is a click rather
  than a user id typed from memory, and typing one is still allowed."
  [exchange actor]
  (->> (get-in (identity-context exchange) [:organization :users])
       (remove #(= actor (:id %)))
       (mapv #(select-keys % [:id :display-name :email]))))

(defn- active-organization-slug [exchange]
  (get-in (identity-context exchange) [:organization :organization-id]))

(defn- require-control-role! [context capability]
  (when (and (#{:approval/submit :stop/request :work-governance/admin}
                capability)
             (not (#{:owner :admin}
                   (get-in context [:organization :role]))))
    (throw (ex-info "この操作にはOrganizationのownerまたはadmin権限が必要です。"
                    {:type :identity/forbidden :capability capability}))))

(defn- cursor-path [context worker-id]
  [:organism-cursors
   (get-in context [:user :id])
   (get-in context [:organization :id])
   worker-id])

(defn- remembered-cursor [context worker-id]
  (get-in (store/snapshot) (cursor-path context worker-id)))

(defn- remember-cursor! [context worker-id cursor]
  (let [path (cursor-path context worker-id)]
    (when (and cursor (not= cursor (get-in (store/snapshot) path)))
      (store/transact! assoc-in path cursor))))

(defn- capability-keyword [value]
  (let [value (some-> value str (str/replace #"^:" ""))]
    (when (and (not (str/blank? value))
               (re-matches #"[a-z][a-z0-9.-]{0,62}/[a-z][a-z0-9.-]{0,62}"
                           value))
      (keyword value))))

(defn- require-work-organization! [context value]
  (let [active (get-in context [:organization :organization-id])]
    (when-not (= active value)
      (throw (ex-info "work governance organization boundary"
                      {:type :work-governance/organization-boundary
                       :expected active :actual value})))
    active))

(defn- decision-keyword [value]
  (case (some-> value str (str/replace #"^:" ""))
    "approved" :approved
    "rejected" :rejected
    nil))

(defn- organism-intent [context worker-id request capability now]
  (let [requested-expiry
        (try
          (when-let [value (:expires-at request)]
            (if (number? value) (long value) (Long/parseLong (str value))))
          (catch Exception _ nil))
        expires-at (min (+ now 3600000)
                        (max (+ now 1000)
                             (or requested-expiry (+ now 900000))))]
    {:intent/id (str "intent-" (java.util.UUID/randomUUID))
     :intent/organization
     (get-in context [:organization :organization-id])
     :intent/worker worker-id
     :intent/capability capability
     :intent/issued-by (or (get-in context [:user :did])
                           (get-in context [:user :id]))
     :intent/expires-at expires-at
     :intent/parent (:parent request)
     :intent/payload
     (select-keys request [:type :summary :target :reference
                           :decision :reason])}))

(defn- require-visible-worker! [exchange worker-id]
  (let [organization (active-organization-slug exchange)
        visible (some #(when (= worker-id (:ao.worker/id %)) %)
                      (:items (organism-gateway/directory organization)))]
    (or visible
        (throw (ex-info "organism worker was not found in the active organization"
                        {:type :ao.worker/not-found :id worker-id})))))

(defn- send-chat-stream! [^HttpExchange exchange config request]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" "application/x-ndjson; charset=utf-8")
    (.set "Cache-Control" "no-store")
    (.set "X-Content-Type-Options" "nosniff"))
  (.sendResponseHeaders exchange 200 0)
  (with-open [writer (OutputStreamWriter. (.getResponseBody exchange)
                                          StandardCharsets/UTF_8)]
    (let [write-event!
          (fn [event]
            (.write writer (json/write-str event))
            (.write writer "\n")
            (.flush writer))]
      (try
        (let [response
              (service/run-chat-stream!
               config request
               #(write-event! {:type "delta" :content %}))]
          (write-event! {:type "done"
                         :provider (:provider response)
                         :model (:model response)
                         :message (:message response)
                         :usage (:usage response)}))
        (catch Exception error
          (write-event! {:type "error" :message (.getMessage error)}))))))

(defn- send-openai-stream!
  "Serve `POST /v1/chat/completions` with `stream: true` as OpenAI SSE.

  The response headers are written on the first frame rather than up front,
  and that is the whole reason this is not a few lines. Once `200` and
  `text/event-stream` are on the wire the status can no longer change, so a
  failure that happens before the provider has produced anything — a denied
  provider, a refused local model — could only be reported inside a successful
  stream, where a client reads it as an empty answer rather than an error.
  Deferring the headers leaves those failures on an untouched exchange, where
  the handler's own `ex-data` mapping still turns them into a real status.

  After the first delta that is no longer available, so an error there is
  reported as an `error` frame instead. That is a worse contract than a status
  code and it is the honest one: the alternative is closing a 200 stream on a
  truncated answer, which reads as success."
  [^HttpExchange exchange config chat include-usage?]
  (let [response-id (store/new-id "chatcmpl")
        envelope (service/stream-envelope
                  response-id (service/chosen-model config chat))
        writer (volatile! nil)
        open-writer!
        (fn []
          (or @writer
              (do
                (doto (.getResponseHeaders exchange)
                  (.set "Content-Type" "text/event-stream; charset=utf-8")
                  (.set "Cache-Control" "no-store")
                  (.set "X-Content-Type-Options" "nosniff"))
                (.sendResponseHeaders exchange 200 0)
                (vreset! writer (OutputStreamWriter. (.getResponseBody exchange)
                                                     StandardCharsets/UTF_8)))))
        frame!
        (fn [payload]
          (let [^java.io.Writer out (open-writer!)]
            (.write out "data: ")
            (.write out (if (string? payload) payload (json/write-str payload)))
            (.write out "\n\n")
            (.flush out)))
        open-stream!
        (fn []
          (when-not @writer
            (frame! (service/openai-chunk envelope {:role "assistant"} nil))))]
    (try
      (let [result (service/run-chat-stream!
                    config (assoc chat :response-id response-id)
                    (fn [delta]
                      (open-stream!)
                      (frame! (service/openai-chunk
                               envelope {:content delta} nil))))]
        ;; A completion that streamed no delta at all — an empty answer — still
        ;; owes the client a well-formed stream rather than a bare `[DONE]`.
        (open-stream!)
        (frame! (service/openai-chunk envelope {} "stop"))
        (when include-usage?
          (frame! (service/openai-usage-chunk envelope (:usage result))))
        (frame! "[DONE]"))
      (catch Exception error
        (if @writer
          (do (frame! {:error {:type (name (or (:type (ex-data error))
                                               :provider/error))
                               :message (.getMessage error)}})
              (frame! "[DONE]"))
          (throw error)))
      (finally
        (when-let [^java.io.Writer out @writer]
          (.close out))))))

(defn- runtime-state!
  "The operator's view of what this process is doing right now.

  **Named `public-state` until 2026-08-08, and served with no session at all.**
  That reading of『public』was『anything already on this machine』, which is a
  fair thing to mean for a process bound to loopback — and it stopped being
  true the moment a tunnel put the same handler on the internet, where
  `GET /api/state` answered strangers with `:last-response`, i.e. the text of
  the most recent assistant message (measured from off-host, 2026-08-08).

  So it is session-bound now, and the name says what it is rather than who may
  read it. The `:schema` string keeps its `public-state.v1` spelling: it is a
  wire identifier, and renaming a contract to match an internal rename would
  break readers to fix nothing.

  Nothing in this repository fetches it — `server-process` reaches for
  `/health`, which is the route that legitimately takes no session because it
  says only『the process is up』and names no content.

  **The session check lives here, not in the route table.** Not a preference:
  the request handler's `cond` is within a couple of forms of the JVM's 64 KB
  method limit, and wrapping the branch in a `do` to add the guard fails the
  whole namespace with『Method code too large!』(measured 2026-08-08 — main
  compiles, main plus that `do` does not). The `!` and the exchange argument
  are what carry the requirement at the call site instead. Anyone adding a
  route here will meet the same ceiling; splitting that `cond` is its own
  change."
  [config exchange]
  (require-app-session! exchange)
  (let [state (store/snapshot)]
    {:schema "cloud.itonami.app.public-state.v1"
     :privacy {:bind (str (get-in config [:server :host]) ":"
                          (get-in config [:server :port]))
               :cloud-enabled? (get-in config [:routing :cloud-enabled?])}
     :routing (:routing config)
     :providers (mapv #(select-keys % [:id :name :kind :local? :enabled?])
                      (:providers config))
     :agents (:agents state)
     :sessions (count (:sessions state))
     :memory-datoms (count (:datoms state))
     :last-response (:last-response state)}))

(defn- control-plane-error-status [type]
  (get {:tenant-connection/storage-budget-exhausted 507
        :tenant-repository/not-found 404
        :tenant-repository/state-required 400
        :repository-storage/edit-conflict 409
        :repository-storage/expected-cid-required 428
        :repository-storage/initialization-conflict 409
        :repository-storage/invalid-state 422
        :repository-storage/invalid-datom 422
        :repository-storage/unsafe-edn 422
        :repository-storage/tagged-edn 422
        :repository-storage/workspace-missing 404
        :repository-storage/config-required 501
        :oauth-resource/invalid-token 401
        :oauth-resource/invalid-audience 401
        :oauth-resource/unknown-subject 401
        :oauth-resource/insufficient-scope 403
        :oauth-resource/not-configured 401
        :oauth-resource/introspection-failed 502
        :oauth-resource/insecure-introspection 500
        :oauth-resource/insecure-resource 500
        :oauth-resource/introspection-credentials-missing 500
        :oauth-resource/no-authorization-server 500
        :http/payload-too-large 413
        :capture/not-found 404
        :capture/invalid-text 400
        :capture/blank-text 400
        :capture/text-too-long 413
        :capture/invalid-mode 400
        :capture/invalid-source 400
        :capture/invalid-outcome 400
        :capture/unclarified 409
        :chronicle/frame-not-found 404
        :chronicle/disabled 409
        :chronicle/permission-required 428
        :chronicle/command-timeout 504
        :chronicle/command-failed 502}
       type 502))
(defn- organization-actor-candidates [exchange]
  (let [context (identity-context exchange)
        tenant-id (:active-organization-id context)
        organization (get-in context [:organization :organization-id])]
    (vec
     (concat
      (map (fn [user]
             {:actor/kind :user :actor/id (:id user)
              :actor/label (or (:display-name user) (:email user) (:id user))})
           (get-in context [:organization :users]))
      (for [agent (agent-session/sessions)
            :when (and (= tenant-id (:organization-id agent))
                       (not (:revoked? agent)) (not (:expired? agent)))]
        {:actor/kind :agent :actor/id (:id agent)
         :actor/label (or (:label agent) (:id agent))})
      (for [worker (if organization
                     (:items (organism-gateway/directory organization)) [])]
        {:actor/kind :organism-worker :actor/id (:ao.worker/id worker)
         :actor/label (or (:ao.worker/name worker) (:ao.worker/id worker))})
      (when organization
        [{:actor/kind :organization :actor/id organization
          :actor/label (or (get-in context [:organization :name]) organization)}])))))

(defn- handle-work-governance!
  "Keep the governed-work router outside the already-large HttpHandler JVM
  method. The return value is sent here; authorization errors still flow into
  the handler's common exception mapping."
  [config exchange method path]
  (cond
    (and (= method "GET") (= path "/api/work-governance"))
    (let [_session (require-app-session! exchange)
          organization (active-organization-slug exchange)]
      (send! exchange 200
             (assoc (work-runtime/organization-view organization)
                    :actor-candidates (organization-actor-candidates exchange))))

    (and (= method "GET") (= path "/api/work-governance/health"))
    (do (require-app-session! exchange)
        (send! exchange 200 (work-runtime/health)))

    (and (= method "POST") (= path "/api/work-governance/reconcile"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (send! exchange 200 (work-runtime/reconcile-once! config)))

    (and (= method "POST")
         (id-from-path path #"/api/work-governance/dead-letters/([^/]+)/replay"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          dead-id (id-from-path path
                                #"/api/work-governance/dead-letters/([^/]+)/replay")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (send! exchange 200
             (work-runtime/replay-dead-letter! dead-id
                                                (System/currentTimeMillis))))

    (and (= method "POST") (= path "/api/work-governance/organizations"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          body (read-governance-body exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (require-work-organization! context (:org/id body))
      (send! exchange 200
             (work-runtime/put-organization! (organization-body->edn body))))

    (and (= method "POST") (= path "/api/work-governance/roles"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          body (read-governance-body exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (require-work-organization! context (:yakuwari/organization body))
      (send! exchange 200 (work-runtime/put-role! body)))

    (and (= method "POST") (= path "/api/work-governance/approval-policies"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          body (read-governance-body exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (require-work-organization! context (:approval.policy/organization body))
      (send! exchange 200
             (work-runtime/put-approval-policy!
              (approval-policy-wire->edn body))))

    (and (= method "POST") (= path "/api/work-governance/work-items"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          body (read-governance-body exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (require-work-organization! context (:work.item/organization body))
      (send! exchange 200 (work-runtime/put-work-item! body)))

    (and (= method "POST")
         (id-from-path path
                       #"/api/work-governance/work-items/([^/]+)/approve/start"))
    (let [session (require-human-session! exchange)
          item-id (id-from-path
                   path #"/api/work-governance/work-items/([^/]+)/approve/start")
          body (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (work-approval/start!
              session item-id (decision-keyword (:decision body))
              (get-in config [:server :webauthn-rp-id])
              (get-in config [:server :public-origin]))))

    (and (= method "POST")
         (id-from-path path
                       #"/api/work-governance/work-items/([^/]+)/approve/finish"))
    (let [session (require-human-session! exchange)
          item-id (id-from-path
                   path #"/api/work-governance/work-items/([^/]+)/approve/finish")
          body (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (work-approval/finish! session item-id (:transaction-id body)
                                    (:credential body))))

    (and (= method "POST")
         (id-from-path path
                       #"/api/work-governance/work-items/([^/]+)/verifications"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          item-id (id-from-path
                   path #"/api/work-governance/work-items/([^/]+)/verifications")
          body (assoc (read-governance-body exchange)
                      :verification.receipt/work-item item-id)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (require-work-organization!
       context (:work.item/organization
                (get-in (work-runtime/ledger) [:work-items item-id])))
      (when (= :review (:verification.receipt/kind body))
        (throw (ex-info "review evidence requires the Passkey review route"
                        {:type :work-approval/passkey-required})))
      (send! exchange 200 (work-runtime/record-verification! body)))

    (and (= method "POST")
         (id-from-path path
                       #"/api/work-governance/work-items/([^/]+)/review/start"))
    (let [session (require-human-session! exchange)
          item-id (id-from-path
                   path #"/api/work-governance/work-items/([^/]+)/review/start")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (work-approval/start-review!
              session item-id (get-in config [:server :webauthn-rp-id])
              (get-in config [:server :public-origin]))))

    (and (= method "POST")
         (id-from-path path
                       #"/api/work-governance/work-items/([^/]+)/review/finish"))
    (let [session (require-human-session! exchange)
          item-id (id-from-path
                   path #"/api/work-governance/work-items/([^/]+)/review/finish")
          body (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (work-approval/finish-review! session item-id
                                           (:transaction-id body)
                                           (:credential body))))

    (and (= method "POST")
         (id-from-path path
                       #"/api/work-governance/work-items/([^/]+)/complete"))
    (let [session (require-app-session! exchange)
          context (identity-context exchange)
          item-id (id-from-path
                   path #"/api/work-governance/work-items/([^/]+)/complete")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (require-control-role! context :work-governance/admin)
      (require-work-organization!
       context (:work.item/organization
                (get-in (work-runtime/ledger) [:work-items item-id])))
      (send! exchange 200
             (work-runtime/complete-work! item-id (System/currentTimeMillis))))

    :else (send! exchange 404 {:error "not found"})))

(defn- handle-ao-messenger! [exchange method path]
  (cond
    (and (= method "GET") (= path "/api/ao/messenger"))
    (let [{:keys [organization principal principals]}
          (organism-messenger-context exchange)]
      (send! exchange 200 (messenger/overview organization principal principals)))

    (and (= method "GET") (= path "/api/ao/messenger/poll"))
    (let [{:keys [organization principal principals]}
          (organism-messenger-context exchange)
          params (query-params exchange)]
      (send! exchange 200
             (messenger/poll organization principal principals (:cursor params)
                             (try (Long/parseLong (or (:limit params) "50"))
                                  (catch Exception _ 50)))))

    (and (= method "POST") (= path "/api/ao/messenger/ack"))
    (let [{:keys [organization principal]} (organism-messenger-context exchange)]
      (send! exchange 200
             (messenger/acknowledge! organization principal
                                     (:message-ids (read-json exchange)))))

    (and (= method "POST") (= path "/api/ao/messenger/trust"))
    (let [{:keys [organization principal principals]}
          (organism-messenger-context exchange)
          request (read-json exchange)]
      (send! exchange 200
             (messenger/set-trust! organization principal principals
                                   (:sender-id request)
                                   (if (contains? request :allowed?)
                                     (:allowed? request) true))))

    (and (= method "POST") (= path "/api/ao/messenger/devices"))
    (let [{:keys [organization principal principals]}
          (organism-messenger-context exchange)]
      (send! exchange 200
             (messenger/register-device! organization principal principals
                                         (read-json exchange))))

    (and (= method "POST") (= path "/api/ao/messenger/prekey-bundles"))
    (let [{:keys [organization principal principals]}
          (organism-messenger-context exchange)
          request (read-json exchange)]
      (send! exchange 200
             (messenger/consume-prekey-bundles!
              organization principal principals (:principal request))))

    (and (= method "POST") (= path "/api/ao/messenger/device-directory"))
    (let [{:keys [organization principals]} (organism-messenger-context exchange)
          request (read-json exchange)
          target (:principal request)]
      (when-not (contains? principals target)
        (throw (ex-info "target is not in this organization"
                        {:type :messenger/unknown-principal})))
      (send! exchange 200 (messenger/device-directory organization target)))

    (and (= method "POST")
         (id-from-path path #"/api/ao/messenger/conversations/([^/]+)/messages"))
    (let [{:keys [organization principal]} (organism-messenger-context exchange)
          conversation-id
          (id-from-path path #"/api/ao/messenger/conversations/([^/]+)/messages")]
      (send! exchange 202
             (messenger/send-message! organization principal conversation-id
                                      (read-json exchange))))

    :else (send! exchange 404 {:error "not found"})))

(defn- handle-app-messenger! [config exchange method path]
  (cond
    (and (= method "GET") (= path "/api/messenger"))
    (let [{:keys [organization actor principals]} (messenger-context exchange)]
      (send! exchange 200 (messenger/overview organization actor principals)))

    (and (= method "GET") (= path "/api/messenger/quarantine"))
    (let [{:keys [organization actor principals]} (messenger-context exchange)]
      (send! exchange 200 (messenger/quarantine organization actor principals)))

    (and (= method "GET") (= path "/api/messenger/devices"))
    (let [{:keys [organization actor]} (messenger-context exchange)]
      (send! exchange 200 (messenger/device-directory organization actor)))

    (and (= method "POST") (= path "/api/messenger/devices"))
    (let [{:keys [session organization actor principals]} (messenger-context exchange)]
      (require-origin! exchange config) (require-csrf! exchange session)
      (send! exchange 200
             (messenger/register-device! organization actor principals
                                         (read-json exchange))))

    (and (= method "POST") (= path "/api/messenger/prekey-bundles"))
    (let [{:keys [session organization actor principals]} (messenger-context exchange)
          request (read-json exchange)]
      (require-origin! exchange config) (require-csrf! exchange session)
      (send! exchange 200
             (messenger/consume-prekey-bundles!
              organization actor principals (:principal request))))

    (and (= method "POST") (= path "/api/messenger/device-directory"))
    (let [{:keys [session organization principals]} (messenger-context exchange)
          request (read-json exchange)
          target (:principal request)]
      (require-origin! exchange config) (require-csrf! exchange session)
      (when-not (contains? principals target)
        (throw (ex-info "target is not in this organization"
                        {:type :messenger/unknown-principal})))
      (send! exchange 200 (messenger/device-directory organization target)))

    (and (= method "POST") (= path "/api/messenger/trust"))
    (let [{:keys [session organization actor principals]} (messenger-context exchange)
          request (read-json exchange)]
      (require-origin! exchange config) (require-csrf! exchange session)
      (send! exchange 200
             (messenger/set-trust! organization actor principals (:sender-id request)
                                   (if (contains? request :allowed?)
                                     (:allowed? request) true))))

    (and (= method "POST") (= path "/api/messenger/conversations"))
    (let [{:keys [session organization actor principals]} (messenger-context exchange)
          request (read-json exchange)]
      (require-origin! exchange config) (require-csrf! exchange session)
      (let [conversation (messenger/create-conversation!
                          organization actor principals request)]
        (send! exchange 201
               {:schema messenger/schema :id (:conversation/id conversation)
                :kind (name (:conversation/kind conversation))
                :title (:conversation/title conversation)
                :members (:conversation/members conversation)})))

    (and (= method "GET")
         (id-from-path path #"/api/messenger/conversations/([^/]+)/messages"))
    (let [{:keys [organization actor principals]} (messenger-context exchange)
          id (id-from-path path #"/api/messenger/conversations/([^/]+)/messages")]
      (send! exchange 200 (messenger/messages organization actor id principals)))

    (and (= method "POST")
         (id-from-path path #"/api/messenger/conversations/([^/]+)/messages"))
    (let [{:keys [session organization actor]} (messenger-context exchange)
          id (id-from-path path #"/api/messenger/conversations/([^/]+)/messages")]
      (require-origin! exchange config) (require-csrf! exchange session)
      (send! exchange 202
             (messenger/send-message! organization actor id (read-json exchange))))

    (and (= method "POST")
         (id-from-path path #"/api/messenger/conversations/([^/]+)/read"))
    (let [{:keys [session organization actor]} (messenger-context exchange)
          id (id-from-path path #"/api/messenger/conversations/([^/]+)/read")]
      (require-origin! exchange config) (require-csrf! exchange session)
      (send! exchange 200 (messenger/mark-read! organization actor id)))

    :else (send! exchange 404 {:error "not found"})))

(defn- handle-mail-projects!
  "Filing mail against local projects.

  Organization-scoped, because a project is shared — unlike `/api/workspace/inbox`,
  whose marks are one person's. Nothing here moves or deletes a message; the
  assignment is a third plane laid over the two that already exist."
  [config exchange method path]
  (cond
    (and (= method "GET") (= path "/api/mail/projects"))
    (let [session (require-app-session! exchange)]
      (send! exchange 200
             (assoc (mail-projects/overview (:organization-id session))
                    ;; Beside the counts, because "filing works" and "filing is
                    ;; storing bodies" are different facts and the second is the
                    ;; one that fails silently.
                    :sealing (age-key/status))))

    ;; What the receiving server decided about each message's authenticity.
    ;; A read, and organization-scoped like the rest of this router.
    (and (= method "GET") (= path "/api/mail/projects/authentication"))
    (let [_ (require-app-session! exchange)]
      (send! exchange 200
             (authentication/summarize
              (vals (get-in (store/snapshot) [:mail :messages] {})))))

    (and (= method "GET") (= path "/api/mail/projects/unassigned"))
    (let [session (require-app-session! exchange)]
      (send! exchange 200
             (mail-projects/unassigned (:organization-id session))))

    (and (= method "POST") (= path "/api/mail/projects/rules"))
    (let [session (require-app-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 201
             (mail-projects/add-rule!
              (:organization-id session)
              {:project (:project request)
               :match {:from (:from request)
                       :from-domain (:from-domain request)
                       :subject-contains (:subject-contains request)
                       :label (:label request)}})))

    (and (= method "POST")
         (id-from-path path #"/api/mail/projects/rules/([^/]+)/remove"))
    (let [session (require-app-session! exchange)
          rule (id-from-path path #"/api/mail/projects/rules/([^/]+)/remove")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (mail-projects/remove-rule! (:organization-id session) rule)))

    (and (= method "POST") (= path "/api/mail/projects/apply"))
    (let [session (require-app-session! exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (mail-projects/apply-rules! (:organization-id session)
                                         (:user-id session))))

    (and (= method "POST") (= path "/api/mail/projects/assign"))
    (let [session (require-app-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (mail-projects/assign! (:organization-id session)
                                    (:message request)
                                    (:project request)
                                    (:user-id session))))

    (and (= method "POST") (= path "/api/mail/projects/unassign"))
    (let [session (require-app-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             ;; A project may be named to remove one filing; without it the
             ;; message leaves every project, which is what the single-filing
             ;; version meant.
             (mail-projects/unassign! (:organization-id session)
                                      (:message request)
                                      (:project request))))

    ;; A conversation is the unit a person files. Nobody decides that the third
    ;; reply belongs to `legal` and the fourth does not.
    (and (= method "POST") (= path "/api/mail/projects/assign-thread"))
    (let [session (require-app-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (mail-projects/assign-thread! (:organization-id session)
                                           (:thread request)
                                           (:project request)
                                           (:user-id session))))

    (and (= method "GET")
         (id-from-path path #"/api/mail/projects/messages/([^/]+)"))
    (let [session (require-app-session! exchange)
          message (id-from-path path #"/api/mail/projects/messages/([^/]+)")]
      (send! exchange 200
             {:schema "cloud.itonami.app.mail-projects.v1"
              :message message
              :projects (mail-projects/projects-of (:organization-id session)
                                                   message)}))

    :else (send! exchange 404 {:error "not_found" :path path})))

(defn- project-route?
  "Both project prefixes, as ONE test.

  Two `cond` clauses would be clearer to read and this app cannot afford them:
  `handler`'s reify method is within a few hundred bytes of the JVM's 64 KB
  bytecode limit, and adding the second clause pushed it over — the compiler
  then refuses the whole namespace, not the clause. One predicate and one call
  site is what fits."
  [path]
  (or (str/starts-with? path "/api/projects")
      (str/starts-with? path "/api/mail/projects")))

(defn- handle-projects!
  "The LOCAL project catalogue, which is a different thing from
  `/api/workspace/projects`.

  That one reads GitHub Projects v2 through `gh`; these are ordinary Git
  repositories this machine owns, one per organization/user/project, and they
  answer with no network at all. Both are called \"projects\" by their own
  sources, so neither name could be taken from the other — the paths say which
  authority is being asked."
  [config exchange method path]
  (cond
    (str/starts-with? path "/api/mail/projects")
    (handle-mail-projects! config exchange method path)

    (and (= method "GET") (= path "/api/projects"))
    (let [session (require-app-session! exchange)]
      (send! exchange 200
             (project-repository/local-projects-snapshot
              (project-scope session nil))))

    (and (= method "POST") (= path "/api/projects"))
    (let [session (require-app-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 201
             {:schema "cloud.itonami.app.projects.v1"
              :item (project-repository/create-project!
                     (project-scope session (:project request))
                     {:title (:title request)
                      :description (:description request)})}))

    (and (= method "POST")
         (id-from-path path #"/api/projects/([^/]+)/issues"))
    (let [session (require-app-session! exchange)
          project (id-from-path path #"/api/projects/([^/]+)/issues")
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 201
             (project-repository/create-issue!
              (project-scope session project) request)))

    (and (= method "POST")
         (re-matches #"/api/projects/([^/]+)/issues/([^/]+)" path))
    (let [session (require-app-session! exchange)
          [_ project issue] (re-matches #"/api/projects/([^/]+)/issues/([^/]+)" path)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (project-repository/update-issue!
              (project-scope session project) issue request)))

    ;; The mail filed against one project. Under /api/projects rather than
    ;; /api/mail because the question is "what does this project have", and the
    ;; project is what the caller already has in hand.
    ;; Send this project's annexed mail bodies to B2. A write, so it takes the
    ;; same origin+CSRF boundary as any other; and POST rather than GET because
    ;; it moves bytes off this machine.
    (and (= method "POST")
         (id-from-path path #"/api/projects/([^/]+)/push"))
    (let [session (require-app-session! exchange)
          project (id-from-path path #"/api/projects/([^/]+)/push")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (if-let [location (project-repository/project-location
                         (project-scope session project))]
        (send! exchange 200 (project-remote/push! (:directory location) location))
        (send! exchange 404 {:error "no_such_project" :project project})))

    (and (= method "GET")
         (id-from-path path #"/api/projects/([^/]+)/remote"))
    (let [session (require-app-session! exchange)
          project (id-from-path path #"/api/projects/([^/]+)/remote")]
      (if-let [location (project-repository/project-location
                         (project-scope session project))]
        (send! exchange 200 (project-remote/status (:directory location) location))
        (send! exchange 404 {:error "no_such_project" :project project})))

    ;; Move a project to another of the caller's tenants (ADR-0024). Gated to a
    ;; browser session rather than any app session: this changes who owns
    ;; something, and an agent session that could move a project into a tenant
    ;; it already holds a connection to would be granting itself access by
    ;; moving the target. That also keeps it out of the generated command
    ;; registry, where it would be a command certain to refuse.
    ;;
    ;; (Deliberately not naming the gate function in this comment: `route-scan`
    ;; reads a clause's body as the text up to the NEXT clause, so a leading
    ;; comment belongs to the clause ABOVE it, and naming a stricter gate here
    ;; silently restricts the route before this one.)
    (and (= method "POST")
         (id-from-path path #"/api/projects/([^/]+)/transfer"))
    (let [session (require-human-session! exchange)
          request (read-json exchange)
          project (id-from-path path #"/api/projects/([^/]+)/transfer")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (project-transfer/transfer-project!
              session {:project-id project :to-tenant (:tenant request)})))

    (and (= method "GET")
         (id-from-path path #"/api/projects/([^/]+)/mail"))
    (let [session (require-app-session! exchange)
          project (id-from-path path #"/api/projects/([^/]+)/mail")]
      (send! exchange 200
             (mail-projects/project-mail (:organization-id session) project)))

    (and (= method "GET")
         (id-from-path path #"/api/projects/([^/]+)"))
    (let [session (require-app-session! exchange)
          project (id-from-path path #"/api/projects/([^/]+)")]
      (send! exchange 200
             (project-repository/project-board
              (project-scope session project))))

    :else (send! exchange 404 {:error "not_found" :path path})))

(defn- site-route? [path]
  (or (str/starts-with? path "/api/sites")
      (str/starts-with? path "/s/")))

(defn- handle-sites! [config exchange method path]
  (cond
    (and (= method "GET") (id-from-path path #"/s/([^/]+)"))
    (let [site-id (id-from-path path #"/s/([^/]+)")]
      (if-let [site (sites/published site-id)]
        (send-site-html! exchange 200 (:html site))
        (send-site-html! exchange 404 "<!doctype html><title>Not found</title><h1>Site not found</h1>")))

    (and (= method "GET") (= path "/api/sites"))
    (let [session (require-app-session! exchange)
          project (:project (query-params exchange))]
      (send! exchange 200 (sites/list-sites (:organization-id session) project)))

    (and (= method "POST") (= path "/api/sites"))
    (let [session (require-human-session! exchange)
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 201
             (sites/create! (:organization-id session) (:user-id session) request)))

    (and (= method "GET")
         (id-from-path path #"/api/sites/([^/]+)/preview"))
    (let [session (require-app-session! exchange)
          project (:project (query-params exchange))
          site-id (id-from-path path #"/api/sites/([^/]+)/preview")]
      (send-site-html! exchange 200
                       (:html (sites/detail (:organization-id session) project site-id))))

    (and (= method "POST")
         (id-from-path path #"/api/sites/([^/]+)/publish"))
    (let [session (require-human-session! exchange)
          site-id (id-from-path path #"/api/sites/([^/]+)/publish")
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (sites/publish! (:organization-id session) (:user-id session)
                             (:project request) site-id)))

    (and (= method "GET") (id-from-path path #"/api/sites/([^/]+)"))
    (let [session (require-app-session! exchange)
          project (:project (query-params exchange))
          site-id (id-from-path path #"/api/sites/([^/]+)")]
      (send! exchange 200 (sites/detail (:organization-id session) project site-id)))

    (and (= method "PUT") (id-from-path path #"/api/sites/([^/]+)"))
    (let [session (require-human-session! exchange)
          site-id (id-from-path path #"/api/sites/([^/]+)")
          request (read-json exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (sites/update! (:organization-id session) (:user-id session)
                            site-id request)))

    :else (send! exchange 404 {:error "not_found" :path path})))

(defn- conversation-route? [path]
  (contains? #{"/api/session" "/api/chat" "/api/chat/stream"
               "/api/session/clear"} path))

(defn- handle-capture!
  "Human-only, record-only capture. No route in here invokes a model or an
  executor; classification is a later mutation over the immutable raw text."
  [config exchange method path]
  (cond
    (and (= method "GET") (= path "/api/captures/chronicle"))
    (let [session (require-human-session! exchange)]
      (send! exchange 200 (chronicle/capture-candidates (:user-id session))))

    (and (= method "POST") (= path "/api/captures/chronicle/capture"))
    (let [session (require-human-session! exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (chronicle/capture! (:user-id session))
      (send! exchange 200 (chronicle/capture-candidates (:user-id session))))

    (and (= method "GET") (= path "/api/captures"))
    (let [session (require-human-session! exchange)]
      (send! exchange 200
             (capture/snapshot (:user-id session) (:organization-id session))))

    (and (= method "POST") (= path "/api/captures"))
    (let [session (require-human-session! exchange)
          request (read-json exchange)
          frame-id (:chronicle-frame-id request)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (let [source (when-not (str/blank? (str frame-id))
                     (chronicle/capture-source (:user-id session) frame-id))]
        (send! exchange 201
               (capture/public-item
                (capture/create! (:user-id session) (:organization-id session)
                                 (dissoc request :chronicle-frame-id) source)))))

    (and (= method "POST")
         (id-from-path path #"/api/captures/([^/]+)/clarify"))
    (let [session (require-human-session! exchange)
          id (id-from-path path #"/api/captures/([^/]+)/clarify")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (capture/public-item
              (capture/clarify! id (:user-id session) (:organization-id session)
                                (read-json exchange)))))

    (and (= method "POST")
         (id-from-path path #"/api/captures/([^/]+)/review"))
    (let [session (require-human-session! exchange)
          id (id-from-path path #"/api/captures/([^/]+)/review")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (capture/public-item
              (capture/review! id (:user-id session) (:organization-id session)))))

    (and (= method "POST")
         (id-from-path path #"/api/captures/([^/]+)/complete"))
    (let [session (require-human-session! exchange)
          id (id-from-path path #"/api/captures/([^/]+)/complete")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (capture/public-item
              (capture/complete! id (:user-id session) (:organization-id session)))))

    (and (= method "POST")
         (id-from-path path #"/api/captures/([^/]+)/reopen"))
    (let [session (require-human-session! exchange)
          id (id-from-path path #"/api/captures/([^/]+)/reopen")]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200
             (capture/public-item
              (capture/reopen! id (:user-id session) (:organization-id session)))))

    :else (send! exchange 404 {:error "not found"})))

(defn- handle-conversation! [config exchange method path]
  (cond
    (and (= method "GET") (= path "/api/session"))
    (let [session (require-app-session! exchange)
          params (query-params exchange)
          logical-id (or (:session params) "desktop")
          scoped-id (scoped-chat-session-id session logical-id (:project params))]
      (send! exchange 200
             (assoc (public-session scoped-id)
                    :id logical-id :project (:project params))))

    (and (= method "POST") (contains? #{"/api/chat" "/api/chat/stream"} path))
    (let [session (require-app-session! exchange)
          request (read-json exchange)
          prompt (:prompt request)
          session-id (scoped-chat-session-id
                      session (:session request) (:project request))
          chat {:messages [{:role "user" :content prompt}]
                :model (:model request)
                :provider-id (:provider request)
                :session-id session-id
                :agent-id (:agent request)
                ;; Device-local memory is personal data. Agent sessions can
                ;; chat, but cannot read or add to a human user's Chronicle.
                :memory-user-id (:user-id session)
                :memory-eligible? (identity/human-session? session)
                :project-id (:project request)
                :memory-source (if (:tool-assisted? request)
                                 "tool-chat" "chat")}]
      (if (str/blank? prompt)
        (send! exchange 400 {:error {:type "invalid_request"
                                     :message "prompt is required"}})
        (if (= path "/api/chat/stream")
          (send-chat-stream! exchange config chat)
          (send! exchange 200 (service/run-chat! config chat)))))

    (and (= method "POST") (= path "/api/session/clear"))
    (let [session (require-app-session! exchange)
          request (read-json exchange)
          logical-id (or (:session request) "desktop")
          scoped-id (scoped-chat-session-id session logical-id (:project request))]
      (store/clear-session! scoped-id)
      (send! exchange 200 {:ok true :session logical-id
                           :project (:project request)}))

    :else (send! exchange 405 {:error {:type "method_not_allowed"}})))

(defn- handle-chronicle! [config exchange method path]
  (let [session (require-human-session! exchange)
        user-id (:user-id session)]
    (cond
      (and (= method "GET") (= path "/api/chronicle"))
      (send! exchange 200 (chronicle/overview user-id))

      (and (= method "GET") (= path "/api/chronicle/search"))
      (send! exchange 200
             (chronicle/search user-id (:q (query-params exchange))))

      (and (= method "POST") (= path "/api/chronicle/settings"))
      (let [request (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (chronicle/configure! user-id request)
        (send! exchange 200 (chronicle/overview user-id)))

      (and (= method "POST") (= path "/api/chronicle/capture"))
      (do
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (chronicle/capture! user-id)
        (send! exchange 200 (chronicle/overview user-id)))

      (and (= method "POST") (= path "/api/chronicle/open-settings"))
      (do
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200 (chronicle/open-screen-recording-settings!)))

      (and (= method "POST") (= path "/api/chronicle/delete"))
      (do
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200 (chronicle/delete-all! user-id)))

      :else (send! exchange 405 {:error {:type "method_not_allowed"}}))))

(defn- route-domain-verification!
  "Keep the domain-ownership surface out of the already-large HttpHandler
  method. Returns true only when it handled the request."
  [exchange config method path]
  (cond
    ;; No `require-origin!` on this read, and its absence is the fix rather
    ;; than an omission.
    ;;
    ;; `require-origin!` demands the header EQUAL the configured origin, and a
    ;; browser does not send `Origin` on a same-origin GET — only on non-GET
    ;; requests and on cross-origin ones. So this route answered 403 to the one
    ;; caller it has, `fetch('/api/identity/domain-verifications')`, on every
    ;; page load. Measured 2026-08-12 from the browser console; it was the only
    ;; GET in this file that asked (157 POST, 1 PUT, 1 DELETE, and this).
    ;;
    ;; Dropping it costs nothing: the request has no effect to forge, and this
    ;; server sends no `Access-Control-Allow-Origin` at all, so a cross-origin
    ;; page can cause the request but cannot read the answer. The session gate
    ;; below is what actually protects it, and the two writes underneath keep
    ;; both `require-origin!` and `require-csrf!`.
    (and (= method "GET") (= path "/api/identity/domain-verifications"))
    (let [session (require-human-session! exchange)]
      (send! exchange 200 (domain-verification/list-for-session session))
      true)

    (and (= method "POST") (= path "/api/identity/domain-verifications"))
    (let [session (require-human-session! exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 201 (domain-verification/start! session (read-json exchange)))
      true)

    (and (= method "POST")
         (= path "/api/identity/domain-verifications/verify"))
    (let [session (require-human-session! exchange)]
      (require-origin! exchange config)
      (require-csrf! exchange session)
      (send! exchange 200 (domain-verification/verify! session (read-json exchange)))
      true)

    :else false))

(defn handler [config]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (.getPath (.getRequestURI exchange))]
        (try
          (cond
            (and (= method "GET") (= path "/"))
            (send-html! exchange (web/page-html config))

            (and (= method "GET") (= path "/health"))
            (send! exchange 200 {:ok true :service "cloud-itonami-app"
                                 :schema "cloud.itonami.app.health.v1"})

            (and (= method "GET")
                 (= path "/.well-known/oauth-protected-resource/mcp"))
            (send! exchange 200 (oauth-resource/metadata config))

            (and (contains? #{"POST" "GET" "DELETE"} method)
                 (= path "/mcp"))
            (send-mcp-http! exchange config)
            (and (= method "POST") (= path "/api/webhooks/github/projects"))
            (let [body (read-body-bytes exchange)
                  _ (verify-github-webhook! exchange config body)
                  event (.getFirst (.getRequestHeaders exchange)
                                   "X-GitHub-Event")]
              (when (contains? #{"projects_v2" "projects_v2_item"} event)
                (work-reconciler/wake!))
              (send! exchange 202 {:accepted true :event event}))

            ;; Exchange material for authn.kotobase.net.  This is a mutation:
            ;; it creates a signed bearer assertion, so it has the same
            ;; origin+CSRF boundary as credential issuance and additionally
            ;; requires a human passkey session (email/agent are refused).
            ;; Public by necessity, like /health. A DID document is the public
            ;; half of a key pair plus the purposes it may be used for; a
            ;; verifier who has to authenticate to fetch it cannot verify
            ;; anything. Contains no private key material and no workspace state.
            ;;
            ;; Publication is still a DEPLOYMENT step: this serves the document,
            ;; but `did:web:<domain>` resolves to `https://<domain>/.well-known/did.json`
            ;; and nothing here makes DNS point at this process. Until it does,
            ;; `credential/issuer-verification-method` names the `did:key`
            ;; instead, so issued credentials stay verifiable either way.
            ;; Resolved from the Host, because that is what `did:web:<domain>`
            ;; asked for. A deployment now holds several tenants with domains
            ;; (every User owns a personal one, ADR-0023), so serving whichever
            ;; came first would publish a key under a name nobody asked about.
            ;; ADR-0025.
            (and (= method "GET") (= path "/.well-known/did.json"))
            (let [domain (identity/did-web-domain-for-host
                          (.getFirst (.getRequestHeaders exchange) "Host"))]
              (if (str/blank? (str domain))
                (send! exchange 404
                       {:error "この deployment は did:web を発行していません。"
                        :schema credential/schema})
                (send! exchange 200 (credential/did-web-document domain))))

            ;; Issue a membership credential for the session's ACTIVE membership.
            ;; Session + origin + CSRF gated like every other mutating route: it
            ;; mints a signed assertion about a person, and an assertion anyone
            ;; could cause this app to make is not worth anything.
            (and (= method "POST") (= path "/api/credentials/membership"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (identity/require-passkey! session)
              (send! exchange 200
                     (assoc (credential/issue-membership!
                             ;; The session's user is the actor in the audit
                             ;; event. The credential's own issuer is the
                             ;; organization, so without this the ledger could
                             ;; not say who pressed the button.
                             (assoc (identity/membership-credential-context session)
                                    :actor (:user-id session)))
                            :schema credential/schema)))

            ;; The register of what has been issued. Records only — this app does
            ;; not keep the signed documents, so there is nothing here to hand
            ;; back to a holder who lost one; they ask for a new credential.
            (and (= method "GET") (= path "/api/credentials"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     {:schema credential/schema
                      :issued (credential/issued-credentials (store/snapshot))
                      :may-revoke? (credential/may-revoke?
                                    (identity/membership-role session))}))

            ;; Revoke by status index. Owner/admin only: this flips a bit that
            ;; stops another person's credential from being honoured ANYWHERE it
            ;; is presented, which is strictly more power than a member holds.
            (and (= method "POST")
                 (re-matches #"/api/credentials/(\d+)/revoke" path))
            (let [session (require-app-session! exchange)
                  index (id-from-path path #"/api/credentials/(\d+)/revoke")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (when-not (credential/may-revoke? (identity/membership-role session))
                (throw (ex-info "Credential の失効には owner または admin 権限が必要です。"
                                {:type :identity/forbidden})))
              (send! exchange 200
                     (assoc (credential/revoke! (Long/parseLong index)
                                                (:user-id session))
                            :schema credential/schema)))

            ;; Verify a credential someone presents. Session-gated deliberately:
            ;; verification is a pure computation, but leaving it open would make
            ;; this an oracle anyone can feed arbitrary JSON to, and the app's
            ;; posture everywhere else is that unauthenticated callers get
            ;; /health and the two public credential documents, nothing more.
            ;;
            ;; An invalid credential is a 200 with :valid? false, not an error
            ;; status. "Is this credential good?" was answered successfully; the
            ;; answer was no.
            (and (= method "POST") (= path "/api/credentials/verify"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              ;; read-json-raw, not read-json: a credential is a document whose
              ;; keys are part of what was signed, and read-json keywordizes every
              ;; key at every depth. That is what read-json-raw exists for.
              (let [body (read-json-raw exchange)
                    presented (or (get body "credential") body)]
                (send! exchange 200
                       (assoc (credential/verify-presented presented)
                              :schema credential/schema))))

            ;; Verify a credential issued by ANOTHER organization. Separate from
            ;; /api/credentials/verify because the trust question is different:
            ;; that route checks a credential against this app's own key, while
            ;; this one has to decide whether to believe a stranger. It answers
            ;; only for issuers in :credentials :trusted-issuers, which is
            ;; deny-by-default and shipped empty — for did:web the trust list is
            ;; not hardening, it IS the trust model, since anyone can publish a
            ;; DID document at a domain they control and sign with the matching
            ;; key.
            ;;
            ;; :valid? is true only when revocation was actually determined. A
            ;; credential naming a status list we cannot resolve comes back
            ;; :revocation :unchecked and :valid? false.
            (and (= method "POST") (= path "/api/credentials/verify/external"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              ;; read-json-raw, not read-json: a credential is a document whose
              ;; keys are part of what was signed, and read-json keywordizes every
              ;; key at every depth. That is what read-json-raw exists for.
              (let [body (read-json-raw exchange)
                    presented (or (get body "credential") body)]
                (send! exchange 200
                       (credential-trust/verify-external config presented))))

            ;; Issue the same membership claim as an SD-JWT VC, whose SUBJECT
            ;; the holder can withhold. The Data Integrity path above discloses
            ;; everything to whoever sees the credential, so presenting it twice
            ;; links those presentations; this format does not.
            ;;
            ;; Both formats exist rather than one replacing the other, and the
            ;; response says `bearer-presentable?` because without `cnf` this
            ;; proves what the organization asserted and not who presented it.
            (and (= method "POST") (= path "/api/credentials/membership/sd-jwt-vc"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (identity/require-passkey! session)
              (let [context (identity/membership-credential-context session)]
                (send! exchange 200
                       (credential-sd-jwt/issue
                        (assoc context
                               :issued-at (quot (System/currentTimeMillis) 1000))))))

            (and (= method "POST") (= path "/api/credentials/sd-jwt-vc/verify"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              ;; A presentation is a compact string, not a document, so read-json
              ;; is right here — but the field is read explicitly rather than
              ;; falling back to the whole body.
              (let [body (read-json exchange)
                    presentation (or (:presentation body) (:credential body))]
                (send! exchange 200
                       (credential-sd-jwt/verify presentation))))

            ;; Ask a wallet for a presentation (OID4VP 1.0, Verifier side).
            ;; Returns the Authorization Request; the caller renders it as a QR
            ;; code or a link. This app cannot BE a wallet -- a Passkey cannot
            ;; produce a key-binding proof over a nonce we chose -- so only the
            ;; asking half exists.
            (and (= method "POST") (= path "/api/presentations/requests"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (assoc (presentation-request/create!
                             {:response-uri (str (origin config)
                                                 "/api/presentations/responses")
                              :actor (:user-id session)})
                            :schema presentation-request/schema)))

            (and (= method "GET") (= path "/api/presentations/requests"))
            (let [_ (require-app-session! exchange)]
              (send! exchange 200
                     {:schema presentation-request/schema
                      :pending (presentation-request/pending (store/snapshot))}))

            ;; Where the wallet posts back (response_mode=direct_post).
            ;;
            ;; NO session and NO CSRF, necessarily: the caller is a wallet acting
            ;; for the holder, not a browser carrying our cookie, so a session gate
            ;; here would make the endpoint unusable. What authorises the request
            ;; instead is `state` -- a 256-bit value this app minted, stored, and
            ;; will accept exactly once. That is the whole reason §5.3 makes it
            ;; REQUIRED when there is no key binding.
            ;;
            ;; read-json-raw: a vp_token is a document whose keys are part of what
            ;; was signed.
            (and (= method "POST") (= path "/api/presentations/responses"))
            (let [body (read-json-raw exchange)]
              (send! exchange 200
                     (presentation-request/validate-response body)))

            ;; Which external issuers this deployment believes. Read-only, and
            ;; worth exposing: an operator who cannot see that the list is empty
            ;; will read every external verification failure as a bug.
            (and (= method "GET") (= path "/api/credentials/trusted-issuers"))
            (let [_ (require-app-session! exchange)]
              (send! exchange 200
                     {:schema credential-trust/schema
                      :trusted-issuers (vec (sort (credential-trust/trusted-issuers config)))}))

            ;; The status list a verifier fetches to learn whether a credential
            ;; we issued has been revoked. Public for the same reason, and signed
            ;; so that serving it from anywhere is safe: an unverified list of
            ;; zeros would un-revoke everything, so the proof is the point.
            (and (= method "GET") (= path credential/status-list-id-suffix))
            (send! exchange 200
                   (credential/sign
                    (credential/status-list-credential
                     (store/snapshot)
                     (identity/organization-domain-for-did-web))
                    (identity/organization-domain-for-did-web)))

            ;; Public like /health: every value returned is a read of public
            ;; chain state or a pure PieceCID computation. Nothing here touches
            ;; the workspace, so it is deliberately not behind require-session!.
            (and (= method "GET") (= path "/api/filecoin"))
            (send! exchange 200 (assoc (filecoin/status)
                                       :sample (filecoin/sample)))

            ;; What each enrolled authenticator actually proves, and whether it
            ;; clears each authority's floor. This is the read that closes the
            ;; loop on `:credential-policy`: the shipped floor is the strongest
            ;; one known to be reachable, and raising it should follow from what
            ;; this reports on real hardware rather than from a guess.
            (and (= method "GET") (= path "/api/passkeys/assurance"))
            (let [session (require-app-session! exchange)
                  state (store/snapshot)
                  credentials (->> (vals (get-in state [:identity :passkeys] {}))
                                   (filter #(= (:user-id session) (:user-id %)))
                                   (sort-by :created-at))]
              (send! exchange 200
                     {:schema "cloud.itonami.app.passkeys.assurance.v1"
                      :credentials (mapv credential-assurance/report credentials)
                      :authorities
                      (into {}
                            (for [k (sort (keys authority-api/adapters))]
                              [k {:policy (credential-assurance/policy-for
                                                (get-in config [:authorities k :credential-policy]))
                                  :accepted
                                  (mapv :credential-id
                                        (filter #(empty?
                                                  (credential-assurance/policy-issues
                                                   % (credential-assurance/policy-for
                                                (get-in config [:authorities k :credential-policy]))))
                                                credentials))}]))}))

            ;; ---- fleet directory + 事業者としての参与 ----
            ;;
            ;; Reads are unauthenticated: the directory is the fleet's public
            ;; face and every blueprint in it is already public OSS. The writes
            ;; are the operator's own record and take the session, the origin
            ;; check and the CSRF token, because a declaration carries a name
            ;; and a date and an endpoint registration changes what the app
            ;; reports as running.

            (and (= method "GET") (= path "/api/fleet"))
            (send! exchange 200
                   {:counts (fleet/counts)
                    :facets {:role (fleet/facets :role)
                             :maturity (fleet/facets :maturity)
                             :execution (fleet/facets :execution)
                             :iso3166 (take 30 (fleet/facets :iso3166))}})

            (and (= method "GET") (= path "/api/fleet/search"))
            (let [q (query-params exchange)
                  ;; query-params keywordizes its keys.
                  crit (cond-> {}
                         (seq (:text q)) (assoc :text (:text q))
                         (seq (:role q)) (assoc :role (keyword (:role q)))
                         (seq (:maturity q)) (assoc :maturity (keyword (:maturity q)))
                         (seq (:iso3166 q)) (assoc :iso3166 (:iso3166 q))
                         (= "true" (:callable q)) (assoc :callable? true))
                  hits (fleet/search crit)
                  op (operator/profile)]
              (send! exchange 200
                     {:total (count hits)
                      ;; Paged at 200. Said out loud rather than silently cut:
                      ;; a directory that quietly shows a slice of 1,213 reads
                      ;; as a complete answer.
                      :shown (min 200 (count hits))
                      :actors (into []
                                    (map (fn [a]
                                           (cond-> (select-keys
                                                    a [:repo :id :name :domain :role
                                                       :maturity :execution :iso3166
                                                       :isic :isic-rev5 :isco-08
                                                       :governor :endpoint :deploy-config])
                                             op (assoc :fit (operator/fit op a)))))
                                    (take 200 hits))}))

            (and (= method "GET") (= path "/api/operator"))
            (send! exchange 200
                   (let [op (operator/profile)]
                     {:summary (operator/summary)
                      :profile op
                      :caveat operator/attestation-caveat
                      :adoptions (operator/adoptions)
                      :matches (when op
                                 (into [] (comp (map #(select-keys
                                                       % [:repo :name :domain :role
                                                          :maturity :fit :deploy-config]))
                                                (take 20))
                                       (operator/matches op)))}))

            (and (= method "GET")
                 (re-matches #"/api/operator/readiness/([^/]+)" path))
            (let [repo (second (re-matches #"/api/operator/readiness/([^/]+)" path))
                  a (fleet/actor repo)]
              (if a
                (send! exchange 200
                       (assoc (operator/readiness (operator/profile) a)
                              :actor (select-keys a [:repo :name :domain :role :maturity
                                                     :governor :required-technologies
                                                     :deploy-config :endpoint])
                              :adoption (operator/adoption repo)
                              :caveat operator/attestation-caveat))
                (send! exchange 404 {:error "unknown blueprint"})))

            (and (= method "POST") (= path "/api/operator/profile"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (operator/save-profile! (read-json exchange))))

            (and (= method "POST") (= path "/api/operator/declare"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (let [{:keys [repo by note]} (read-json exchange)]
                (send! exchange 200 (operator/declare! repo {:by by :note note}))))

            (and (= method "POST") (= path "/api/operator/withdraw"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (let [{:keys [repo by]} (read-json exchange)]
                (send! exchange 200 (operator/withdraw! repo {:by by}))))

            (and (= method "POST") (= path "/api/operator/endpoint"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (let [{:keys [repo endpoint health-path by]} (read-json exchange)]
                (send! exchange 200
                       (operator/register-endpoint!
                        repo (cond-> {:endpoint endpoint :by by}
                               (seq health-path) (assoc :health-path health-path))))))

            ;; ---- agent session — a CLI or MCP client's way in ----
            ;;
            ;; Enrollment is the one route that takes no session, because it is
            ;; the route that issues one. What it takes instead is the data
            ;; directory's 0600 key file, which is the boundary that actually
            ;; holds here: anything able to read it can rewrite state.edn and
            ;; mint itself a session directly. See `agent-session`'s docstring.
            ;;
            ;; Origin is not required (a CLI has none to send) and CSRF is not
            ;; required (there is no cookie a browser could be tricked into
            ;; attaching). Listing and revoking DO take a session, because by
            ;; then one exists and the question is who is asking.

            (and (= method "POST") (= path "/api/agent-session"))
            (send! exchange 200 (agent-session/enroll! (read-json exchange)))

            (and (= method "GET") (= path "/api/agent-session"))
            (do (require-app-session! exchange)
                (send! exchange 200 {:schema agent-session/schema
                                     :sessions (agent-session/sessions)}))

            (and (= method "POST")
                 (re-matches #"/api/agent-session/([^/]+)/revoke" path))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (agent-session/revoke!
                      (second (re-matches #"/api/agent-session/([^/]+)/revoke"
                                          path)))))

            ;; ---- 事業 (business) — the entity the analysis planes join on ----
            ;;
            ;; Every route here takes the session: a business belongs to an
            ;; organization, like a funding account, and the portfolio reads what
            ;; that organization has bound. The reads are NOT public the way the
            ;; fleet directory is — the directory is public OSS, while which
            ;; blueprint some organization decided is one of its businesses is
            ;; that organization's own record.
            ;;
            ;; The portfolio touches no analysis plane in write mode. It reads
            ;; the BMC base datoms and repo taxonomy out of the configured
            ;; workspace checkout and reports each face's state; with no checkout
            ;; configured every plane-backed face is :unresolvable and says so.

            (and (= method "GET") (= path "/api/business"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200 (business/portfolio config session)))

            (and (= method "POST") (= path "/api/business"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (business/create! session (read-json exchange))))

            (and (= method "POST")
                 (re-matches #"/api/business/([^/]+)/bind" path))
            (let [session (require-app-session! exchange)
                  id (second (re-matches #"/api/business/([^/]+)/bind" path))]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (business/bind! session id (read-json exchange))))

            ;; ---- 事業の canvas（読みは投影、書きは提案）----
            ;;
            ;; The read is the FOLDED canvas, generated upstream by
            ;; `gftd canvas datoms`. The write is a proposal recorded in this
            ;; app's store: `canvas-ledger.edn` is append-only and governed, and
            ;; this app has no governor. So there is no route that appends to it —
            ;; not one that fails, one that does not exist.

            (and (= method "GET")
                 (re-matches #"/api/business/([^/]+)/canvas" path))
            (let [session (require-app-session! exchange)
                  id (second (re-matches #"/api/business/([^/]+)/canvas" path))]
              (if-some [snapshot (canvas/snapshot config session id)]
                (send! exchange 200 snapshot)
                (send! exchange 404 {:error {:type "not-found"
                                             :message "該当する business がありません"}})))

            (and (= method "POST")
                 (re-matches #"/api/business/([^/]+)/canvas/propose" path))
            (let [session (require-app-session! exchange)
                  id (second (re-matches #"/api/business/([^/]+)/canvas/propose" path))]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (canvas/propose! session id (read-json exchange))))

            (and (= method "POST")
                 (re-matches #"/api/business/[^/]+/canvas/proposals/([^/]+)/withdraw" path))
            (let [session (require-app-session! exchange)
                  proposal-id (second (re-matches
                                       #"/api/business/[^/]+/canvas/proposals/([^/]+)/withdraw"
                                       path))
                  {:keys [by]} (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (canvas/withdraw! session proposal-id {:by by})))

            ;; ---- 事業の loops（stock-flow 構造・シミュレーション・leverage）----
            ;;
            ;; Read-only, and there is no write path: the simulator is
            ;; `xmile.execute/run` and the leverage ledger belongs to
            ;; `loop-system-dynamics`. This app runs the one and reads the other.

            (and (= method "GET")
                 (re-matches #"/api/business/([^/]+)/loops" path))
            (let [session (require-app-session! exchange)
                  id (second (re-matches #"/api/business/([^/]+)/loops" path))]
              (if-some [snapshot (loops/snapshot config session id)]
                (send! exchange 200 snapshot)
                (send! exchange 404 {:error {:type "not-found"
                                             :message "該当する business がありません"}})))

            ;; ---- portfolio matrix ----
            ;;
            ;; Its own endpoint rather than part of /api/business, which loads at
            ;; startup: this one re-runs every bound XMILE model (plus one run per
            ;; constant for the sensitivity sweep) and reads 4.8 MB of repo
            ;; planes. Paid when somebody opens the pane, not when the app boots.

            (and (= method "GET") (= path "/api/portfolio/matrix"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200 (portfolio/matrix config session)))

            ;; ---- 事業の repo と実測 ----
            ;;
            ;; Both read-only, both joined out of generated files in the
            ;; configured workspace: repo-taxonomy + repo-maturity on
            ;; :repo/path, and metrics/<product>.edn on the bound canvas
            ;; product. Neither has a write path.

            (and (= method "GET")
                 (re-matches #"/api/business/([^/]+)/repos" path))
            (let [session (require-app-session! exchange)
                  id (second (re-matches #"/api/business/([^/]+)/repos" path))]
              (if-some [snapshot (business-repos/snapshot config session id)]
                (send! exchange 200 snapshot)
                (send! exchange 404 {:error {:type "not-found"
                                             :message "該当する business がありません"}})))

            (and (= method "GET")
                 (re-matches #"/api/business/([^/]+)/metrics" path))
            (let [session (require-app-session! exchange)
                  id (second (re-matches #"/api/business/([^/]+)/metrics" path))]
              (if-some [snapshot (business-metrics/snapshot config session id)]
                (send! exchange 200 snapshot)
                (send! exchange 404 {:error {:type "not-found"
                                             :message "該当する business がありません"}})))

            ;; ---- funding accounts (what the payment authority stands on) ----
            ;; These are reads and writes of the organization's own record, not
            ;; an outward authority, so they are not behind an `:authorities`
            ;; switch. They still require the session, the origin check and the
            ;; CSRF token: linking an account changes which account a settlement
            ;; would be drawn on, and recording a balance changes whether the
            ;; funds gate opens.

            (and (= method "GET") (= path "/api/funding"))
            (let [session (require-human-session! exchange)]
              (send! exchange 200 (funding/snapshot config session)))

            (and (= method "POST") (= path "/api/funding/accounts"))
            (let [session (require-human-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (funding/link-account! session (read-json exchange))))

            ;; A balance is RECORDED, never fetched: this app has no bank
            ;; connector, and the `:as-of` in the body is the instant the bank
            ;; stated, not the instant of this request. See
            ;; `cloud.itonami.app.funding` for why that distinction is load-bearing.
            (and (= method "POST")
                 (re-matches #"/api/funding/accounts/([^/]+)/balance" path))
            (let [session (require-human-session! exchange)
                  [_ account-id] (re-matches
                                  #"/api/funding/accounts/([^/]+)/balance" path)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (funding/record-balance! session account-id
                                              (read-json exchange))))

            (and (= method "POST")
                 (re-matches #"/api/funding/accounts/([^/]+)/close" path))
            (let [session (require-human-session! exchange)
                  [_ account-id] (re-matches
                                  #"/api/funding/accounts/([^/]+)/close" path)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (funding/close-account! session account-id)))

            ;; ---- governed outward authorities (ADR-2607300300) ----
            ;; Every stage requires an app session, the origin check and the CSRF
            ;; token, exactly as the other write surfaces do. `authority-api`
            ;; refuses a disabled authority, and computes the cross-domain posture
            ;; server-side rather than accepting one from the client.

            ;; What is being paid for, and what it costs to stop. Behind a
            ;; session because opening this decrypts vault items — the reveal
            ;; is governed and lands in kagi's ledger, so an unauthenticated
            ;; request must not be able to trigger one. The response carries no
            ;; credential: `kagi.vault-read` redacts sensitive field values
            ;; before this app ever sees them.
            (and (= method "GET") (= path "/api/contracts"))
            (let [_session (require-app-session! exchange)]
              (send! exchange 200 (contracts/report)))

            (and (= method "GET") (= path "/api/authority"))
            (let [session (require-human-session! exchange)]
              (send! exchange 200 (authority-api/overview config session)))

            (and (= method "GET")
                 (authority-from-path path #"/api/authority/([^/]+)"))
            (let [session (require-human-session! exchange)
                  a (authority-from-path path #"/api/authority/([^/]+)")]
              (send! exchange 200 (authority-api/proposals config session a)))

            (and (= method "POST")
                 (authority-from-path path #"/api/authority/([^/]+)/review"))
            (let [session (require-human-session! exchange)
                  a (authority-from-path path #"/api/authority/([^/]+)/review")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (authority-api/review! config session a
                                            (read-json exchange))))

            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/approve/start"))
            (let [session (require-human-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/approve/start")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (authority-api/start-approval!
                      config session a id
                      (get-in config [:server :webauthn-rp-id])
                      (get-in config [:server :public-origin]))))

            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/approve/finish"))
            (let [session (require-human-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/approve/finish")
                  body (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (authority-api/finish-approval!
                      config session a id
                      (:transaction-id body) (:credential body))))

            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/reject"))
            (let [session (require-human-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/reject")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/reject! config session a id)))

            ;; Refresh asks the authority what became of a PENDING proposal.
            ;; Read-only against the authority: it hits the actor's consent
            ;; surface, which cannot decide -- the decision lives on a listener
            ;; this app has no route to, which is the point.
            ;; Resolve everything at once. The per-proposal refresh below is still
            ;; there, but nothing ever called it -- which is why an :authority-pending
            ;; proposal used to sit forever. A caller that opens the authority panel can
            ;; hit this once instead of knowing which proposals to chase.
            (and (= method "POST") (= path "/api/authority/resolve-pending"))
            (let [session (require-human-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/resolve-pending! config session)))

            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/refresh"))
            (let [session (require-human-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/refresh")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/refresh! config session a id)))

            ;; Commit is a separate call from finish-approval! on purpose: the
            ;; hand-off to the actor can refuse (governor, transport), and that
            ;; refusal is an outcome to record and show, not a failure of the
            ;; consent that already happened.
            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/commit"))
            (let [session (require-human-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/commit")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/commit! config session a id)))

            (and (= method "GET") (= path "/api/state"))
            (send! exchange 200 (runtime-state! config exchange))

            ;; Versioned tenant control plane. A connection is immutable loop
            ;; context; unlike the browser organization switcher it never
            ;; mutates shared active-organization state.
            (and (= method "GET") (= path "/v1/tenants"))
            (send! exchange 200
                   (tenant-connection/tenants (require-app-session! exchange)))

            (and (= method "GET") (= path "/v1/tenant-connections"))
            (send! exchange 200
                   (tenant-connection/connections
                    (require-app-session! exchange)))

            (and (= method "POST") (= path "/v1/tenant-connections"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 202
                     (tenant-connection/request! session (read-json exchange))))

            (and (= method "GET")
                 (id-from-path
                  path #"/v1/tenant-connections/([^/]+)/repository"))
            (send! exchange 200
                   (tenant-repository/read!
                    (require-app-session! exchange)
                    (id-from-path
                     path #"/v1/tenant-connections/([^/]+)/repository")))

            (and (= method "POST")
                 (id-from-path
                  path #"/v1/tenant-connections/([^/]+)/repository"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (tenant-repository/write!
                      session
                      (id-from-path
                       path #"/v1/tenant-connections/([^/]+)/repository")
                      (read-json-limited exchange (* 16 1024 1024) keyword))))

            (and (= method "POST")
                 (id-from-path
                  path #"/v1/tenant-connections/([^/]+)/repository/publish"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (tenant-repository/publish!
                      session
                      (id-from-path
                       path
                       #"/v1/tenant-connections/([^/]+)/repository/publish"))))

            (and (= method "GET")
                 (id-from-path path #"/v1/tenant-connections/([^/]+)"))
            (send! exchange 200
                   (tenant-connection/connection
                    (require-app-session! exchange)
                    (id-from-path path #"/v1/tenant-connections/([^/]+)")))

            (and (= method "POST")
                 (id-from-path path #"/v1/tenant-connections/([^/]+)/approve"))
            (let [session (require-human-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (tenant-connection/approve!
                      session
                      (id-from-path path
                                    #"/v1/tenant-connections/([^/]+)/approve"))))

            (and (= method "POST")
                 (id-from-path path #"/v1/tenant-connections/([^/]+)/renew"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 202
                     (tenant-connection/request-renewal!
                      session
                      (id-from-path path
                                    #"/v1/tenant-connections/([^/]+)/renew")
                      (or (:ttl-seconds request) (:ttl_seconds request)))))

            (and (= method "POST")
                 (id-from-path path #"/v1/tenant-connections/([^/]+)/revoke"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (tenant-connection/revoke!
                      session
                      (id-from-path path
                                    #"/v1/tenant-connections/([^/]+)/revoke"))))

            (and (= method "POST")
                 (id-from-path path #"/v1/tenant-connections/([^/]+)/context"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (tenant-connection/context!
                      session
                      (id-from-path path
                                    #"/v1/tenant-connections/([^/]+)/context")
                      (:capability request))))

            (and (= method "GET") (= path "/api/identity"))
            (send! exchange 200
                   (identity/public-state
                    (cookie-value exchange identity/cookie-name)))

            (auth-lifecycle-path? path)
            (handle-auth-lifecycle! exchange config method path)

            (and (= method "POST") (= path "/api/identity/register"))
            (do
              (require-origin! exchange config)
              (let [{:keys [token]} (identity/register! (read-json exchange))]
                (send! exchange 201
                       (identity/public-state token)
                       {"Set-Cookie"
                        (session-cookie token)})))

            (and (= method "POST") (= path "/api/identity/users"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (let [invitation (identity/add-user! session (read-json exchange))]
                (send! exchange 201
                       {:identity
                        (identity/public-state
                         (cookie-value exchange identity/cookie-name))
                        :invitation invitation})))

            (and (= method "POST") (= path "/api/identity/organization"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (identity/configure-organization! session (read-json exchange))
              (send! exchange 200
                     (identity/public-state
                      (cookie-value exchange identity/cookie-name))))

            (and (= method "POST") (= path "/api/identity/organizations"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (identity/create-organization! session (read-json exchange))
              (send! exchange 201
                     (identity/public-state
                      (cookie-value exchange identity/cookie-name))))

            (route-domain-verification! exchange config method path)
            nil

            (and (= method "POST")
                 (= path "/api/identity/organizations/switch"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (identity/switch-organization! session (read-json exchange))
              (send! exchange 200
                     (identity/public-state
                      (cookie-value exchange identity/cookie-name))))

            (and (= method "POST")
                 (= path "/api/identity/organizations/accept"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (identity/accept-organization-invitation!
               session (read-json exchange))
              (send! exchange 200
                     (identity/public-state
                      (cookie-value exchange identity/cookie-name))))

            (and (= method "GET") (= path "/api/cloud/alias"))
            (let [_session (require-app-session! exchange)
                  public (identity/public-state
                          (cookie-value exchange identity/cookie-name))
                  account-id (get-in public [:user :account-id])]
              (when-not account-id
                (throw (ex-info
                        "Relay を使う前に Organization ID を設定してください。"
                        {:type :identity/organization-required})))
              (send! exchange 200 (relay/alias-status! config account-id)))

            (and (= method "POST") (= path "/api/cloud/alias"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)
                  public (identity/public-state
                          (cookie-value exchange identity/cookie-name))
                  account-id (get-in public [:user :account-id])
                  destination (or (:destination request)
                                  (get-in public [:user :contact-email]))]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (when-not account-id
                (throw (ex-info
                        "Relay を使う前に Organization ID を設定してください。"
                        {:type :identity/organization-required})))
              (send! exchange 202
                     (relay/reserve-alias! config account-id destination)))

            (and (= method "POST") (= path "/api/passkeys/register/start"))
            (let [session (require-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (identity/start-passkey-registration!
                      session (rp-id config) (origin config))))

            (and (= method "POST") (= path "/api/passkeys/onboarding/resume"))
            (let [{:keys [token]} (do
                                    (require-origin! exchange config)
                                    (identity/resume-owner-onboarding!))]
              (send! exchange 200
                     (identity/public-state token)
                     {"Set-Cookie" (session-cookie token)}))

            (and (= method "POST") (= path "/api/passkeys/register/finish"))
            (let [session (require-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 201
                     (identity/finish-passkey-registration!
                      session (:transaction-id request) (:credential request))))

            (and (= method "POST") (= path "/api/passkeys/enroll/start"))
            (let [request (read-json exchange)]
              (require-origin! exchange config)
              (send! exchange 200
                     (identity/start-enrollment!
                      (:account-id request) (:enrollment-code request)
                      (rp-id config) (origin config))))

            (and (= method "POST") (= path "/api/passkeys/enroll/finish"))
            (let [request (read-json exchange)
                  result (do
                           (require-origin! exchange config)
                           (identity/finish-enrollment!
                            (:transaction-id request) (:credential request)))]
              (send! exchange 201
                     (identity/public-state (:token result))
                     {"Set-Cookie" (session-cookie (:token result))}))

            (and (= method "POST") (= path "/api/passkeys/authenticate/start"))
            (do
              (require-origin! exchange config)
              (send! exchange 200
                     (identity/start-passkey-authentication!
                      (rp-id config) (origin config))))

            (and (= method "POST") (= path "/api/email-authenticate/start"))
            (let [request (read-json exchange)]
              (require-origin! exchange config)
              (send! exchange 202
                     (identity/start-email-authentication!
                      config (:email request))))

            (and (= method "POST") (= path "/api/email-authenticate/finish"))
            (let [request (read-json exchange)
                  result (do
                           (require-origin! exchange config)
                           (identity/finish-email-authentication!
                            (:token request)))]
              (send! exchange 200
                     (identity/public-state (:token result))
                     {"Set-Cookie" (session-cookie (:token result))}))

            (and (= method "POST")
                 (provider-from-path path #"/api/auth/sso/([^/]+)/start"))
            (let [provider (provider-from-path
                            path #"/api/auth/sso/([^/]+)/start")
                  request (read-json exchange)
                  link? (= "link" (some-> (:mode request) name))
                  session (when link? (require-app-session! exchange))]
              (require-origin! exchange config)
              (when link? (require-csrf! exchange session))
              (send! exchange 200
                     (identity/start-sso-authentication!
                      provider (origin config)
                      {:mode (if link? :link :authenticate)
                       :session session})))

            (and (= method "POST") (= path "/api/passkeys/authenticate/finish"))
            (let [request (read-json exchange)
                  result (do
                           (require-origin! exchange config)
                           (identity/finish-passkey-authentication!
                            (:transaction-id request) (:credential request)))]
              (send! exchange 200
                     (identity/public-state (:token result))
                     {"Set-Cookie" (session-cookie (:token result))}))

            (and (= method "POST")
                 (provider-from-path path #"/api/connections/([^/]+)/start"))
            (let [session (require-app-session! exchange)
                  provider (provider-from-path
                            path #"/api/connections/([^/]+)/start")
                  body (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (identity/start-oauth!
                      session provider (origin config)
                      ;; Adding a SECOND account rather than reconnecting the
                      ;; one that is there. Without saying so the consent
                      ;; screen reuses the browser's current account and the
                      ;; round trip returns the connection that already
                      ;; exists — which looks, to the person who clicked, like
                      ;; nothing happened.
                      {:add-account? (boolean (:add-account body))})))

            (and (= method "GET")
                 (provider-from-path path #"/api/oauth/([^/]+)/callback"))
            (let [provider (provider-from-path
                            path #"/api/oauth/([^/]+)/callback")
                  params (query-params exchange)]
              (if (identity/sso-transaction? provider (:state params))
                (try
                  (let [result (identity/complete-sso-authentication!
                                provider params)]
                    (redirect! exchange
                               (str "/?auth=sso&provider=" (name provider)
                                    "#settings")
                               {"Set-Cookie" (session-cookie (:token result))}))
                  (catch Exception error
                    (identity/record-auth-failure! provider error)
                    (redirect! exchange
                               (str "/?auth=error&provider=" (name provider)
                                    "#settings"))))
                (try
                  (identity/complete-oauth! provider params)
                  (redirect! exchange
                             (str "/?connection=connected&provider="
                                  (name provider) "#settings"))
                  (catch Exception _
                    (redirect! exchange
                               (str "/?connection=error&provider="
                                    (name provider) "#settings"))))))

            (conversation-route? path)
            (handle-conversation! config exchange method path)

            (str/starts-with? path "/api/captures")
            (handle-capture! config exchange method path)

            (and (= method "GET") (= path "/api/workspace"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (workspace/snapshot)))

            ;; Whether mail is arriving, separately from what has arrived.
            ;; An empty Inbox and a sync that has been failing since Tuesday
            ;; look identical in the message list and must not here.
            (and (= method "GET") (= path "/api/mail-sync"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (mail-sync/status)))

            (and (= method "POST") (= path "/api/mail-sync/sync"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (mail-sync/sync-all!)))

            ;; Every mailbox this workspace has been pointed at. Read-only and
            ;; credential-free: `public-account` is what decides that, and it
            ;; selects the fields it hands out rather than removing the ones it
            ;; remembers to.
            (and (= method "GET") (= path "/api/mail/accounts"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     {:schema mail-account/schema :ok? true
                      :accounts (mapv mail-account/public-account
                                      (mail-account/accounts
                                       (:user-did session)))}))

            ;; Registering a mailbox reached over IMAP — the kind OAuth cannot
            ;; reach at all. A password crosses here, so this needs the same
            ;; origin and CSRF checks as anything else that writes.
            (and (= method "POST") (= path "/api/mail/accounts"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     {:schema mail-account/schema :ok? true
                      :account (mail-account/public-account
                                (mail-account/add-imap-account!
                                 request
                                 {:user-did (:user-did session)}))}))

            (and (= method "DELETE")
                 (id-from-path path #"/api/mail/accounts/([^/]+)"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (mail-account/remove-account!
                      (id-from-path path #"/api/mail/accounts/([^/]+)"))))

            ;; One mailbox, on demand. Somebody who has just connected an
            ;; account should not have to wait out the sync interval to find
            ;; out whether it works.
            (and (= method "POST")
                 (id-from-path path #"/api/mail/accounts/([^/]+)/sync"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (mail-sync/sync-account!
                      (mail-account/account!
                       (id-from-path path #"/api/mail/accounts/([^/]+)/sync")
                       (:user-did session)))))

            ;; Sending. This app could not do it at all until now — it showed
            ;; an inbox and had no way to answer anything in it.
            (and (= method "POST") (= path "/api/mail/send"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (mail-send/send! (:account-id request)
                                      request
                                      {:user-did (:user-did session)})))
            (str/starts-with? path "/api/ao/messenger")
            (handle-ao-messenger! exchange method path)

            ;; Messages are speech, never execution authority. Kept outside
            ;; this already-large JVM method for the same reason as governance.
            (str/starts-with? path "/api/messenger")
            (handle-app-messenger! config exchange method path)

            ;; The archive, as this reader has marked it. Not through
            ;; `workspace/snapshot`: that cache is keyed per server, and what
            ;; one person has read is not what the next one has.
            (and (= method "GET") (= path "/api/workspace/inbox"))
            (let [session (require-app-session! exchange)
                  params (query-params exchange)]
              (send! exchange 200
                     (app-mailbox/view (:user-id session)
                                       {:label (:label params)
                                        :query (:q params)
                                        :unread? (when (= "true" (:unread params)) true)})))

            (and (= method "GET")
                 (id-from-path path #"/api/workspace/inbox/threads/([^/]+)"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (app-mailbox/thread
                      (id-from-path path #"/api/workspace/inbox/threads/([^/]+)")
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/inbox/messages/([^/]+)/read"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (app-mailbox/set-read!
                      (id-from-path path #"/api/workspace/inbox/messages/([^/]+)/read")
                      ;; Absent means read: the button that sends nothing is
                      ;; the one that marks it read.
                      (if (contains? request :read?) (:read? request) true)
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/inbox/messages/([^/]+)/label"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (app-mailbox/set-label!
                      (id-from-path path #"/api/workspace/inbox/messages/([^/]+)/label")
                      (:label request)
                      (if (contains? request :on?) (:on? request) true)
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/inbox/messages/([^/]+)/trash"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (app-mailbox/set-trashed!
                      (id-from-path path #"/api/workspace/inbox/messages/([^/]+)/trash")
                      (if (contains? request :trashed?) (:trashed? request) true)
                      (:user-id session))))

            (and (= method "GET") (= path "/api/workspace/projects"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (workspace/snapshot :projects workspace/projects-snapshot)))

            ;; Local Git projects. Kept outside this already-large JVM method for
            ;; the same reason as governance and the messenger: adding these three
            ;; clauses inline pushed `handler`'s reify method past the 64 KB
            ;; bytecode limit and the compiler refused the whole namespace.
            (project-route? path)
            (handle-projects! config exchange method path)

            (site-route? path)
            (handle-sites! config exchange method path)

            ;; The archive half of this is cached for a minute by
            ;; `workspace/snapshot`; the created documents are not, because a
            ;; document that does not appear in the list a moment after it was
            ;; created reads as a failed create.
            (and (= method "GET") (= path "/api/workspace/drive"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/drive-view
                      (workspace/snapshot :drive workspace/drive-snapshot)
                      (:user-id session)
                      ;; A page of created documents; the archive half is
                      ;; already capped by `drive-snapshot`.
                      {:cursor (:cursor (query-params exchange))})))

            ;; ── esign ───────────────────────────────────────────────────────
            ;; A signature is not a share and not a credential: see
            ;; `cloud.itonami.app.esign`. Every one of these needs the session's
            ;; DID as well as its user id, because a commitment names the signer
            ;; by DID and a user with two Passkeys has two of them.

            (and (= method "GET") (= path "/api/esign"))
            (let [session (require-app-session! exchange)
                  who (esign-who session)]
              (send! exchange 200
                     {:schema esign/schema
                      ;; The asker's own DID, so the pane can tell which signer
                      ;; row is theirs. A pane that guessed from the user id
                      ;; would guess wrong for a user with two Passkeys.
                      :my-did (:did who)
                      :envelopes (esign/envelopes (store/snapshot) who)}))

            (and (= method "POST") (= path "/api/esign/envelopes"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (esign/envelope-view
                      (esign/create!
                       {:document-id (:document-id request)
                        :purpose (some-> (:purpose request) keyword)
                        :intent (:intent request)
                        :signer-dids (vec (:signer-dids request))
                        :actor (:user-id session)
                        :organization-did (identity/session-organization-did session)})
                      (esign-who session))))

            (and (= method "GET")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (esign/envelope! (store/snapshot)
                                      (id-from-path path #"/api/esign/envelopes/([^/]+)")
                                      (esign-who session))))

            ;; The response carries the commitment and the outline, so the
            ;; signing UI can show exactly what the challenge was computed over
            ;; rather than asking the signer to trust that it was the right
            ;; thing.
            (and (= method "POST")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/sign/start"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (esign/start-signature!
                      {:envelope-id (id-from-path
                                     path #"/api/esign/envelopes/([^/]+)/sign/start")
                       :did (:did (esign-who session))
                       :user-id (:user-id session)
                       :rp-id (rp-id config)
                       :origin (origin config)})))

            (and (= method "POST")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/sign/finish"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (let [result (esign/finish-signature!
                                   {:transaction-id (:transaction-id request)
                                    :response (:credential request)
                                    :user-id (:user-id session)
                                    :rp-id (rp-id config)})]
                       {:schema esign/schema
                        :envelope (esign/envelope-view (:envelope result)
                                                       (esign-who session))
                        :verification (select-keys (:verification result)
                                                   [:verified :user-verified?
                                                    :sign-count])})))

            (and (= method "POST")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/decline"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (esign/envelope-view
                      (esign/decline!
                       {:envelope-id (id-from-path
                                      path #"/api/esign/envelopes/([^/]+)/decline")
                        :did (:did (esign-who session))
                        :user-id (:user-id session)
                        :reason (:reason request)})
                      (esign-who session))))

            ;; Downloaded and handed to a counterparty. Digests, DIDs, public
            ;; keys and signed credentials — no document bytes and no outline,
            ;; which is what makes `forget-content` possible at all.
            (and (= method "GET")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/evidence"))
            (let [session (require-app-session! exchange)
                  id (id-from-path path #"/api/esign/envelopes/([^/]+)/evidence")
                  ;; Through `envelope!` for the permission check, then the
                  ;; stored envelope for the record: the view is for a UI and
                  ;; the evidence is not a projection of it.
                  _ (esign/envelope! (store/snapshot) id (esign-who session))]
              (send! exchange 200
                     (esign/evidence
                      (get-in (store/snapshot) [:esign :envelopes id]))))

            ;; The erasure path. Destroys the outline a signer read; keeps the
            ;; proof that a document with that digest was signed. Does NOT
            ;; remove the document from the Drive — that is `purge!`.
            (and (= method "POST")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/forget-content"))
            (let [session (require-app-session! exchange)
                  id (id-from-path
                      path #"/api/esign/envelopes/([^/]+)/forget-content")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (esign/envelope! (store/snapshot) id (esign-who session))
              (send! exchange 200
                     (esign/envelope-view (esign/forget-content! id)
                                          (esign-who session))))

            ;; The three fields 電子帳簿保存法 requires be retained and
            ;; searchable. A separate step from signing because most envelopes
            ;; are not transactions — making it part of every signature would
            ;; push operators into inventing a 取引金額 for a consent form.
            (and (= method "POST")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/retention"))
            (let [session (require-app-session! exchange)
                  id (id-from-path path #"/api/esign/envelopes/([^/]+)/retention")
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (esign/envelope! (store/snapshot) id (esign-who session))
              (send! exchange 200
                     (esign/record-retention!
                      id {:transaction-date (:transaction-date request)
                          :amount-minor (:amount-minor request)
                          :currency (:currency request)
                          :counterparty (:counterparty request)
                          :basis (:basis request)
                          :note (:note request)})))

            ;; 検索要件: date range, amount range, counterparty, combinable.
            (and (= method "GET") (= path "/api/esign/retention"))
            (let [session (require-app-session! exchange)
                  q (query-params exchange)]
              (require-app-session! exchange)
              (send! exchange 200
                     {:schema esign-retention/schema
                      :entries (esign-retention/search
                                (store/snapshot)
                                {:date-from (get q "date-from")
                                 :date-to (get q "date-to")
                                 :amount-min (some-> (get q "amount-min") parse-long)
                                 :amount-max (some-> (get q "amount-max") parse-long)
                                 :currency (get q "currency")
                                 :counterparty (get q "counterparty")})
                      :user (:user-id session)}))

            ;; What is still missing for 電子帳簿保存法 on one envelope. Gaps,
            ;; never a tick — see `retention/compliance-gaps`.
            (and (= method "GET")
                 (id-from-path path #"/api/esign/envelopes/([^/]+)/compliance"))
            (let [session (require-app-session! exchange)
                  id (id-from-path path #"/api/esign/envelopes/([^/]+)/compliance")]
              (esign/envelope! (store/snapshot) id (esign-who session))
              (send! exchange 200
                     (esign/compliance
                      (store/snapshot) id
                      {:procedure-documented?
                       (boolean (get-in config [:esign :procedure-documented?]))})))

            ;; Verifying a record this app may not have issued. Behind the
            ;; session like everything else: the verification itself is pure and
            ;; fetches nothing, but this process binds loopback, so an
            ;; unauthenticated endpoint would add parsing surface without being
            ;; reachable by the counterparty it would be for. A counterparty
            ;; runs `esign/verify-evidence` themselves — that it needs no
            ;; session, no network and no clock is the point of it.
            (and (= method "POST") (= path "/api/esign/verify"))
            (let [session (require-app-session! exchange)
                  ;; `read-json-raw`, not `read-json`: an evidence record's
                  ;; `commitment` is RFC 8785 JSON and its keys MUST stay
                  ;; strings. Keywordizing them and printing them back produces
                  ;; different canonical bytes, so every signature would fail to
                  ;; verify for a reason that had nothing to do with the
                  ;; signature. This is why the record is string-keyed
                  ;; end to end — see `esign/signature-entry`.
                  request (read-json-raw exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (esign/verify-evidence (get request "evidence")
                                            {:rp-id (rp-id config)})))

            (and (= method "POST") (= path "/api/workspace/drive/documents"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/create! (some-> (:kind request) name keyword)
                                        (:title request)
                                        (:user-id session)
                                        (documents/store-instance)
                                        {:folder (:folder request)})))

            ;; Folders. Separate from documents because a folder has no
            ;; bytes, no versions and no resource kind — routing it through
            ;; the document endpoint would mean a request whose half the
            ;; fields are meaningless.
            (and (= method "POST") (= path "/api/workspace/drive/folders"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/create-folder! (:title request)
                                               (:user-id session)
                                               (:folder request))))

            (and (= method "GET") (= path "/api/workspace/drive/folders"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (or (documents/folders (store/snapshot) (:user-id session)
                                            (:folder (query-params exchange)))
                         {:error {:type "drive/not-found"
                                  :message "そのフォルダはありません。"}})))

            ;; Readable, not writable: a viewer may copy, and that is the
            ;; point of the operation.
            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/copy"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/copy!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/copy")
                      (:user-id session)
                      (documents/store-instance)
                      {:title (:title request) :folder (:folder request)})))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/move"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/move!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/move")
                      (:folder request)
                      (:user-id session))))

            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/content
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)")
                      (:user-id session))))

            ;; The payload only, never a whole envelope: the resource kind is
            ;; rebuilt from what the item already records, so an edit cannot
            ;; rewrite its own discriminant.
            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/versions"))
            (let [session (require-app-session! exchange)
                  request (read-json-raw exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/update!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/versions")
                      (get request "payload")
                      (:user-id session)
                      ;; The version this edit was made from. A save that
                      ;; does not say is refused, not applied — see
                      ;; `documents/update!`.
                      (get request "etag"))))

            ;; Nothing prunes on its own, so this is a thing the owner asks
            ;; for — see `documents/prune!` for why not automatically.
            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/prune"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/prune!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/prune")
                      (:user-id session)
                      (or (:keep request) documents/default-keep-versions))))

            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/history"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/history
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/history")
                      (:user-id session))))

            ;; A restore is a save, so it carries an etag like one — putting
            ;; an old version back on top of a change you have not seen is
            ;; the lost update wearing a different hat.
            (and (= method "POST")
                 (re-matches #"/api/workspace/drive/documents/([^/]+)/versions/(\d+)/restore"
                             path))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)
                  [_ id index]
                  (re-matches #"/api/workspace/drive/documents/([^/]+)/versions/(\d+)/restore"
                              path)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/restore-version! id (parse-long index)
                                                 (:user-id session) (:etag request))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/rename"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/rename!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/rename")
                      (:title request)
                      (:user-id session))))

            (and (= method "GET")
                 (re-matches #"/api/workspace/drive/documents/([^/]+)/versions/(\d+)" path))
            (let [session (require-app-session! exchange)
                  [_ id index]
                  (re-matches #"/api/workspace/drive/documents/([^/]+)/versions/(\d+)" path)]
              (send! exchange 200
                     (documents/version-content id (parse-long index)
                                                (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/restore"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/restore!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/restore")
                      (:user-id session))))

            ;; Irreversible, and only reachable for something already in the
            ;; trash — `documents/purge!` refuses anything else.
            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/purge"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/purge!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/purge")
                      (:user-id session))))

            ;; The candidates come from identity rather than from documents:
            ;; who exists is the directory's question, and `documents` stays
            ;; able to grant to any principal string without knowing where
            ;; the name came from.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/sharing"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (assoc (documents/sharing
                             (id-from-path path
                                           #"/api/workspace/drive/documents/([^/]+)/sharing")
                             (:user-id session))
                            :candidates (share-candidates exchange (:user-id session)))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/sharing"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)
                  id (id-from-path path
                                   #"/api/workspace/drive/documents/([^/]+)/sharing")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (case (:action request)
                       "revoke" (documents/revoke-grant! id (:principal request)
                                                        (:user-id session))
                       "link" (documents/create-link! id (:role request)
                                                      (:expires-in-hours request)
                                                      (:user-id session)
                                                      (System/currentTimeMillis))
                       "revoke-link" (documents/revoke-link! id (:token request)
                                                            (:user-id session))
                       (documents/grant! id (:principal request) (:role request)
                                         (:user-id session)))))

            ;; Inside the documents, not only across their names. Separate
            ;; from the Drive listing because it reads every readable
            ;; document's bytes and the listing must not.
            (and (= method "GET") (= path "/api/workspace/drive/search"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/search (:q (query-params exchange))
                                       (:user-id session))))

            ;; Binary out. Not `send!` — a PPTX is a zip, and a CSV that has
            ;; been through a JSON string is a CSV with quotes in it.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/export"))
            (let [session (require-app-session! exchange)
                  params (query-params exchange)
                  {:keys [media-type filename bytes]}
                  (documents/export
                   (id-from-path path #"/api/workspace/drive/documents/([^/]+)/export")
                   (or (:format params) "edn")
                   (:user-id session)
                   (documents/store-instance)
                   {:tab (:tab params)})]
              (send-bytes! exchange media-type filename bytes))

            ;; The same shape as import: the body is the file, the name is
            ;; in the query. Uploading is not importing — an import becomes
            ;; a document of one of the four surfaces, and this stays bytes.
            (and (= method "POST") (= path "/api/workspace/drive/upload"))
            (let [session (require-app-session! exchange)
                  params (query-params exchange)
                  body (read-body-bytes exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/upload! (:filename params)
                                        (:media-type params)
                                        body
                                        (:user-id session)
                                        (documents/store-instance)
                                        {:folder (:folder params)})))

            ;; A page the browser can print. Not a PDF: one of a Japanese
            ;; document needs a CJK font embedded, and the browser already
            ;; has one — so the reader's own "print to PDF" is the export,
            ;; with their fonts, paper size and margins.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/print"))
            (let [session (require-app-session! exchange)
                  out (documents/printable
                       (id-from-path path
                                     #"/api/workspace/drive/documents/([^/]+)/print")
                       (:user-id session))
                  page (str "<!doctype html><html lang=\"ja\"><head>"
                            "<meta charset=\"utf-8\">"
                            "<title>" (docs-html/esc (:title out)) "</title>"
                            "<style>"
                            ;; No web fonts and no colours: a printed page
                            ;; uses the reader's own fonts, and a colour
                            ;; background is ink somebody pays for.
                            "body{font-family:system-ui,sans-serif;line-height:1.7;"
                            "max-width:44rem;margin:2rem auto;padding:0 1rem;color:#000}"
                            "table{border-collapse:collapse;width:100%}"
                            "td,th{border:1px solid #999;padding:.3rem .5rem;"
                            "text-align:left;vertical-align:top}"
                            "pre{white-space:pre-wrap;border:1px solid #ccc;padding:.5rem}"
                            "blockquote{border-left:3px solid #ccc;margin-left:0;"
                            "padding-left:1rem}"
                            ".docs-ref{color:#555;font-size:.9em}"
                            ".answer-line{border-bottom:1px solid #999;height:1.6rem}"
                            ".slide svg{width:100%;height:auto;border:1px solid #ccc}"
                            ;; The notes under the slide, in the reader's
                            ;; own type: this is a handout, and a presenter
                            ;; reads them at arm's length.
                            ".slide-notes{margin:.75rem 0 0;white-space:pre-wrap;"
                            "font-size:.9375rem;line-height:1.7}"
                            ;; One slide per page, and never a heading left
                            ;; alone at the bottom of one.
                            "@media print{body{margin:0;max-width:none}"
                            ".slide{break-after:page}"
                            "h1,h2,h3{break-after:avoid}"
                            "table,pre,blockquote,.question{break-inside:avoid}}"
                            "</style></head><body>"
                            (:html out)
                            "</body></html>")]
              (doto (.getResponseHeaders exchange)
                (.set "Content-Type" "text/html; charset=utf-8")
                (.set "Cache-Control" "no-store")
                (.set "X-Content-Type-Options" "nosniff"))
              (let [bytes (.getBytes ^String page StandardCharsets/UTF_8)]
                (.sendResponseHeaders exchange 200 (alength bytes))
                (with-open [o (.getResponseBody exchange)] (.write o bytes))))

            ;; Inline, and only for the few types that cannot carry script.
            ;; A separate route rather than a flag on the download, so the
            ;; safe path cannot be reached for a .html by adding a query
            ;; parameter — `documents/file-bytes` decides, and this refuses
            ;; anything it did not mark inline.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/preview"))
            (let [session (require-app-session! exchange)
                  out (documents/file-bytes
                       (id-from-path path
                                     #"/api/workspace/drive/documents/([^/]+)/preview")
                       (:user-id session))]
              (if-not (:inline? out)
                (send! exchange 415
                       {:error {:type "drive/not-previewable"
                                :message "この形式はプレビューできません。"}})
                (do (doto (.getResponseHeaders exchange)
                      (.set "Content-Type" (:media-type out))
                      (.set "Cache-Control" "no-store")
                      (.set "X-Content-Type-Options" "nosniff")
                      ;; Belt as well as braces: even if a type slipped
                      ;; through the allowlist, nothing in this response may
                      ;; load or run anything.
                      (.set "Content-Security-Policy"
                            "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                      (.set "Content-Disposition" "inline"))
                    (.sendResponseHeaders exchange 200 (alength ^bytes (:bytes out)))
                    (with-open [o (.getResponseBody exchange)]
                      (.write o ^bytes (:bytes out))))))

            ;; One page of an uploaded PDF. One clause, delegating, because
            ;; this `cond` compiles to a single method and the JVM caps one
            ;; at 64 KB — the two routes written out here were what first
            ;; exceeded it (`Method code too large!`, measured, not feared).
            ;; The bodies live in `page-routes` above.
            (page-route? method path) (page-routes! exchange path)

            ;; Served as an attachment with a fixed octet-stream type,
            ;; whatever the file claims to be. Bytes uploaded by one person
            ;; and served from this origin to another are stored XSS if the
            ;; browser is allowed to decide they are HTML.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/download"))
            (let [session (require-app-session! exchange)
                  out (documents/file-bytes
                       (id-from-path path
                                     #"/api/workspace/drive/documents/([^/]+)/download")
                       (:user-id session))]
              (.set (.getResponseHeaders exchange) "X-Content-Type-Options" "nosniff")
              (send-bytes! exchange (:media-type out) (:filename out) (:bytes out)))

            ;; The body is the file. No multipart: one file per request with
            ;; the name in the query is the whole of what this needs, and a
            ;; multipart parser is a lot of surface to add for a boundary
            ;; string.
            (and (= method "POST") (= path "/api/workspace/drive/import"))
            (let [session (require-app-session! exchange)
                  params (query-params exchange)
                  body (read-body-bytes exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/import! (or (:format params) "edn")
                                        (:title params)
                                        body
                                        (:user-id session)
                                        (documents/store-instance)
                                        {:folder (:folder params)})))

            ;; What this document points at, and what points at it. Both
            ;; resolve through `locate`, so a reference to something the
            ;; asker may not read is reported as unresolved rather than
            ;; leaking that it exists.
            ;; Inserting or removing rows is a save too, and the formulas
            ;; follow — `sheets.edit` rewrites them, and the answer says
            ;; what did not follow.
            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/rows"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/shift-rows!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/rows")
                      {:tab (:tab request) :axis (:axis request) :at (:at request)
                       :count (:count request) :action (:action request)}
                      (:user-id session)
                      (:etag request))))

            ;; Sorting is a save, so it takes an etag like a save.
            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/sort"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/sort-range!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/sort")
                      {:tab (:tab request) :range (:range request)
                       :by (:by request) :ascending? (:ascending? request)}
                      (:user-id session)
                      (:etag request))))

            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/references"))
            (let [session (require-app-session! exchange)
                  id (id-from-path path
                                   #"/api/workspace/drive/documents/([^/]+)/references")]
              (send! exchange 200
                     (merge (documents/references id (:user-id session))
                            (documents/referenced-by id (:user-id session)))))

            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/comments"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/comments
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/comments")
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/comments"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/comment!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/comments")
                      (:text request) (:anchor request) (:user-id session)
                      ;; Present means a reply; `documents/comment!` decides
                      ;; which thread it lands in.
                      (:parent-id request))))

            (and (= method "POST")
                 (re-matches #"/api/workspace/drive/documents/([^/]+)/comments/([^/]+)/resolve"
                             path))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)
                  [_ id comment-id]
                  (re-matches #"/api/workspace/drive/documents/([^/]+)/comments/([^/]+)/resolve"
                              path)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/resolve-comment! id comment-id
                                                 (not= false (:resolved? request))
                                                 (:user-id session))))

            (and (= method "POST")
                 (re-matches #"/api/workspace/drive/documents/([^/]+)/comments/([^/]+)/delete"
                             path))
            (let [session (require-app-session! exchange)
                  [_ id comment-id]
                  (re-matches #"/api/workspace/drive/documents/([^/]+)/comments/([^/]+)/delete"
                              path)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/delete-comment! id comment-id (:user-id session))))

            ;; The form as something to fill in. Readable by anyone who may
            ;; read the document, because a form shared read-only is a form
            ;; meant to be answered.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/form"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/form-for-answering
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/form")
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/submissions"))
            (let [session (require-app-session! exchange)
                  request (read-json-raw exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/submit!
                      (id-from-path path
                                    #"/api/workspace/drive/documents/([^/]+)/submissions")
                      (get request "answers")
                      (:user-id session))))

            ;; Owner only, and a mutation: it creates a document.
            (and (= method "POST")
                 (id-from-path path
                               #"/api/workspace/drive/documents/([^/]+)/responses-sheet"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/responses-sheet!
                      (id-from-path path
                                    #"/api/workspace/drive/documents/([^/]+)/responses-sheet")
                      (:user-id session))))

            ;; Suggestions. A commenter may propose; a writer may accept.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/suggestions"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/suggestions
                      (id-from-path path
                                    #"/api/workspace/drive/documents/([^/]+)/suggestions")
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/suggestions"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/suggest!
                      (id-from-path path
                                    #"/api/workspace/drive/documents/([^/]+)/suggestions")
                      (:block request) (:text request) (:user-id session))))

            (and (= method "POST")
                 (id-from-path path
                               #"/api/workspace/drive/documents/([^/]+)/suggestions/[^/]+/accept"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/accept-suggestion!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/")
                      (second (re-find #"/suggestions/([^/]+)/accept" path))
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path
                               #"/api/workspace/drive/documents/([^/]+)/suggestions/[^/]+/reject"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/reject-suggestion!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/")
                      (second (re-find #"/suggestions/([^/]+)/reject" path))
                      (:user-id session))))

            ;; Owner only — the responses are theirs.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/submissions"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/submissions
                      (id-from-path path
                                    #"/api/workspace/drive/documents/([^/]+)/submissions")
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/shared/([^/]+)/submissions"))
            (let [session (require-app-session! exchange)
                  request (read-json-raw exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/submit-via-link!
                      (id-from-path path
                                    #"/api/workspace/drive/shared/([^/]+)/submissions")
                      (get request "answers")
                      (:user-id session)
                      (System/currentTimeMillis))))

            ;; Behind the app session like everything else — see
            ;; `documents/link-content` for why a token is not a way around it.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/drive/shared/([^/]+)"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/link-content
                      (id-from-path path #"/api/workspace/drive/shared/([^/]+)")
                      (:user-id session)
                      (System/currentTimeMillis))))

            (and (= method "POST") (= path "/api/workspace/drive/trash/empty"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (documents/empty-trash! (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/drive/documents/([^/]+)/trash"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/trash!
                      (id-from-path path #"/api/workspace/drive/documents/([^/]+)/trash")
                      (:user-id session))))

            ;; The machine's calendar and this app's appointments, in one
            ;; list, because a person has one afternoon.
            ;;
            ;; Only the EventKit half is cached. `workspace/snapshot` caches
            ;; under one key for the whole server, and appointments are per
            ;; principal — putting them through it would hand one person's
            ;; meetings to the next reader for a minute.
            ;; The practice surface. `status` answers while disabled — a
            ;; client has to be able to render something when the record is
            ;; off — and every other route refuses, because a summary of a
            ;; record this app does not hold has no honest value to return.
            (and (= method "GET") (= path "/api/workspace/lawfirm/fax"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (fax/status)))

            ;; The bytes travel in the request; the DESTINATION does not, and
            ;; cannot. It is read from the practice record by `dispatch-plan`.
            ;; A caller can choose which 送達 to execute and nothing about
            ;; where it goes.
            (and (= method "POST") (= path "/api/workspace/lawfirm/fax/dispatch"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (fax/dispatch!
                      {:transmission-id (get request "transmission-id")
                       :bytes (.decode (java.util.Base64/getDecoder)
                                       ^String (str (get request "document-base64")))
                       :filename (get request "filename")
                       :today (subs (store/now) 0 10)})))

            ;; The provider's callback. No session — a fax machine has none.
            ;; Authenticated by the configured token in the path, and bounded
            ;; by what it can do: record a status against a 送達 this practice
            ;; already sent, resolved by the provider's own GUID.
            (and (= method "POST")
                 (id-from-path path #"/api/fax/callback/([^/]+)"))
            (send! exchange 200
                   (fax/on-callback!
                    {:token (id-from-path path #"/api/fax/callback/([^/]+)")
                     :payload (slurp (.getRequestBody exchange))
                     :today (subs (store/now) 0 10)}))

            (and (= method "GET") (= path "/api/workspace/lawfirm"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (lawfirm/status)))

            (and (= method "GET") (= path "/api/workspace/lawfirm/summary"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (lawfirm/summary (subs (store/now) 0 10))))

            (and (= method "GET") (= path "/api/workspace/lawfirm/docket"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (lawfirm/docket (subs (store/now) 0 10))))

            ;; Arrivals become proposals the practice's gate decides on. Not
            ;; a write endpoint even though it changes the record: what it
            ;; posts is what the archive says arrived, and the governor is
            ;; what turns that into a fact.
            (and (= method "POST") (= path "/api/workspace/lawfirm/inbound/sync"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (lawfirm/sync-inbound!
                      {:since (get request "since" "1970-01-01")
                       :bengoshi-id (get request "bengoshi-id")
                       :client-id (get request "client-id")
                       :today (subs (store/now) 0 10)})))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/lawfirm/matters/([^/]+)/drive"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (lawfirm/publish-matter-drive!
                      (:user-id session)
                      (id-from-path path #"/api/workspace/lawfirm/matters/([^/]+)/drive"))))

            (and (= method "GET") (= path "/api/workspace/scheduler"))
            (let [session (require-app-session! exchange)
                  mirror (workspace/snapshot :scheduler workspace/calendar-snapshot)
                  mine (:items (scheduler/events (:user-id session)))
                  items (vec (sort-by (juxt #(str (:start %)) #(str (:id %)))
                                      (concat (:items mirror) mine)))]
              (send! exchange 200
                     (assoc mirror
                            :items items
                            :you (:user-id session)
                            ;; Recomputed, or a day rail built from the
                            ;; mirror alone would show the meeting in the
                            ;; list and not on the day it is on.
                            :days (mapv (fn [{:keys [date]}]
                                          {:date date
                                           :items (filterv
                                                   #(str/starts-with? (str (:start %))
                                                                      (str date))
                                                   items)})
                                        (:days mirror)))))

            (and (= method "POST") (= path "/api/workspace/scheduler/events"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (scheduler/create! {:title (:title request)
                                         :start (:start request)
                                         :end (:end request)
                                         :all-day? (:all-day? request)
                                         :attendees (:attendees request)}
                                        (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/scheduler/events/([^/]+)/invite"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (scheduler/invite!
                      (id-from-path path
                                    #"/api/workspace/scheduler/events/([^/]+)/invite")
                      (:person request)
                      (:user-id session))))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/scheduler/events/([^/]+)/respond"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (scheduler/respond!
                      (id-from-path path
                                    #"/api/workspace/scheduler/events/([^/]+)/respond")
                      (:status request)
                      (:user-id session))))

            ;; What the asker has already said yes to that overlaps this.
            ;; Asked separately rather than folded into every event in the
            ;; list: it is a question about one candidate, and answering it
            ;; for all of them is quadratic in a list nobody is looking at.
            (and (= method "GET")
                 (id-from-path path #"/api/workspace/scheduler/events/([^/]+)/conflicts"))
            (let [session (require-app-session! exchange)
                  id (id-from-path path
                                   #"/api/workspace/scheduler/events/([^/]+)/conflicts")]
              (send! exchange 200
                     {:schema scheduler/schema :ok? true :id id
                      :conflicts (scheduler/conflicts id (:user-id session))}))

            (and (= method "POST")
                 (id-from-path path #"/api/workspace/scheduler/events/([^/]+)/cancel"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (scheduler/cancel!
                      (id-from-path path
                                    #"/api/workspace/scheduler/events/([^/]+)/cancel")
                      (:user-id session))))

            ;; Worker runs are live queue state, so they bypass the workspace
            ;; read cache.
            (and (= method "GET") (= path "/api/workspace/worker"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (worker/snapshot config)))

            ;; Kept outside this JVM method because the main HTTP router is
            ;; near the bytecode limit; governance has its own bounded router.
            (str/starts-with? path "/api/work-governance")
            (handle-work-governance! config exchange method path)

            (and (= method "GET") (= path "/api/organism-workers"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (organism-gateway/directory
                      (active-organization-slug exchange))))

            (and (= method "GET")
                 (id-from-path path #"/api/organism-workers/([^/]+)/snapshot"))
            (let [worker-id
                  (id-from-path path
                                #"/api/organism-workers/([^/]+)/snapshot")]
              (require-app-session! exchange)
              (require-visible-worker! exchange worker-id)
              (send! exchange 200 (organism-gateway/snapshot worker-id)))

            (and (= method "GET")
                 (id-from-path path #"/api/organism-workers/([^/]+)/activity"))
            (let [worker-id
                  (id-from-path path
                                #"/api/organism-workers/([^/]+)/activity")
                  params (query-params exchange)
                  context (identity-context exchange)]
              (require-app-session! exchange)
              (require-visible-worker! exchange worker-id)
              (let [result
                    (organism-gateway/activity
                     (or (:cursor params)
                         (remembered-cursor context worker-id))
                     (try
                       (Long/parseLong (or (:limit params) "100"))
                       (catch Exception _ 100)))]
                (remember-cursor! context worker-id (:cursor result))
                (send! exchange 200 result)))

            (and (= method "GET")
                 (id-from-path path #"/api/organism-workers/([^/]+)/receipts"))
            (let [worker-id
                  (id-from-path path
                                #"/api/organism-workers/([^/]+)/receipts")]
              (require-app-session! exchange)
              (require-visible-worker! exchange worker-id)
              (send! exchange 200 (organism-gateway/receipts worker-id)))

            (and (= method "POST")
                 (id-from-path path #"/api/organism-workers/([^/]+)/messenger-transport"))
            (let [worker-id
                  (id-from-path path
                                #"/api/organism-workers/([^/]+)/messenger-transport")
                  session (require-app-session! exchange)
                  context (identity-context exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (require-visible-worker! exchange worker-id)
              (require-control-role! context :work-governance/admin)
              (send! exchange 201
                     (organism-messenger-transport/issue!
                      worker-id (active-organization-slug exchange))))

            (and (= method "POST")
                 (id-from-path path #"/api/organism-workers/([^/]+)/intents"))
            (let [session (require-app-session! exchange)
                  context (identity-context exchange)
                  worker-id
                  (id-from-path path
                                #"/api/organism-workers/([^/]+)/intents")
                  request (read-json exchange)
                  capability
                  (if (nil? (:capability request))
                    :intent/submit
                    (or (capability-keyword (:capability request))
                        (throw
                         (ex-info "capability must be a qualified safe name"
                                  {:type :ao.intent/invalid}))))
                  now (System/currentTimeMillis)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (require-visible-worker! exchange worker-id)
              (require-control-role! context capability)
              (send! exchange 202
                     (organism-gateway/submit-intent!
                      worker-id
                      (organism-intent context worker-id request capability now)
                      now)))

            (and (= method "POST")
                 (id-from-path
                  path
                  #"/api/organism-workers/([^/]+)/intents/([^/]+)/decision"))
            (let [[_ worker-id intent-id]
                  (re-matches
                   #"/api/organism-workers/([^/]+)/intents/([^/]+)/decision"
                   path)
                  session (require-app-session! exchange)
                  context (identity-context exchange)
                  request (read-json exchange)
                  decision (decision-keyword (:decision request))
                  now (System/currentTimeMillis)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (require-visible-worker! exchange worker-id)
              (require-control-role! context :approval/submit)
              (when-not (#{:approved :rejected} decision)
                (throw (ex-info "decision must be approved or rejected"
                                {:type :ao.intent/invalid})))
              (send! exchange 202
                     (organism-gateway/submit-intent!
                      worker-id
                      (organism-intent
                       context worker-id
                       {:parent intent-id
                        :type "approval"
                        :reference intent-id
                        :decision decision
                        :reason (:reason request)}
                       :approval/submit
                       now)
                      now)))

            (and (= method "POST") (= path "/api/workers"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 202
                     (worker/enqueue! config (read-json exchange))))

            (and (= method "POST") (= path "/api/workers/clear"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (worker/clear-finished!)
              (send! exchange 200 (worker/snapshot config)))

            (and (= method "POST")
                 (id-from-path path #"/api/workers/([^/]+)/cancel"))
            (let [session (require-app-session! exchange)
                  id (id-from-path path #"/api/workers/([^/]+)/cancel")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (worker/cancel! id)
              (send! exchange 200 (worker/snapshot config)))

            (and (= method "GET") (= path "/v1/models"))
            (send! exchange 200 {:object "list"
                                 :data (service/available-models config)})

            (and (= method "POST") (= path "/v1/chat/completions"))
            (let [request (read-json exchange)
                  chat {:messages (:messages request)
                        :model (:model request)
                        :provider-id (:provider request)
                        :session-id (or (:session_id request) "openai")
                        :agent-id (:agent_id request)
                        :temperature (:temperature request)}]
              (if (:stream request)
                (send-openai-stream!
                 exchange config chat
                 (boolean (get-in request [:stream_options :include_usage])))
                (send! exchange 200
                       (service/openai-response
                        (service/run-chat! config chat)))))

            :else
            (send! exchange 404 {:error {:type "not_found" :path path}}))
          (catch clojure.lang.ExceptionInfo error
            (send! exchange
                   (case (:type (ex-data error))
                     :identity/unauthenticated 401
                     :identity/forbidden 403
                     :identity/invalid-csrf 403
                     :identity/invalid-origin 403
                     :provider/denied 403
                     :identity/already-registered 400
                     :identity/already-member 409
                     :identity/invalid-registration 400
                     :identity/invalid-invitation 400
                     :identity/email-already-used 409
                     :passkey/invalid-transaction 400
                     :passkey/invalid-enrollment 400
                     :passkey/user-verification-required 403
                     :passkey/verification-failed 403
                     :passkey/required 428
                     :email-login/invalid-token 400
                     :email-login/not-configured 503
                     :sso/unsupported 400
                     :sso/not-configured 503
                     :sso/cancelled 400
                     :sso/invalid-state 400
                     :sso/missing-code 400
                     :sso/missing-token 502
                     :sso/missing-subject 502
                     :sso/subject-already-bound 409
                     :sso/link-required 409
                     :sso/signup-disabled 403
                     :sso/verification-failed 403
                     :sso/rate-limited 429
                     :identity/session-not-found 404
                     :identity/login-identity-not-found 404
                     :identity/last-login-method 409
                     :site/not-found 404
                     :site/slug-conflict 409
                     :site/html-too-large 413
                     :site/project-required 400
                     :site/project-not-found 404
                     :site/title-required 400
                     :site/title-too-long 400
                     :site/invalid-slug 400
                     :project/invalid-id 400
                     :project/invalid-issue 400
                     :project/invalid-column 400
                     :project/unknown-repository 400
                     :project/unknown-agent 400
                     :project/unknown-blocker 400
                     :project/invalid-blocker 400
                     :project/issue-not-found 404
                     :kotobase-federation/passkey-required 403
                     :kotobase-federation/no-subject-did 428
                     ;; 403 rather than 428: a Passkey is required and this
                     ;; caller can never present one, so telling it to go and
                     ;; enrol would be an instruction it cannot follow.
                     :identity/agent-session-forbidden 403
                     :tenant-connection/forbidden 403
                     :tenant-connection/human-approval-required 403
                     :tenant-connection/capability-denied 403
                     :tenant-connection/not-found 404
                     :tenant-connection/not-active 409
                     :tenant-connection/invalid-state 409
                     :tenant-connection/expired 409
                     :tenant-connection/budget-exhausted 429
                     :tenant-connection/tenant-required 400
                     :tenant-connection/invalid-ttl 400
                     :tenant-connection/invalid-capability 400
                     :tenant-connection/invalid-budget 400
                     ;; Agent-session enrolment. The key is a credential, so a
                     ;; wrong one is 403 rather than 400 -- 400 would read as
                     ;; "you sent the field badly" when the field was fine and
                     ;; the secret was not. Everything else here is genuinely a
                     ;; malformed or unanswerable request, except the ambiguous
                     ;; owner, which is a conflict with the store's shape.
                     :agent-session/invalid-key 403
                     :agent-session/label-missing 400
                     :agent-session/ttl-invalid 400
                     :agent-session/unknown-user 400
                     :agent-session/not-found 404
                     :agent-session/no-owner 409
                     :agent-session/ambiguous-user 409
                     ;; The ceremony succeeded and the credential it produced is
                     ;; not one we can root a did:key in. Understood request,
                     ;; unacceptable content -- 422, like :drive/invalid-document.
                     ;; Previously these fell through to 502, which reads as
                     ;; "the server is broken" and hid a real bug for as long as
                     ;; it existed.
                     :did/invalid-public-key 422
                     :did/unsupported-public-key 422
                     :passkey/onboarding-unavailable 409
                     :identity/organization-id-immutable 409
                     ;; Credential issuance preconditions. 428 for the missing
                     ;; Passkey to match :passkey/required — the client must do
                     ;; something first — and 409 for an organization that has
                     ;; not claimed an ID, which is a conflict with current state
                     ;; rather than a malformed request.
                     :credential/no-subject-did 428
                     :credential/no-membership 409
                     :credential/organization-incomplete 409
                     ;; A presented credential that is not one this app issued.
                     ;; Understood request, unacceptable content — 422, like
                     ;; :did/invalid-public-key above.
                     :credential/unknown-verification-method 422
                     :credential/unknown-role 400
                     :credential/no-subject 400
                     :credential/no-domain 409
                     ;; External-issuer trust. 403 for an issuer the operator has
                     ;; not decided to believe: the request was understood and
                     ;; authenticated, and refused on policy. The fetch failures
                     ;; are 502 -- this app could not reach or parse the
                     ;; issuer's document, which is an upstream fault and not the
                     ;; caller's.
                     :credential-trust/untrusted-issuer 403
                     :credential-trust/internal-address 403
                     :credential-trust/insecure-transport 403
                     :credential-trust/method-not-an-assertion-method 422
                     :credential-trust/method-not-in-document 422
                     :credential-trust/unsupported-key-type 422
                     :credential-trust/document-id-mismatch 422
                     :credential-trust/unsupported-did-method 422
                     :credential-trust/document-unavailable 502
                     :credential-trust/document-unparseable 502
                     :credential-trust/document-too-large 502
                     :ao.worker/not-found 404
                     :ao.messenger/invalid 400
                     :ao.messenger/inactive 409
                     :ao.intent/invalid 400
                     :ao.intent/rejected 409
                     :github-projects/webhook-disabled 503
                     :github-projects/webhook-signature-invalid 403
                     :work-governance/organization-boundary 403
                     :work-governance/invalid-approval-policy 422
                     :work-runtime/invalid-organization 422
                     :work-runtime/unknown-approval-role 422
                     :work-runtime/organization-policy-role-conflict 409
                     :work-approval/organization-boundary 403
                     :work-approval/not-found 404
                     :work-approval/person-required 403
                     :work-approval/not-eligible 403
                     :work-approval/passkey-required 428
                     :work-approval/assertion-mismatch 403
                     :work-runtime/not-in-review 409
                     :work-runtime/verification-required 409
                     :work-runtime/success-receipt-required 409
                     :identity/organization-required 409
                     :worker/invalid-request 400
                     :worker/not-found 404
                     :worker/not-cancellable 409
                     :drive/unknown-kind 400
                     ;; The request was understood and the document it carries
                     ;; is not one the model accepts — which is 422, not 400.
                     :drive/invalid-document 422
                     ;; Purging something that is not in the trash yet is a
                     ;; conflict with its current state, not a bad request.
                     :drive/not-trashed 409
                     ;; The document moved under the editor. 409 is the whole
                     ;; point: the client has to re-read before it can win.
                     :drive/stale-version 409
                     :drive/unsupported-format 415
                     ;; A folder dragged into its own child. The request was
                     ;; understood; the arrangement it asks for is not one a
                     ;; tree can hold.
                     :drive/invalid-move 409
                     :drive/not-a-folder 400
                     ;; Asking a file for its document, or a document for
                     ;; its bytes. The request was understood and is about
                     ;; the wrong kind of thing.
                     :drive/not-a-document 409
                     :drive/not-a-file 409
                     :drive/not-previewable 415
                     ;; Asking a spreadsheet, or a zip, to be pages. The
                     ;; request is about the wrong kind of thing.
                     :pageview/not-viewable 415
                     ;; Readable, claimed to be a PDF, and no page came out.
                     ;; 422 rather than 500: the server understood the
                     ;; request and the entity is what it cannot process.
                     :pageview/no-pages 422
                     ;; A decoder said no. Same status, different sentence:
                     ;; the fix is a decoder, not a permission and not a
                     ;; different file.
                     :pageview/undecodable 422
                     ;; A page has fewer images than the client asked for.
                     :pageview/no-such-image 404
                     ;; The image is there and this cannot encode it — a
                     ;; colour space or bit depth `png.encode` does not
                     ;; write. Named rather than encoded wrongly, because a
                     ;; wrongly encoded image opens.
                     :pageview/unsupported-image 415
                     ;; The file is fine; showing it is what this refuses.
                     ;; 413 names the size as the reason, which is what the
                     ;; message tells the reader too.
                     :pageview/too-large 413
                     ;; The paragraph moved under the proposal. 409 for the
                     ;; same reason a stale save is: the client has to
                     ;; re-read before it can win.
                     :drive/suggestion-stale 409
                     :drive/suggestion-settled 409
                     :drive/invalid-suggestion 422
                     ;; Restoring what is already current is a request that
                     ;; conflicts with the state, not a malformed one.
                     :drive/already-current 409
                     ;; Replying to a resolved thread: reopening is an act
                     ;; somebody takes on purpose.
                     :drive/comment-resolved 409
                     :drive/invalid-share 400
                     :drive/invalid-submission 422
                     :drive/invalid-comment 400
                     :drive/not-found 404
                     :drive/not-permitted 403
                     :drive/no-content 409
                     :drive/quota-exceeded 507
                     ;; The model says these bytes exist and the store
                     ;; disagrees, which is a broken backend rather than
                     ;; anything the caller did.
                     :drive/object-missing 502
                     :drive/refused 409
                     ;; ---- appointments ----
                     ;; An event that is not yours and one that does not
                     ;; exist answer the same, so the code is the same: 404
                     ;; here is "there is no such event for you".
                     :scheduler/not-found 404
                     :scheduler/not-organizer 403
                     ;; Answering an invitation you do not have. Not 404 —
                     ;; you can see the event, you are simply not on it.
                     :scheduler/not-invited 403
                     ;; The request was understood and the appointment it
                     ;; describes is not one the model accepts, which is 422
                     ;; for the same reason an unacceptable document is.
                     :scheduler/invalid-event 422
                     :scheduler/unknown-rsvp 400
                     :scheduler/no-such-person 400
                     ;; ---- mail ----
                     :mail/not-found 404
                     :mail/invalid-label 400
                     ;; Asking for `:inbox` or `:trash` as a label. The
                     ;; request was understood and names a place rather than
                     ;; a label, which is a bad request and not a refusal.
                     :mail/reserved-label 400
                     ;; ---- messenger ----
                     :messenger/invalid 400
                     :messenger/unknown-principal 400
                     :messenger/not-found 404
                     :messenger/forbidden 403
                     ;; ---- sheets ----
                     :sheets/invalid-range 400
                     ;; The request was understood and the range is one this
                     ;; cannot reorder without changing what it computes,
                     ;; which is a conflict with the state and not a bad
                     ;; request.
                     :sheets/range-has-formulas 409
                     :scheduler/organizer-is-not-an-attendee 400
                     :oauth/unsupported 400
                     :oauth/missing-code 400
                     :oauth/invalid-state 400
                     :oauth/not-configured 501
                     :relay/not-configured 501
                     :relay/invalid-destination 400
                     :relay/request-failed
                     (or (:status (ex-data error)) 502)

                     ;; ---- authority spine ----
                     ;; Without these an authority that is simply switched off
                     ;; answers 502, which reads as "this server is broken" when
                     ;; the truth is "this surface is deliberately not on".
                     :authority/disabled 501
                     :authority/unknown-authority 404
                     :authority/proposal-not-found 404
                     ;; The Passkey assertion did not authorise THIS proposal.
                     :authority/approval-mismatch 403
                     ;; The assertion was genuine and for this proposal, but the
                     ;; authenticator behind it is not one this authority
                     ;; accepts. Distinct from a mismatch on purpose: the fix is
                     ;; to enrol a platform authenticator, not to retry.
                     :authority/credential-not-accepted 403
                     :authority/domain-invalid 500
                     :authority/material-invalid 500

                     ;; ---- 事業 (business) ----
                     :business/slug-missing 400
                     :business/slug-invalid 400
                     ;; The slug is the name this business is referred to by in
                     ;; prose and commits, so a collision is a conflict with an
                     ;; existing record rather than a malformed request.
                     :business/slug-taken 409
                     :business/not-found 404

                     ;; ---- 事業の canvas 提案 ----
                     :canvas/action-unsupported 400
                     :canvas/canvas-id-missing 400
                     :canvas/value-missing 400
                     :canvas/anonymous-proposal 400
                     ;; Understood, but the business has no canvas to change yet —
                     ;; a conflict with the record's state, not a bad request.
                     :canvas/product-unbound 409
                     :canvas/proposal-not-found 404

                     ;; ---- funding accounts ----
                     :funding/institution-missing 400
                     :funding/currency-unsupported 400
                     :funding/account-type-invalid 400
                     :funding/amount-invalid 400
                     :funding/as-of-invalid 400
                     :funding/source-invalid 400
                     :funding/account-not-found 404
                     ;; Understood, but at odds with what the account already
                     ;; says it is — a conflict, not a malformed request.
                     :funding/currency-mismatch 409
                     :funding/account-inactive 409

                     ;; ---- payment settlement ----
                     :payment/op-unsupported 400
                     :payment/payee-missing 400
                     :payment/reference-missing 400
                     :payment/amount-invalid 400
                     :payment/currency-mismatch 409
                     :payment/account-not-linked 409
                     :payment/account-inactive 409
                     :payment/duplicate-settlement 409
                     ;; The balance is missing or too old to answer "will this
                     ;; clear?". The request is well-formed; the precondition is
                     ;; not met, and recording a balance resolves it.
                     :payment/balance-unknown 409
                     ;; Exactly what 402 is for.
                     :payment/insufficient-funds 402
                     ;; Held by the cross-domain SIM-swap invariant, not by
                     ;; anything wrong with the request.
                     :payment/spend-hold 423
                     ;; Only reachable if the server failed to compute a fact it
                     ;; owns — `authority.api` injects both on every review.
                     :payment/posture-unknown 500
                     :payment/settlement-history-unknown 500

                     (control-plane-error-status (:type (ex-data error))))
                   {:error {:type (name (or (:type (ex-data error))
                                           :provider/error))
                            :message (.getMessage error)
                            :details (ex-data error)}}))
          (catch Exception error
            (send! exchange 500 {:error {:type "internal_error"
                                         :message (.getMessage error)}})))))))

(defn- with-kotobase-federation [delegate config]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (.getPath (.getRequestURI exchange))]
        (if (and (= method "POST")
                 (= path "/api/integrations/kotobase/assertion"))
          (try
            (route-kotobase-federation! exchange config)
            (catch clojure.lang.ExceptionInfo error
              (send! exchange
                     (case (:type (ex-data error))
                       :identity/unauthenticated 401
                       :identity/invalid-origin 403
                       :identity/invalid-csrf 403
                       :identity/agent-session-forbidden 403
                       :kotobase-federation/passkey-required 403
                       :kotobase-federation/no-subject-did 428
                       400)
                     {:error {:type (name (or (:type (ex-data error))
                                             :kotobase-federation/error))
                              :message (.getMessage error)}}))
            (catch Exception error
              (send! exchange 500 {:error {:type "internal_error"
                                           :message (.getMessage error)}})))
          (.handle ^HttpHandler delegate exchange))))))

(defn- bot-id-from [path pattern]
  (some-> (re-matches pattern path) second))

(defn- handle-bots!
  "The Bots surface.

  `require-human-session!` rather than `require-app-session!`, and that is the
  decision this handler makes: a Bot is an agent, and an agent session reaching
  these routes could create one, widen its grant, and approve its own held
  write. `bot/may-approve?` refuses the last of those on its own, but a
  boundary that only holds at the innermost check is one refactor from not
  holding — so the outer gate refuses the whole family."
  [config exchange method path]
  (let [session (require-human-session! exchange)]
    (cond
      (and (= method "GET") (= path "/api/bots"))
      (send! exchange 200 (bots/overview config session))

      (and (= method "POST") (= path "/api/bots"))
      (let [body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (bots/create! config session body)
        (send! exchange 200 (bots/overview config session)))

      (and (= method "POST") (= path "/api/bots/suggestions"))
      (let [body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200 {:suggestions (bots/suggestions (:connectors body))}))

      (and (= method "GET") (bot-id-from path #"/api/bots/([^/]+)/messages"))
      (send! exchange 200
             {:messages (bots/messages
                         session (bot-id-from path #"/api/bots/([^/]+)/messages"))})

      (and (= method "POST") (bot-id-from path #"/api/bots/([^/]+)/messages"))
      (let [bot-id (bot-id-from path #"/api/bots/([^/]+)/messages")
            body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200
               {:messages (bots/send! config session bot-id (:text body))}))

      (and (= method "POST")
           (bot-id-from path #"/api/bots/([^/]+)/cards/[^/]+/answer"))
      (let [bot-id (bot-id-from path #"/api/bots/([^/]+)/cards/[^/]+/answer")
            card-id (bot-id-from path #"/api/bots/[^/]+/cards/([^/]+)/answer")
            body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200
               {:messages (bots/answer! config session bot-id card-id
                                        (:answer body))}))

      (and (= method "GET") (bot-id-from path #"/api/bots/([^/]+)/accounts"))
      (send! exchange 200
             (bots/accounts session
                            (bot-id-from path #"/api/bots/([^/]+)/accounts")))

      ;; Naming an account is about the CONNECTION, not about any one Bot — two
      ;; Bots sharing a Google account must see the same name for it — so it is
      ;; not under a bot id even though this is where somebody does it.
      (and (= method "POST") (= path "/api/bots/accounts/label"))
      (let [body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200
               (bots/label-account! session (:connection body) (:label body))))

      (and (= method "POST")
           (bot-id-from path #"/api/bots/([^/]+)/cards/[^/]+/decide"))
      (let [bot-id (bot-id-from path #"/api/bots/([^/]+)/cards/[^/]+/decide")
            card-id (bot-id-from path #"/api/bots/[^/]+/cards/([^/]+)/decide")
            body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (send! exchange 200
               {:messages (bots/decide! config session bot-id card-id
                                        (:decision body))}))

      (and (= method "POST") (bot-id-from path #"/api/bots/([^/]+)/archive"))
      (let [bot-id (bot-id-from path #"/api/bots/([^/]+)/archive")]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (bots/archive! session bot-id)
        (send! exchange 200 (bots/overview config session)))

      (and (= method "POST") (bot-id-from path #"/api/bots/([^/]+)"))
      (let [bot-id (bot-id-from path #"/api/bots/([^/]+)")
            body (read-json exchange)]
        (require-origin! exchange config)
        (require-csrf! exchange session)
        (bots/update! session bot-id body)
        (send! exchange 200 (bots/overview config session)))

      :else (send! exchange 405 {:error {:type "method_not_allowed"}}))))

(defn- bots-handler
  "Its own context, for the reason `/api/chronicle` has one: `handler` is at the
  JVM's 64 KB method ceiling and will not take another branch."
  [config]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (.getPath (.getRequestURI exchange))]
        (try
          (handle-bots! config exchange method path)
          (catch clojure.lang.ExceptionInfo error
            (send! exchange
                   (case (:type (ex-data error))
                     :identity/unauthenticated 401
                     :identity/invalid-origin 403
                     :identity/invalid-csrf 403
                     :identity/agent-session-forbidden 403
                     :bot/forbidden 403
                     :bot/approval-refused 403
                     :bot/not-found 404
                     :bot/disabled 409
                     :bot/not-held 409
                     :bot/choice-answered 409
                     :provider/denied 409
                     400)
                   {:error {:type (name (or (:type (ex-data error)) :bot/error))
                            :message (.getMessage error)}}))
          (catch Exception error
            (send! exchange 500 {:error {:type "internal_error"
                                         :message (.getMessage error)}})))))))

(defn- chronicle-handler [config]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (.getPath (.getRequestURI exchange))]
        (try
          (handle-chronicle! config exchange method path)
          (catch clojure.lang.ExceptionInfo error
            (send! exchange
                   (case (:type (ex-data error))
                     :identity/unauthenticated 401
                     :identity/invalid-origin 403
                     :identity/invalid-csrf 403
                     :identity/agent-session-forbidden 403
                     :chronicle/user-required 400
                     :chronicle/disabled 409
                     :chronicle/permission-required 428
                     :chronicle/settings-unavailable 503
                     :chronicle/command-timeout 504
                     :chronicle/command-failed 502
                     400)
                   {:error {:type (name (or (:type (ex-data error))
                                            :chronicle/error))
                            :message (.getMessage error)}}))
          (catch Exception error
            (send! exchange 500 {:error {:type "internal_error"
                                         :message (.getMessage error)}})))))))

(defn- update-handler [configuration]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (.getPath (.getRequestURI exchange))]
        (try
          (cond
            (and (= method "GET") (= path "/api/update"))
            (send! exchange 200 (updater/status))

            (and (= method "POST") (= path "/api/update/check"))
            (do (require-origin! exchange configuration)
                (send! exchange 200 (updater/check! configuration)))

            (and (= method "POST") (= path "/api/update/download"))
            (do (require-origin! exchange configuration)
                (send! exchange 200 (updater/download! configuration)))

            :else
            (send! exchange 404 {:error {:type "not_found"}}))
          (catch clojure.lang.ExceptionInfo error
            (send! exchange
                   (case (:type (ex-data error))
                     :identity/invalid-origin 403
                     :update/not-available 409
                     :update/origin 502
                     :update/http 502
                     :update/digest 502
                     :update/signature 502
                     :update/size 502
                     400)
                   {:error {:type (name (or (:type (ex-data error))
                                            :update/error))
                            :message (.getMessage error)}}))
          (catch Exception error
            (send! exchange 500 {:error {:type "internal_error"
                                         :message (.getMessage error)}})))))))

(defn start!
  ([] (start! (config/load-config)))
  ([configuration]
   (when @server
     (throw (ex-info "server already running" {})))
   (reset! active-config configuration)
   (identity/configure! configuration)
   ;; Written here rather than lazily on first enrollment so that a CLI run at
   ;; any point after the server is up has something to read. Creating it is
   ;; idempotent and cheap; a missing key would otherwise look to the operator
   ;; like the feature is absent rather than like they are early.
   (agent-session/ensure-key!)
   (mail-sync/start! configuration)
   (chronicle/start! configuration)
   (folder-sync/start! configuration)
   (updater/start! configuration)
   (let [host (get-in configuration [:server :host])
         port (get-in configuration [:server :port])
         instance (HttpServer/create (InetSocketAddress. host (int port)) 0)]
     (.createContext instance "/"
                     (with-kotobase-federation (handler configuration)
                                               configuration))
     ;; Its own context rather than another branch in `handler`: that method is
     ;; already at the JVM's 64 KB ceiling, and two more lines in its `cond`
     ;; failed to compile with "Method code too large". A longer prefix wins over
     ;; "/" in com.sun.net.httpserver, so this takes the route regardless.
     (.createContext instance "/icon.png"
                     (reify HttpHandler
                       (handle [_ exchange]
                         (send-icon! exchange))))
     ;; Keep this family outside `handler`; that method sits at the JVM's
     ;; 64 KB bytecode limit. A longer HttpServer prefix wins over "/".
     (.createContext instance "/api/chronicle"
                     (chronicle-handler configuration))
     (.createContext instance "/api/bots"
                     (bots-handler configuration))
     (.createContext instance "/api/update"
                     (update-handler configuration))
     (.createContext instance "/api/folder-sync"
                     (reify HttpHandler
                       (handle [_ exchange]
                         (let [method (.getRequestMethod exchange)
                               path (.getPath (.getRequestURI exchange))]
                           (try
                             (let [session (require-app-session! exchange)]
                               (cond
                                 (and (= method "GET")
                                      (= path "/api/folder-sync"))
                                 (send! exchange 200
                                        (folder-sync/status (:user-id session)))

                                 (and (= method "POST")
                                      (= path "/api/folder-sync/sync"))
                                 (do
                                   (require-origin! exchange configuration)
                                   (require-csrf! exchange session)
                                   (send! exchange 200
                                          {:schema folder-sync/schema
                                           :results
                                           (folder-sync/sync-configured!
                                            (:user-id session))}))

                                 :else
                                 (send! exchange 404
                                        {:error {:type "not_found"}})))
                             (catch clojure.lang.ExceptionInfo error
                               (send! exchange
                                      (case (:type (ex-data error))
                                        :identity/unauthenticated 401
                                        :identity/invalid-origin 403
                                        :identity/invalid-csrf 403
                                        400)
                                      {:error {:type (name (or (:type (ex-data error))
                                                              :folder-sync/error))
                                               :message (.getMessage error)}}))
                             (catch Exception error
                               (send! exchange 500
                                      {:error {:type "internal_error"
                                               :message (.getMessage error)}})))))))
     (.setExecutor instance (executor/task-executor))
     (.start instance)
     (reset! server instance)
     (work-reconciler/start! configuration)
     {:host host :port port})))

(defn stop! []
  (mail-sync/stop!)
  (chronicle/stop!)
  (folder-sync/stop!)
  (updater/stop!)
  (work-reconciler/stop!)
  (when-let [instance @server]
    (.stop instance 0)
    (reset! server nil))
  (reset! active-config nil))

(defn -main [& _]
  (let [{:keys [host port]} (start!)]
    (println (str "cloud-itonami-app listening on http://" host ":" port))
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop!))
    @(promise)))
