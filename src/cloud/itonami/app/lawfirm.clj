(ns cloud.itonami.app.lawfirm
  "The host side of `cloud-itonami/lawfirm` — where a practice record meets
  this app's inbox, Drive and durable state.

  The practice owns every rule. This namespace owns none of them: it supplies
  the effects `lawfirm.workspace` declares as protocols and reads the values
  `lawfirm.projection` computes. Nothing here decides whether a 送達 may
  happen, whether a 期限 has lapsed, or whether an answer may be sent — those
  are `lawfirm.governor`'s, and an adapter that re-derived any of them would
  be a second answer to a question that must have one.

  ## Disabled by default

  Like `:agent-control` and every `:authority`, `:lawfirm` ships off. Enabling
  it means this app holds a legal practice's record, which is a deployment
  decision and not a default.

  ## Where the record lives

  In this app's own `state.edn`, under `:lawfirm/db`, written through
  `store/transact!` — the same atomic-move-under-lock path every other piece
  of state here uses. `lawfirm.store/durable-store` asks for exactly one
  thing, a `(fn [db] ...)`, and reusing the app's proven durability is better
  than inventing a second file format for the same directory.

  `persist-db!` drops a snapshot older than the one already written. The
  practice's `durable-store` publishes before it persists and stamps every
  transition with a version precisely so a host can do this; it cannot do it
  itself, because only the host knows what it has already written.

  ## What the Drive port does and does not do

  It reconciles the matter's folder structure and stops there. This app's
  `documents/create!` makes an *editable office document* — a sheet, a doc, a
  form — and a lawfirm 書面 is an external document identified by an
  `:object-ref`. Creating a blank spreadsheet named 準備書面 would put
  something in the Drive that looks like the filing and is not it. So the
  folders are real and the documents are deliberately absent; see the ADR."
  (:require [cloud.itonami.app.config :as config]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.workspace :as workspace]
            [clojure.string :as str]
            [lawfirm.actor :as actor]
            [lawfirm.projection :as projection]
            [lawfirm.store :as lf-store]
            [lawfirm.workspace :as lf-workspace]
            [mail.mailbox :as mailbox]))

(def schema "cloud.itonami.app.lawfirm.v1")

(defn enabled?
  "True when an operator turned the practice surface on. Fail-closed: an
  absent key is off, and so is a value that is not literally `true`."
  ([] (enabled? (config/load-config)))
  ([configuration] (true? (get-in configuration [:lawfirm :enabled?]))))

(defn- require-enabled! []
  (when-not (enabled?)
    (throw (ex-info "法律事務所の記録面は無効です（config の :lawfirm :enabled? を true に）。"
                    {:type :lawfirm/disabled}))))

;; ---------------------------------------------------------------------------
;; The record
;; ---------------------------------------------------------------------------

(defn- persist-db!
  "Write `db` into the app's state, unless a newer one is already there.

  Two accepted practice writes can reach this out of order — the practice
  publishes a transition before it persists it, which is what makes the atom
  the in-process authority. The version stamp is how a host declines the
  stale one, and this is the host."
  [db]
  (store/transact!
   (fn [state]
     (let [current (get-in state [:lawfirm/db lf-store/version-key] 0)
           incoming (get db lf-store/version-key 0)]
       (if (< incoming current) state (assoc state :lawfirm/db db))))))

(defonce ^:private practice-store
  (delay (lf-store/durable-store {:snapshot (:lawfirm/db (store/snapshot))
                                  :persist! persist-db!})))

(defn practice
  "The practice record. Not created until something asks for it, so an app
  with the surface disabled never materialises one."
  []
  (require-enabled!)
  @practice-store)

(defonce ^:private graph-cache (atom nil))

(defn graph
  "The compiled `lawfirm.actor` graph over this app's record.

  The deterministic advisor, deliberately. The only op this app drives is
  `:record-inbound-transmission` — writing down that something arrived — and
  there is no judgement in it for a model to contribute. A model here would
  add a failure mode to a path whose whole job is not to lose evidence."
  []
  (or @graph-cache
      (reset! graph-cache (actor/build-graph {:store (practice)}))))

;; ---------------------------------------------------------------------------
;; InboundPort — this app's inbox as arrivals
;; ---------------------------------------------------------------------------

(defn- entry->arrival
  "One mailbox entry as a `lawfirm.workspace` arrival.

  `:digest` is the provider message id and **not** the snippet. The practice
  asks for 'whatever identifier the caller can later use to find the
  original', and the id is exactly that; the snippet is the first 220
  characters of the message, which is prose, which is the one thing the
  practice's record must not hold (`lawfirm.governor/prose-keys` holds this
  at the gate, so smuggling it here would be refused — but it would be
  refused as a bug rather than never attempted).

  The date is the archive's own JST-formatted `yyyy-MM-dd HH:mm`, truncated
  to the day. A 期限 is a calendar day and so is the day something arrived;
  for a Japanese practice JST is that calendar."
  [entry]
  (let [received (str (:mailbox.message/received-at entry))
        id (:mailbox.message/id entry)]
    (when (and (seq id) (>= (count received) 10))
      {:id id
       :channel :email
       :received-on (subs received 0 10)
       :origin (get-in entry [:sender :email])
       :digest id})))

(defn arrivals
  "Every readable inbox entry as an arrival, oldest first."
  []
  (->> (mailbox/search (workspace/inbox-mailbox) "" {:label :inbox})
       (keep entry->arrival)
       (sort-by (juxt :received-on :id))
       vec))

(defn inbound-port
  "This app's archive inbox, as the practice's `InboundPort`."
  []
  (reify lf-workspace/InboundPort
    (-arrivals [_ since]
      (filterv #(>= (compare (:received-on %) (str since)) 0) (arrivals)))))

(defn sync-inbound!
  "Run everything that arrived on or after `since` through the practice's
  gate, and report what each one did.

  Requests, not writes: an arrival is what this app's archive says happened,
  and it becomes a fact about the practice only by clearing the governor —
  which is why `:record-inbound-transmission` is not an escalated op but is
  still an op. Replaying is safe: the transmission id is derived from the
  message id, so a second run overwrites nothing new."
  [{:keys [since bengoshi-id client-id today]}]
  (require-enabled!)
  (let [g (graph)
        requests (lf-workspace/inbound-requests (inbound-port) (str since)
                                                {:bengoshi-id bengoshi-id
                                                 :client-id client-id})]
    {:schema schema
     :since since
     :considered (count requests)
     :results
     (mapv (fn [request]
             (let [tid (get-in request [:transmission :transmission-id])
                   result (actor/run-request! g request {:today today} tid)]
               {:transmission-id tid
                :received-on (get-in request [:transmission :sent-on])
                :disposition (cond
                               (actor/committed? result) "commit"
                               (actor/escalated? result) "request-approval"
                               :else "hold")
                :violations (mapv (comp name :rule)
                                  (get-in result [:state :verdict :violations]))}))
           requests)}))

;; ---------------------------------------------------------------------------
;; DrivePort — the matter's 一件記録 gets a home
;; ---------------------------------------------------------------------------

(defn- folder-titles
  "The folder titles the practice's tree asks for, parent first."
  [tree]
  (let [items (vals (:drive/items tree))
        folders (filter #(= :folder (:drive/kind %)) items)
        root (first (filter #(str/ends-with? (str (:drive/id %)) "/") folders))]
    {:root (:drive/title root)
     :sections (vec (sort (map :drive/title (remove #(= root %) folders))))}))

(defn- folder-named
  "The id of `actor-id`'s folder called `name` inside `parent`, or nil."
  [actor-id parent name]
  (->> (:folders (documents/folders (store/snapshot) actor-id parent))
       (some #(when (= name (:name %)) (:id %)))))

(defn drive-port
  "Reconcile the matter folder tree into `actor-id`'s Drive.

  Folders only — see the namespace docstring. Idempotent by name: a matter
  reconciled twice does not get a second set of folders, because reconciling
  is what a host does on every sync and a port that duplicated on each run
  would make the Drive unusable after a week."
  [actor-id]
  (reify lf-workspace/DrivePort
    (-publish-tree [_ _matter-id tree]
      (let [{:keys [root sections]} (folder-titles tree)
            root-id (or (folder-named actor-id nil root)
                        (get-in (documents/create-folder! root actor-id) [:item :id]))]
        {:schema schema
         :root {:id root-id :name root}
         :sections
         (vec (for [name sections]
                (if-let [existing (folder-named actor-id root-id name)]
                  {:id existing :name name :created? false}
                  {:id (get-in (documents/create-folder! name actor-id root-id) [:item :id])
                   :name name :created? true})))}))))

(defn publish-matter-drive!
  "Put a matter's 一件記録 structure in `actor-id`'s Drive."
  [actor-id matter-id]
  (require-enabled!)
  (lf-workspace/publish-matter-drive! (drive-port actor-id) (practice) matter-id))

;; ---------------------------------------------------------------------------
;; Read views
;; ---------------------------------------------------------------------------

(defn summary
  "The practice as `lawfirm.projection` computes it — every number read
  through the functions the practice's own gate enforces. This app adds
  nothing to it and recomputes none of it."
  [today]
  (require-enabled!)
  (assoc (projection/practice-summary (practice) today) :schema schema))

(defn docket
  "The whole docket as a `calendar.model` calendar.

  This app serves the projection rather than writing it into the operating
  system's calendar, and that is a limitation being stated rather than a
  design: `workspace/calendar-snapshot` reaches EventKit under a
  `calendar/read` policy, and there is no write path here to reach for. A
  host that gains one implements `lawfirm.workspace/CalendarPort` and the
  projection is already the right shape for it."
  [today]
  (require-enabled!)
  (assoc (projection/deadline-calendar (practice) today) :schema schema))

(defn status
  "What the surface is, for a client that has to render something when it is
  off. Never throws — this is the one read that must answer while disabled."
  []
  {:schema schema
   :enabled? (enabled?)
   :record (if (enabled?)
             {:matters (count (lf-store/matters (practice)))}
             ;; nil, not 0. A disabled surface holds no record, and reporting
             ;; zero matters would be a measurement of something that was
             ;; never looked at.
             {:matters nil})})
