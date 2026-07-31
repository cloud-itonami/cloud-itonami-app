(ns cloud.itonami.app.mcp-http
  "Authenticated MCP Streamable HTTP transport over the canonical stdio MCP
  dispatcher. Supports stateful 2025-06-18 and sessionless 2026-07-28."
  (:require [clojure.string :as str]
            [cloud.itonami.app.mcp :as mcp])
  (:import [java.time Duration Instant]
           [java.util UUID]))

(def protocol-version "2025-06-18")
(def stateless-protocol-version "2026-07-28")
(def ^:private supported-client-versions
  #{"2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25"})
(def ^:private session-ttl (Duration/ofHours 8))
(def ^:private sessions (atom {}))

(defn- header [headers key]
  (some-> (get headers key) str))

(defn- notification? [request]
  (not (contains? request "id")))

(defn- initialize? [request]
  (= "initialize" (get request "method")))

(defn- expire-sessions! []
  (let [cutoff (.minus (Instant/now) session-ttl)]
    (swap! sessions
           (fn [current]
             (into {}
                   (filter
                    (fn [[_ session]]
                      (.isAfter (Instant/parse (:last-seen-at session)) cutoff)))
                   current)))))

(defn- validate-accept! [headers]
  (let [accept (or (header headers "accept") "")]
    (when-not (and (str/includes? accept "application/json")
                   (str/includes? accept "text/event-stream"))
      (throw (ex-info "MCP POST must accept JSON and event streams."
                      {:type :mcp/invalid-transport})))))

(defn- server-info []
  {"name" "cloud-itonami" "version" "1"})

(defn- initialize-result [response]
  (-> response
      (assoc-in ["result" "protocolVersion"] protocol-version)
      (assoc-in ["result" "serverInfo"] (server-info))))

(defn- discover-response [request]
  {"jsonrpc" "2.0" "id" (get request "id")
   "result"
   {"resultType" "complete"
    "supportedVersions" [stateless-protocol-version protocol-version]
    "capabilities" {"tools" {}}
    "ttlMs" 60000
    "cacheScope" "public"
    "_meta" {"io.modelcontextprotocol/serverInfo" (server-info)}}})

(defn- validate-modern! [request headers]
  (let [method (get request "method")
        metadata (get-in request ["params" "_meta"])
        name (or (get-in request ["params" "name"])
                 (get-in request ["params" "uri"]))]
    (when-not (= method (header headers "mcp-method"))
      (throw (ex-info "Mcp-Method does not match the JSON-RPC body."
                      {:type :mcp/header-mismatch})))
    (when (and (contains? #{"tools/call" "resources/read" "prompts/get"}
                          method)
               (not= name (header headers "mcp-name")))
      (throw (ex-info "Mcp-Name does not match the JSON-RPC body."
                      {:type :mcp/header-mismatch})))
    (when-not (= stateless-protocol-version
                 (get metadata
                      "io.modelcontextprotocol/protocolVersion"))
      (throw (ex-info "Modern MCP protocol metadata is missing."
                      {:type :mcp/invalid-protocol-version})))
    (when-not
     (map? (get metadata "io.modelcontextprotocol/clientCapabilities"))
      (throw (ex-info "Modern MCP client capabilities are required."
                      {:type :mcp/invalid-transport})))
    (when (header headers "mcp-session-id")
      (throw (ex-info "Modern MCP is sessionless."
                      {:type :mcp/invalid-transport})))))

(defn- modern-result [response method]
  (if (get response "result")
    (cond-> (-> response
                (assoc-in ["result" "resultType"] "complete")
                (assoc-in ["result" "_meta"
                           "io.modelcontextprotocol/serverInfo"]
                          (server-info)))
      (= method "tools/list")
      (assoc-in ["result" "ttlMs"] 60000)
      (= method "tools/list")
      (assoc-in ["result" "cacheScope"] "public"))
    response))

(defn- modern-post [configuration actor request headers]
  (validate-modern! request headers)
  (let [method (get request "method")]
    (cond
      (= method "server/discover")
      {:status 200 :body (discover-response request) :headers {}}

      (contains? #{"initialize" "notifications/initialized" "ping"
                   "logging/setLevel" "resources/subscribe"
                   "resources/unsubscribe"}
                 method)
      {:status 404
       :body {"jsonrpc" "2.0" "id" (get request "id")
              "error" {"code" -32601 "message" "Method not found"}}
       :headers {}}

      (notification? request)
      (do (mcp/respond configuration actor request)
          {:status 202 :body nil :headers {}})

      :else
      {:status 200
       :body (modern-result
              (mcp/respond configuration actor request) method)
       :headers {}})))

(defn- create-session! [actor]
  (expire-sessions!)
  (let [id (str (UUID/randomUUID))
        now (str (Instant/now))]
    (swap! sessions assoc id {:actor actor :last-seen-at now
                              :request-ids #{}})
    id))

(defn- require-session! [actor headers]
  (expire-sessions!)
  (let [id (header headers "mcp-session-id")
        session (get @sessions id)]
    (when-not (and session (= actor (:actor session))
                   (= protocol-version
                      (header headers "mcp-protocol-version")))
      (throw (ex-info "MCP session is invalid or expired."
                      {:type :mcp/session-not-found})))
    [id session]))

(defn- reserve-id! [session-id session request]
  (when (contains? request "id")
    (let [request-id (get request "id")]
      (when (contains? (:request-ids session) request-id)
        (throw (ex-info "MCP request ID was already used."
                        {:type :mcp/duplicate-request-id})))
      (swap! sessions assoc session-id
             (-> session
                 (assoc :last-seen-at (str (Instant/now)))
                 (update :request-ids
                         #(set (take-last 256 (conj (vec %) request-id)))))))))

(defn handle-post [configuration actor request headers]
  (validate-accept! headers)
  (if (= stateless-protocol-version
         (header headers "mcp-protocol-version"))
    (modern-post configuration actor request headers)
    (if (initialize? request)
      (let [requested (get-in request ["params" "protocolVersion"])]
        (when-not (contains? supported-client-versions requested)
          (throw (ex-info "MCP protocol version is unsupported."
                          {:type :mcp/invalid-protocol-version})))
        (let [request (assoc-in request ["params" "protocolVersion"]
                                protocol-version)
              session-id (create-session! actor)]
          {:status 200
           :body (initialize-result
                  (mcp/respond configuration actor request))
           :headers {"Mcp-Session-Id" session-id
                     "MCP-Protocol-Version" protocol-version}}))
      (let [[session-id session] (require-session! actor headers)]
        (reserve-id! session-id session request)
        (if (notification? request)
          (do (mcp/respond configuration actor request)
              {:status 202 :body nil :headers {}})
          {:status 200 :body (mcp/respond configuration actor request)
           :headers {}})))))

(defn handle-get [actor headers]
  (if (= stateless-protocol-version
         (header headers "mcp-protocol-version"))
    {:status 405 :body nil :headers {"Allow" "POST"}}
    (do (require-session! actor headers)
        {:status 405 :body nil :headers {"Allow" "POST, DELETE"}})))

(defn handle-delete [actor headers]
  (if (= stateless-protocol-version
         (header headers "mcp-protocol-version"))
    {:status 405 :body nil :headers {"Allow" "POST"}}
    (let [[session-id _] (require-session! actor headers)]
      (swap! sessions dissoc session-id)
      {:status 204 :body nil :headers {}})))

(defn clear-sessions! []
  (reset! sessions {})
  true)
