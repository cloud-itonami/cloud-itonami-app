(ns cloud.itonami.app.server
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.authority.api :as authority-api]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.executor :as executor]
            [cloud.itonami.app.filecoin :as filecoin]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.organism-gateway :as organism-gateway]
            [cloud.itonami.app.relay :as relay]
            [cloud.itonami.app.service :as service]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.web :as web]
            [cloud.itonami.app.worker :as worker]
            [cloud.itonami.app.workspace :as workspace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io OutputStreamWriter]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]))

(defonce server (atom nil))

(defn- read-json [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))]
    (if (str/blank? body) {} (json/read-str body :key-fn keyword))))

(defn- read-json-raw
  "The request body with its keys left alone.

  `read-json` keywordizes every key at every depth, which is right for the
  fixed-shape requests around it and destroys a document payload: a Sheets
  tab is keyed by its id and a cell by `\"[1 1]\"`, and turning those into
  `:plan` and `:[1 1]` loses the only thing that made them addressable."
  [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))]
    (if (str/blank? body) {} (json/read-str body))))

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

(defn- session-cookie [token]
  (str identity/cookie-name "=" token
       "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
       identity/session-seconds))

(defn- redirect! [^HttpExchange exchange location]
  (doto (.getResponseHeaders exchange)
    (.set "Location" location)
    (.set "Cache-Control" "no-store")
    (.set "Referrer-Policy" "no-referrer"))
  (.sendResponseHeaders exchange 303 -1)
  (.close exchange))

(defn- send-html! [^HttpExchange exchange html]
  (let [bytes (.getBytes html StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "text/html; charset=utf-8")
      (.set "Cache-Control" "no-store")
      (.set "Content-Security-Policy"
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'")
      (.set "Permissions-Policy"
            "publickey-credentials-create=(self), publickey-credentials-get=(self)"))
    (.sendResponseHeaders exchange 200 (alength bytes))
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

(defn- require-origin! [exchange config]
  (when-not (= (origin config)
               (.getFirst (.getRequestHeaders exchange) "Origin"))
    (throw (ex-info "リクエスト元を確認できません。"
                    {:type :identity/invalid-origin}))))

(defn- require-session! [exchange]
  (or (identity/session (cookie-value exchange identity/cookie-name))
      (throw (ex-info "認証が必要です。" {:type :identity/unauthenticated}))))

(defn- require-app-session! [exchange]
  (identity/require-passkey! (require-session! exchange)))

(defn- require-csrf! [exchange session]
  (when-not (= (:csrf session)
               (.getFirst (.getRequestHeaders exchange) "X-CLOUD-ITONAMI-CSRF"))
    (throw (ex-info "CSRF token が一致しません。"
                    {:type :identity/invalid-csrf}))))

(defn- provider-from-path [path pattern]
  (some-> (re-matches pattern path) second keyword))

(defn- id-from-path [path pattern]
  (some-> (re-matches pattern path) second))

;; /api/authority/<authority>/... -- the authority key is a path segment so a
;; disabled or unknown authority is refused by name rather than by inspecting a
;; body. `authority-api/review!` etc. refuse an unknown key rather than
;; defaulting, so a typo cannot reach a different authority.
(defn- authority-from-path [path pattern]
  (some-> (re-matches pattern path) second keyword))

(defn- authority+id-from-path [path pattern]
  (when-let [[_ a i] (re-matches pattern path)]
    [(keyword a) i]))

(defn- public-session [session-id]
  {:schema "cloud.itonami.app.session.v1"
   :id session-id
   :messages (mapv #(select-keys % [:id :role :content :at])
                   (store/session-messages session-id))})

(defn- identity-context [exchange]
  (identity/public-state (cookie-value exchange identity/cookie-name)))

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
  (when (and (#{:approval/submit :stop/request} capability)
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

(defn- public-state [config]
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

            ;; Public like /health: every value returned is a read of public
            ;; chain state or a pure PieceCID computation. Nothing here touches
            ;; the workspace, so it is deliberately not behind require-session!.
            (and (= method "GET") (= path "/api/filecoin"))
            (send! exchange 200 (assoc (filecoin/status)
                                       :sample (filecoin/sample)))

            ;; ---- governed outward authorities (ADR-2607300300) ----
            ;; Every stage requires an app session, the origin check and the CSRF
            ;; token, exactly as the other write surfaces do. `authority-api`
            ;; refuses a disabled authority, and computes the cross-domain posture
            ;; server-side rather than accepting one from the client.

            (and (= method "GET") (= path "/api/authority"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200 (authority-api/overview config session)))

            (and (= method "GET")
                 (authority-from-path path #"/api/authority/([^/]+)"))
            (let [session (require-app-session! exchange)
                  a (authority-from-path path #"/api/authority/([^/]+)")]
              (send! exchange 200 (authority-api/proposals config session a)))

            (and (= method "POST")
                 (authority-from-path path #"/api/authority/([^/]+)/review"))
            (let [session (require-app-session! exchange)
                  a (authority-from-path path #"/api/authority/([^/]+)/review")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (authority-api/review! config session a
                                            (read-json exchange))))

            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/approve/start"))
            (let [session (require-app-session! exchange)
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
            (let [session (require-app-session! exchange)
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
            (let [session (require-app-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/reject")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/reject! config session a id)))

            ;; Commit is a separate call from finish-approval! on purpose: the
            ;; hand-off to the actor can refuse (governor, transport), and that
            ;; refusal is an outcome to record and show, not a failure of the
            ;; consent that already happened.
            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/commit"))
            (let [session (require-app-session! exchange)
                  [a id] (authority+id-from-path
                          path #"/api/authority/([^/]+)/proposals/([^/]+)/commit")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/commit! config session a id)))

            (and (= method "GET") (= path "/api/state"))
            (send! exchange 200 (public-state config))

            (and (= method "GET") (= path "/api/identity"))
            (send! exchange 200
                   (identity/public-state
                    (cookie-value exchange identity/cookie-name)))

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
                            path #"/api/connections/([^/]+)/start")]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (identity/start-oauth! session provider (origin config))))

            (and (= method "GET")
                 (provider-from-path path #"/api/oauth/([^/]+)/callback"))
            (let [provider (provider-from-path
                            path #"/api/oauth/([^/]+)/callback")
                  params (query-params exchange)]
              (try
                (identity/complete-oauth! provider params)
                (redirect! exchange
                           (str "/?connection=connected&provider="
                                (name provider) "#settings"))
                (catch Exception _
                  (redirect! exchange
                             (str "/?connection=error&provider="
                                  (name provider) "#settings")))))

            (and (= method "GET") (= path "/api/session"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (public-session (or (:session (query-params exchange))
                                         "desktop"))))

            (and (= method "GET") (= path "/api/workspace"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (workspace/snapshot)))

            (and (= method "GET") (= path "/api/workspace/inbox"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (workspace/snapshot :inbox workspace/inbox-snapshot)))

            (and (= method "GET") (= path "/api/workspace/projects"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (workspace/snapshot :projects workspace/projects-snapshot)))

            ;; The archive half of this is cached for a minute by
            ;; `workspace/snapshot`; the created documents are not, because a
            ;; document that does not appear in the list a moment after it was
            ;; created reads as a failed create.
            (and (= method "GET") (= path "/api/workspace/drive"))
            (let [session (require-app-session! exchange)]
              (send! exchange 200
                     (documents/drive-view
                      (workspace/snapshot :drive workspace/drive-snapshot)
                      (:user-id session))))

            (and (= method "POST") (= path "/api/workspace/drive/documents"))
            (let [session (require-app-session! exchange)
                  request (read-json exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (documents/create! (some-> (:kind request) name keyword)
                                        (:title request)
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
                      (:user-id session))))

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

            (and (= method "GET") (= path "/api/workspace/scheduler"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (workspace/snapshot :scheduler workspace/calendar-snapshot)))

            ;; Worker runs are live queue state, so they bypass the workspace
            ;; read cache.
            (and (= method "GET") (= path "/api/workspace/worker"))
            (do
              (require-app-session! exchange)
              (send! exchange 200 (worker/snapshot config)))

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

            (and (= method "POST") (= path "/api/chat"))
            (let [_session (require-app-session! exchange)
                  request (read-json exchange)
                  prompt (:prompt request)]
              (if (str/blank? prompt)
                (send! exchange 400 {:error {:type "invalid_request"
                                             :message "prompt is required"}})
                (send! exchange 200
                       (service/run-chat!
                        config
                        {:messages [{:role "user" :content prompt}]
                         :model (:model request)
                         :provider-id (:provider request)
                         :session-id (or (:session request) "desktop")
                         :agent-id (:agent request)}))))

            (and (= method "POST") (= path "/api/chat/stream"))
            (let [_session (require-app-session! exchange)
                  request (read-json exchange)
                  prompt (:prompt request)]
              (if (str/blank? prompt)
                (send! exchange 400 {:error {:type "invalid_request"
                                             :message "prompt is required"}})
                (send-chat-stream!
                 exchange config
                 {:messages [{:role "user" :content prompt}]
                  :model (:model request)
                  :provider-id (:provider request)
                  :session-id (or (:session request) "desktop")
                  :agent-id (:agent request)})))

            (and (= method "POST") (= path "/api/session/clear"))
            (let [_session (require-app-session! exchange)
                  request (read-json exchange)
                  session (or (:session request) "desktop")]
              (store/clear-session! session)
              (send! exchange 200 {:ok true :session session}))

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
                     :passkey/invalid-transaction 400
                     :passkey/invalid-enrollment 400
                     :passkey/user-verification-required 403
                     :passkey/verification-failed 403
                     :passkey/required 428
                     :passkey/onboarding-unavailable 409
                     :identity/organization-id-immutable 409
                     :ao.worker/not-found 404
                     :ao.intent/invalid 400
                     :ao.intent/rejected 409
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
                     :drive/invalid-share 400
                     :drive/not-found 404
                     :drive/not-permitted 403
                     :drive/no-content 409
                     :drive/quota-exceeded 507
                     ;; The model says these bytes exist and the store
                     ;; disagrees, which is a broken backend rather than
                     ;; anything the caller did.
                     :drive/object-missing 502
                     :drive/refused 409
                     :oauth/unsupported 400
                     :oauth/missing-code 400
                     :oauth/invalid-state 400
                     :oauth/not-configured 501
                     :relay/not-configured 501
                     :relay/invalid-destination 400
                     :relay/request-failed
                     (or (:status (ex-data error)) 502)
                     502)
                   {:error {:type (name (or (:type (ex-data error))
                                           :provider/error))
                            :message (.getMessage error)
                            :details (ex-data error)}}))
          (catch Exception error
            (send! exchange 500 {:error {:type "internal_error"
                                         :message (.getMessage error)}})))))))

(defn start!
  ([] (start! (config/load-config)))
  ([configuration]
   (when @server
     (throw (ex-info "server already running" {})))
   (identity/configure! configuration)
   (let [host (get-in configuration [:server :host])
         port (get-in configuration [:server :port])
         instance (HttpServer/create (InetSocketAddress. host (int port)) 0)]
     (.createContext instance "/" (handler configuration))
     (.setExecutor instance (executor/task-executor))
     (.start instance)
     (reset! server instance)
     {:host host :port port})))

(defn stop! []
  (when-let [instance @server]
    (.stop instance 0)
    (reset! server nil)))

(defn -main [& _]
  (let [{:keys [host port]} (start!)]
    (println (str "cloud-itonami-app listening on http://" host ":" port))
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop!))
    @(promise)))
