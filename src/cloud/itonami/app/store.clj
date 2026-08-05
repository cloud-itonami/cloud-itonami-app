(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.work-partition-store :as work-partitions]
            [kotoba.kgraph :as kgraph])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.state.v1")

(defn initial-state []
  {:schema schema
   :agents [{:id "local" :name "Local" :system-prompt
             "You are a private, local-first assistant. Be concise and useful."}]
   :sessions {}
   ;; One `drive.workspace` per principal — the tree, the ACL, the quota and
   ;; the version history. The bytes those versions point at are not in here;
   ;; they are in an object store. See `cloud.itonami.app.documents`.
   :drive {:workspaces {}}
   :datoms []
   :events []
   :last-response nil})

(defn state-file []
  (io/file (config/data-dir) "state.edn"))

(defn- load-state []
  (let [file (state-file)]
    (let [main (if (.isFile file)
                 (merge (initial-state) (edn/read-string (slurp file)))
                 (initial-state))]
      (if-let [work (work-partitions/load-ledger (:work-governance main))]
        (assoc main :work-governance work)
        main))))

(defonce state (atom (load-state)))
(defonce ^:private last-committed-state (atom @state))

(defn snapshot [] @state)

(defn- persist! [value]
  (let [file (state-file)
        temporary (io/file (.getParentFile file) "state.edn.tmp")
        work (:work-governance value)]
    (.mkdirs (.getParentFile file))
    ;; Governed work has an independent physical tenant boundary. Persist it
    ;; before the main store: a crash may leave an unreferenced AgentRun, but
    ;; can never leave an AgentRun dispatch without its durable intent.
    (when work (work-partitions/persist-ledger! work))
    (spit temporary (pr-str (dissoc value :work-governance)))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE])))
  value)

(defn- transaction-lock-file []
  (io/file (config/data-dir) "state.edn.lock"))

(defn- cross-process-rebase? []
  (= "1" (System/getenv "CLOUD_ITONAMI_MULTI_PROCESS")))

(defn transact! [f & args]
  (locking state
    (let [lock-file (transaction-lock-file)]
      (.mkdirs (.getParentFile lock-file))
      (with-open [channel (FileChannel/open
                           (.toPath lock-file)
                           (into-array StandardOpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE]))
                  _lock (.lock channel)]
        ;; Another process may have committed since this process loaded its
        ;; atom. Rebase under the cross-process lock before applying `f`.
        ;; A deliberately replaced in-memory atom (tests and recovery tools)
        ;; is treated as the caller's new base rather than silently discarded.
        (when (and (cross-process-rebase?)
                   (= @state @last-committed-state))
          (reset! state (load-state)))
        (let [next-value (apply swap! state f args)]
          (persist! next-value)
          (reset! last-committed-state next-value))))))

(defn update-agent-control!
  "Atomically update the durable Agent Control partition.

  Agent Control used this seam before it was reachable from the server.  Keep
  the partition update inside the same state.edn transaction as every other
  app record so an AgentRun cannot be visible only in memory."
  [f & args]
  (transact!
   (fn [s]
     (assoc s :agent-control
            (apply f (or (:agent-control s) {}) args)))))

(defn new-id [prefix]
  (str prefix "-" (UUID/randomUUID)))

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
  (get-in @state [:sessions session-id :messages] []))

(defn append-message!
  [session-id {:keys [role content] :as message} max-messages]
  (let [message-id (or (:id message) (new-id "msg"))
        recorded (assoc message :id message-id :at (or (:at message) (now)))]
    (transact!
     (fn [s]
       (let [messages (conj (vec (get-in s [:sessions session-id :messages] []))
                            recorded)
             kept (vec (take-last max-messages messages))
             datoms (-> (:datoms s)
                        (kgraph/assert-datom [message-id :message/session session-id])
                        (kgraph/assert-datom [message-id :message/role role])
                        (kgraph/assert-datom [message-id :message/content content]))]
         (-> s
             (assoc-in [:sessions session-id]
                       {:id session-id :updated-at (now) :messages kept})
             (assoc :datoms datoms)))))
    recorded))

(defn record-response! [response]
  (transact!
   (fn [s]
     (-> s
         (assoc :last-response response)
         (update :events #(vec (take-last 100
                                         (conj (or % [])
                                               {:type :chat/completed
                                                :at (now)
                                                :provider (:provider response)
                                                :model (:model response)}))))))))

(defn clear-session! [session-id]
  (transact! update :sessions dissoc session-id))
