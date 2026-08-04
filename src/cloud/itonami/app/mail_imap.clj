(ns cloud.itonami.app.mail-imap
  "Reading a mailbox over IMAP, for the accounts OAuth cannot reach.

  This app could read Gmail and it could read Microsoft 365, which between
  them is a large fraction of mail and not the same thing as *mail*. A
  mailbox at a host that has never issued an OAuth client — a personal
  domain, a university, a provider that hands out app passwords and nothing
  else — could not be connected at all, and the answer to somebody asking for
  one was to tell them their mail was not the supported kind.

  Nothing here speaks IMAP. `kotoba-lang/org-ietf-imap` does, it is tested
  against a scripted transport rather than only against a live server, and
  re-deriving the same UID SEARCH / UID FETCH incantations inside an
  application is how a protocol ends up implemented twice and verified
  nowhere."
  (:require [clojure.string :as str]
            [cloud.itonami.app.mail-account :as account]
            [imap.client :as imap])
  (:import [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(def ^:private default-limit
  "How much of a mailbox one sync reads.

  A ceiling rather than a page, because IMAP has no cursor a client can
  resume from: `search-all!` returns every UID, and what bounds the work is
  how many of those get fetched. High enough that an ordinary inbox arrives
  whole, low enough that pointing this at a decade of mail does not read the
  decade."
  200)

(defn- parse-received-at
  "An RFC 2822 `Date:` header as an instant, or nil.

  nil rather than now: a message with an unparseable date has an unknown
  date, and substituting the sync time would sort it to the top of the
  mailbox as though it had just arrived."
  [value]
  (when-not (str/blank? (str value))
    (try
      (-> (ZonedDateTime/parse (str/trim (str value))
                               DateTimeFormatter/RFC_1123_DATE_TIME)
          .toInstant str)
      (catch DateTimeParseException _ nil)
      (catch Exception _ nil))))

(defn- address-parts
  "`\"Name <a@b>\"` -> `{:display \"Name\" :email \"a@b\"}`."
  [value]
  (let [value (str/trim (str value))
        email (or (second (re-find #"<([^>]+)>" value))
                  (when (str/includes? value "@") value))]
    {:display (or (not-empty (str/trim (str/replace value #"<[^>]*>" "")))
                  email value)
     :email (str/lower-case (str/trim (or email "")))}))

(defn- message->normalized
  "One fetched IMAP message in the shape every account kind reports.

  The same map `mail-gmail` and the Graph reader produce, so that the sync
  above them does not branch on which protocol a mailbox was reached over —
  that is the whole point of normalizing here rather than in the mailbox."
  [account message]
  (let [headers (:headers message)
        sender (address-parts (:from headers))
        text (or (not-empty (str (:text message)))
                 (not-empty (str (:body message)))
                 "")]
    {:provider-message-id (str (:uid message))
     ;; A mail's thread is its `References`/`In-Reply-To` root where it has
     ;; one, and itself where it does not. IMAP has no thread id of its own —
     ;; the JMAP/`THREAD` extension is not something every server has — so
     ;; this is the standing RFC 2822 convention rather than a guess.
     :thread-id (or (some-> (:references headers)
                            str/trim (str/split #"\s+") first not-empty)
                    (some-> (:in-reply-to headers) str/trim not-empty)
                    (some-> (:message-id headers) str/trim not-empty)
                    (str (:uid message)))
     :message-id (some-> (:message-id headers) str/trim not-empty)
     :subject (or (not-empty (str/trim (str (:subject headers)))) "(件名なし)")
     :from (or (not-empty (:display sender)) "送信者不明")
     :from-email (or (not-empty (:email sender)) "unknown@local.invalid")
     :to (or (not-empty (str (:to headers))) (:address account) "")
     :received-at (or (parse-received-at (:date headers)) (str (Instant/now)))
     :body text
     :snippet (subs text 0 (min 220 (count text)))
     :labels ["INBOX"]
     ;; IMAP reports \Seen per message in a FETCH of FLAGS, which
     ;; `list-recent!` does not request. Rather than claim a read state this
     ;; sync did not read, every message arrives unread and the local mark
     ;; layer owns the difference — the same rule the on-disk archive follows,
     ;; inverted because there the messages genuinely had been read.
     :read? false
     :size-bytes (count (.getBytes (str (:raw message)) "UTF-8"))
     :uid (:uid message)}))

(defn- with-session
  "Open a session, hand it to `f`, and close it whether or not `f` threw.

  An IMAP session is a socket. Leaking one per failed sync is how a process
  that syncs every minute runs out of file descriptors overnight.

  `:transport` and `:password` are injection points for tests, and are the
  reason there is a test for this namespace at all: without them the only way
  to exercise it is to point it at somebody's real mailbox, which is how the
  hand-rolled Gmail reader this replaces went unverified for its whole life."
  [account {:keys [transport password]} f]
  (let [{:keys [host port username]} (:imap account)
        password (or password (account/password (:id account)))]
    (when (str/blank? password)
      (throw (ex-info "このアカウントのパスワードが Keychain にありません。再登録してください。"
                      {:type :mail/missing-credential :id (:id account)})))
    (let [session (imap/connect! host (cond-> {:port port}
                                        transport (assoc :transport transport)))]
      (try
        (imap/login! session username password)
        (imap/select! session "INBOX")
        (f session)
        (finally
          (try (imap/logout! session) (catch Exception _ nil)))))))

(defn sync!
  "Read the recent end of an IMAP mailbox.

  Returns `{:messages [...] :cursor {...}}`. The cursor is the highest UID
  seen, which is not used to skip work on the next run — re-reading is how a
  message edited or re-flagged upstream gets corrected locally — but is what
  lets `status` say whether a sync is making progress or standing still."
  ([account] (sync! account {}))
  ([account {:keys [limit] :or {limit default-limit} :as options}]
   (with-session
     account options
     (fn [session]
       (let [messages (imap/list-recent! session {:limit limit})]
         {:messages (mapv #(message->normalized account %) messages)
          :cursor {:highest-uid (reduce max 0 (map :uid messages))
                   :fetched (count messages)}})))))

(defn set-read!
  "Set or clear \\Seen upstream, so the next client to open this mailbox
  agrees with what was done here."
  ([account uid read?] (set-read! account uid read? {}))
  ([account uid read? options]
   (with-session
     account options
     (fn [session]
       (if read?
         (imap/mark-seen! session (parse-long (str uid)))
         (imap/mark-unseen! session (parse-long (str uid))))
       true))))
