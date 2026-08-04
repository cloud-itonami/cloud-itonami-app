(ns cloud.itonami.app.mail-pop3
  "Reading a mailbox over POP3, for the accounts that offer nothing else.

  The last of the three protocols. Plenty of ISP and legacy hosting
  mailboxes still speak only POP3, and until `kotoba-lang/org-ietf-pop3`
  existed there was nothing in this workspace that could open one.

  ## Why this is not just IMAP with different verbs

  POP3 has no folders, no flags, and no server-side state worth the name.
  Three consequences shape everything here:

  - **Read state is local, and only local.** There is no `\\Seen`. A
    message's read state is whatever this app's own mark layer says, and
    nothing is written back — because there is nowhere to write it.
  - **Message numbers are per session.** They renumber the moment anything
    is deleted, so the UIDL is the only identifier that survives and it is
    what `:provider-message-id` carries. Keying on the number would mean
    that after one deletion every message is attributed to the wrong one.
  - **Reading must not delete.** POP3's historical default removed the
    server's only copy as a side effect of RETR. Nothing here deletes, ever
    — not on sync, not on trash. A mail app that quietly emptied the
    server's mailbox as the price of showing it would have destroyed the
    account for every other client it is opened in."
  (:require [clojure.string :as str]
            [cloud.itonami.app.mail-account :as account]
            [mime.address :as address]
            [mime.parse :as mime]
            [pop3.client :as pop3])
  (:import [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(def ^:private default-limit
  "How many messages one sync reads, newest first.

  POP3 has no search and no date filter — the only way to find the recent
  end of a maildrop is to list all of it and take the tail — so this bounds
  the RETRs, which is where the cost is."
  200)

(defn- charset-decoder [charset ^String binary]
  (try
    (String. (.getBytes binary "ISO-8859-1") ^String charset)
    (catch Exception _ binary)))

(defn- parse-received-at [value]
  (when-not (str/blank? (str value))
    (try
      (-> (ZonedDateTime/parse (str/trim (str value))
                               DateTimeFormatter/RFC_1123_DATE_TIME)
          .toInstant str)
      (catch DateTimeParseException _ nil)
      (catch Exception _ nil))))

(defn- message->normalized
  [uid raw]
  (let [opts {:decoder charset-decoder}
        parts (mime/message-parts (mime/parse raw opts) opts)
        headers (:headers parts)
        from (address/parse-one (str (get headers "from")) charset-decoder)
        text (or (not-empty (str (:text parts)))
                 (not-empty (str (:html parts)))
                 "")]
    {;; The UIDL, not the message number. Numbers renumber on deletion and
     ;; are meaningless in the next session.
     :provider-message-id uid
     :thread-id (or (some-> (get headers "references")
                            str/trim (str/split #"\s+") first not-empty)
                    (some-> (get headers "in-reply-to") str/trim not-empty)
                    (some-> (:message-id parts) str/trim not-empty)
                    uid)
     :message-id (:message-id parts)
     :subject (or (not-empty (str/trim (str (:subject parts)))) "(件名なし)")
     :from (or (not-empty (str (:name from)))
               (not-empty (str (:address from)))
               "送信者不明")
     :from-email (or (not-empty (str/lower-case (str (:address from))))
                     "unknown@local.invalid")
     :to (str/join ", " (:to parts))
     :received-at (or (parse-received-at (:date parts)) (str (Instant/now)))
     :body text
     :snippet (subs text 0 (min 220 (count text)))
     :labels ["INBOX"]
     ;; POP3 has no server-side read state, so every message arrives unread
     ;; and this app's mark layer owns the difference. Claiming a read state
     ;; the protocol cannot report would be inventing one.
     :read? false
     :attachments (mapv #(select-keys % [:filename :content-type :size])
                        (:attachments parts))
     :size-bytes (count (.getBytes (str raw) "UTF-8"))}))

(defn- with-session
  [account {:keys [transport password]} f]
  (let [{:keys [host port username]} (:pop3 account)
        password (or password (account/password (:id account)))
        token (when (str/blank? (str password))
                (try (account/access-token! account) (catch Exception _ nil)))]
    (when (and (str/blank? (str password)) (str/blank? (str token)))
      (throw (ex-info "このアカウントのパスワードが Keychain にありません。再登録してください。"
                      {:type :mail/missing-credential :id (:id account)})))
    (let [session (pop3/connect! host (cond-> {:port port}
                                       transport (assoc :transport transport)))]
      (try
        (-> session
            (pop3/capabilities!)
            (pop3/authenticate! {:user username
                                 :password password
                                 :access-token token})
            (f))
        (finally
          ;; QUIT, not just close: it is what releases the maildrop lock, and
          ;; a dropped socket leaves the mailbox locked until the server
          ;; times the session out — refusing every other client meanwhile.
          (try (pop3/quit! session) (catch Exception _ nil)))))))

(defn sync!
  "Read the recent end of a POP3 maildrop.

  `:known-uids` are the UIDLs this app already holds; those messages are
  not re-downloaded, because POP3 has no way to ask for 'what is new' and
  the alternative is refetching the whole maildrop every minute."
  ([account] (sync! account {}))
  ([account {:keys [limit known-uids] :or {limit default-limit} :as options}]
   (with-session
     account options
     (fn [session]
       (let [known (set known-uids)
             listing (pop3/list-messages! session)
             ;; Newest last in a maildrop, so the tail is the recent end.
             wanted (->> (take-last limit listing)
                         (remove #(contains? known (:uid %))))]
         {:messages (mapv (fn [{:keys [number uid]}]
                            (message->normalized
                             (or uid (str "n" number))
                             (pop3/retrieve! session number)))
                          wanted)
          :cursor {:count (count listing)
                   :fetched (count wanted)
                   ;; Every UIDL seen this session, so the next one can skip
                   ;; what it already has. This is POP3's entire notion of a
                   ;; cursor: there is no sequence number to resume from.
                   :uids (vec (keep :uid listing))}})))))
