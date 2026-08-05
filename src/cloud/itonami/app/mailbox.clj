(ns cloud.itonami.app.mailbox
  "What one person has done with the mail: read it, starred it, filed it.

  The Inbox showed forty messages out of an on-disk archive and nothing
  else. Every one of them arrived marked read — hardcoded, because files on
  disk carry no read state — with the single label `:inbox` and no way to
  add another, no way to unstar what you starred, no way to get anything out
  of the list, and no threads. `mail.mailbox` has had `set-read`,
  `add-label`, `remove-label`, `trash`, `thread`, `unread-count` and a
  `search` that reads the body since it was written. The app called
  `deliver` and `search`.

  ## One box, out of an archive and every connected account

  There were two mailboxes in this app and only one of them was visible.
  `mail-sync` pulled Gmail and Microsoft 365 into the store every minute; the
  inbox was built here out of `workspace/inbox-mailbox`, which reads `.eml`
  files off disk and knows nothing about any of that. So connecting Google
  did work, and then appeared to do nothing at all, because the mail it
  fetched had nowhere to surface.

  `box` now folds both into one `mail.mailbox`: the on-disk archive, and
  every message any connected account has synced. Threads, labels, search
  and unread counts are the model's, and they now range over all of it —
  which is the point of putting the two in one box rather than serving two
  lists and calling that integration.

  ## Marks over content this app does not own

  Neither half is ours to write: the archive is files, and a synced message
  is a copy of something living at a provider. So what is stored here is only
  the difference — per principal, per message id, what they have marked — and
  the box is rebuilt by laying those marks over the merged box with the
  model's own functions. Nothing here reimplements what a label is.

  Two things follow, and both are said out loud in the interface. **Trashing
  does not delete anything**: it takes the message out of your inbox view and
  the message stays exactly where it was. And **read comes from wherever the
  message came from** — the archive has no read state, so its messages arrive
  read (they were read years ago, in another program), while a synced message
  arrives with the read state its provider reports. A mark raised here wins
  over both, because it is the more recent statement about the same message."
  (:require [clojure.string :as str]
            [cloud.itonami.app.mail-sync :as mail-sync]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.workspace :as workspace]
            [mail.inbound :as inbound]
            [mail.mailbox :as mailbox]))

(def schema "cloud.itonami.app.mailbox.v1")

(defonce ^:private write-lock (Object.))

(def ^:private markable
  "Labels a person may add or take off by hand.

  `:inbox` and `:trash` are not among them: they are where a message *is*,
  moved by trashing and restoring, and letting a client set them directly
  would make `trash` and `add-label :trash` two ways to do one thing that
  can disagree — a message both in the inbox and in the trash."
  (disj mailbox/system-labels :inbox :trash))

(defn- marks-path [principal] [:mail :marks principal])

(defn- marks [state principal] (get-in state (marks-path principal) {}))

(defn- actor! [actor]
  (when (str/blank? (str actor))
    (throw (ex-info "メールを扱う人を特定できません。" {:type :identity/unauthenticated})))
  actor)

(defn- known!
  "Refuse a mark on a message this box does not have.

  Without this, a client could name any string and this would store a mark
  against it forever — a mailbox full of marks on messages that do not
  exist, which nothing would ever clear."
  [box id]
  (when-not (get-in box [:mailbox/messages id])
    (throw (ex-info "そのメールはありません。" {:type :mail/not-found :id id})))
  id)

(defn- apply-marks
  "The archive's box with one principal's marks laid over it.

  Through `mail.mailbox`'s own functions, so a mark means exactly what the
  model says it means — `trash` takes `:inbox` off and puts `:trash` on, and
  writing that rule here would be writing it twice."
  [box marks]
  (reduce
   (fn [box' [id mark]]
     (if-not (get-in box' [:mailbox/messages id])
       ;; A mark on a message the archive no longer has. Skipped rather than
       ;; resurrected: `add-label` would happily create the entry as a map
       ;; with a label and no message in it, and that would reach the list as
       ;; a mail with no sender and no subject.
       box'
       (cond-> box'
         (contains? mark :read?) (mailbox/set-read id (:read? mark))
         (:trashed? mark) (mailbox/trash id)
         true (as-> b (reduce (fn [acc label] (mailbox/add-label acc id (keyword label)))
                              b
                              (remove nil? (:labels mark)))))))
   box
   marks))

(defn- synced-entry
  "One synced message as a `mail.mailbox` entry.

  Through `mail.inbound/from-parts` and `mailbox/message-entry`, the same two
  functions `workspace/inbox-mailbox` uses on an archived `.eml`, so a synced
  message and an archived one are the same kind of thing by the time they
  reach the box — and `search`, which reads the body, reads both."
  [message]
  (let [received (inbound/from-parts
                  {:provider (:kind message)
                   :provider-message-id (str (:provider-message-id message))
                   :from (:from-email message)
                   :to [(or (not-empty (str (:account-address message)))
                            "local@cloud-itonami.invalid")]
                   :subject (:subject message)
                   ;; The body, not the snippet: `mailbox/search` searches the
                   ;; message's parts, so what goes in here is the difference
                   ;; between searching the mail and searching its first line.
                   :text (or (not-empty (str (:body message)))
                             (not-empty (str (:snippet message)))
                             "")
                   :received-at (:received-at message)})]
    (assoc
     (mailbox/message-entry
      (:id message)
      (or (not-empty (str (:thread-id message))) (:id message))
      (:mail.inbound/message received)
      {:received-at (:received-at message)
       :size-bytes (or (:size-bytes message) 0)
       ;; `:inbox` plus whatever the provider and `mail-sync/classify` said.
       ;; Keywords, because that is what `mail.mailbox` labels are, and a
       ;; string label here would never match a filter.
       :labels (into #{:inbox}
                     (map #(if (keyword? %) % (keyword (str %))))
                     (:labels message))
       ;; What the provider says, rather than the archive's hardcoded true.
       :read? (boolean (:read? message))})
     :available? true
     :sender {:display (:from message) :email (:from-email message)}
     :snippet (or (:snippet message) "")
     :account-id (:account-id message)
     ;; The RFC 5322 `Message-ID`, which is what a reply's `In-Reply-To` has
     ;; to name. Without it a reply is a new conversation that happens to
     ;; share a subject line.
     :message-id (:message-id message))))

(defn merged-box
  "The archive and every synced account, in one `mail.mailbox`.

  Read-only and the same for everyone — what one person has read or filed is
  laid over the top by `box`, per principal.

  Synced messages are delivered after the archive's, and `mailbox/deliver` is
  idempotent by id, so the two cannot collide: an archived message is keyed
  by its filename and a synced one by `account|provider-id`."
  []
  (let [archive (workspace/inbox-mailbox)
        synced (mail-sync/messages)
        box (reduce mailbox/deliver archive (map synced-entry synced))]
    (assoc box
           :mailbox/source (str (:mailbox/source archive)
                                (when (seq synced)
                                  (str " + " (count synced) " synced")))
           :mailbox/file-count (+ (or (:mailbox/file-count archive) 0)
                                  (count synced)))))

(defn box
  "Everything `actor` can see — archive and accounts — as they have marked it."
  [actor]
  (actor! actor)
  (apply-marks (merged-box) (marks (store/snapshot) actor)))

(defn view
  "The messages `actor` can see, filtered.

  `label` and `unread?` and the text query all go to `mailbox/search`, which
  reads the body as well as the envelope — the interface has been filtering
  a snippet, so a word that appears in the fifth paragraph of a message and
  not in its first 220 characters could not be found."
  ([actor] (view actor {}))
  ([actor {:keys [label query unread?]}]
   (let [current (box actor)
         label (if (str/blank? (str label)) :inbox (keyword (str label)))
         found (mailbox/search current query {:label label :unread? unread?})]
     {:schema schema :ok? true
      :source (:mailbox/source current)
      :model "kotoba-lang/mail"
      ;; Not "archive" any more: this box is the archive *and* every connected
      ;; account, and a client told "archive" would be right to hide the reply
      ;; box it now has somewhere to send from.
      :mode "unified"
      :sealed-receiver "net-kotobase/mail-worker"
      :count (:mailbox/file-count current)
      :label (name label)
      :unread (mailbox/unread-count current)
      ;; Every label in play, so the interface can offer them without
      ;; knowing which ones this person has invented.
      :labels (vec (sort (map name (:mailbox/labels current))))
      :items (mapv workspace/entry-view found)})))

(defn thread
  "Every message in one conversation, oldest first.

  `mail.mailbox` has kept threads since the first message was delivered —
  each entry carries the `Message-ID` header as its thread — and nothing
  could ask for one."
  [thread-id actor]
  (let [current (box actor)]
    {:schema schema :ok? true :thread thread-id
     :items (mapv workspace/entry-view (mailbox/thread current thread-id))}))

(defn- update-mark! [id actor f]
  (actor! actor)
  (locking write-lock
    ;; The merged box, not just the archive: marking a synced message used to
    ;; be refused as "そのメールはありません" because the check only knew about
    ;; files on disk.
    (known! (merged-box) id)
    (store/transact! update-in (conj (marks-path actor) id) (fnil f {}))
    {:schema schema :ok? true :id id
     :message (workspace/entry-view (get-in (box actor) [:mailbox/messages id]))}))

(defn set-read! [id read? actor]
  (update-mark! id actor #(assoc % :read? (boolean read?))))

(defn set-label!
  "Put `label` on a message, or take it off.

  One route for both, because a star is a toggle and two endpoints for the
  two directions is two places for the same permission check."
  [id label on? actor]
  (let [label (keyword (str/replace (str/trim (str label)) #"^:" ""))]
    (when (str/blank? (name label))
      (throw (ex-info "ラベル名が空です。" {:type :mail/invalid-label})))
    (when (contains? #{:inbox :trash} label)
      (throw (ex-info "受信トレイとゴミ箱はラベルではなく置き場所です。"
                      {:type :mail/reserved-label :label (name label)
                       :markable (vec (sort (map name markable)))})))
    (update-mark! id actor
                  (fn [mark]
                    (update mark :labels
                            (fn [labels]
                              (let [labels (set (map name (or labels [])))]
                                (vec (sort (if on?
                                             (conj labels (name label))
                                             (disj labels (name label))))))))))))

(defn set-trashed!
  "Move a message out of the inbox view, or put it back.

  It does not delete: the archive is files this app reads and does not own,
  and a trash that quietly did nothing to them while saying `削除` would be
  the worst of both."
  [id trashed? actor]
  (update-mark! id actor #(assoc % :trashed? (boolean trashed?))))
