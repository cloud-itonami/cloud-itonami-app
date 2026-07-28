(ns cloud.itonami.app.relay
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- setting [config key default]
  (or (get-in config [:cloud-relay key]) default))

(defn- access-token [config]
  (let [env-name (setting config :access-token-env
                          "CLOUD_ITONAMI_RELAY_ACCESS_TOKEN")]
    (some-> (System/getenv env-name) str/trim not-empty)))

(defn configured? [config]
  (boolean (and (get-in config [:cloud-relay :base-url])
                (access-token config))))

(defn- endpoint [config path]
  (str (str/replace (setting config :base-url "") #"/$" "")
       path))

(defn- request! [config method path body]
  (let [token (access-token config)]
    (when-not token
      (throw (ex-info
              "Private Relay provider を設定して認証してください。"
              {:type :relay/not-configured})))
    (let [builder (doto (HttpRequest/newBuilder (URI/create (endpoint config path)))
                    (.timeout (Duration/ofSeconds 20))
                    (.header "Accept" "application/json")
                    (.header "Authorization" (str "Bearer " token)))
          request (if body
                    (-> builder
                        (.header "Content-Type" "application/json")
                        (.method method
                                 (HttpRequest$BodyPublishers/ofString
                                  (json/write-str body)))
                        (.build))
                    (-> builder
                        (.method method (HttpRequest$BodyPublishers/noBody))
                        (.build)))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          payload (try
                    (json/read-str (.body response) :key-fn keyword)
                    (catch Exception _ {:error "InvalidCloudResponse"}))]
      (if (<= 200 (.statusCode response) 299)
        payload
        (throw (ex-info
                (or (:message payload)
                    (some-> (:error payload) str)
                    "グローバルメール登録に失敗しました。")
                {:type :relay/request-failed
                 :status (.statusCode response)
                 :response payload}))))))

(defn alias-status! [config account-id]
  (if-not (configured? config)
    {:configured? false :found false}
    (assoc
     (request! config "GET"
               (str "/v1/aliases/me?accountId="
                    (URLEncoder/encode account-id StandardCharsets/UTF_8))
               nil)
     :configured? true)))

(defn reserve-alias! [config account-id destination]
  (when (str/blank? destination)
    (throw (ex-info "転送先メールアドレスが必要です。"
                    {:type :relay/invalid-destination})))
  (assoc
   (request! config "POST" "/v1/aliases/reserve"
             {:accountId account-id :destination destination})
   :configured? true))
