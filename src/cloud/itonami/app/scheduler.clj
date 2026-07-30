(ns cloud.itonami.app.scheduler
  "Appointments this app owns: made here, invited to here, answered here.

  The Scheduler view read the machine's own calendar through
  `kotoba-lang/shell` and EventKit and showed the next seven days. That is a
  window onto somebody else's calendar — nothing in this app could make an
  appointment, ask anyone to it, or answer one.

  Meanwhile `kotoba-lang/calendar` has had attendees, RSVP with a real
  `:needs-action` state, conflict detection that ignores declined events,
  and free/busy since it was written, and this app called exactly three of
  its functions: `event`, `add-event`, `events-in-order`. The model could
  run a meeting; the app could only mirror one.

  ## One calendar per principal, and an invitation is a mention

  An event lives in its organizer's calendar and names its attendees. There
  is no second copy in the invitee's calendar to keep in step — being
  invited is being named, so accepting cannot drift from what you were asked
  to, and an organizer editing the time does not have to find and update
  everyone else's copy.

  What it costs is that finding your invitations reads every calendar. That
  is the same trade `documents/search` makes and for the same reason: at a
  household's scale it is right, nothing can be stale, and there is no index
  to rebuild. When it stops being right, the fix is an index keyed on the
  event, invalidated when its attendees change.

  ## Permission is who you are to the event

  The organizer may invite, reschedule and cancel. An attendee may answer.
  Everyone else may not see it at all — an event you are not on is not a
  shorter version of an event, it is somebody else's afternoon."
  (:require [calendar.model :as calendar]
            [calendar.validate :as calendar-validate]
            [clojure.string :as str]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.workspace :as workspace])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.util Base64 UUID]))

(def calendar-schema "cloud.itonami.app.scheduler.v1")

(defonce ^:private write-lock (Object.))

(defn- calendars [state] (get-in state [:scheduler :calendars] {}))

(defn- calendar-for [state principal]
  (or (get (calendars state) principal) (calendar/calendar principal)))

(defn- calendar-path [principal] [:scheduler :calendars principal])

(defn- actor! [actor]
  (when (str/blank? (str actor))
    (throw (ex-info "予定を扱う人を特定できません。" {:type :identity/unauthenticated})))
  actor)

(defn- locate
  "The event `id` and whose calendar it is in, or nil.

  Every calendar, because an invitation is a mention in somebody else's:
  looking only in the asker's would find the ones they organize and none of
  the ones they were asked to."
  [state id]
  (some (fn [[owner cal]]
          (when-let [event (calendar/event-by-id cal id)]
            {:owner owner :calendar cal :event event}))
        (calendars state)))

(defn- visible?
  "Whether `actor` may see this event at all. The organizer and the people
  on it — nobody else, and not a redacted version for anybody else."
  [event actor]
  (or (calendar/organized-by? event actor) (calendar/invited? event actor)))

(defn- found! [state id actor]
  (let [found (locate state id)]
    (when-not (and found (visible? (:event found) actor))
      ;; The same answer for an event that does not exist and one that is
      ;; not yours: telling them apart tells a stranger that a meeting they
      ;; were not invited to happened.
      (throw (ex-info "その予定はありません。" {:type :scheduler/not-found :id id})))
    found))

(defn- organizer! [state id actor]
  (let [{:keys [event] :as found} (found! state id actor)]
    (when-not (calendar/organized-by? event actor)
      (throw (ex-info "主催者だけが変更できます。"
                      {:type :scheduler/not-organizer :id id})))
    found))

(defn- event-view
  "One event as the app hands it out.

  `:rsvp` is the dense map from `attendee-responses`, so an invitee who has
  not answered appears as `:needs-action` rather than not appearing —
  `:calendar/rsvp` is sparse and a list built from it silently omits exactly
  the people the organizer is waiting on.

  `:your-rsvp` is nil for the organizer, who was not asked."
  [event owner actor]
  {:id (:calendar/id event)
   :title (:calendar/title event)
   :start (:calendar/start event)
   :end (:calendar/end event)
   :all-day? (boolean (:calendar/all-day? event))
   :calendar (or (:calendar/source event) "この端末のアプリ")
   :origin "app"
   :organizer (or (:calendar/organizer event) owner)
   :attendees (vec (:calendar/attendees event))
   :rsvp (into {} (map (fn [[person status]] [person (name status)]))
               (calendar/attendee-responses event))
   :role (if (calendar/organized-by? event actor) "organizer" "attendee")
   :your-rsvp (some-> (calendar/rsvp-status event actor) name)})

(defn- validate!
  "Refuse an event the model calls broken, before it is stored.

  `calendar.validate` already knows what a broken event is — no times, or an
  end before its start. Writing that test here would be a second opinion
  about the same thing, and the two would drift."
  [event]
  (let [problems (calendar-validate/event-problems event)]
    (when (some #(= :error (:calendar/severity %)) problems)
      (throw (ex-info (str/join " " (map :calendar/msg problems))
                      {:type :scheduler/invalid-event
                       :problems (mapv (fn [p] {:code (str (:calendar/code p))
                                                :message (:calendar/msg p)})
                                       problems)})))))

(defn events
  "Every event `actor` can see: the ones they organize and the ones they are
  on, oldest first, each with what it is to them."
  [actor]
  (actor! actor)
  (let [state (store/snapshot)]
    {:schema calendar-schema :ok? true
     :items (->> (calendars state)
                 (mapcat (fn [[owner cal]]
                           (->> (calendar/events-in-order cal)
                                (filter #(visible? % actor))
                                (map #(event-view % owner actor)))))
                 (sort-by (juxt :start :id))
                 vec)}))

(defn conflicts
  "What `actor` has already said yes to that overlaps `event-id`.

  Asked of the asker's own view of the world — their organized events and
  their invitations — because a clash is a clash for a person, not for a
  calendar. Declined events are not clashes, which is `calendar/conflicts`'s
  rule and the reason declining is worth doing."
  [event-id actor]
  (let [state (store/snapshot)
        {:keys [event]} (found! state event-id actor)
        mine (reduce (fn [cal item]
                       (calendar/add-event cal item))
                     (calendar/calendar actor)
                     (mapcat (fn [[_ cal]]
                               (filter #(visible? % actor) (calendar/events-in-order cal)))
                             (calendars state)))]
    (mapv (fn [clash] {:id (:calendar/id clash)
                       :title (:calendar/title clash)
                       :start (:calendar/start clash)
                       :end (:calendar/end clash)})
          (calendar/conflicts mine actor event))))

(defn create!
  "A new appointment, organized by `actor`.

  Attendees may be named at creation — inviting people is the reason to make
  an appointment rather than a note — and the organizer is never one of
  them: being asked to your own meeting would put you in your own RSVP list
  waiting for your own answer."
  [{:keys [title start end all-day? attendees]} actor]
  (actor! actor)
  (locking write-lock
    (let [id (store/new-id "evt")
          people (->> attendees
                      (map str)
                      (map str/trim)
                      (remove str/blank?)
                      (remove #(= % actor))
                      distinct
                      vec)
          event (calendar/event
                 id
                 {:calendar/title (or (not-empty (str/trim (str title))) "無題の予定")
                  :calendar/start (some-> start str not-empty)
                  :calendar/end (some-> end str not-empty)
                  :calendar/all-day? (boolean all-day?)
                  :calendar/organizer actor
                  :calendar/attendees people})]
      (validate! event)
      (store/transact! update-in (calendar-path actor)
                       (fnil calendar/add-event (calendar/calendar actor)) event)
      {:schema calendar-schema :ok? true :event (event-view event actor actor)})))

(defn invite!
  "Add `person` to an event. Organizer only."
  [id person actor]
  (actor! actor)
  (locking write-lock
    (let [state (store/snapshot)
          {:keys [owner event]} (organizer! state id actor)
          person (str/trim (str person))]
      (when (str/blank? person)
        (throw (ex-info "誰を招くのか指定してください。"
                        {:type :scheduler/no-such-person :id id})))
      (when (= person actor)
        (throw (ex-info "主催者は招待の対象ではありません。"
                        {:type :scheduler/organizer-is-not-an-attendee :id id})))
      (if (calendar/invited? event person)
        ;; Already on it. Not an error and not a second invitation: the
        ;; attendee list would gain a duplicate and the RSVP map would not,
        ;; so the same person would appear twice with one answer.
        {:schema calendar-schema :ok? true :already? true
         :event (event-view event owner actor)}
        (let [next-calendar (calendar/add-attendee (calendar-for state owner) id person)]
          (store/transact! assoc-in (calendar-path owner) next-calendar)
          {:schema calendar-schema :ok? true :already? false
           :event (event-view (calendar/event-by-id next-calendar id) owner actor)})))))

(defn respond!
  "Answer an invitation: accepted, declined or tentative.

  `calendar/respond` returns the calendar unchanged when the answer is not
  one of those or the person was never invited. Unchanged is the model's
  last line of defence and a poor answer for an API — a client that asked
  wrongly would get 200 and no change — so both cases are refused here by
  name before it is called."
  [id status actor]
  (actor! actor)
  (locking write-lock
    (let [state (store/snapshot)
          {:keys [owner event]} (found! state id actor)
          status (keyword (str/replace (str/trim (str status)) #"^:" ""))]
      (when-not (contains? calendar/rsvp-statuses status)
        (throw (ex-info (str "答えられるのは "
                             (str/join "・" (map name (sort calendar/rsvp-statuses)))
                             " です。")
                        {:type :scheduler/unknown-rsvp :status (str status)})))
      (when-not (calendar/invited? event actor)
        (throw (ex-info "招かれていない予定には答えられません。"
                        {:type :scheduler/not-invited :id id})))
      (let [next-calendar (calendar/respond (calendar-for state owner) id actor status)]
        (store/transact! assoc-in (calendar-path owner) next-calendar)
        {:schema calendar-schema :ok? true
         :event (event-view (calendar/event-by-id next-calendar id) owner actor)}))))

(defn cancel!
  "Remove an event. Organizer only.

  Gone rather than marked cancelled: an attendee's copy is a mention in this
  event, so there is nothing left pointing at it once it goes. A cancelled
  event that stays visible is a different feature — it needs its own state
  in the model, and inventing one here would put it somewhere
  `kotoba-lang/calendar` does not know to look."
  [id actor]
  (actor! actor)
  (locking write-lock
    (let [state (store/snapshot)
          {:keys [owner]} (organizer! state id actor)]
      (store/transact! update-in (conj (calendar-path owner) :calendar/events)
                       dissoc id)
      {:schema calendar-schema :ok? true :id id})))

;; Durable AgentRun schedules share the calendar lifecycle but not its event model.
(def schedule-schema "cloud.itonami.agent-schedule.v1")
(def watcher-schema "cloud.itonami.agent-watcher.v1")
(def allowed-event-types
  #{"mail.changed" "calendar.changed" "project.changed" "drive.changed"})
(def ^:private snapshot-event-types
  #{"calendar.changed" "project.changed" "drive.changed"})
(def ^:private source-check-interval (Duration/ofSeconds 60))
(def minimum-interval-seconds 300)
(def maximum-interval-seconds (* 7 24 60 60))
(defonce ^:private worker (atom nil))

(defn- instant [value]
  (if (instance? Instant value) value (Instant/parse value)))

(defn- public-schedule [schedule]
  (dissoc schedule :actor))

(defn schedules [actor]
  (->> (vals (get-in (store/snapshot) [:agent-control :schedules] {}))
       (filter #(= actor (:actor %)))
       (sort-by :created-at)
       reverse
       (mapv public-schedule)))

(defn watchers [actor]
  (->> (vals (get-in (store/snapshot) [:agent-control :watchers] {}))
       (filter #(= actor (:actor %)))
       (sort-by :created-at)
       reverse
       (mapv public-schedule)))

(defn create-schedule!
  [_configuration {:keys [goal interval-seconds model provider mode]} actor]
  (when-not (and (string? goal)
                 (<= 1 (count (str/trim goal)) 2000))
    (throw (ex-info "Schedule goal is required."
                    {:type :schedule/invalid})))
  (when-not (and (int? interval-seconds)
                 (<= minimum-interval-seconds interval-seconds
                     maximum-interval-seconds))
    (throw (ex-info "Schedule interval is outside the safe range."
                    {:type :schedule/invalid
                     :minimum minimum-interval-seconds
                     :maximum maximum-interval-seconds})))
  (let [now (Instant/now)
        id (str "schedule-" (UUID/randomUUID))
        schedule {:schema schedule-schema :id id :actor actor :enabled? true
                  :goal (str/trim goal) :interval-seconds interval-seconds
                  :model model :provider provider :mode mode
                  :created-at (str now)
                  :next-run-at (str (.plusSeconds now interval-seconds))
                  :last-dispatched-at nil :last-run-id nil :last-error nil}]
    (store/update-agent-control! assoc-in [:schedules id] schedule)
    (public-schedule schedule)))

(defn disable! [actor schedule-id]
  (let [schedule (get-in (store/snapshot)
                         [:agent-control :schedules schedule-id])]
    (when-not (and schedule (= actor (:actor schedule)))
      (throw (ex-info "Schedule was not found." {:type :schedule/not-found})))
    (let [disabled (assoc schedule :enabled? false
                         :disabled-at (store/now))]
      (store/update-agent-control! assoc-in [:schedules schedule-id] disabled)
      (public-schedule disabled))))

(defn create-watcher!
  [_configuration
   {:keys [goal event-type source-provider provider model mode]} actor]
  (when-not (and (string? goal)
                 (<= 1 (count (str/trim goal)) 2000))
    (throw (ex-info "Watcher goal is required."
                    {:type :watcher/invalid})))
  (when-not (contains? allowed-event-types event-type)
    (throw (ex-info "Watcher event type is not supported."
                    {:type :watcher/invalid
                     :allowed-event-types allowed-event-types})))
  (let [id (str "watcher-" (UUID/randomUUID))
        watcher {:schema watcher-schema :id id :actor actor :enabled? true
                 :goal (str/trim goal) :event-type event-type
                 :source-provider-filter
                 (some-> source-provider str/trim not-empty)
                 :provider provider :model model :mode mode
                 :created-at (store/now)
                 :last-event-id nil :last-dispatched-at nil
                 :last-run-id nil :last-error nil}]
    (store/update-agent-control! assoc-in [:watchers id] watcher)
    (public-schedule watcher)))

(defn disable-watcher! [actor watcher-id]
  (let [watcher (get-in (store/snapshot)
                        [:agent-control :watchers watcher-id])]
    (when-not (and watcher (= actor (:actor watcher)))
      (throw (ex-info "Watcher was not found." {:type :watcher/not-found})))
    (let [disabled (assoc watcher :enabled? false :disabled-at (store/now))]
      (store/update-agent-control! assoc-in [:watchers watcher-id] disabled)
      (public-schedule disabled))))

(defn- matches-event? [watcher event]
  (and (:enabled? watcher)
       (= (:event-type watcher) (:type event))
       (or (nil? (:source-provider-filter watcher))
           (= (:source-provider-filter watcher) (:provider event)))
       (not= (:last-event-id watcher) (:id event))))

(defn dispatch-event!
  "Dispatch an external event to matching actor-owned watchers.

  Event payloads are deliberately reduced to id/type/provider before they enter
  durable state or an AgentRun goal. Reserving the id before run creation makes
  relay redelivery idempotent. The resulting run retains normal HIL behavior."
  [configuration event]
  (let [event (select-keys event [:id :type :provider])]
    (when-not (and (string? (:id event))
                   (contains? allowed-event-types (:type event)))
      (throw (ex-info "Watcher event is invalid." {:type :watcher/invalid-event})))
    (->> (vals (get-in (store/snapshot)
                       [:agent-control :watchers] {}))
         (filter #(matches-event? % event))
         (mapv
          (fn [watcher]
            (let [reserved
                  (assoc watcher :last-event-id (:id event)
                         :last-dispatched-at (store/now) :last-error nil)]
              (store/update-agent-control!
               assoc-in [:watchers (:id watcher)] reserved)
              (try
                (let [run
                      (agent-control/create-run!
                       configuration
                       (cond-> {:goal
                                (str (:goal watcher)
                                     "\n\nTrigger: " (:type event)
                                     (when-let [provider (:provider event)]
                                       (str " from " provider)))
                                :model (:model watcher)
                                :mode (:mode watcher)}
                         (:provider watcher)
                         (assoc :provider (:provider watcher)))
                       (:actor watcher))]
                  (store/update-agent-control!
                   assoc-in [:watchers (:id watcher) :last-run-id]
                   (:agent.run/id run))
                  {:watcher-id (:id watcher)
                   :run-id (:agent.run/id run) :status :dispatched})
                (catch Exception error
                  (store/update-agent-control!
                   assoc-in [:watchers (:id watcher) :last-error]
                   {:at (store/now) :message (.getMessage error)
                    :type (some-> error ex-data :type)})
                  {:watcher-id (:id watcher) :status :failed
                   :error (.getMessage error)}))))))))

(defn- canonical [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (map (fn [[key item]] [key (canonical item)]))
          value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- snapshot-fingerprint [event-type snapshot]
  (let [projection
        (case event-type
          "calendar.changed"
          (mapv #(select-keys % [:id :title :start :end :calendar :all-day?])
                (:items snapshot))

          "project.changed" (:items snapshot)

          "drive.changed"
          (mapv #(select-keys % [:id :name :folder :media-type
                                 :size-bytes :available?])
                (:items snapshot)))
        bytes (.getBytes (pr-str (canonical projection))
                         StandardCharsets/UTF_8)]
    (-> (MessageDigest/getInstance "SHA-256")
        (.digest bytes)
        (->> (.encodeToString
              (.withoutPadding (Base64/getUrlEncoder)))))))

(defn- snapshot-ready? [event-type snapshot]
  (case event-type
    "calendar.changed" (not= "unavailable" (:status snapshot))
    "project.changed" (= "connected" (:status snapshot))
    "drive.changed" (and (map? snapshot) (vector? (:items snapshot)))
    false))

(defn observe-snapshot!
  "Record a content-free source fingerprint and dispatch only later changes.

  The first successful observation establishes a baseline and never creates a
  run. Raw snapshot fields are not written to Agent state."
  [configuration event-type provider snapshot]
  (when-not (contains? snapshot-event-types event-type)
    (throw (ex-info "Snapshot watcher event type is invalid."
                    {:type :watcher/invalid-event})))
  (let [path [:watcher-sources event-type]
        previous (get-in (store/agent-control) path)
        ready? (snapshot-ready? event-type snapshot)
        fingerprint (when ready?
                      (snapshot-fingerprint event-type snapshot))
        observed {:event-type event-type :provider provider
                  :ready? ready? :fingerprint fingerprint
                  :checked-at (store/now)
                  :status (or (:status snapshot)
                              (if ready? "connected" "unavailable"))}]
    (store/update-agent-control! assoc-in path observed)
    (if (and ready? (:fingerprint previous)
             (not= fingerprint (:fingerprint previous)))
      (dispatch-event!
       configuration {:id fingerprint :type event-type :provider provider})
      [])))

(defn- source-due? [event-type now]
  (let [checked-at
        (get-in (store/agent-control)
                [:watcher-sources event-type :checked-at])]
    (or (nil? checked-at)
        (not (.isAfter (.plus (Instant/parse checked-at)
                              source-check-interval)
                       now)))))

(defn poll-watchers!
  "Poll only source types that currently have an enabled watcher."
  [configuration]
  (let [enabled-types
        (->> (vals (get-in (store/agent-control) [:watchers] {}))
             (filter :enabled?)
             (map :event-type)
             (filter snapshot-event-types)
             set)
        now (Instant/now)]
    (into {}
          (for [event-type enabled-types
                :when (source-due? event-type now)]
            [event-type
             (try
               (case event-type
                 "calendar.changed"
                 (observe-snapshot!
                  configuration event-type "eventkit"
                  (workspace/snapshot :scheduler workspace/calendar-snapshot))
                 "project.changed"
                 (observe-snapshot!
                  configuration event-type "github"
                  (workspace/snapshot :projects workspace/projects-snapshot))
                 "drive.changed"
                 (observe-snapshot!
                  configuration event-type "onedrive"
                  (workspace/snapshot :drive workspace/drive-snapshot)))
               (catch Exception error
                 (store/update-agent-control!
                  assoc-in [:watcher-sources event-type]
                  {:event-type event-type :ready? false
                   :checked-at (store/now) :status "error"
                   :last-error (.getMessage error)})
                 []))]))))

(defn- due? [now schedule]
  (and (:enabled? schedule)
       (not (.isAfter (instant (:next-run-at schedule)) now))))

(defn- reserve-dispatch! [schedule now]
  (let [reserved
        (assoc schedule
               :last-dispatched-at (str now)
               :next-run-at
               (str (.plusSeconds now (:interval-seconds schedule)))
               :last-error nil)]
    (store/update-agent-control!
     assoc-in [:schedules (:id schedule)] reserved)
    reserved))

(defn tick!
  "Dispatch each due schedule at most once for this persisted due time."
  ([configuration] (tick! configuration (Instant/now)))
  ([configuration now]
   (let [now (instant now)]
     (->> (vals (get-in (store/snapshot)
                        [:agent-control :schedules] {}))
          (filter #(due? now %))
          (mapv
           (fn [schedule]
             (let [reserved (reserve-dispatch! schedule now)]
               (try
                 (let [run
                       (agent-control/create-run!
                        configuration
                        (select-keys reserved
                                     [:goal :model :provider :mode])
                        (:actor reserved))]
                   (store/update-agent-control!
                    assoc-in [:schedules (:id reserved) :last-run-id]
                    (:agent.run/id run))
                   {:schedule-id (:id reserved)
                    :run-id (:agent.run/id run) :status :dispatched})
                 (catch Exception error
                   (store/update-agent-control!
                    assoc-in [:schedules (:id reserved) :last-error]
                    {:at (store/now) :message (.getMessage error)
                     :type (some-> error ex-data :type)})
                   {:schedule-id (:id reserved) :status :failed
                    :error (.getMessage error)})))))))))

(defn start! [configuration]
  (when-not @worker
    (reset!
     worker
     (future
       (while (not (Thread/interrupted))
         (try
           (tick! configuration)
           (poll-watchers! configuration)
           (catch Exception _))
         (Thread/sleep 30000)))))
  true)

(defn stop! []
  (when-let [task @worker]
    (future-cancel task)
    (reset! worker nil))
  true)

