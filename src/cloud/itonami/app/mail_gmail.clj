(ns cloud.itonami.app.mail-gmail
  "Reading and writing one Gmail account, through `kotoba-lang/com-gmail`.

  This app used to hold its own copy of the Gmail API: URL strings, a
  base64url body decoder, a header lookup, a history cursor, a watch
  registration. All of it worked, none of it was tested — every one of those
  functions could only be exercised against a live mailbox, so none of them
  ever was — and `com-gmail` already had the same surface behind an
  injectable transport, which is exactly what makes it testable here.

  What stays in this namespace is the part that is genuinely this app's:
  turning a Gmail message into the shape every account kind reports, so the
  sync above it never learns which protocol a mailbox was reached over."
  (:require [clojure.string :as str]
            [gmail.history :as history]
            [gmail.labels :as labels]
            [gmail.mime :as mime]
            [cloud.itonami.app.mail-authentication :as authentication]
            [gmail.threads :as threads]
            [gmail.client :as gmail]
            [cloud.itonami.app.mail-account :as account])
  (:import [java.time Instant]))

(def ^:private page-size 100)

(defn- opts
  "The per-request options `com-gmail` takes.

  `:retry` is on: a background sync that gives up on the first 429 stops
  syncing for a minute and then asks again with the same odds, whereas the
  library's full-jitter backoff is exactly the behaviour a rate limit wants."
  [token]
  {:token token :retry true})

(defn- header [payload name] (mime/header payload name))

(defn- address-parts [value]
  (let [value (str/trim (str value))
        email (or (second (re-find #"<([^>]+)>" value))
                  (when (str/includes? value "@") value))]
    {:display (or (not-empty (str/trim (str/replace value #"<[^>]*>" "")))
                  email value)
     :email (str/lower-case (str/trim (or email "")))}))

(defn- message->normalized
  "One Gmail message in the shape every account kind reports."
  [label-names message]
  (let [payload (:payload message)
        from (header payload "from")
        sender (address-parts from)
        text (or (not-empty (str (mime/plain-text-body payload)))
                 ;; No text/plain part. The HTML one is what the message
                 ;; actually says, and showing nothing because it was not
                 ;; offered in two formats would be losing the message over a
                 ;; sender's choice of mail client.
                 (not-empty (str (mime/html-body payload)))
                 (str (:snippet message))
                 "")
        label-ids (:labelIds message)]
    {:provider-message-id (:id message)
     :thread-id (or (:threadId message) (:id message))
     :message-id (header payload "message-id")
     :subject (or (not-empty (str/trim (str (header payload "subject"))))
                  "(件名なし)")
     :from (or (not-empty (:display sender)) "送信者不明")
     :from-email (or (not-empty (:email sender)) "unknown@local.invalid")
     :to (or (header payload "to") "")
     :received-at (or (some-> (:internalDate message)
                              str parse-long Instant/ofEpochMilli str)
                      (header payload "date"))
     :body text
     :snippet (or (not-empty (str (:snippet message)))
                  (subs text 0 (min 220 (count text))))
     ;; Display names, not Gmail's opaque label ids: `Label_17` means nothing
     ;; to a reader, and the ids differ between accounts, so two mailboxes
     ;; with a label of the same name would otherwise file under two labels.
     ;; The receiving server's own SPF/DKIM/DMARC verdict, kept because
     ;; nothing else can answer "is this from who it says". Dropping these is
     ;; what made impersonation detection structurally impossible rather than
     ;; merely unimplemented — see mail-authentication, and the :gaps entry it
     ;; closes in yabai's abuse ledger. Three headers, not all of them: keeping
     ;; the whole envelope would put somebody's entire mail metadata in the
     ;; store to answer one question.
     :headers (into {}
                    (keep (fn [name]
                            (when-let [value (header payload name)]
                              [name value])))
                    (keys authentication/retained-headers))
     :labels (mapv #(get label-names % %) label-ids)
     :read? (not (some #{"UNREAD"} label-ids))
     :size-bytes (or (:sizeEstimate message) 0)}))

(defn- label-names [token]
  (into {} (map (juxt :id :name)) (labels/list-labels (opts token))))

(defn- thread-messages
  "Every message in a thread, normalized.

  Threads rather than messages because Gmail's own unit is the thread: a
  conversation arrives as one object with its messages inside, so this is one
  request where walking messages would be one per message."
  [token label-names thread-id]
  (let [thread (threads/get-thread thread-id (opts token))]
    (mapv #(message->normalized label-names %) (:messages thread))))

(def ^:private default-thread-limit
  "How many inbox threads a full sync reads when nobody says.

  Not a page: `full-sync!` follows `nextPageToken` until it has this many, so
  this is a budget rather than a boundary. It was one page, and one page is a
  boundary that looks like a budget — an inbox of four thousand threads synced
  the newest hundred and reported success, and nothing anywhere said the other
  thirty-nine hundred existed."
  1000)

(defn- list-inbox-threads
  "Inbox threads, newest first, following pagination up to `limit`.

  Gmail caps `maxResults` at 500 per call and answers with a `nextPageToken`
  when there is more. Reading one page and stopping is what made a full sync
  quietly partial."
  [token limit]
  (loop [collected [] page-token nil]
    (let [remaining (- limit (count collected))
          listing (threads/list-threads
                   (cond-> (assoc (opts token)
                                  :q "in:inbox"
                                  :max-results (min 500 remaining))
                     page-token (assoc :page-token page-token)))
          collected (into collected (:threads listing))
          next-token (:nextPageToken listing)]
      (if (and next-token (< (count collected) limit) (seq (:threads listing)))
        (recur collected next-token)
        collected))))

(defn- full-sync!
  [token {:keys [limit] :or {limit default-thread-limit}}]
  (let [names (label-names token)
        threads (list-inbox-threads token limit)
        listing {:threads threads}
        messages (vec (mapcat #(thread-messages token names (:id %))
                              threads))]
    {:messages messages
     ;; The cursor comes from the newest thread this sync actually saw.
     ;; Gmail's profile historyId is the mailbox's *current* position, which
     ;; is ahead of what was just read whenever mail arrives mid-sync — and
     ;; storing that would silently skip those messages forever.
     ;;
     ;; nil when the listing was empty, which leaves the next sync a full one.
     ;; That is the right answer for an empty mailbox: there is no position to
     ;; resume from yet.
     :cursor {:history-id (->> (:threads listing)
                               (keep #(some-> (:historyId %) str parse-long))
                               (reduce max 0)
                               (#(when (pos? %) (str %))))}}))

(defn- incremental-sync!
  "What changed since `history-id`.

  Gmail answers 404 once a cursor is older than the history it retains, and
  the only correct response to that is a full resync — retrying the same
  expired cursor gets the same 404 forever."
  [token history-id options]
  (try
    (let [names (label-names token)
          page (history/list-history history-id (opts token))
          records (:history page)
          changed (->> records
                       (mapcat #(concat (:messagesAdded %) (:labelsAdded %)
                                        (:labelsRemoved %)))
                       (keep #(get-in % [:message :threadId]))
                       distinct)
          deleted (->> records
                       (mapcat :messagesDeleted)
                       (keep #(get-in % [:message :id]))
                       distinct)]
      {:messages (vec (mapcat #(thread-messages token names %) changed))
       :deleted (vec deleted)
       :cursor {:history-id (str (or (:historyId page) history-id))}})
    (catch clojure.lang.ExceptionInfo error
      (if (= 404 (:status (ex-data error)))
        (full-sync! token options)
        (throw error)))))

(defn sync!
  "Read one Gmail account, incrementally when there is a cursor to do it from."
  ([account] (sync! account {}))
  ([account options]
   (let [token (account/access-token! account)]
     (when (str/blank? (str token))
       (throw (ex-info "Google の認可が切れています。接続し直してください。"
                       {:type :mail/credential-rejected :id (:id account)})))
     (if-let [history-id (:history-id (account/cursor (:id account)))]
       (incremental-sync! token history-id options)
       (full-sync! token options)))))

(defn set-read!
  "Add or remove Gmail's UNREAD label on a whole thread."
  [account thread-id read?]
  (let [token (account/access-token! account)]
    (threads/modify-thread!
     thread-id
     (if read?
       {:remove-label-ids ["UNREAD"]}
       {:add-label-ids ["UNREAD"]})
     (opts token))
    true))

(defn set-label!
  "Put a label on a thread upstream, or take it off, by display name.

  Created on demand when it does not exist, because a label somebody types
  here should end up on the message in Gmail rather than only in this app's
  local marks — that difference is invisible until they open Gmail and find
  nothing filed."
  [account thread-id label on?]
  (let [token (account/access-token! account)
        label-id (labels/find-or-create-label! (str label) (opts token))]
    (threads/modify-thread!
     thread-id
     (if on? {:add-label-ids [label-id]} {:remove-label-ids [label-id]})
     (opts token))
    true))

(defn set-trashed!
  "Gmail's own trash, which is reversible and is not deletion."
  [account thread-id trashed?]
  (let [token (account/access-token! account)]
    (if trashed?
      (threads/trash-thread! thread-id (opts token))
      (threads/untrash-thread! thread-id (opts token)))
    true))

(defn send!
  "Send a message as this account, through Gmail's own API.

  `users.messages.send` rather than SMTP: the account is reached by an OAuth
  grant, and making somebody also produce an app password to send from a
  mailbox this app is already authenticated to would be asking for a second
  credential to do a thing the first one covers."
  [account {:keys [raw thread-id]}]
  (let [token (account/access-token! account)]
    (gmail/request! "/messages/send"
                    (assoc (opts token)
                           :method :post
                           :body (cond-> {:raw raw}
                                   thread-id (assoc :threadId thread-id))))))
