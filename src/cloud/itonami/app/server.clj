(ns cloud.itonami.app.server
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.authority.api :as authority-api]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.canvas :as canvas]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.contracts :as contracts]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.credential-sd-jwt :as credential-sd-jwt]
            [cloud.itonami.app.credential-trust :as credential-trust]
            [cloud.itonami.app.presentation-request :as presentation-request]
            [cloud.itonami.app.credential-assurance :as credential-assurance]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.esign :as esign]
            [cloud.itonami.app.esign.retention :as esign-retention]
            [cloud.itonami.app.executor :as executor]
            [cloud.itonami.app.filecoin :as filecoin]
            [cloud.itonami.app.fleet :as fleet]
            [cloud.itonami.app.operator :as operator]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.loops :as loops]
            [cloud.itonami.app.metrics :as business-metrics]
            [cloud.itonami.app.organism-gateway :as organism-gateway]
            [cloud.itonami.app.relay :as relay]
            [cloud.itonami.app.repos :as business-repos]
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

;; A signer is named by DID, not by user id: a commitment names the key that
;; will sign and a user with two Passkeys has two DIDs. `:principal` is still the
;; user id, because that is what the Drive's ACL is keyed on — `cloud.itonami.app.esign`
;; needs both and neither substitutes for the other.
(defn- esign-who [session]
  {:principal (:user-id session)
   :did (get-in (store/snapshot) [:identity :users (:user-id session) :did])})

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
            (and (= method "GET") (= path "/.well-known/did.json"))
            (let [domain (identity/organization-domain-for-did-web)]
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
                              [k {:policy (credential-assurance/policy-for config k)
                                  :accepted
                                  (mapv :credential-id
                                        (filter #(empty?
                                                  (credential-assurance/policy-issues
                                                   % (credential-assurance/policy-for config k)))
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
            (let [session (require-app-session! exchange)]
              (send! exchange 200 (funding/snapshot config session)))

            (and (= method "POST") (= path "/api/funding/accounts"))
            (let [session (require-app-session! exchange)]
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
            (let [session (require-app-session! exchange)
                  [_ account-id] (re-matches
                                  #"/api/funding/accounts/([^/]+)/balance" path)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200
                     (funding/record-balance! session account-id
                                              (read-json exchange))))

            (and (= method "POST")
                 (re-matches #"/api/funding/accounts/([^/]+)/close" path))
            (let [session (require-app-session! exchange)
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

            ;; Refresh asks the authority what became of a PENDING proposal.
            ;; Read-only against the authority: it hits the actor's consent
            ;; surface, which cannot decide -- the decision lives on a listener
            ;; this app has no route to, which is the point.
            ;; Resolve everything at once. The per-proposal refresh below is still
            ;; there, but nothing ever called it -- which is why an :authority-pending
            ;; proposal used to sit forever. A caller that opens the authority panel can
            ;; hit this once instead of knowing which proposals to chase.
            (and (= method "POST") (= path "/api/authority/resolve-pending"))
            (let [session (require-app-session! exchange)]
              (require-origin! exchange config)
              (require-csrf! exchange session)
              (send! exchange 200 (authority-api/resolve-pending! config session)))

            (and (= method "POST")
                 (authority+id-from-path
                  path #"/api/authority/([^/]+)/proposals/([^/]+)/refresh"))
            (let [session (require-app-session! exchange)
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
                                        (:user-id session))))

            ;; What this document points at, and what points at it. Both
            ;; resolve through `locate`, so a reference to something the
            ;; asker may not read is reported as unresolved rather than
            ;; leaking that it exists.
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
