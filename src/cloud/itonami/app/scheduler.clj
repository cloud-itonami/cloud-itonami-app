(ns cloud.itonami.app.scheduler
  "Appointments this app owns: made here, invited to here, answered here.

  **The rules are not here any more.** They live in `yotei.schedule`
  (`cloud-itonami/yotei`), and this namespace is what stands between them and
  this app's store: it reads the snapshot, calls a pure function, writes the
  calendars it returns, and holds the write lock while it does.

  ## Why they moved

  Scheduling had two halves in two repositories. A 予約 is a stranger taking a
  slot the owner published; an appointment is an organizer proposing a time to
  people they already know. This app had the second and no 予約; yotei had 予約
  and no appointments. Both must answer 'do these overlap' the same way, and
  two implementations of that is how they stop doing so.

  What is left here is the part that is genuinely this app's: `store/state` is
  this app's atom, `store/new-id` is this app's id scheme, and the write lock
  is this app's concurrency. None of those is a scheduling rule.

  ## The shape of every function below

  Read, decide, write, return. `yotei.schedule` takes the whole calendars map
  and returns the next one, and the exceptions it throws
  (`:scheduler/not-found`, `:scheduler/not-organizer`, `:scheduler/not-invited`,
  `:scheduler/unknown-rsvp`, `:scheduler/invalid-event`,
  `:identity/unauthenticated`) reach `server.clj`'s handler unchanged — they
  are the same ex-info types this namespace used to throw itself, which is why
  the HTTP status mapping there did not have to move with them."
  (:require [cloud.itonami.app.store :as store]
            [yotei.schedule :as schedule]))

(def schema schedule/schema)

(defonce ^:private write-lock (Object.))

(defn- calendars [state] (get-in state [:scheduler :calendars] {}))

(defn- commit!
  "Write the calendars a `yotei.schedule` call returned, and hand back its
  result.

  The whole map, not a path inside it: the pure functions are free to touch
  more than one principal's calendar — `invite` writes the organizer's, whoever
  that is — and a narrower write here would have to know which, which is the
  coupling the move removed."
  [{:keys [calendars result]}]
  (store/transact! assoc-in [:scheduler :calendars] calendars)
  result)

(defn events
  "Every event `actor` can see, oldest first, each with what it is to them."
  [actor]
  (schedule/events (calendars (store/snapshot)) actor))

(defn conflicts
  "What `actor` has already said yes to that overlaps `event-id`."
  [event-id actor]
  (schedule/conflicts (calendars (store/snapshot)) event-id actor))

(defn create!
  "A new appointment, organized by `actor`.

  The id is minted here because it is this app's id scheme; `yotei.schedule`
  takes one rather than generating one, so it stays a function that can be
  called twice with the same result."
  [attrs actor]
  (locking write-lock
    (commit! (schedule/create (calendars (store/snapshot))
                              (store/new-id "evt") attrs actor))))

(defn invite!
  "Add `person` to an event. Organizer only."
  [id person actor]
  (locking write-lock
    (commit! (schedule/invite (calendars (store/snapshot)) id person actor))))

(defn respond!
  "Answer an invitation: accepted, declined or tentative."
  [id status actor]
  (locking write-lock
    (commit! (schedule/respond (calendars (store/snapshot)) id status actor))))

(defn cancel!
  "Remove an event. Organizer only."
  [id actor]
  (locking write-lock
    (commit! (schedule/cancel (calendars (store/snapshot)) id actor))))
