(ns cloud.itonami.app.relay
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.identity :as identity])
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
    (or (some-> (System/getenv env-name) str/trim not-empty)
        ;; One known item, never enumeration. This is the same credential the
        ;; mail-sync push poller already uses for this relay.
        (identity/keychain-find "cloud-itonami-app.webhooks" "relay-access"))))

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

(defn provision-bot-mailbox!
  "Register one immutable Bot address and the mailbox that receives it."
  [config {:keys [bot-id organization address destination]}]
  (when (or (str/blank? (str bot-id))
            (str/blank? (str organization))
            (str/blank? (str address))
            (str/blank? (str destination)))
    (throw (ex-info "Bot mailbox の登録内容が不足しています。"
                    {:type :relay/invalid-destination})))
  (request! config "POST" "/v1/bot-mailboxes"
            {:botId bot-id :organization organization
             :address address :destination destination}))

(defn send-bot-mail!
  "Send through the relay's Resend boundary as a registered Bot address."
  [config {:keys [bot-id organization from name to cc subject text
                  in-reply-to]}]
  (request! config "POST" "/v1/bot-mail/send"
            {:botId bot-id :organization organization :from from :name name
             :to (vec to) :cc (vec cc) :subject subject :text text
             :inReplyTo in-reply-to}))
