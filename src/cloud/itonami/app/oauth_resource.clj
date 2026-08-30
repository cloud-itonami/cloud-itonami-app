(ns cloud.itonami.app.oauth-resource
  "OAuth 2.1 resource-server boundary for hosted API and MCP requests.

  Local opaque app sessions remain valid. Hosted access tokens are resolved by
  RFC 7662 introspection, must be active, unexpired, audience-bound to the exact
  resource URL and carry the route's scope. No bearer token is persisted."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.util Base64]))

(def scopes
  ["mcp:tools" "tenant:connect" "repository:read" "repository:write"
   "domain:read" "domain:write"])

(defonce ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      .build))

(def ^:dynamic *environment* #(System/getenv %))
(def ^:dynamic *introspect*
  (fn [configuration token]
    (let [endpoint (get-in configuration [:mcp :oauth :introspection-endpoint])
          client-id (some-> (get-in configuration [:mcp :oauth :client-id-env])
                            *environment* not-empty)
          client-secret (some-> (get-in configuration
                                         [:mcp :oauth :client-secret-env])
                                *environment* not-empty)
          public? (true? (get-in configuration
                                 [:mcp :oauth :public-introspection?]))]
      (when (str/blank? endpoint)
        (throw (ex-info "OAuth introspection endpoint is not configured"
                        {:type :oauth-resource/not-configured})))
      (let [uri (URI/create endpoint)
            loopback? (contains? #{"localhost" "127.0.0.1" "::1"}
                                 (.getHost uri))]
        (when-not (or (= "https" (.getScheme uri))
                      (and (= "http" (.getScheme uri)) loopback?))
          (throw (ex-info "OAuth introspection requires HTTPS"
                          {:type :oauth-resource/insecure-introspection}))))
      (when (and (not public?) (not (and client-id client-secret)))
        (throw (ex-info "OAuth introspection client credentials are missing"
                        {:type :oauth-resource/introspection-credentials-missing})))
      (let [form (str "token="
                      (URLEncoder/encode token StandardCharsets/UTF_8)
                      "&token_type_hint=access_token")
            builder (-> (HttpRequest/newBuilder (URI/create endpoint))
                        (.timeout (Duration/ofSeconds 10))
                        (.header "Content-Type"
                                 "application/x-www-form-urlencoded")
                        (.header "Accept" "application/json"))]
        (when (and client-id client-secret)
          (.header builder "Authorization"
                   (str "Basic "
                        (.encodeToString
                         (Base64/getEncoder)
                         (.getBytes (str client-id ":" client-secret)
                                    StandardCharsets/UTF_8)))))
        (let [response (.send client
                              (.build (.POST builder
                                             (HttpRequest$BodyPublishers/ofString
                                              form)))
                              (HttpResponse$BodyHandlers/ofString))]
          (when-not (<= 200 (.statusCode response) 299)
            (throw (ex-info "OAuth introspection failed"
                            {:type :oauth-resource/introspection-failed
                             :status (.statusCode response)})))
          (json/read-str (.body response) :key-fn keyword))))))

(defn- resource-origin [configuration service]
  (or (get-in configuration [service :resource-origin])
      (get-in configuration [:server :public-origin])))

(defn resource-url-for [configuration service path]
  (let [base (str/replace (resource-origin configuration service)
                          #"/+$" "")
        uri (URI/create base)
        loopback? (contains? #{"localhost" "127.0.0.1" "::1"}
                             (.getHost uri))]
    (when-not (or (= "https" (.getScheme uri))
                  (and (= "http" (.getScheme uri)) loopback?))
      (throw (ex-info "hosted agent resource requires HTTPS"
                      {:type :oauth-resource/insecure-resource})))
    (str base path)))

(defn resource-url [configuration]
  (resource-url-for configuration :mcp "/mcp"))

(defn a2a-resource-url [configuration]
  (resource-url-for configuration :a2a "/a2a"))


(defn oauth-resource-route? [method path]
  "Whether this request is the RFC 9728 protected-resource document.

  The judgement is in `oauth_resource_core.kotoba` and RUNS from there.
  This namespace still owns metadata, resource URL and introspection."
  (oracle/call :oauth-resource 'oauth-resource-route? [(str method) (str path)]))

(defn metadata-url [configuration]
  (str (str/replace (resource-origin configuration :mcp) #"/+$" "")
       "/.well-known/oauth-protected-resource/mcp"))

(defn a2a-metadata-url [configuration]
  (str (str/replace (resource-origin configuration :a2a) #"/+$" "")
       "/.well-known/oauth-protected-resource/a2a"))

(defn- authorization-servers [configuration]
  (vec (remove str/blank?
               (get-in configuration [:mcp :oauth :authorization-servers]))))

(defn- metadata* [resource resource-name supported-scopes servers]
  (when (empty? servers)
    (throw (ex-info "OAuth authorization server is not configured"
                    {:type :oauth-resource/no-authorization-server})))
  {:resource resource
   :resource_name resource-name
   :bearer_methods_supported ["header"]
   :scopes_supported supported-scopes
   :authorization_servers servers})

(defn metadata [configuration]
  (metadata* (resource-url configuration) "Itonami Cloud MCP" scopes
             (authorization-servers configuration)))

(defn a2a-metadata [configuration]
  (metadata* (a2a-resource-url configuration) "Cloud Itonami A2A"
             ["a2a:tasks"] (authorization-servers configuration)))

(defn challenge-for [metadata-document required-scope]
  (str "Bearer resource_metadata=\"" metadata-document "\""
       (when required-scope (str ", scope=\"" required-scope "\""))))

(defn challenge [configuration required-scope]
  (challenge-for (metadata-url configuration) required-scope))

(defn a2a-challenge [configuration required-scope]
  (challenge-for (a2a-metadata-url configuration) required-scope))

(defn- stable-session-id [configuration claims]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes
                         (str (or (:iss claims)
                                  (get-in configuration
                                          [:mcp :oauth :introspection-endpoint]))
                              "\u0000" (:sub claims)
                              "\u0000" (:client_id claims))
                         StandardCharsets/UTF_8))]
    (str "oauth-"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                         (take 16 digest))))))

(defn- audience-set [value]
  (cond
    (string? value) #{value}
    (sequential? value) (set (map str value))
    :else #{}))

(defn- scope-set [value]
  (cond
    (string? value) (set (remove str/blank? (str/split value #"\s+")))
    (sequential? value) (set (map str value))
    :else #{}))

(defn- membership-for [user-id]
  (some #(when (= user-id (:user-id %)) %)
        (vals (get-in (store/snapshot) [:identity :memberships]))))

(defn session
  "Resolve one externally-issued access token into an ephemeral agent session.
  REQUIRED-SCOPE and RESOURCE are checked before a local identity is returned."
  [configuration token required-scope resource]
  (when-not (str/blank? token)
    (let [claims (*introspect* configuration token)
          now (.getEpochSecond (Instant/now))
          expires (some-> (:exp claims) long)
          token-scopes (scope-set (:scope claims))]
      (when-not (true? (:active claims))
        (throw (ex-info "OAuth access token is inactive"
                        {:type :oauth-resource/invalid-token})))
      (when (and expires (<= expires now))
        (throw (ex-info "OAuth access token is expired"
                        {:type :oauth-resource/invalid-token})))
      (when-not (contains? (audience-set (:aud claims)) resource)
        (throw (ex-info "OAuth access token audience does not match this resource"
                        {:type :oauth-resource/invalid-audience})))
      (when-not (contains? token-scopes required-scope)
        (throw (ex-info "OAuth access token lacks the required scope"
                        {:type :oauth-resource/insufficient-scope
                         :required-scope required-scope})))
      (let [user-id (some-> (:sub claims) str not-empty)
            client-id (some-> (:client_id claims) str not-empty)
            _ (when-not client-id
                (throw (ex-info "OAuth introspection omitted client_id"
                                {:type :oauth-resource/invalid-token})))
            membership (membership-for user-id)]
        (when-not membership
          (throw (ex-info "OAuth subject has no Itonami membership"
                          {:type :oauth-resource/unknown-subject})))
        {:id (stable-session-id configuration claims)
         :kind :agent
         :issued-via :oauth
         :user-id user-id
         :membership-id (:id membership)
         :organization-id (:organization-id membership)
         :oauth/scopes token-scopes
         :oauth/client-id client-id}))))
