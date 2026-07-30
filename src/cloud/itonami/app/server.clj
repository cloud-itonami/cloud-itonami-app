(ns cloud.itonami.app.server
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
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

(defn- public-session [session-id]
  {:schema "cloud.itonami.app.session.v1"
   :id session-id
   :messages (mapv #(select-keys % [:id :role :content :at])
                   (store/session-messages session-id))})

(defn- active-organization-slug [exchange]
  (get-in (identity/public-state
           (cookie-value exchange identity/cookie-name))
          [:organization :organization-id]))

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

            (and (= method "GET") (= path "/api/workspace/drive"))
            (do
              (require-app-session! exchange)
              (send! exchange 200
                     (workspace/snapshot :drive workspace/drive-snapshot)))

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
                  params (query-params exchange)]
              (require-app-session! exchange)
              (require-visible-worker! exchange worker-id)
              (send! exchange 200
                     (organism-gateway/activity
                      (:cursor params)
                      (try
                        (Long/parseLong (or (:limit params) "100"))
                        (catch Exception _ 100)))))

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
                  response (service/run-chat!
                            config
                            {:messages (:messages request)
                             :model (:model request)
                             :provider-id (:provider request)
                             :session-id (or (:session_id request) "openai")
                             :agent-id (:agent_id request)
                             :temperature (:temperature request)})]
              (send! exchange 200 (service/openai-response response)))

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
                     :identity/organization-required 409
                     :worker/invalid-request 400
                     :worker/not-found 404
                     :worker/not-cancellable 409
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
