(ns cloud.itonami.app.agent-session
  "Sessions for a CLI or an MCP client, rooted in local ownership of the store.

  ## Why this is not a hole in the Passkey gate

  A Passkey gates `/api/*` because the loopback server is reachable by every
  process and every page on this machine, and a browser session belonging to a
  half-enrolled user must not act. That is a real boundary and it stays.

  It is not, however, a boundary against something that can already read and
  write `data/state.edn`. That file IS the app: sessions, memberships,
  organizations, every record. A process holding it can mint itself any session
  it likes by editing the file directly. Requiring a Passkey on top of that
  proves nothing extra — it only stops the operator from doing deliberately what
  an attacker in the same position would do anyway.

  So an agent session is issued against the one thing that is actually load
  bearing here: **can you read a 0600 file inside the data directory**. The
  enrollment key is written by the server, never leaves the machine, and never
  travels over the wire except as the one-shot proof that mints a session.

  Owner decision, 2026-07-31, choosing this over 'keep the Passkey as the root':
  the alternative required a browser ceremony to succeed once, and the point of
  this namespace is the case where it cannot.

  ## What an agent session still cannot do

  Nothing here touches approval. `payment_commit` carries out what a human
  already approved and `approve/finish` needs a WebAuthn user-verifying
  assertion, which no agent can produce — see ADR-0006 and `mcp`'s docstring.
  An agent may ask, record, and carry out what was approved. It may not approve.
  That gate is a different mechanism from `require-passkey!` and this namespace
  leaves it exactly where it was.

  ## Why the CLI cannot just write the file

  `store/state` is `(defonce state (atom (load-state)))` — read once at process
  start and never re-read. A CLI writing `state.edn` beside a running server is
  silently reverted by the server's next `transact!`. So enrollment is a route on
  the running server, and the CLI is a client of it. Measured rather than
  assumed: this is why there is an HTTP route here at all."
  (:require [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute PosixFilePermission PosixFilePermissions]
           [java.security MessageDigest SecureRandom]
           [java.time Instant]
           [java.util Base64]))

(def schema "cloud.itonami.app.agent-session.v1")

(def key-file-name "agent-enrollment.key")

(def default-ttl-days 30)

(defn- refuse [type message]
  (throw (ex-info message {:type type})))

(defn key-file ^Path []
  (.toPath (java.io.File. (config/data-dir) key-file-name)))

(defn- owner-only
  "0600. The whole proof rests on this file being unreadable by other users, so
  the permissions are set explicitly rather than inherited from the umask."
  []
  (PosixFilePermissions/asFileAttribute
   (java.util.EnumSet/of PosixFilePermission/OWNER_READ
                         PosixFilePermission/OWNER_WRITE)))

(defn- random-key []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn ensure-key!
  "The enrollment key, creating it if absent. Returns the key.

  Called at server start so that a CLI run afterwards has something to read.
  Stable across restarts: regenerating it every boot would invalidate nothing
  (tokens are separate records) while making every scripted enrollment race the
  server's lifecycle for no gain."
  []
  (let [path (key-file)
        dir (.getParent path)]
    (when-not (Files/isDirectory dir (into-array LinkOption []))
      (Files/createDirectories dir (into-array java.nio.file.attribute.FileAttribute [])))
    (if (Files/isRegularFile path (into-array LinkOption []))
      (str/trim (String. (Files/readAllBytes path) "UTF-8"))
      (let [key (random-key)]
        (Files/createFile path (into-array [(owner-only)]))
        (Files/write path (.getBytes key "UTF-8")
                     (into-array java.nio.file.OpenOption []))
        key))))

(defn- key-matches?
  "Constant time. A timing-distinguishable compare on a 32-byte secret held by a
  loopback server that answers as fast as the caller can ask is worth avoiding
  even though the attack is awkward."
  [presented]
  (let [expected (ensure-key!)]
    (and (not (str/blank? presented))
         (MessageDigest/isEqual (.getBytes (str/trim presented) "UTF-8")
                                (.getBytes expected "UTF-8")))))

(defn- owner-user-id
  "The user an agent session acts as.

  Picked rather than asked for when there is exactly one owner membership, which
  is every single-operator install. With more than one, the caller has to say
  which — guessing would silently pick a tenant."
  [requested]
  (let [state (:identity (store/snapshot))
        owners (filterv #(= :owner (:role %)) (vals (:memberships state)))]
    (cond
      (some-> requested str/trim not-empty)
      (let [id (str/trim requested)]
        (when-not (some #(= id (:user-id %)) (vals (:memberships state)))
          (refuse :agent-session/unknown-user
                  (str "membership を持たない user です: " id)))
        id)

      (= 1 (count owners)) (:user-id (first owners))

      (empty? owners)
      (refuse :agent-session/no-owner
              "owner membership がありません。先にアプリを一度開いて組織を作ってください")

      :else
      (refuse :agent-session/ambiguous-user
              (str "owner membership が " (count owners)
                   " 件あります。--user-id で指定してください")))))

(defn enroll!
  "Mint an agent session, or refuse.

  `enrollment-key` must equal the data directory's 0600 key file. `label` is
  required and free-form: an unlabelled agent session is one nobody can later
  decide to revoke, because there is nothing to tell it from the others."
  [{:keys [enrollment-key label user-id ttl-days]}]
  (when-not (key-matches? enrollment-key)
    (refuse :agent-session/invalid-key
            "enrollment key が一致しません。data dir の agent-enrollment.key を読んでください"))
  (let [label (some-> label str str/trim not-empty)]
    (when-not label
      (refuse :agent-session/label-missing
              "label が必要です（あとで revoke するときの識別子になります）"))
    (let [ttl (or (some-> ttl-days int) default-ttl-days)
          _ (when-not (pos? ttl)
              (refuse :agent-session/ttl-invalid "ttl-days は 1 以上にしてください"))
          issued (identity/issue-session!
                  (owner-user-id user-id)
                  {:kind :agent
                   :label label
                   :issued-via :local-ownership
                   :ttl-seconds (* ttl 24 60 60)})]
      (assoc issued :schema schema :label label))))

(defn- public [session]
  (-> (select-keys session [:id :label :user-id :organization-id
                            :created-at :expires-at :revoked?])
      (assoc :kind (name (or (:kind session) :passkey))
             :issued-via (some-> (:issued-via session) name)
             :expired? (not (pos? (compare (Instant/parse (:expires-at session))
                                           (Instant/now)))))))

(defn sessions
  "Every agent session on this install, live and dead.

  Revoked and expired ones are listed rather than filtered: 'what has been given
  access' is the question this answers, and a list that quietly drops the dead
  ones cannot answer it."
  []
  (->> (get-in (store/snapshot) [:identity :sessions])
       vals
       (filter #(= :agent (:kind %)))
       (sort-by :created-at)
       (mapv public)))

(defn revoke!
  "Revoke by session id. Returns the revoked record, or refuses."
  [id]
  (let [id (some-> id str str/trim not-empty)
        record (get-in (store/snapshot) [:identity :sessions id])]
    (when-not (and record (= :agent (:kind record)))
      (refuse :agent-session/not-found
              (str "agent session がありません: " id)))
    (store/transact! assoc-in [:identity :sessions id :revoked?] true)
    (public (get-in (store/snapshot) [:identity :sessions id]))))

(defn agent?
  "Whether a resolved session was issued to a CLI/MCP client rather than a
  browser. `identity/require-passkey!` reads this."
  [session]
  (= :agent (:kind session)))
