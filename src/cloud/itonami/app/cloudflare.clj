(ns cloud.itonami.app.cloudflare
  "Credentialed HTTP host for yadori's pure Cloudflare request maps."
  (:require [clojure.data.json :as json]
            [kotoba.lang.text :as str]
            [cloud.itonami.app.http-client :as http]
            [yadori.cloudflare :as yadori])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(def ^:dynamic *environment* #(System/getenv %))
(def ^:dynamic *send!*
  (fn [request]
    (http/request request)))

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
        hdrs {"Authorization" (str "Bearer " token)
              "Content-Type" "application/json"}
        method-str (case method
                     :get "GET"
                     :post "POST"
                     :patch "PATCH"
                     :delete "DELETE"
                     (throw (ex-info "unsupported Cloudflare request method"
                                     {:type :domain-service/method :method method})))
        response (*send!* {:url (str base path (query-string query))
                           :method (keyword (str/lower method-str))
                           :timeout-seconds 30
                           :headers hdrs
                           :body (when (contains? #{:post :patch} method)
                                   (json/write-str (or body {})))})
        status (:status response)
        decoded (try (json/read-str (:body response) :key-fn keyword)
                     (catch Exception _ {:success false
                                         :errors [{:message "Cloudflare returned invalid JSON"}]}))]
    (when-not (<= 200 status 299)
      (throw (ex-info (or (get-in decoded [:errors 0 :message]) (str "Cloudflare HTTP " status))
                      {:type :domain-service/http :status status})))
    decoded))
