(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store-core :as core]
            [kotoba.kgraph :as kgraph]
            [cloud.itonami.app.work-partition-store :as work-partitions]
            [langchain.edn-persist :as edn-persist]
            [cloud.itonami.app.host :as host])
  (:import [java.time Instant]))

;; The schema string and the shape of a fresh state live in `store-core`, which
;; is `.cljc`. Re-exported here so the fifty-odd callers that say `store/schema`
;; and `store/initial-state` keep working: this namespace is the one they know,
;; and moving the definition should not move the name.
(def schema core/schema)

(def ^:dynamic *environment*
  "Environment lookup seam. Repository deployments inject the same canonical
  state file used by actors; ordinary desktop runs keep the legacy data dir."
  #(System/getenv %))

(defn initial-state [] (core/initial-state))

(defn state-file []
  (io/file (or (not-empty (*environment* "KOTOBA_REPOSITORY_STATE_FILE"))
               (.getPath (io/file (config/data-dir) "state.edn")))))

(defn- repository-mode? []
  (boolean (not-empty (*environment* "KOTOBA_REPOSITORY_STATE_FILE"))))

(defn- load-state []
  (let [file (state-file)]
    (let [main (if (.isFile file)
                 (merge (initial-state) (edn/read-string (slurp file)))
                 (initial-state))]
      (if-let [work (work-partitions/load-ledger (:work-governance main))]
        (assoc main :work-governance work)
        main))))

(defonce state (atom (load-state)))

(defn snapshot [] @state)

(defn- persist! [value]
  (let [file (state-file)
        work (:work-governance value)]
    (.mkdirs (.getParentFile file))
    ;; Governed work has an independent physical tenant boundary. Persist it
    ;; before the main store: a crash may leave an unreferenced AgentRun, but
    ;; can never leave an AgentRun dispatch without its durable intent.
    (when work (work-partitions/persist-ledger! work))
    ;; Confined write under the state file's parent (kotoba-lang/fs), then
    ;; same-directory atomic rename. No ambient spit of the durable bytes.
    (host/write-atomic! file (pr-str (dissoc value :work-governance))
                        host/store-max-bytes))
  value)

(defn transact! [f & args]
  (if (repository-mode?)
    (edn-persist/with-state-lock
     (state-file)
     (fn []
       (locking state
         (let [next-value (apply f (load-state) args)]
           (reset! state next-value)
           (persist! next-value)))))
    (locking state
      (let [next-value (apply swap! state f args)]
        (persist! next-value)))))

(defn update-agent-control!
  "Atomically update the durable Agent Control partition.

  Agent Control used this seam before it was reachable from the server. Keep
  the partition update inside the same state.edn transaction as every other
  app record so an AgentRun cannot be visible only in memory.

  Kept when ADR-0014's kanban runtime was replayed onto main: main's
  `transact!` had moved to `langchain.edn-persist`'s cross-process lock, which
  is the newer of the two, but nothing had ever defined this var — so
  `cloud.itonami.app.agent-control` called it at four sites and could not
  compile. It is listed in `namespaces_test`'s `known-broken` for exactly that
  reason."
  [f & args]
  (transact!
   (fn [s]
     (assoc s :agent-control
            (apply f (or (:agent-control s) {}) args)))))

(defn new-id [prefix] (core/new-id prefix))

(def ^:private instant-format
  "ISO-8601 with exactly six fractional digits, always.

  `DateTimeFormatter/ISO_INSTANT` and `Instant/toString` both omit trailing
  zeros, which is fine to read and wrong to sort."
  (-> (java.time.format.DateTimeFormatterBuilder.)
      (.appendInstant 6)
      (.toFormatter)))

(defonce ^:private last-instant
  ;; The last instant `now` handed out, so the next one is always after it.
  (atom nil))

(defn now
  "The current instant, as a string, and strictly after the previous one.

  `Instant/now` is neither of the things this Drive assumes about it. It has
  microsecond resolution, so two operations in the same microsecond get the
  same timestamp — and versions, comments and the document listing are all
  ordered by these, so a tie is an order nothing decides. It is also not
  monotonic: an NTP correction can move it backwards, and a version stamped
  before the one it replaced is a history that reads wrong for ever.

  Stepping by a microsecond on a tie makes every ordering total by
  construction. A burst of a thousand operations in one instant ends a
  millisecond ahead of the clock and converges as soon as real time passes,
  which is a smaller error than two events that cannot be told apart.

  And it is formatted with a **fixed six fractional digits**, because these
  strings are compared as strings. `Instant/toString` drops trailing zeros in
  groups of three, so a timestamp lands on `…:00.123Z` about once in eleven
  hundred instead of `…:00.123456Z` — and `\"Z\"` sorts after `\"4\"`, so that
  one sorts *after* every longer timestamp in its own second. The order is
  then the opposite of the truth, in a listing and in the keyset cursor built
  from it. Measured: 446 of 500,000 printed with three digits and one with
  none.

  Timestamps written before this change keep their own widths and compare
  with each other exactly as badly as they always did; what stops is new ones
  joining them.

  This is not the whole answer to ordering — `documents` sorts by timestamp
  *and* id as well, because two processes do not share this atom. It is the
  answer within one, which is where the ambiguity was observable."
  []
  (.format instant-format
           (swap! last-instant
                  (fn [previous]
                    (let [candidate (Instant/now)]
                      (if (and previous (not (.isAfter candidate ^Instant previous)))
                        (.plusNanos ^Instant previous 1000)
                        candidate))))))

(defn session-messages [session-id]
  (core/session-messages @state session-id))

(defn session-context-refs [session-id]
  (core/session-context-refs @state session-id))

(defn set-session-context-refs! [session-id refs]
  (let [at (now)]
    (transact! #(core/set-context-refs % session-id refs at)))
  (session-context-refs session-id))

(defn append-message!
  [session-id {:keys [role content] :as message} max-messages]
  (let [message-id (or (:id message) (new-id "msg"))
        recorded (assoc message :id message-id :at (or (:at message) (now)))]
    ;; The id and the timestamp are minted HERE and handed down. `store-core`
    ;; has no clock and no id generator, which is what lets a test assert on
    ;; the exact transcript a known message produces.
;; The transcript window is `store-core`'s; the datoms are not, because
    ;; `kotoba.kgraph` is `.clj` in an external library and a portable
    ;; namespace cannot reach it. Both halves stay in one transaction.
    (transact! (fn [s]
                 (-> (core/append-message s session-id recorded max-messages)
                     (update :datoms
                             #(-> %
                                  (kgraph/assert-datom [message-id :message/session session-id])
                                  (kgraph/assert-datom [message-id :message/role role])
                                  (kgraph/assert-datom [message-id :message/content content]))))))
    recorded))

(defn record-response! [response]
  (let [at (now)]
    (transact! #(core/record-response % response at))))

(defn clear-session! [session-id]
  (transact! #(core/clear-session % session-id)))
