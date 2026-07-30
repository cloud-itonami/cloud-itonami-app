(ns cloud.itonami.app.mcp-client
  "Fail-closed HTTP client profiles for remote MCP tool servers."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net InetAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration Instant]
           [java.util UUID]))

(def protocol-version "2025-06-18")
(def ^:private maximum-response-bytes (* 2 1024 1024))
(def ^:private cache-seconds 60)
(defonce ^:private cache (atom {}))
(defonce ^:private sessions (atom {}))
(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 8))
      .build))

(defn- loopback-host? [host]
  (try
    (every? #(.isLoopbackAddress ^InetAddress %)
            (InetAddress/getAllByName host))
    (catch Exception _ false)))

(defn- endpoint! [{:keys [url]}]
  (let [uri (URI/create (str url))
        scheme (.getScheme uri)
        host (.getHost uri)]
    (when-not (and host
                   (or (= "https" scheme)
                       (and (= "http" scheme) (loopback-host? host)))
                   (nil? (.getUserInfo uri))
                   (nil? (.getFragment uri)))
      (throw (ex-info "MCP client endpoint must be HTTPS or loopback HTTP."
                      {:type :mcp-client/invalid-endpoint})))
    uri))

(defn- token [profile]
  (some-> (:access-token-env profile) System/getenv str/trim not-empty))

(defn- response-body! [stream]
  (with-open [input stream]
    (let [bytes (.readNBytes input (inc maximum-response-bytes))]
      (when (> (alength bytes) maximum-response-bytes)
        (throw (ex-info "MCP response exceeded the size limit."
                        {:type :mcp-client/response-too-large})))
      (String. bytes StandardCharsets/UTF_8))))

(defn- response-payload [content-type raw request-id]
  (if (str/includes? (or content-type "") "text/event-stream")
    (or
     (some (fn [line]
             (when (str/starts-with? line "data:")
               (let [payload (json/read-str
                              (str/trim (subs line (count "data:"))))]
                 (when (= request-id (get payload "id")) payload))))
           (str/split-lines raw))
     (throw (ex-info "MCP event stream omitted the request response."
                     {:type :mcp-client/invalid-response})))
    (json/read-str raw)))

(defn- request!
  [profile method params]
  (let [uri (endpoint! profile)
        request-id (str (UUID/randomUUID))
        body (json/write-str
              {"jsonrpc" "2.0" "id" request-id
               "method" method "params" (or params {})})
        builder (-> (HttpRequest/newBuilder uri)
                    (.timeout (Duration/ofSeconds
                               (long (or (:timeout-seconds profile) 30))))
                    (.header "Accept" "application/json, text/event-stream")
                    (.header "Content-Type" "application/json")
                    (.header "MCP-Protocol-Version" protocol-version)
                    (.header "Mcp-Method" method))
        _ (when-let [name (or (get params "name") (get params "uri"))]
            (.header builder "Mcp-Name" (str name)))
        _ (when-let [access-token (token profile)]
            (.header builder "Authorization" (str "Bearer " access-token)))
        _ (when-let [session-id (get @sessions (:id profile))]
            (.header builder "Mcp-Session-Id" session-id))
        response (.send client
                        (-> builder
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            .build)
                        (HttpResponse$BodyHandlers/ofInputStream))
        status (.statusCode response)
        session-id (some-> (.firstValue (.headers response)
                                        "Mcp-Session-Id")
                           (.orElse nil))
        content-type (some-> (.firstValue (.headers response)
                                         "Content-Type")
                            (.orElse nil))
        raw (response-body! (.body response))]
    (when session-id
      (swap! sessions assoc (:id profile) session-id))
    (when-not (<= 200 status 299)
      (throw (ex-info "MCP server returned an unsuccessful status."
                      {:type :mcp-client/http-failed :status status})))
    (let [payload (response-payload content-type raw request-id)]
      (when-not (= request-id (get payload "id"))
        (throw (ex-info "MCP response id does not match the request."
                        {:type :mcp-client/invalid-response})))
      (when-let [error (get payload "error")]
        (throw (ex-info "MCP server returned a JSON-RPC error."
                        {:type :mcp-client/rpc-error :error error})))
      (get payload "result"))))

(defn profiles [configuration]
  (->> (get-in configuration [:mcp :clients] [])
       (filter :enabled?)
       (take 8)
       vec))

(defn- profile! [configuration profile-id]
  (or (some #(when (= profile-id (:id %)) %) (profiles configuration))
      (throw (ex-info "MCP client profile was not found."
                      {:type :mcp-client/not-found :profile profile-id}))))

(defn initialize! [configuration profile-id]
  (let [profile (profile! configuration profile-id)]
    (swap! sessions dissoc profile-id)
    (request!
     profile "initialize"
     {"protocolVersion" protocol-version
      "capabilities" {}
      "clientInfo" {"name" "cloud-itonami" "version" "0.1.0"}})))

(defn- request-with-reinitialize!
  [configuration profile method params]
  (try
    (request! profile method params)
    (catch clojure.lang.ExceptionInfo error
      (if (= 404 (:status (ex-data error)))
        (do
          (initialize! configuration (:id profile))
          (request! profile method params))
        (throw error)))))

(defn tools! [configuration profile-id]
  (let [profile (profile! configuration profile-id)
        cached (get @cache profile-id)
        now (Instant/now)]
    (if (and cached (.isAfter (Instant/parse (:expires-at cached)) now))
      (:tools cached)
      (do
        (initialize! configuration profile-id)
        (let [tools (vec (get (request-with-reinitialize!
                               configuration profile "tools/list" {})
                              "tools" []))]
          (when (> (count tools) 128)
            (throw (ex-info "MCP server advertised too many tools."
                            {:type :mcp-client/tool-limit})))
          (swap! cache assoc profile-id
                 {:tools tools
                  :expires-at (str (.plusSeconds now cache-seconds))})
          tools)))))

(defn- safe-segment [value]
  (str/replace (str value) #"[^A-Za-z0-9_-]" "_"))

(defn tool-definitions [configuration]
  (vec
   (mapcat
    (fn [profile]
      (map
       (fn [tool]
         {:name (str "mcp__" (safe-segment (:id profile)) "__"
                     (safe-segment (get tool "name")))
          :description (str "[MCP " (:id profile) "] "
                            (or (get tool "description") (get tool "name")))
          :parameters (or (get tool "inputSchema")
                          {:type "object" :properties {}})
          :mcp/profile (:id profile)
          :mcp/tool (get tool "name")})
       (tools! configuration (:id profile))))
    (profiles configuration))))

(defn call-tool!
  [configuration exposed-name arguments]
  (let [definition
        (some #(when (= exposed-name (:name %)) %)
              (tool-definitions configuration))]
    (when-not definition
      (throw (ex-info "MCP tool was not found."
                      {:type :mcp-client/tool-not-found
                       :tool exposed-name})))
    (request-with-reinitialize!
     configuration
     (profile! configuration (:mcp/profile definition))
     "tools/call"
     {"name" (:mcp/tool definition)
      "arguments" (or arguments {})})))

(defn close!
  "Terminate a stateful Streamable HTTP session when the profile has one."
  [configuration profile-id]
  (let [profile (profile! configuration profile-id)
        session-id (get @sessions profile-id)]
    (when session-id
      (let [builder
            (-> (HttpRequest/newBuilder (endpoint! profile))
                (.timeout (Duration/ofSeconds 10))
                (.header "Mcp-Session-Id" session-id)
                (.header "MCP-Protocol-Version" protocol-version)
                (.header "Accept" "application/json, text/event-stream"))
            _ (when-let [access-token (token profile)]
                (.header builder "Authorization"
                         (str "Bearer " access-token)))
            response
            (.send client
                   (-> builder
                       (.method "DELETE"
                                (HttpRequest$BodyPublishers/noBody))
                       .build)
                   (HttpResponse$BodyHandlers/discarding))]
        (when-not (contains? #{204 405} (.statusCode response))
          (throw (ex-info "MCP session termination failed."
                          {:type :mcp-client/http-failed
                           :status (.statusCode response)})))
        (swap! sessions dissoc profile-id)))
    true))
