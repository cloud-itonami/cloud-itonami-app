(ns cloud.itonami.app.mail-account
  "Every mailbox this workspace has been pointed at, of whatever kind.

  Before this there was no such list. Mail was reached provider-first — *'the
  Google token'*, *'the Microsoft token'* — which quietly assumed a person has
  at most one account per provider, and that every account is one of the two
  providers this app had written HTTP for. Both assumptions are wrong in the
  ordinary case: people have a work Gmail and a personal one, and they have
  mailboxes at hosts that have never heard of OAuth.

  So the unit here is the **account**, not the provider. An account is one
  mailbox with one set of credentials and one sync cursor, and the rest of
  the mail code walks accounts without caring which of the three kinds it is
  holding:

  | kind         | reached over            | credential                      |
  |--------------|-------------------------|---------------------------------|
  | `:gmail`     | Gmail API v1 (com-gmail)| an OAuth grant, refreshed       |
  | `:microsoft` | Microsoft Graph         | an OAuth grant, refreshed       |
  | `:imap`      | IMAP4rev1 / SMTP        | a password, held in the Keychain|
  | `:pop3`      | POP3 / SMTP             | a password, held in the Keychain|

  ## Two halves, kept apart on purpose

  An OAuth account is **derived**: it exists because `identity` holds a live
  connection, and it disappears when that connection does. Nothing here can
  create one — that is what the consent screen is for — so `accounts` reads
  them out of `identity/connections-for` every time rather than keeping a copy
  that could disagree with it.

  An IMAP or POP3 account is **declared**: somebody typed a host and a password, and
  that record has to live somewhere, so it lives in the store under
  `[:mail :accounts]`. Its password does not: that goes to the Keychain under
  this namespace's own service name, and only ever comes back one item at a
  time by a name the caller already knows.

  A **delegated** account is the third case and the odd one: an OAuth grant
  this app did not obtain and does not own, held on this machine by some
  other tool, named item by item in configuration. It is how a local-first
  workspace reads a mailbox without marching its owner through a second
  consent screen for a mailbox they already authorised once. Named and only
  named — nothing here searches the keychain or guesses a slug, because an
  application that reaches for whichever Google token is lying around is an
  application that reads mail it was never pointed at.

  All three carry the same sync state, in the same place, so a caller showing
  a list of mailboxes does not have to ask three questions."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.mail-account.v1")

(def keychain-service
  "Deliberately not `identity`'s OAuth service. What is stored here is a mail
  password somebody typed, not a token this app was granted, and the two have
  different lifetimes and different blast radii."
  "cloud-itonami-app.mail")

(def kinds #{:gmail :microsoft :imap :pop3})

(def ^:private oauth-kind
  "Which provider backs which account kind. Only these two providers are
  mailboxes — a GitHub connection is a live OAuth grant and not a mailbox, and
  walking providers rather than mail kinds is how it would end up in a list of
  inboxes."
  {:google :gmail :microsoft :microsoft})

(defn- accounts-path [] [:mail :accounts])

;; Delegated credentials come from configuration, not from the store: they
;; name keychain items another tool owns, and a deployment describes them the
;; same way it describes anything else it was set up with.
(defonce ^:private delegated-config (atom {}))

(defn configure!
  "Take the `:mail-sync` configuration's delegated credentials, if any.

  Called by `mail-sync/start!`. Empty by default and empty when unconfigured:
  a workspace that was merely installed must not be reading anybody's mail."
  [configuration]
  (reset! delegated-config (get-in configuration [:providers] {}))
  true)

(defn- delegated-credential [provider]
  (get-in @delegated-config [provider :delegated-credential]))

;; ---------------------------------------------------------------------------
;; Identity

(defn account-id
  "A stable name for one mailbox.

  For an OAuth account it is the provider's own subject, not the address: an
  address can be reassigned to a new person and an alias can deliver to a
  mailbox whose primary address is something else, and either would silently
  re-point a cursor at a different mailbox. For IMAP there is no subject, so
  the host and username together — which is exactly what was typed in — are
  the account."
  [kind identifier]
  (str (name kind) ":" identifier))

(defn- connection-account
  "One live OAuth connection, as an account."
  [kind connection]
  (let [id (account-id kind (:provider-subject connection))]
    {:id id
     :kind kind
     :address (:email connection)
     :display-name (or (not-empty (str (:display-name connection)))
                       (:email connection))
     :connection-id (:id connection)
     :user-did (:user-did connection)
     :status :connected
     :derived? true}))

(defn- stored-accounts [state]
  (get-in state (accounts-path) {}))

(defn- declared-accounts
  "The IMAP accounts somebody registered.

  Filtered by kind rather than returned whole, because this same map is where
  the *derived* accounts' sync state is kept: an OAuth account's cursor
  outlives any one connection record and has to be written somewhere, and
  writing it here keeps one shape for `sync-state`. A stored entry with an
  OAuth kind is that state, not an account, and must not be listed as one —
  otherwise disconnecting Google would leave its mailbox in the list forever."
  [state]
  (->> (vals (stored-accounts state))
       (filter #(contains? #{:imap :pop3} (:kind %)))))

(defn- delegated-account
  "A mailbox reached with a credential another tool on this machine owns."
  [provider kind credential]
  {:id (account-id kind (str "delegated:" (:refresh-account credential)))
   :kind kind
   :address (:refresh-account credential)
   :display-name (str (:refresh-account credential) " (委任)")
   :delegated-credential credential
   :provider provider
   :status :connected
   :delegated? true
   :derived? true})

(defn accounts
  "Every mailbox: live OAuth grants, delegated credentials, declared IMAP
  accounts.

  Optionally narrowed to one person's `did:key`. Sorted by id so a list of
  mailboxes does not reshuffle itself between two reads of the same state.

  A delegated account is deliberately not narrowed by DID: it belongs to the
  deployment rather than to a person in it, which is the same reason
  `identity/connected-providers` stays deployment-wide."
  ([] (accounts nil))
  ([did]
   (let [state (store/snapshot)
         oauth (mapcat (fn [[provider kind]]
                         (map #(connection-account kind %)
                              (identity/connections-for provider did)))
                       oauth-kind)
         delegated (keep (fn [[provider kind]]
                           (when-let [credential (delegated-credential provider)]
                             (delegated-account provider kind credential)))
                         oauth-kind)
         declared (cond->> (declared-accounts state)
                    did (filter #(= did (:user-did %))))]
     (->> (concat oauth delegated declared)
          (map (fn [account]
                 (assoc account
                        :sync (get-in state
                                      (conj (accounts-path) (:id account) :sync)
                                      {:status :never-synced}))))
          (sort-by :id)
          vec))))

(defn account
  "One mailbox by id, or nil."
  ([id] (account id nil))
  ([id did] (first (filter #(= id (:id %)) (accounts did)))))

(defn account!
  "One mailbox by id, or a refusal naming it."
  ([id] (account! id nil))
  ([id did]
   (or (account id did)
       (throw (ex-info "そのメールアカウントは登録されていません。"
                       {:type :mail/unknown-account :id id})))))

;; ---------------------------------------------------------------------------
;; Credentials

(defn- keychain-account-name [id secret-kind]
  (str id ":" (name secret-kind)))

(defn- keychain-put! [account-name secret]
  (when-not (str/blank? secret)
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "add-generic-password" "-U"
                        "-s" keychain-service "-a" account-name "-w" secret])
                      (.redirectErrorStream true)
                      .start)
          completed? (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)]
      (when-not (and completed? (zero? (.exitValue process)))
        (.destroyForcibly process)
        (throw (ex-info "メールのパスワードを macOS Keychain に保存できませんでした。"
                        {:type :mail/keychain-error})))
      (str "keychain://" keychain-service "/" account-name))))

(defn- keychain-delete! [account-name]
  (try
    (-> (ProcessBuilder.
         ^java.util.List
         ["security" "delete-generic-password"
          "-s" keychain-service "-a" account-name])
        (.redirectErrorStream true)
        .start
        (.waitFor 5 java.util.concurrent.TimeUnit/SECONDS))
    (catch Exception _ nil)))

(defn password
  "The stored password for an IMAP account, or nil.

  One item, named in full by the caller — `identity/keychain-find` refuses to
  enumerate, and this does not work around that."
  [id]
  (identity/keychain-find keychain-service
                          (keychain-account-name id :password)))

(defn- provider-of [account]
  (or (:provider account)
      (some (fn [[provider kind]] (when (= kind (:kind account)) provider))
            oauth-kind)))

(defn access-token!
  "A currently-valid token for an OAuth account, refreshed if it needed to be.

  Resolved from the account's own connection rather than from its provider:
  `identity/provider-access-token!` answers *'the token for Google'*, which
  stops having an answer the moment somebody connects a second Google account,
  and is right to refuse rather than pick. This knows which mailbox it is
  holding.

  A delegated account refuses loudly rather than returning nil. Configured
  and refused is not the same as not configured, and only one of the two is
  worth telling somebody about: a delegated grant goes stale on its own —
  Google expires refresh tokens issued by a client still in testing after a
  week — and the symptom is a mailbox that quietly stops updating."
  [account]
  (let [provider (provider-of account)]
    (cond
      (:connection-id account)
      (when-let [connection (first (filter #(= (:connection-id account) (:id %))
                                           (identity/connections-for provider)))]
        (identity/connection-access-token! connection))

      (:delegated-credential account)
      (or (identity/delegated-access-token! provider
                                            (:delegated-credential account))
          (throw (ex-info (str (name provider)
                               " の委任認証情報が拒否されました。再認可が必要です。")
                          {:type :mail/credential-rejected
                           :id (:id account)
                           :provider provider}))))))

;; ---------------------------------------------------------------------------
;; Declaring an IMAP account

(def ^:private default-ports {:imap 993 :smtp 465 :pop3 995})

(defn- require-text! [value field]
  (let [value (str/trim (str value))]
    (when (str/blank? value)
      (throw (ex-info (str field "を入力してください。")
                      {:type :mail/invalid-account :field field})))
    value))

(defn- port! [value fallback]
  (let [port (if (str/blank? (str value)) fallback (parse-long (str value)))]
    (when-not (and port (< 0 port 65536))
      (throw (ex-info "ポート番号が不正です。"
                      {:type :mail/invalid-account :field "port"})))
    port))

(defn add-imap-account!
  "Register a mailbox reached over IMAP or POP3, with SMTP for sending.

  The password is written to the Keychain and the record that goes to the
  store holds only a reference to it — the same division `identity` keeps for
  OAuth tokens, and for the same reason: `state.edn` is a file that gets
  copied, read and backed up, and a password in it is a password in all of
  those places too.

  SMTP defaults to the reading host's username and the submission port,
  because for every host this is aimed at they are the same account; a host
  where they differ can say so.

  `:protocol` is `\"imap\"` (the default) or `\"pop3\"`. POP3 is the lesser
  of the two in every respect — no folders, no server-side flags, message
  numbers that renumber on deletion — and is offered because plenty of ISP
  and legacy hosting mailboxes still speak nothing else."
  [{:keys [address host port username password protocol
           smtp-host smtp-port smtp-username display-name]}
   {:keys [user-did]}]
  (let [kind (if (= "pop3" (str/lower-case (str/trim (str (or protocol "imap")))))
               :pop3 :imap)
        address (str/lower-case (require-text! address "メールアドレス"))
        host (require-text! host (if (= :pop3 kind) "POP3 サーバー" "IMAP サーバー"))
        username (str/trim (or (not-empty (str username)) address))
        password (require-text! password "パスワード")
        smtp-host (str/trim (or (not-empty (str smtp-host)) host))
        id (account-id kind (str username "@" host))
        now (store/now)
        reading {:host host
                 :port (port! port (get default-ports kind))
                 :username username}
        record {:id id
                :kind kind
                :address address
                :display-name (or (not-empty (str display-name)) address)
                :user-did user-did
                :status :connected
                ;; Keyed by the protocol it is, so `mail-pop3` and
                ;; `mail-imap` each read their own and neither has to check
                ;; which kind of host it was handed.
                (if (= :pop3 kind) :pop3 :imap) reading
                :smtp {:host smtp-host
                       :port (port! smtp-port (:smtp default-ports))
                       :username (str/trim (or (not-empty (str smtp-username))
                                               username))}
                :password-ref (keychain-put! (keychain-account-name id :password)
                                             password)
                :created-at now}]
    (store/transact!
     (fn [state]
       (-> state
           ;; Merged, not replaced: re-registering an account somebody is
           ;; already syncing should change its host or its password without
           ;; throwing away the cursor and re-downloading the mailbox.
           (update-in (conj (accounts-path) id) merge record)
           (update :events conj {:type :mail/account-added :at now :id id}))))
    (dissoc record :password-ref)))

(defn remove-account!
  "Forget a mailbox.

  Only an IMAP account can be forgotten here. An OAuth account exists because
  a grant exists, so removing it is disconnecting the grant — a different act,
  belonging to `identity`, and doing it from here would leave the connection
  live and the mailbox merely hidden."
  [id]
  (let [existing (get-in (store/snapshot) (conj (accounts-path) id))]
    (when-not (contains? #{:imap :pop3} (:kind existing))
      (throw (ex-info "OAuth で接続したアカウントはここでは削除できません。接続を解除してください。"
                      {:type :mail/not-removable :id id})))
    (keychain-delete! (keychain-account-name id :password))
    (store/transact!
     (fn [state]
       (-> state
           (update-in (accounts-path) dissoc id)
           (update :events conj {:type :mail/account-removed
                                 :at (store/now) :id id}))))
    {:schema schema :ok? true :id id :removed? true}))

;; ---------------------------------------------------------------------------
;; Sync state

(defn record-sync!
  "What one account's last sync did."
  [id result]
  (store/transact!
   (fn [state]
     (update-in state (conj (accounts-path) id :sync)
                merge (assoc result :last-synced-at (store/now)
                             :last-attempt-at (store/now)
                             :status :ready
                             :last-error nil)))))

(defn record-error!
  "Why one account's last sync did not happen.

  Kept beside the cursor rather than replacing it: a failed sync must not lose
  the position a later one resumes from, and an account that fails once should
  come back where it left off rather than re-reading the mailbox."
  [id error]
  (store/transact!
   (fn [state]
     (update-in state (conj (accounts-path) id :sync)
                merge {:status :error
                       :last-error (.getMessage ^Exception error)
                       :last-attempt-at (store/now)}))))

(defn cursor [id]
  (get-in (store/snapshot) (conj (accounts-path) id :sync :cursor)))

(defn public-account
  "One account as this app hands it out. Never a credential, never a reference
  to one."
  [account]
  (-> account
      (select-keys [:id :kind :address :display-name :status :sync :user-did])
      (assoc :removable? (contains? #{:imap :pop3} (:kind account))
             :delegated? (boolean (:delegated? account))
             ;; Never-synced and failing must not look the same to somebody
             ;; asking whether their mail is arriving.
             :status (get-in account [:sync :status] :never-synced))
      (cond-> (:imap account)
        (assoc :imap (select-keys (:imap account) [:host :port :username])))
      (cond-> (:pop3 account)
        (assoc :pop3 (select-keys (:pop3 account) [:host :port :username])))
      (cond-> (:smtp account)
        (assoc :smtp (select-keys (:smtp account) [:host :port :username])))))
