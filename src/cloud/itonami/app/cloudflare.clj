(ns cloud.itonami.app.cloudflare
  "Credentialed HTTP host for yadori's pure Cloudflare request maps."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [yadori.cloudflare :as yadori])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(defonce ^:private client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) .build))

(def ^:dynamic *environment* #(System/getenv %))
(def ^:dynamic *send!*
  (fn [request]
    (let [response (.send client request (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (.body response)})))

(defn- env-name [configuration key default]
  (or (get-in configuration [:domain-service key]) default))

(defn account-id [configuration]
  (some-> (*environment* (env-name configuration :account-id-env
                                   "CLOUDFLARE_ACCOUNT_ID"))
          str/trim not-empty))

(defn api-token [configuration]
  (some-> (*environment* (env-name configuration :api-token-env
                                   "CLOUDFLARE_API_TOKEN"))
          str/trim not-empty))

(defn available? [configuration]
  (boolean (and (account-id configuration) (api-token configuration))))

(defn- encoded [x]
  (URLEncoder/encode (str x) StandardCharsets/UTF_8))

(defn- query-string [query]
  (when (seq query)
    (str "?" (str/join "&" (map (fn [[k v]]
                                  (str (encoded (name k)) "=" (encoded v)))
                                query)))))

(defn request!
  "Execute one yadori request. Never logs or returns the bearer token."
  [configuration {:keys [method path query body]}]
  (let [token (or (api-token configuration)
                  (throw (ex-info "Cloudflare API token is not configured"
                                  {:type :domain-service/not-configured})))
        base (str/replace (or (get-in configuration [:domain-service :api-base])
                              yadori/api-base) #"/+$" "")
        builder (-> (HttpRequest/newBuilder
                     (URI/create (str base path (query-string query))))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Content-Type" "application/json"))
        publisher #(HttpRequest$BodyPublishers/ofString (json/write-str (or body {})))
        builder (case method
                  :get (.GET builder)
                  :post (.POST builder (publisher))
                  :patch (.method builder "PATCH" (publisher))
                  :delete (.DELETE builder)
                  (throw (ex-info "unsupported Cloudflare request method"
                                  {:type :domain-service/method :method method})))
        response (*send!* (.build builder))
        status (:status response)
        decoded (try (json/read-str (:body response) :key-fn keyword)
                     (catch Exception _ {:success false
                                         :errors [{:message "Cloudflare returned invalid JSON"}]}))]
    (when-not (<= 200 status 299)
      (throw (ex-info (or (get-in decoded [:errors 0 :message]) (str "Cloudflare HTTP " status))
                      {:type :domain-service/http :status status})))
    decoded))
