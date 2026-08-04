(ns cloud.itonami.app.mail-imap
  "Reading a mailbox over IMAP, for the accounts OAuth cannot reach.

  This app could read Gmail and it could read Microsoft 365, which between
  them is a large fraction of mail and not the same thing as *mail*. A
  mailbox at a host that has never issued an OAuth client — a personal
  domain, a university, a provider that hands out app passwords and nothing
  else — could not be connected at all.

  ## Two libraries, two subjects

  `kotoba-lang/org-ietf-imap` speaks the protocol. `kotoba-lang/org-ietf-mime`
  parses the messages it returns. Neither job is done here, and the second
  one nearly was: the first version of this namespace read a `:text` the
  IMAP library had produced with its own hand-written transfer-encoding
  decoder, which returned a `multipart/*` body raw — boundaries, per-part
  headers, base64 of every attachment — and called that the message. That
  is what the inbox would have shown for any mail with an attachment or an
  HTML alternative, which is most of it.

  `mime.parse` gets it right, including the part that is easy to get
  backwards: `multipart/alternative` orders its parts worst-to-best
  (RFC 2046 §5.1.4), so the *last* `text/plain` is the message and the
  first is a fallback for clients that can do no better."
  (:require [clojure.string :as str]
            [cloud.itonami.app.mail-account :as account]
            [imap.client :as imap]
            [mime.address :as address]
            [mime.parse :as mime])
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

(defn- charset-decoder
  "What `org-ietf-mime` needs for the charsets it will not guess at.

  That library decodes UTF-8, US-ASCII and ISO-8859-1 itself and asks for a
  decoder for everything else, rather than inventing ISO-2022-JP inside a
  pure library. On the JVM the correct one is already present — and
  Japanese mail is exactly the case that needs it, since a
  `charset=ISO-2022-JP` subject read as latin-1 is mojibake, and mojibake
  is what this app would then show."
  [charset ^String binary]
  (try
    (String. (.getBytes binary "ISO-8859-1") ^String charset)
    (catch Exception _ binary)))

(defn- parse-received-at
  "An RFC 5322 `Date:` header as an instant, or nil.

  nil rather than now: a message with an unparseable date has an unknown
  date, and substituting the sync time sorts it to the top of the mailbox
  as though it had just arrived."
  [value]
  (when-not (str/blank? (str value))
    (try
      (-> (ZonedDateTime/parse (str/trim (str value))
                               DateTimeFormatter/RFC_1123_DATE_TIME)
          .toInstant str)
      (catch DateTimeParseException _ nil)
      (catch Exception _ nil))))

(defn- internal-date
  "IMAP's own INTERNALDATE (`17-Jul-2026 02:44:25 +0900`) as an instant.

  The fallback when a `Date:` header is missing or unparseable: it is when
  the *server* received the message, which is not when the sender wrote it
  but is a real time and puts the message in the right order."
  [value]
  (when-not (str/blank? (str value))
    (try
      (-> (ZonedDateTime/parse (str/trim (str value))
                               (DateTimeFormatter/ofPattern "d-MMM-yyyy HH:mm:ss Z"))
          .toInstant str)
      (catch Exception _ nil))))

(defn- message->normalized
  "One fetched IMAP message in the shape every account kind reports.

  The envelope comes from `mime/message-parts`, which returns exactly the
  map `mail.inbound/from-parts` takes — the same shape the Gmail and Graph
  readers produce here, so the sync above never branches on which protocol
  a mailbox was reached over."
  [_account fetched]
  (let [opts {:decoder charset-decoder}
        parts (mime/message-parts (mime/parse (:raw fetched) opts) opts)
        headers (:headers parts)
        text (or (not-empty (str (:text parts)))
                 ;; No text/plain part. The HTML one is what the message
                 ;; actually says, and showing nothing because it was not
                 ;; offered in two formats loses the message over a sender's
                 ;; choice of mail client.
                 (not-empty (str (:html parts)))
                 "")
        ;; `message-parts` hands back address *strings* (that is what
        ;; `mail.message` takes). A list row wants the display name too, and
        ;; that only survives in the raw header — so it is parsed once here
        ;; rather than by a second regex of this app's own.
        from (address/parse-one (str (get headers "from")) charset-decoder)
        flags (:flags fetched)]
    {:provider-message-id (str (:uid fetched))
     ;; A mail's thread is its `References` root where it has one, and
     ;; itself where it does not. IMAP has no thread id of its own — the
     ;; THREAD extension is not something every server has — so this is the
     ;; standing RFC 5322 convention rather than a guess.
     :thread-id (or (some-> (get headers "references")
                            str/trim (str/split #"\s+") first not-empty)
                    (some-> (get headers "in-reply-to") str/trim not-empty)
                    (some-> (:message-id parts) str/trim not-empty)
                    (str (:uid fetched)))
     :message-id (:message-id parts)
     :subject (or (not-empty (str/trim (str (:subject parts)))) "(件名なし)")
     :from (or (not-empty (str (:name from)))
               (not-empty (str (:address from)))
               "送信者不明")
     :from-email (or (not-empty (str/lower-case (str (:address from))))
                     "unknown@local.invalid")
     :to (str/join ", " (:to parts))
     :received-at (or (parse-received-at (:date parts))
                      (internal-date (:internal-date fetched))
                      (str (Instant/now)))
     :body text
     :snippet (subs text 0 (min 220 (count text)))
     :labels ["INBOX"]
     ;; The server's own \Seen, read on the same FETCH rather than assumed.
     ;; Assuming was how every synced message arrived unread and overwrote
     ;; what somebody had actually done in another client.
     :read? (boolean (and flags (contains? flags :seen)))
     :attachments (mapv #(select-keys % [:filename :content-type :size])
                        (:attachments parts))
     :size-bytes (or (:size fetched)
                     (count (.getBytes (str (:raw fetched)) "UTF-8")))
     :uid (:uid fetched)}))

(defn- with-session
  "Open a session, hand it to `f`, and close it whether or not `f` threw.

  An IMAP session is a socket. Leaking one per failed sync is how a process
  that syncs every minute runs out of file descriptors overnight.

  `:transport` and `:password` are injection points for tests, and are the
  reason there is a test for this namespace at all: without them the only
  way to exercise it is to point it at somebody's real mailbox.

  Authentication prefers XOAUTH2 when the account carries an OAuth token —
  which is how a mailbox already connected by OAuth is read over IMAP
  without also asking its owner for an app password — and uses LOGIN with
  the stored password otherwise."
  [account {:keys [transport password read-only?] :or {read-only? true}} f]
  (let [{:keys [host port username]} (:imap account)
        password (or password (account/password (:id account)))
        token (when (str/blank? (str password))
                (try (account/access-token! account) (catch Exception _ nil)))]
    (when (and (str/blank? (str password)) (str/blank? (str token)))
      (throw (ex-info "このアカウントのパスワードが Keychain にありません。再登録してください。"
                      {:type :mail/missing-credential :id (:id account)})))
    (let [session (imap/connect! host (cond-> {:port port}
                                        transport (assoc :transport transport)))]
      (try
        (let [session (imap/capabilities! session)
              session (if (str/blank? (str password))
                        (imap/authenticate-xoauth2! session username token)
                        (imap/login! session username password))
              ;; EXAMINE, not SELECT: a sync that only reads must not be
              ;; able to set \Seen by accident, and on a shared mailbox that
              ;; accident is visible to everybody else looking at it.
              session (if read-only?
                        (imap/examine! session "INBOX")
                        (imap/select! session "INBOX"))]
          (f session))
        (finally
          (try (imap/logout! session) (catch Exception _ nil)))))))

(defn sync!
  "Read the recent end of an IMAP mailbox.

  Returns `{:messages [...] :cursor {...}}`. The cursor carries
  **`:uidvalidity`**: RFC 3501 §2.3.1.1 makes a UID meaningful only
  together with the UIDVALIDITY it was issued under, so when a server
  reissues that value every stored UID now names a different message —
  silently, and looking like corruption rather than like a protocol event."
  ([account] (sync! account {}))
  ([account {:keys [limit] :or {limit default-limit} :as options}]
   (with-session
     account options
     (fn [session]
       (let [messages (imap/list-recent! session {:limit limit})
             mailbox (:mailbox session)]
         {:messages (mapv #(message->normalized account %) messages)
          :cursor {:uidvalidity (:uidvalidity mailbox)
                   :uidnext (:uidnext mailbox)
                   :highest-uid (reduce max 0 (keep :uid messages))
                   :fetched (count messages)}})))))

(defn set-read!
  "Set or clear \\Seen upstream, so the next client to open this mailbox
  agrees with what was done here. Opens it writable, unlike `sync!`."
  ([account uid read?] (set-read! account uid read? {}))
  ([account uid read? options]
   (with-session
     account (assoc options :read-only? false)
     (fn [session]
       (imap/store-flags! session (parse-long (str uid))
                          (if read? :add :remove) [:seen])
       true))))

(defn set-flagged!
  "Star a message upstream, or unstar it.

  Reachable only since the IMAP library grew `store-flags!`: while `\\Seen`
  was the only flag it could write, a star raised here stayed local and
  every other client the account is opened in disagreed."
  ([account uid on?] (set-flagged! account uid on? {}))
  ([account uid on? options]
   (with-session
     account (assoc options :read-only? false)
     (fn [session]
       (imap/store-flags! session (parse-long (str uid))
                          (if on? :add :remove) [:flagged])
       true))))

(defn append-sent!
  "Put a copy of a sent message into the account's Sent folder.

  Without this, mail this app sends exists at the recipient and nowhere in
  the sender's own mailbox, so every other client the account is opened in
  shows an empty Sent folder for everything sent from here.

  The folder is found by its RFC 6154 `\\Sent` special-use flag rather than
  by guessing at a name — it is `Sent`, `Sent Items`, `送信済みメール` or
  `[Gmail]/Sent Mail` depending on the server and the language the account
  was created in, and a guess is wrong more often than right. A server that
  flags no folder gets a reported miss rather than a guess: a copy filed
  into a folder nobody uses is not better than no copy, it is a second
  place to look that nobody looks in."
  ([account raw-message] (append-sent! account raw-message {}))
  ([account raw-message options]
   (with-session
     account (assoc options :read-only? false)
     (fn [session]
       (if-let [sent (->> (imap/list-mailboxes! session)
                          (filter #(contains? (:attributes %) :sent))
                          first
                          :name)]
         (do (imap/append! session sent raw-message {:flags [:seen]})
             {:appended? true :mailbox sent})
         {:appended? false :reason :no-sent-folder})))))
