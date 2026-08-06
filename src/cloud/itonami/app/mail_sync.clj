(ns cloud.itonami.app.mail-sync
  "Pulling every connected mailbox into one local store.

  ## What this used to be, and why it did not show up

  This namespace synced Gmail and Microsoft Graph into
  `[:mail-sync :messages]`, and it worked. Nothing displayed it. The inbox
  this app serves is built by `cloud.itonami.app.mailbox` out of
  `workspace/inbox-mailbox`, which reads `.eml` files off disk — so mail
  arrived, was parsed, was labelled, was written to the store, and was then
  visible to no one. Connecting Google appeared to do nothing, because from
  the outside it did do nothing.

  So the fix is not more sync. It is one plane: everything written here goes
  to `[:mail :messages]`, which is where `mailbox` now reads from as well as
  from the archive, and a message that syncs is a message that shows up.

  ## Accounts, not providers

  The other half is the unit. `sync-all!` used to walk `[:google :microsoft]`
  — two providers, one mailbox each, and no way to say *'my other Gmail'* or
  *'my mailbox at a host neither of these companies runs'*. It now walks
  `mail-account/accounts`, which is however many mailboxes of whatever kinds
  somebody has connected, and each one carries its own cursor, its own error
  and its own credential.

  A failure is per account for the same reason: one expired grant used to
  mean `sync-all!` recorded an error against a *provider* and moved on, and
  with two Gmail accounts connected that reads as 'Gmail is broken' when one
  of the two mailboxes is perfectly fine."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mail-account :as account]
            [cloud.itonami.app.mail-gmail :as gmail]
            [cloud.itonami.app.mail-imap :as imap]
            [cloud.itonami.app.mail-pop3 :as pop3]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent Executors ScheduledExecutorService
            ThreadFactory TimeUnit]))

(def schema "cloud.itonami.app.mail-sync.v1")

(defonce ^:private http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      .build))
(defonce ^:private scheduler (atom nil))
(defonce ^:private syncing? (atom false))
(defonce ^:private runtime-config (atom {}))

(defn- keychain-secret [service account]
  (identity/keychain-find service account))

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

;; ---------------------------------------------------------------------------
;; Local labels

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

;; ---------------------------------------------------------------------------
;; The one message plane

(defn- archive-root []
  (io/file (or (System/getenv "CLOUD_ITONAMI_MAIL_ARCHIVE")
               (str (io/file (config/data-dir) "mail-archive")))))

(defn- archive-message!
  "A copy on disk, under the account it came from.

  Per account rather than per provider: two Gmail mailboxes used to write
  into one `google/` directory keyed by message id, and a Gmail message id is
  unique within a mailbox and not across mailboxes."
  [message]
  (let [safe-account (str/replace (str (:account-id message))
                                  #"[^A-Za-z0-9._-]" "_")
        safe-id (str/replace (str (:provider-message-id message))
                             #"[^A-Za-z0-9._-]" "_")
        file (io/file (archive-root) safe-account (str safe-id ".json"))]
    (.mkdirs (.getParentFile file))
    (spit file (json/write-str message))
    (.getPath file)))

(defn message-id
  "The local id for one synced message.

  Qualified by account, not by provider. `google:<id>` collides the moment
  somebody connects a second Google account, and the collision is silent:
  one mailbox's message overwrites the other's, and which one survives
  depends on the order the accounts happened to sync in."
  [account-id provider-message-id]
  (str account-id "|" provider-message-id))

(defn- upsert-messages!
  [account messages cursor]
  (let [now (store/now)
        account-id (:id account)
        prepared
        (mapv (fn [message]
                (let [message (assoc message
                                     :account-id account-id
                                     :kind (:kind account)
                                     :account-address (:address account)
                                     :id (message-id
                                          account-id
                                          (:provider-message-id message))
                                     :synced-at now)]
                  (-> message
                      (assoc :labels (classify message))
                      (as-> m (assoc m :archive-path (archive-message! m))))))
              messages)]
    (store/transact!
     (fn [state]
       (let [with-messages
             (reduce (fn [current message]
                       (assoc-in current [:mail :messages (:id message)]
                                 message))
                     state prepared)]
         (-> with-messages
             (update-in [:mail :accounts account-id :sync]
                        merge
                        {:status :ready
                         :cursor cursor
                         :last-synced-at now
                         :last-attempt-at now
                         :last-error nil
                         :message-count
                         (count (filter #(= account-id (:account-id %))
                                        (vals (get-in with-messages
                                                      [:mail :messages]))))})
             (update :events
                     #(vec (take-last 100
                                      (conj (or % [])
                                            {:type :mail/synced :at now
                                             :account-id account-id
                                             :changed (count prepared)}))))))))
    {:account-id account-id :changed (count prepared) :cursor cursor}))

(defn- delete-messages! [account-id provider-ids]
  (when (seq provider-ids)
    (store/transact!
     update-in [:mail :messages]
     (fn [messages]
       (reduce dissoc (or messages {})
               (map #(message-id account-id %) provider-ids))))))

(defn messages
  "Every synced message, newest first, optionally for one account."
  ([] (messages nil))
  ([account-id]
   (cond->> (vals (get-in (store/snapshot) [:mail :messages]))
     account-id (filter #(= account-id (:account-id %)))
     true (sort-by :received-at #(compare %2 %1))
     true vec)))

;; ---------------------------------------------------------------------------
;; Microsoft Graph
;;
;; Still a direct HTTP reader rather than a client library, because there is
;; no `com-microsoft-graph` in this workspace to route it through, and writing
;; one to hold a single delta query would be inventing a library rather than
;; using one. Gmail's half of this namespace *did* have a tested library
;; sitting unused, which is why that half is now three lines and this is not.

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
     :size-bytes (count (.getBytes ^String body StandardCharsets/UTF_8))}))

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

(defn- sync-microsoft! [account]
  (let [token (account/access-token! account)
        _ (when (str/blank? (str token))
            (throw (ex-info "Microsoft の認可が切れています。接続し直してください。"
                            {:type :mail/credential-rejected
                             :id (:id account)})))
        folders (graph-folders! token)
        previous (:folder-deltas (account/cursor (:id account)))
        results (mapv #(graph-folder-sync! token % (get previous (:id %)))
                      folders)
        deltas (into {} (map (fn [folder result]
                               [(:id folder) (:delta-link result)])
                             folders results))]
    {:messages (vec (mapcat :messages results))
     :deleted (vec (mapcat :deleted results))
     :cursor {:folder-deltas deltas}}))

;; ---------------------------------------------------------------------------
;; One account, then all of them

(defonce ^:private sync-started-at (atom nil))

(def ^:private sync-lease-ms
  "How long a sync may hold the lock before another may take it.

  Not a timeout on the work — nothing is cancelled — but a bound on how long a
  wedged run can keep every later one out. Measured 2026-08-06: a full sync of
  1000 threads held the flag past 40 minutes, and because the scheduler asks
  every 300 seconds and gets `already-running` immediately, NOTHING synced in
  that window. The mailbox looked healthy and was frozen.

  Twenty minutes is longer than any full sync observed and shorter than a person
  would take to notice."
  (* 20 60 1000))

(defn- claim-sync!
  "Take the sync lock, or take it back from a run that has held it too long."
  []
  (if (compare-and-set! syncing? false true)
    (do (reset! sync-started-at (System/currentTimeMillis)) true)
    (let [started @sync-started-at]
      (boolean
       (when (and started
                  (> (- (System/currentTimeMillis) started) sync-lease-ms))
         ;; The previous holder is not stopped — it may still be making progress,
         ;; and killing it mid-flight would lose what it fetched. What is taken
         ;; back is only the right to start another.
         (reset! sync-started-at (System/currentTimeMillis))
         true)))))

(defn- release-sync! []
  (reset! sync-started-at nil)
  (reset! syncing? false))

(defn sync-account!
  "Read one mailbox and fold what it returned into the message plane.

  The dispatch on `:kind` is the only place in this app that learns there is
  more than one way to reach a mailbox."
  [account]
  (try
    (let [{:keys [messages deleted cursor]}
          (case (:kind account)
            :gmail (gmail/sync! account)
            :microsoft (sync-microsoft! account)
            :imap (imap/sync! account)
            :pop3 (pop3/sync! account
                              ;; POP3 has no 'what is new' — the only way to
                              ;; avoid refetching the whole maildrop every
                              ;; minute is to tell it what this app already
                              ;; holds.
                              {:known-uids (:uids (account/cursor (:id account)))}))]
      (delete-messages! (:id account) deleted)
      (upsert-messages! account messages cursor))
    (catch Exception error
      (account/record-error! (:id account) error)
      {:account-id (:id account) :error (.getMessage error)})))

(defn sync-all!
  "Every connected mailbox, one after another.

  Serial on purpose: these are somebody's mail accounts on a laptop, not a
  fleet, and three mailboxes opening sockets and refreshing grants at once
  buys a second of wall clock in exchange for a failure that is harder to
  read."
  ([] (sync-all! nil))
  ([did]
   (if-not (claim-sync!)
     {:status :already-running :since @sync-started-at}
     (try
       (let [accounts (mapv sync-account! (account/accounts did))]
         {:schema schema :status :completed
          :accounts accounts
          ;; Filed as it arrives. Without this, rules ran only when somebody
          ;; asked, so mail synced every minute and was filed whenever a person
          ;; remembered — which is a filing system that works in a demo and
          ;; silently stops working in use.
          ;;
          ;; Resolved at call time rather than required: `mail-projects` pulls in
          ;; `project-repository`, and a hard dependency would put DataLad and
          ;; git-annex in the load path of every sync, including deployments that
          ;; file nothing.
          :filed (try
                   (when-let [apply-all! (requiring-resolve
                                          'cloud.itonami.app.mail-projects/apply-all!)]
                     (apply-all!))
                   (catch Exception error
                     ;; Filing is downstream of the sync. The mail is already in
                     ;; the store and losing that because a project repository
                     ;; was busy would be the wrong trade.
                     {:ok? false :error (.getMessage error)}))})
       (finally (release-sync!))))))

;; ---------------------------------------------------------------------------
;; Push relay

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

;; ---------------------------------------------------------------------------

(defn status
  "Which mailboxes this deployment has, and what each last did.

  Reported without a provider round trip, so it stays answerable when the
  network or the grant is down — which is exactly when somebody asks. Per
  account rather than per provider, because 'Google: error' does not say
  which of two Google mailboxes stopped working."
  ([] (status nil))
  ([did]
   {:schema schema
    :enabled? (boolean @scheduler)
    :messages (count (get-in (store/snapshot) [:mail :messages]))
    :accounts (mapv account/public-account (account/accounts did))}))

(defn start!
  ([] (start! {}))
  ([configuration]
   (reset! runtime-config (:mail-sync configuration))
   ;; Delegated credentials name keychain items another tool owns, and
   ;; `mail-account` is what turns each one into a mailbox to sync.
   (account/configure! (:mail-sync configuration))
   ;; Off unless asked for. A workspace app that begins pulling somebody's
   ;; mail because it was installed has answered a question nobody put to it.
   (when (and (:enabled? @runtime-config) (not @scheduler))
     (let [executor (Executors/newSingleThreadScheduledExecutor
                     (reify ThreadFactory
                       (newThread [_ runnable]
                         (doto (Thread. runnable "cloud-itonami-mail-sync")
                           (.setDaemon true)))))
           interval (long (or (:interval-seconds @runtime-config) 60))]
       (.scheduleWithFixedDelay
        ^ScheduledExecutorService executor
        ^Runnable #(try (sync-all!) (catch Exception _ nil))
        10 interval TimeUnit/SECONDS)
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
