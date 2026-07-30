(ns cloud.itonami.app.mail-sync
  "OAuth-backed local-first mail synchronization for Gmail and Microsoft Graph."
  (:require [cloud.itonami.app.config :as config]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration Instant]
           [java.util Base64]
           [java.util.concurrent Executors ScheduledExecutorService
            ThreadFactory TimeUnit]))

(defonce ^:private http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      .build))
(defonce ^:private scheduler (atom nil))
(defonce ^:private syncing? (atom false))
(defonce ^:private runtime-config (atom {}))

(defn- keychain-secret [service account]
  (try
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "find-generic-password"
                        "-s" service "-a" account "-w"])
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when (and completed? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 500 "")))))
    (catch Exception _ nil)))

(defn- url-encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- query-string [values]
  (str/join "&"
            (map (fn [[key value]]
                   (str (url-encode (name key)) "=" (url-encode value)))
                 values)))

(defn- request-json!
  ([token url] (request-json! token url {}))
  ([token url headers]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.header "Authorization" (str "Bearer " token))
                     (.header "Accept" "application/json")
                     (.header "User-Agent" "cloud-itonami-app")
                     .GET)
         _ (doseq [[header value] headers] (.header builder header value))
         response (.send http-client (.build builder)
                         (HttpResponse$BodyHandlers/ofString))
         status (.statusCode response)
         body (try
                (json/read-str (.body response) :key-fn keyword)
                (catch Exception _ {:raw (.body response)}))]
     (if (<= 200 status 299)
       body
       (throw (ex-info "メールプロバイダーの同期要求が失敗しました。"
                       {:type :mail/provider-error :status status
                        :url (first (str/split url #"\?"))}))))))

(defn- post-json! [token url body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Accept" "application/json")
                    (.header "Content-Type" "application/json")
                    (.header "User-Agent" "cloud-itonami-app")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/write-str body)))
                    .build)
        response (.send http-client request
                        (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        data (try (json/read-str (.body response) :key-fn keyword)
                  (catch Exception _ {:raw (.body response)}))]
    (if (<= 200 status 299)
      data
      (throw (ex-info "メール通知の登録要求が失敗しました。"
                      {:type :mail/watch-error :status status})))))

(defn- patch-json! [token url body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Accept" "application/json")
                    (.header "Content-Type" "application/json")
                    (.method "PATCH"
                             (java.net.http.HttpRequest$BodyPublishers/ofString
                              (json/write-str body)))
                    .build)
        response (.send http-client request
                        (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "メール通知の更新要求が失敗しました。"
                      {:type :mail/watch-error
                       :status (.statusCode response)})))
    (json/read-str (.body response) :key-fn keyword)))

(defn classify
  "Add portable local labels without changing provider-side labels."
  [{:keys [subject from-email labels]}]
  (let [text (str/lower-case (str subject " " from-email))
        remote (set (map #(-> % str str/lower-case
                             (str/replace #"[^a-z0-9._-]+" "-")
                             keyword)
                         labels))]
    (cond-> remote
      (re-find #"invoice|receipt|billing|請求|領収|支払" text)
      (conj :finance)
      (re-find #"security|alert|password|login|認証|セキュリティ" text)
      (conj :security)
      (re-find #"notification|noreply|no-reply|通知" text)
      (conj :notification)
      (re-find #"newsletter|digest|ニュース|メルマガ" text)
      (conj :newsletter))))

(defn- archive-root []
  (io/file (or (System/getenv "CLOUD_ITONAMI_MAIL_ARCHIVE")
               (str (io/file (config/data-dir) "mail-archive")))))

(defn- archive-message! [message]
  (let [provider (name (:provider message))
        safe-id (str/replace (:provider-message-id message)
                             #"[^A-Za-z0-9._-]" "_")
        file (io/file (archive-root) provider (str safe-id ".json"))]
    (.mkdirs (.getParentFile file))
    (spit file (json/write-str message))
    (.getPath file)))

(defn- upsert-messages! [provider messages cursor]
  (let [now (store/now)
        prepared
        (mapv (fn [message]
                (let [message (assoc message
                                     :provider provider
                                     :id (str (name provider) ":"
                                              (:provider-message-id message))
                                     :synced-at now)
                      labels (classify message)
                      message (assoc message :labels labels)
                      archive-path (archive-message! message)]
                  (assoc message :archive-path archive-path)))
              messages)]
    (store/transact!
     (fn [state]
       (let [with-messages
             (reduce (fn [current message]
                       (assoc-in current [:mail-sync :messages (:id message)]
                                 message))
                     state prepared)]
         (-> with-messages
             (assoc-in [:mail-sync :providers provider]
                       {:provider provider :status :ready
                        :cursor cursor :last-synced-at now
                        :message-count
                        (count (filter #(= provider (:provider %))
                                       (vals (get-in with-messages
                                                     [:mail-sync :messages]))))
                        :last-error nil})
             (update :events #(vec (take-last 100
                                             (conj (or % [])
                                                   {:type :mail/synced :at now
                                                    :provider provider
                                                    :changed (count prepared)}))))))))
    {:provider provider :changed (count prepared) :cursor cursor}))

(defn- delete-messages! [provider provider-ids]
  (when (seq provider-ids)
    (store/transact!
     update-in [:mail-sync :messages]
     (fn [messages]
       (reduce dissoc (or messages {})
               (map #(str (name provider) ":" %) provider-ids))))))

(defn- gmail-header [payload header]
  (some (fn [entry]
          (when (= (str/lower-case header)
                   (str/lower-case (:name entry)))
            (:value entry)))
        (:headers payload)))

(defn- gmail-body [part]
  (let [mime (:mimeType part)
        encoded (get-in part [:body :data])]
    (cond
      (and encoded (or (= mime "text/plain") (= mime "text/html")))
      (try
        (String. (.decode (Base64/getUrlDecoder) ^String encoded)
                 StandardCharsets/UTF_8)
        (catch Exception _ ""))
      (seq (:parts part))
      (or (some #(let [body (gmail-body %)]
                   (when (and (not (str/blank? body))
                              (= "text/plain" (:mimeType %)))
                     body))
                (:parts part))
          (some gmail-body (:parts part))
          "")
      :else "")))

(defn- gmail-message! [token id label-names]
  (let [data (request-json!
              token
              (str "https://gmail.googleapis.com/gmail/v1/users/me/messages/"
                   (url-encode id) "?format=full"))
        payload (:payload data)
        from (gmail-header payload "from")
        email (or (second (re-find #"<([^>]+)>" (or from ""))) from)
        labels (mapv #(get label-names % %) (:labelIds data))
        body (gmail-body payload)]
    {:provider-message-id (:id data)
     :thread-id (:threadId data)
     :subject (or (gmail-header payload "subject") "(件名なし)")
     :from (or from "送信者不明")
     :from-email (or email "unknown@local.invalid")
     :to (or (gmail-header payload "to") "")
     :received-at (or (gmail-header payload "date")
                      (some-> (:internalDate data) Long/parseLong Instant/ofEpochMilli str))
     :snippet (or (:snippet data) (subs body 0 (min 220 (count body))))
     :body body :labels labels
     :read? (not (some #{"UNREAD"} (:labelIds data)))
     :size-bytes (or (:sizeEstimate data) 0)}))

(defn- gmail-labels! [token]
  (->> (:labels (request-json!
                 token
                 "https://gmail.googleapis.com/gmail/v1/users/me/labels"))
       (map (juxt :id :name))
       (into {})))

(defn- gmail-full-sync! [token]
  (let [label-names (gmail-labels! token)
        listing (request-json!
                 token
                 (str "https://gmail.googleapis.com/gmail/v1/users/me/messages?"
                      (query-string {:labelIds "INBOX" :maxResults 100})))
        messages (mapv #(gmail-message! token (:id %) label-names)
                       (:messages listing))
        profile (request-json!
                 token
                 "https://gmail.googleapis.com/gmail/v1/users/me/profile")]
    (upsert-messages! :google messages {:history-id (:historyId profile)})))

(defn- ensure-gmail-watch! [token]
  (when-let [topic (or (some-> (System/getenv "ITONAMI_GMAIL_TOPIC")
                               str/trim not-empty)
                       (some-> (:gmail-topic @runtime-config)
                               str/trim not-empty))]
    (let [expiration (get-in (store/snapshot)
                             [:mail-sync :providers :google :watch-expiration])
          renew? (or (nil? expiration)
                     (neg? (compare (Instant/ofEpochMilli
                                     (Long/parseLong (str expiration)))
                                    (.plusSeconds (Instant/now) 86400))))]
      (when renew?
        (let [watch (post-json!
                     token
                     "https://gmail.googleapis.com/gmail/v1/users/me/watch"
                     {:topicName topic
                      :labelIds ["INBOX"]
                      :labelFilterBehavior "INCLUDE"})]
          (store/transact!
           (fn [state]
             (-> state
                 (assoc-in [:mail-sync :providers :google :watch-expiration]
                           (:expiration watch))
                 (assoc-in [:mail-sync :providers :google :watch-history-id]
                           (:historyId watch))))))))))

(defn- gmail-incremental-sync! [token history-id]
  (try
    (let [history (request-json!
                   token
                   (str "https://gmail.googleapis.com/gmail/v1/users/me/history?"
                        (query-string {:startHistoryId history-id
                                       :historyTypes "messageAdded"
                                       :maxResults 500})))
          changed (->> (:history history)
                       (mapcat #(concat (:messagesAdded %)
                                       (:labelsAdded %)
                                       (:labelsRemoved %)))
                       (keep #(get-in % [:message :id]))
                       distinct)
          deleted (->> (:history history)
                       (mapcat :messagesDeleted)
                       (keep #(get-in % [:message :id]))
                       distinct)
          labels (gmail-labels! token)
          messages (mapv #(gmail-message! token % labels) changed)
          cursor {:history-id (or (:historyId history) history-id)}]
      (delete-messages! :google deleted)
      (upsert-messages! :google messages cursor))
    (catch clojure.lang.ExceptionInfo error
      (if (= 404 (:status (ex-data error)))
        (gmail-full-sync! token)
        (throw error)))))

(defn sync-gmail! []
  (when-let [token (identity/provider-access-token! :google)]
    (let [history-id (get-in (store/snapshot)
                             [:mail-sync :providers :google :cursor :history-id])
          result (if history-id
                   (gmail-incremental-sync! token history-id)
                   (gmail-full-sync! token))]
      (ensure-gmail-watch! token)
      result)))

(defn- graph-folders! [token]
  (:value
   (request-json!
    token
    (str "https://graph.microsoft.com/v1.0/me/mailFolders?"
         (query-string {:$top 100
                        :includeHiddenFolders "true"
                        :$select "id,displayName"})))))

(defn- graph-message [folder data]
  (let [sender (get-in data [:sender :emailAddress])
        body (or (get-in data [:body :content]) (:bodyPreview data) "")]
    {:provider-message-id (:id data)
     :thread-id (or (:conversationId data) (:id data))
     :subject (or (:subject data) "(件名なし)")
     :from (or (:name sender) (:address sender) "送信者不明")
     :from-email (or (:address sender) "unknown@local.invalid")
     :to (str/join ", " (keep #(get-in % [:emailAddress :address])
                              (:toRecipients data)))
     :received-at (:receivedDateTime data)
     :snippet (or (:bodyPreview data)
                  (subs body 0 (min 220 (count body))))
     :body body
     :labels [(:displayName folder)]
     :read? (boolean (:isRead data))
     :size-bytes (count (.getBytes body StandardCharsets/UTF_8))}))

(defn- graph-folder-sync! [token folder delta-link]
  (loop [url (or delta-link
                 (str "https://graph.microsoft.com/v1.0/me/mailFolders/"
                      (url-encode (:id folder)) "/messages/delta?"
                      (query-string
                       {:$select (str "id,conversationId,subject,sender,toRecipients,"
                                      "receivedDateTime,isRead,bodyPreview,body")
                        :$top 50})))
         messages []
         deleted []]
    (let [page (request-json! token url {"Prefer" "odata.maxpagesize=50"})
          current (remove #(contains? % (keyword "@removed")) (:value page))
          removed (keep #(when (contains? % (keyword "@removed")) (:id %))
                        (:value page))
          messages (into messages (map #(graph-message folder %) current))
          deleted (into deleted removed)]
      (if-let [next-link (get page (keyword "@odata.nextLink"))]
        (recur next-link messages deleted)
        {:messages messages :deleted deleted
         :delta-link (get page (keyword "@odata.deltaLink"))}))))

(declare ensure-graph-subscription!)

(defn sync-microsoft! []
  (when-let [token (identity/provider-access-token! :microsoft)]
    (let [folders (graph-folders! token)
          previous (get-in (store/snapshot)
                           [:mail-sync :providers :microsoft :cursor
                            :folder-deltas])
          results (mapv #(graph-folder-sync! token % (get previous (:id %)))
                        folders)
          messages (vec (mapcat :messages results))
          deleted (mapcat :deleted results)
          deltas (into {} (map (fn [folder result]
                                 [(:id folder) (:delta-link result)])
                               folders results))
          result (do
                   (delete-messages! :microsoft deleted)
                   (upsert-messages! :microsoft messages
                                     {:folder-deltas deltas}))]
      (ensure-graph-subscription! token)
      result)))

(defn- ensure-graph-subscription! [token]
  (when-let [client-state (keychain-secret "cloud-itonami-app.webhooks"
                                           "graph-client-state")]
    (let [expiration (get-in (store/snapshot)
                             [:mail-sync :providers :microsoft
                              :subscription-expiration])
          subscription-id (get-in (store/snapshot)
                                  [:mail-sync :providers :microsoft
                                   :subscription-id])
          renew? (or (nil? expiration)
                     (neg? (compare (Instant/parse expiration)
                                    (.plusSeconds (Instant/now) 3600))))]
      (when renew?
        (let [expires-at (str (.plusSeconds (Instant/now) (* 2 24 60 60)))
              subscription (if subscription-id
                             (try
                               (patch-json!
                                token
                                (str "https://graph.microsoft.com/v1.0/subscriptions/"
                                     (url-encode subscription-id))
                                {:expirationDateTime expires-at})
                               (catch Exception _
                                 (post-json!
                                  token
                                  "https://graph.microsoft.com/v1.0/subscriptions"
                                  {:changeType "created,updated,deleted"
                                   :notificationUrl
                                   "https://hooks.itonami.cloud/v1/webhooks/microsoft"
                                   :lifecycleNotificationUrl
                                   "https://hooks.itonami.cloud/v1/webhooks/microsoft"
                                   :resource "/me/mailFolders('inbox')/messages"
                                   :expirationDateTime expires-at
                                   :clientState client-state})))
                             (post-json!
                              token
                              "https://graph.microsoft.com/v1.0/subscriptions"
                              {:changeType "created,updated,deleted"
                               :notificationUrl
                               "https://hooks.itonami.cloud/v1/webhooks/microsoft"
                               :lifecycleNotificationUrl
                               "https://hooks.itonami.cloud/v1/webhooks/microsoft"
                               :resource "/me/mailFolders('inbox')/messages"
                               :expirationDateTime expires-at
                               :clientState client-state}))]
          (store/transact!
           (fn [state]
             (-> state
                 (assoc-in [:mail-sync :providers :microsoft
                            :subscription-id] (:id subscription))
                 (assoc-in [:mail-sync :providers :microsoft
                            :subscription-expiration]
                           (:expirationDateTime subscription))))))))))

(defn- record-error! [provider error]
  (store/transact!
   (fn [state]
     (-> state
         (assoc-in [:mail-sync :providers provider :status] :error)
         (assoc-in [:mail-sync :providers provider :last-error]
                   (.getMessage error))
         (assoc-in [:mail-sync :providers provider :last-attempt-at]
                   (store/now))))))

(defn sync-all! []
  (if-not (compare-and-set! syncing? false true)
    {:status :already-running}
    (try
      {:status :completed
       :providers
       (into {}
             (keep (fn [provider]
                     (when (contains? (identity/connected-providers) provider)
                       [provider
                        (try
                          (case provider
                            :google (sync-gmail!)
                            :microsoft (sync-microsoft!))
                          (catch Exception error
                            (record-error! provider error)
                            {:error (.getMessage error)}))])))
             [:google :microsoft])}
      (finally (reset! syncing? false)))))

(defn- relay-request-json! [method path body]
  (when-let [token (or (System/getenv "ITONAMI_WEBHOOK_RELAY_TOKEN")
                       (keychain-secret "cloud-itonami-app.webhooks"
                                        "relay-access"))]
    (let [base-url (or (System/getenv "ITONAMI_WEBHOOK_RELAY_URL")
                       "https://hooks.itonami.cloud")
          builder (-> (HttpRequest/newBuilder (URI/create (str base-url path)))
                      (.header "Authorization" (str "Bearer " token))
                      (.header "Accept" "application/json"))
          builder (if (= method :post)
                    (-> builder
                        (.header "Content-Type" "application/json")
                        (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                                (json/write-str body))))
                    (.GET builder))
          response (.send http-client (.build builder)
                          (HttpResponse$BodyHandlers/ofString))]
      (when (<= 200 (.statusCode response) 299)
        (json/read-str (.body response) :key-fn keyword)))))

(defn poll-relay! []
  (when-let [response (relay-request-json! :get "/v1/events/poll?limit=50" nil)]
    (let [events (:events response)
          keys (mapv :key events)]
      (when (seq events)
        (sync-all!)
        (relay-request-json! :post "/v1/events/ack" {:keys keys}))
      {:events (count events)})))

(defn start!
  ([] (start! {}))
  ([configuration]
  (reset! runtime-config (:mail-sync configuration))
  (when-not @scheduler
    (let [executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "cloud-itonami-mail-sync")
                          (.setDaemon true)))))]
      (.scheduleWithFixedDelay
       ^ScheduledExecutorService executor
       ^Runnable #(sync-all!) 10 60 TimeUnit/SECONDS)
      (.scheduleWithFixedDelay
       ^ScheduledExecutorService executor
       ^Runnable #(try (poll-relay!) (catch Exception _ nil))
       5 5 TimeUnit/SECONDS)
      (reset! scheduler executor)))
  true))

(defn stop! []
  (when-let [^ScheduledExecutorService executor @scheduler]
    (.shutdownNow executor)
    (reset! scheduler nil)))
