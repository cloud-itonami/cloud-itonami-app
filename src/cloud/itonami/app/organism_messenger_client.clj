(ns cloud.itonami.app.organism-messenger-client
  "External-supervisor client for an OrganismWorker mailbox.

  This process reads the worker's private transport credential and talks only
  to `/api/ao/messenger/*`. It never accepts a mailbox principal: the server
  derives that from the bearer. A supervisor can call `poll!`, produce a normal
  message (not an execution intent), `reply!`, and `ack!` after its durable
  checkpoint succeeds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.organism-messenger-transport :as transport])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def schema "cloud.itonami.app.organism-messenger-client.v1")
(defonce ^:private client (HttpClient/newHttpClient))

(defn- origin []
  (str/replace (or (System/getenv "CLOUD_ITONAMI_ORIGIN")
                   "http://127.0.0.1:1338") #"/$" ""))

(defn- credential! [worker-id]
  (or (transport/read-credential worker-id)
      (throw (ex-info "OrganismWorker messenger credentialがありません。owner/adminがtransportを発行してください。"
                      {:type :ao.messenger/credential-missing
                       :worker-id worker-id}))))

(defn- request! [worker-id method path body]
  (let [token (:token (credential! worker-id))
        builder (-> (HttpRequest/newBuilder (URI/create (str (origin) path)))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json"))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder
                               (HttpRequest$BodyPublishers/ofString
                                (json/write-str (or body {})))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))
        parsed (try (json/read-str (.body response) :key-fn keyword)
                    (catch Exception _ {:raw (.body response)}))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "AO messenger transport request failed"
                      {:type :ao.messenger/http
                       :status (.statusCode response) :response parsed})))
    parsed))

(defn overview! [worker-id]
  (request! worker-id :get "/api/ao/messenger" nil))

(defn poll!
  ([worker-id cursor] (poll! worker-id cursor 50))
  ([worker-id cursor limit]
   (let [query (str "?limit=" (min 100 (max 1 (long limit)))
                    (when-not (str/blank? (str cursor))
                      (str "&cursor="
                           (URLEncoder/encode (str cursor) StandardCharsets/UTF_8))))]
     (request! worker-id :get (str "/api/ao/messenger/poll" query) nil))))

(defn acknowledge! [worker-id message-ids]
  (request! worker-id :post "/api/ao/messenger/ack"
            {:message-ids (vec message-ids)}))

(defn trust! [worker-id sender-id allowed?]
  (request! worker-id :post "/api/ao/messenger/trust"
            {:sender-id sender-id :allowed? (boolean allowed?)}))

(defn reply! [worker-id conversation-id content-or-message]
  (let [message (if (map? content-or-message)
                  content-or-message
                  {:content content-or-message
                   :encryption-mode "local-plaintext"})]
    (request! worker-id :post
              (str "/api/ao/messenger/conversations/"
                   (URLEncoder/encode conversation-id StandardCharsets/UTF_8)
                   "/messages")
              message)))

(defn register-device! [worker-id public-device]
  (request! worker-id :post "/api/ao/messenger/devices" public-device))

(defn prekey-bundles! [worker-id principal]
  (request! worker-id :post "/api/ao/messenger/prekey-bundles"
            {:principal principal}))

(defn -main [& [command worker-id & args]]
  (when (or (str/blank? command) (str/blank? worker-id))
    (binding [*out* *err*]
      (println "usage: clojure -M:ao-messenger <poll|reply|ack|trust> <worker-id> [args]"))
    (System/exit 2))
  (let [result
        (case command
          "poll" (poll! worker-id (first args))
          "reply" (reply! worker-id (first args) (str/join " " (rest args)))
          "ack" (acknowledge! worker-id args)
          "trust" (trust! worker-id (first args) (= "true" (second args)))
          (throw (ex-info "unknown AO messenger command"
                          {:type :ao.messenger/command :command command})))]
    (println (json/write-str result))))
